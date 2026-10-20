package com.worklog.app.export

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.worklog.app.data.Entry
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

object Exporter {

    private val filePart = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val titlePart = DateTimeFormatter.ofPattern("yyyy 年 M 月 d 日")

    fun exportDocx(ctx: Context, day: LocalDate, entries: List<Entry>): Uri {
        val dir = File(ctx.cacheDir, "exports").apply { if (!exists()) mkdirs() }
        val out = File(dir, "工作日志_${day.format(filePart)}.docx")
        val sorted = entries.sortedBy { it.time }

        DocxWriter.write(
            target = out,
            title = "工作日志",
            subtitle = "${day.format(titlePart)}    共 ${sorted.size} 条记录",
            entries = sorted
        )

        return FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", out)
    }
}
