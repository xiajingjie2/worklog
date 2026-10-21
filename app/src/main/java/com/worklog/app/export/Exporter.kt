package com.worklog.app.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.worklog.app.data.AppSettings
import com.worklog.app.data.Entry
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

object Exporter {

    const val MIME_DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

    private val filePart = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val titlePart = DateTimeFormatter.ofPattern("yyyy 年 M 月 d 日")
    private const val FOLDER = "工作日志"

    /**
     * 生成 Word，保存到「下载 / 工作日志」目录（卸载重装后文件仍在），
     * 返回一个可以直接用系统应用打开预览的 Uri。
     */
    fun exportDocx(
        ctx: Context,
        day: LocalDate,
        entries: List<Entry>,
        mode: String
    ): Uri {
        val sorted = entries.sortedBy { it.time }
        val name = buildString {
            append(if (mode == AppSettings.MODE_BRIEF) "简报_" else "工作日志_")
            append(day.format(filePart))
            append(".docx")
        }

        val dir = File(ctx.cacheDir, "exports").apply { if (!exists()) mkdirs() }
        val temp = File(dir, name)

        if (mode == AppSettings.MODE_BRIEF) {
            DocxWriter.writeBrief(temp, day.format(titlePart), sorted)
        } else {
            DocxWriter.write(
                target = temp,
                title = day.format(titlePart),
                subtitle = "共 ${sorted.size} 条记录",
                entries = sorted
            )
        }

        return publish(ctx, temp, name)
    }

    /** 复制到公共下载目录；任何失败都退回缓存文件（仍可预览和分享）。 */
    private fun publish(ctx: Context, source: File, name: String): Uri {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = ctx.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, MIME_DOCX)
                    put(
                        MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER
                    )
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri == null) {
                    fileUri(ctx, source)
                } else {
                    resolver.openOutputStream(uri)?.use { out ->
                        source.inputStream().use { it.copyTo(out) }
                    }
                    val done = ContentValues().apply {
                        put(MediaStore.Downloads.IS_PENDING, 0)
                    }
                    resolver.update(uri, done, null, null)
                    uri
                }
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    FOLDER
                ).apply { if (!exists()) mkdirs() }
                val target = File(dir, name)
                source.copyTo(target, overwrite = true)
                fileUri(ctx, target)
            }
        } catch (t: Throwable) {
            fileUri(ctx, source)
        }
    }

    private fun fileUri(ctx: Context, file: File): Uri =
        FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", file)
}
