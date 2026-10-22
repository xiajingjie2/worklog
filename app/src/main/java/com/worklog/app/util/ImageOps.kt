@file:Suppress("DEPRECATION")

package com.worklog.app.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.File
import kotlin.math.abs

/**
 * 图片朝向与比例处理。
 *
 * 相机拍出来的 JPEG 往往只是把旋转写进 EXIF，像素本身还是横的；
 * 直接解码会得到躺倒的图。这里统一「解码 → 按 EXIF 摆正 → 可选裁成固定比例」。
 */
object ImageOps {

    fun rotationOf(file: File): Float = try {
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

    /** 摆正后的宽高比（宽 / 高） */
    fun aspectOf(file: File): Float? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val rot = rotationOf(file)
        return if (rot == 90f || rot == 270f) {
            bounds.outHeight.toFloat() / bounds.outWidth
        } else {
            bounds.outWidth.toFloat() / bounds.outHeight
        }
    }

    /**
     * 解码并摆正图片；targetAspect 非空时居中裁剪成该比例（用于两张图统一尺寸）。
     */
    fun loadUpright(file: File, maxSide: Int, targetAspect: Float? = null): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sample = 1
            while (bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide) {
                sample *= 2
            }

            val raw = BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sample }
            ) ?: return null

            val rotation = rotationOf(file)
            val upright = if (rotation != 0f) {
                Bitmap.createBitmap(
                    raw, 0, 0, raw.width, raw.height,
                    Matrix().apply { postRotate(rotation) }, true
                )
            } else {
                raw
            }

            if (targetAspect == null) upright else cropToAspect(upright, targetAspect)
        } catch (t: Throwable) {
            null
        }
    }

    private fun cropToAspect(src: Bitmap, aspect: Float): Bitmap {
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return src
        val current = w.toFloat() / h
        if (abs(current - aspect) < 0.02f) return src
        return if (current > aspect) {
            val newW = (h * aspect).toInt().coerceAtLeast(1)
            val x = ((w - newW) / 2).coerceAtLeast(0)
            Bitmap.createBitmap(src, x, 0, newW.coerceAtMost(w), h)
        } else {
            val newH = (w / aspect).toInt().coerceAtLeast(1)
            val y = ((h - newH) / 2).coerceAtLeast(0)
            Bitmap.createBitmap(src, 0, y, w, newH.coerceAtMost(h))
        }
    }
}
