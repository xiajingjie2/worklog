package com.worklog.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.worklog.app.data.EntryStore
import java.io.File
import java.io.FileOutputStream

object Media {

    /** 把系统相机录完的视频复制进应用私有目录。 */
    fun saveVideoCopy(ctx: Context, src: Uri, store: EntryStore): File? {
        return try {
            val dest = File(store.mediaDir(), "VID_${System.currentTimeMillis()}.mp4")
            val input = ctx.contentResolver.openInputStream(src) ?: return null
            input.use { ins ->
                FileOutputStream(dest).use { out -> ins.copyTo(out) }
            }
            dest
        } catch (t: Throwable) {
            null
        }
    }

    /** 抽视频首帧当封面。抽不到就返回 null，列表里用占位图。 */
    fun videoThumb(video: File, store: EntryStore): File? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(video.absolutePath)
            val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return null
            val out = File(store.mediaDir(), video.nameWithoutExtension + "_thumb.jpg")
            FileOutputStream(out).use { frame.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            frame.recycle()
            out
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    fun decodeSampled(path: String, maxSize: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / sample > maxSize || bounds.outHeight / sample > maxSize) {
                sample *= 2
            }
            BitmapFactory.decodeFile(
                path,
                BitmapFactory.Options().apply { inSampleSize = sample }
            )
        } catch (t: Throwable) {
            null
        }
    }
}
