@file:Suppress("DEPRECATION")

package com.worklog.app.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import java.io.File
import java.io.FileOutputStream

/**
 * 给照片烧录一条底部水印（时间 + 位置）。
 * 先按 EXIF 摆正图片，再绘制，最后以 JPEG 覆盖原文件。
 */
object Watermark {

    private const val MAX_SIDE = 2000

    fun apply(file: File, text: String): Boolean {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false

            var sample = 1
            while (bounds.outWidth / sample > MAX_SIDE || bounds.outHeight / sample > MAX_SIDE) {
                sample *= 2
            }

            val decoded = BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sample }
            ) ?: return false

            val rotation = readRotation(file)
            val straight = if (rotation != 0f) {
                Bitmap.createBitmap(
                    decoded, 0, 0, decoded.width, decoded.height,
                    Matrix().apply { postRotate(rotation) }, true
                )
            } else {
                decoded
            }

            val canvasBitmap = straight.copy(Bitmap.Config.ARGB_8888, true) ?: return false
            drawWatermark(canvasBitmap, text)

            FileOutputStream(file).use { out ->
                canvasBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            true
        } catch (t: Throwable) {
            false
        }
    }

    private fun readRotation(file: File): Float {
        return try {
            val exif = ExifInterface(file.absolutePath)
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (t: Throwable) {
            0f
        }
    }

    private fun drawWatermark(bitmap: Bitmap, text: String) {
        val canvas = Canvas(bitmap)
        val width = bitmap.width.toFloat()
        val height = bitmap.height.toFloat()

        val barHeight = (height * 0.055f).coerceAtLeast(40f)
        val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(130, 0, 0, 0)
        }
        canvas.drawRect(0f, height - barHeight, width, height, barPaint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = (height * 0.032f).coerceAtLeast(22f)
        }

        val margin = barHeight * 0.28f
        var shown = text
        val maxWidth = width - margin * 2
        while (shown.length > 3 && textPaint.measureText(shown) > maxWidth) {
            shown = shown.dropLast(2)
        }

        val baseline = height - barHeight / 2f - (textPaint.ascent() + textPaint.descent()) / 2f
        canvas.drawText(shown, margin, baseline, textPaint)
    }
}
