package com.worklog.app.export

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.worklog.app.data.Entry
import com.worklog.app.util.ImageOps
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 手写 OOXML 生成 .docx，零第三方库。
 *
 * 一个最小可用的 Word 文档只需要 4 个部件：
 *   [Content_Types].xml
 *   _rels/.rels
 *   word/document.xml
 *   word/_rels/document.xml.rels
 * 图片则额外放进 word/media/ 并在 document.xml.rels 里登记关系。
 */
object DocxWriter {

    private const val EMU_PER_INCH = 914400L
    private const val CONTENT_WIDTH_IN = 5.9      // A4 去掉左右边距后的正文宽度
    private const val USABLE_HEIGHT_IN = 8.8       // A4 去掉上下边距后的可用高度
    private const val PAGE_FILL = 0.75             // 超过 75% 就另起一页
    private const val MAX_IMAGE_WIDTH = 1600
    private const val CHAR_PER_LINE = 40           // 10.5pt 宋体，5.9 英寸约 40 字
    private const val LINE_HEIGHT_IN = 0.26

    /** Word 里的字体组合：西文名 + 中文字体名 */
    private class Font(val ascii: String, val eastAsia: String)

    private val FONT_YAHEI = Font("Calibri", "Microsoft YaHei")
    private val FONT_SONG = Font("SimSun", "宋体")
    private val FONT_FANGSONG = Font("FangSong_GB2312", "仿宋_GB2312")

    private class Part(
        val name: String,     // 例如 media/image1.jpg
        val bytes: ByteArray,
        val width: Int,
        val height: Int
    )

    // ---------------------------------------------------------------- 日志模式

    fun write(target: File, title: String, subtitle: String, entries: List<Entry>) {
        val parts = mutableListOf<Part>()
        val body = StringBuilder()
        val sorted = entries.sortedBy { it.time }

        body.append(paragraph(title, size = 40, bold = true, align = "center", color = "1F2937"))
        if (subtitle.isNotBlank()) {
            body.append(paragraph(subtitle, size = 18, align = "center", color = "6B7280"))
        }
        body.append(paragraph("", size = 18))

        var used = 1.9 // 标题区已占的高度

        sorted.forEachIndexed { index, entry ->
            val text = entry.note.trim()
            val (first, second) = splitNote(text)
            val entryParts = mutableListOf<Part>()
            val files = imageSources(entry)
            val uniform = uniformAspect(files)
            files.forEach { file ->
                prepareImage(file, parts.size + entryParts.size + 1, uniform)
                    ?.let { entryParts.add(it) }
            }

            val estimated = 0.30 + estimateText(first) + estimateText(second) +
                estimateImage(entryParts) + 0.12

            // 占到页面的 75% 就另起一页，避免一条记录被劈开
            if (index > 0 && used + estimated > USABLE_HEIGHT_IN * PAGE_FILL) {
                body.append(pageBreak())
                used = 0.0
            }

            body.append(
                paragraph(
                    heading(entry, index + 1),
                    size = 22,
                    bold = true,
                    color = "111827",
                    spaceBefore = 160
                )
            )
            if (first.isNotBlank()) {
                body.append(paragraph(first, size = 21, color = "374151", firstLineIndent = true))
            }

            if (entryParts.isNotEmpty()) {
                val start = parts.size
                parts.addAll(entryParts)
                body.append(imagesParagraph(start, entryParts, total = sorted.size))
                if (entry.isVideo) {
                    body.append(
                        paragraph("（视频记录的封面帧）", size = 18, color = "9CA3AF", align = "center")
                    )
                }
            } else if (entry.isVideo) {
                body.append(paragraph("（视频记录）", size = 18, color = "9CA3AF"))
            }

            // 文字较多时，把后半段放到图片下面
            if (second.isNotBlank()) {
                body.append(paragraph(second, size = 21, color = "374151", firstLineIndent = true))
            }

            body.append(paragraph("", size = 14))
            used += estimated
        }

        if (sorted.isEmpty()) {
            body.append(paragraph("（本日没有记录）", size = 22, align = "center", color = "9CA3AF"))
        }

        zip(target, parts, body)
    }

    // ---------------------------------------------------------------- 简报模式

    /**
     * 简报模式：抬头「简报」（宋体），日期右对齐，正文仿宋_GB2312，首行缩进两字。
     */
    fun writeBrief(target: File, day: String, entries: List<Entry>) {
        val parts = mutableListOf<Part>()
        val body = StringBuilder()
        val sorted = entries.sortedBy { it.time }

        body.append(
            paragraph(
                "简  报",
                size = 44,
                bold = true,
                align = "center",
                color = "000000",
                font = FONT_SONG,
                spaceBefore = 0
            )
        )
        body.append(
            paragraph(
                day,
                size = 21,
                align = "right",
                color = "000000",
                font = FONT_FANGSONG,
                spaceBefore = 60
            )
        )
        body.append(paragraph("", size = 12, spaceBefore = 0))

        sorted.forEach { entry ->
            val time = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(entry.time))
            val where = placeText(entry)
            val head = if (where.isBlank()) time else "$time　$where"
            body.append(
                paragraph(
                    head,
                    size = 21,
                    bold = true,
                    color = "000000",
                    font = FONT_FANGSONG,
                    spaceBefore = 160
                )
            )
            if (entry.note.isNotBlank()) {
                body.append(
                    paragraph(
                        entry.note,
                        size = 21,
                        color = "000000",
                        firstLineIndent = true,
                        font = FONT_FANGSONG
                    )
                )
            }

            val entryParts = mutableListOf<Part>()
            val files = imageSources(entry)
            val uniform = uniformAspect(files)
            files.forEach { file ->
                prepareImage(file, parts.size + entryParts.size + 1, uniform)
                    ?.let { entryParts.add(it) }
            }
            if (entryParts.isNotEmpty()) {
                val start = parts.size
                parts.addAll(entryParts)
                body.append(imagesParagraph(start, entryParts, total = sorted.size, brief = true))
            }
        }

        if (sorted.isEmpty()) {
            body.append(
                paragraph("（本日没有记录）", size = 21, align = "center", font = FONT_FANGSONG)
            )
        }

        zip(target, parts, body)
    }

    /**
     * 表格模式：抬头「采集数据表」，下面五列表格
     * 序号 / 地址 / 照片 / 空 / 空。
     */
    fun writeTable(target: File, title: String, entries: List<Entry>) {
        val parts = mutableListOf<Part>()
        val body = StringBuilder()

        body.append(paragraph(title, size = 40, bold = true, align = "center", color = "111827"))
        body.append(paragraph("", size = 14))

        val widths = listOf(700, 3000, 2900, 951, 951)
        body.append("<w:tbl><w:tblPr><w:tblW w:w=\"8502\" w:type=\"dxa\"/><w:jc w:val=\"center\"/>")
        body.append("<w:tblBorders>")
        listOf("top", "left", "bottom", "right", "insideH", "insideV").forEach { edge ->
            body.append("<w:$edge w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"808080\"/>")
        }
        body.append("</w:tblBorders></w:tblPr>")
        body.append("<w:tblGrid>")
        widths.forEach { body.append("<w:gridCol w:w=\"$it\"/>") }
        body.append("</w:tblGrid>")

        body.append("<w:tr>")
        listOf("序号", "地址", "照片", "", "").forEachIndexed { i, text ->
            body.append(
                cell(
                    widths[i],
                    paragraph(text, size = 21, bold = true, align = "center", spaceBefore = 40)
                )
            )
        }
        body.append("</w:tr>")

        entries.sortedBy { it.time }.forEachIndexed { index, entry ->
            body.append("<w:tr>")
            body.append(
                cell(
                    widths[0],
                    paragraph((index + 1).toString(), size = 21, align = "center", spaceBefore = 40)
                )
            )
            val (addr, detail) = splitRecord(entry)
            body.append(cell(widths[1], paragraph(addr, size = 21, spaceBefore = 40)))

            body.append("<w:tc><w:tcPr><w:tcW w:w=\"${widths[2]}\" w:type=\"dxa\"/>")
            body.append("<w:vAlign w:val=\"center\"/></w:tcPr>")
            val entryParts = mutableListOf<Part>()
            val tableFiles = imageSources(entry).take(2)
            val tableAspect = uniformAspect(tableFiles)
            tableFiles.forEach { file ->
                prepareImage(file, parts.size + entryParts.size + 1, tableAspect)
                    ?.let { entryParts.add(it) }
            }
            if (entryParts.isEmpty()) {
                body.append(paragraph("", size = 18, align = "center"))
            } else {
                val start = parts.size
                parts.addAll(entryParts)
                body.append(cellImages(start, entryParts))
            }
            body.append("</w:tc>")

            body.append(cell(widths[3], paragraph(detail, size = 21)))
            body.append(cell(widths[4], paragraph("", size = 21)))
            body.append("</w:tr>")
        }
        body.append("</w:tbl>")
        body.append(paragraph("", size = 12))

        zip(target, parts, body)
    }

    private fun cell(width: Int, content: String): String =
        "<w:tc><w:tcPr><w:tcW w:w=\"$width\" w:type=\"dxa\"/>" +
            "<w:vAlign w:val=\"center\"/></w:tcPr>$content</w:tc>"

    private fun cellImages(startIndex: Int, parts: List<Part>): String {
        val runs = StringBuilder()
        val maxWidthEmu = if (parts.size >= 2) {
            (0.85 * EMU_PER_INCH).toLong()
        } else {
            (1.6 * EMU_PER_INCH).toLong()
        }
        parts.forEachIndexed { i, part ->
            val rid = 100 + startIndex + i + 1
            val byHeight = (1.6 * EMU_PER_INCH * part.width / part.height).toLong()
            val cx = minOf(maxWidthEmu, byHeight)
            val cy = (cx.toDouble() * part.height / part.width).toLong()
            if (i > 0) runs.append(gapRun(1))
            runs.append(drawingRun(rid, cx, cy))
        }
        return "<w:p><w:pPr><w:spacing w:before=\"40\" w:after=\"40\"/>" +
            "<w:jc w:val=\"center\"/></w:pPr>$runs</w:p>"
    }

    private fun zip(target: File, parts: List<Part>, body: StringBuilder) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(target))).use { zip ->
            put(zip, "[Content_Types].xml", contentTypes().toByteArray(Charsets.UTF_8))
            put(zip, "_rels/.rels", rootRels().toByteArray(Charsets.UTF_8))
            put(zip, "word/document.xml", documentXml(body.toString()).toByteArray(Charsets.UTF_8))
            put(zip, "word/_rels/document.xml.rels", documentRels(parts).toByteArray(Charsets.UTF_8))
            parts.forEach { put(zip, "word/${it.name}", it.bytes) }
        }
    }

    // ---------------------------------------------------------------- 文本切分

    /**
     * 一条记录最多两张图。文字太长时按句子断成两段：
     * 前半段放图片上面，后半段放图片下面。
     */
    private fun splitNote(text: String): Pair<String, String> {
        if (text.length <= 60) return text to ""
        val target = (text.length * 0.45).toInt()
        var cut = -1
        for (i in target until text.length) {
            if (text[i] in "。！？；\n") {
                cut = i + 1
                break
            }
        }
        if (cut <= 0) {
            for (i in target downTo 1) {
                if (text[i] in "，、,;") {
                    cut = i + 1
                    break
                }
            }
        }
        if (cut <= 0) cut = target
        if (cut >= text.length) return text to ""
        return text.substring(0, cut).trim() to text.substring(cut).trim()
    }

    private fun estimateText(text: String): Double {
        if (text.isBlank()) return 0.0
        val lines = (text.length + CHAR_PER_LINE - 1) / CHAR_PER_LINE
        return lines * LINE_HEIGHT_IN + 0.08
    }

    private fun estimateImage(parts: List<Part>): Double {
        if (parts.isEmpty()) return 0.0
        val gap = if (parts.size >= 2) 0.30 else 0.0
        val capWidth = if (parts.size >= 2) (CONTENT_WIDTH_IN - gap) / 2 else 3.3
        val maxHeight = 3.4
        return parts.maxOf { p ->
            val byWidth = capWidth
            val byHeight = maxHeight * p.width / p.height
            val w = minOf(byWidth, byHeight)
            w * p.height / p.width
        }
    }

    // ---------------------------------------------------------------- 内容

    private fun heading(entry: Entry, index: Int): String {
        val time = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(entry.time))
        val place = placeText(entry).takeIf { it.isNotBlank() }
        val prefix = "%02d.".format(Locale.CHINA, index)
        return if (place != null) "$prefix  $time　$place" else "$prefix  $time"
    }

    private fun placeText(entry: Entry): String {
        entry.place?.takeIf { it.isNotBlank() }?.let { return it }
        val lat = entry.lat
        val lng = entry.lng
        return if (lat != null && lng != null) {
            String.format(Locale.US, "%.5f, %.5f", lat, lng)
        } else {
            ""
        }
    }

    /**
     * 备注里第一个逗号/冒号之前是「地址」，之后放进第四列。
     * 没有分隔符就整条当地址；备注为空则退回 GPS 地名。
     */
    private fun splitRecord(entry: Entry): Pair<String, String> {
        val text = entry.note.trim()
        val fallback = placeText(entry)
        if (text.isEmpty()) return fallback to ""
        val idx = text.indexOfFirst { it == '，' || it == ',' || it == '：' || it == ':' }
        if (idx < 0) return text to ""
        val head = text.substring(0, idx).trim().ifBlank { fallback }
        val tail = text.substring(idx + 1).trim()
        return head to tail
    }

    private fun imageSources(entry: Entry): List<File> {
        val candidates = if (entry.isVideo) {
            listOfNotNull(entry.thumb)
        } else {
            entry.paths.take(2)
        }
        return candidates.map { File(it) }.filter { it.exists() && it.length() > 0 }
    }

    // ---------------------------------------------------------------- 段落

    private fun paragraph(
        text: String,
        size: Int = 22,
        bold: Boolean = false,
        align: String? = null,
        color: String? = null,
        spaceBefore: Int = 80,
        firstLineIndent: Boolean = false,
        font: Font = FONT_YAHEI
    ): String {
        val pPr = StringBuilder("<w:pPr>")
        // 注意：OOXML 的 pPr 子元素有顺序要求：spacing → ind → jc
        pPr.append("<w:spacing w:before=\"$spaceBefore\" w:after=\"80\" w:line=\"340\" w:lineRule=\"auto\"/>")
        if (firstLineIndent) {
            pPr.append("<w:ind w:firstLine=\"420\" w:firstLineChars=\"200\"/>")
        }
        if (align != null) pPr.append("<w:jc w:val=\"$align\"/>")
        pPr.append("</w:pPr>")

        val rPr = StringBuilder("<w:rPr>")
        rPr.append("<w:rFonts w:ascii=\"${font.ascii}\" w:hAnsi=\"${font.ascii}\" w:eastAsia=\"${font.eastAsia}\"/>")
        if (bold) rPr.append("<w:b/>")
        if (color != null) rPr.append("<w:color w:val=\"$color\"/>")
        rPr.append("<w:sz w:val=\"$size\"/><w:szCs w:val=\"$size\"/>")
        rPr.append("</w:rPr>")

        val safe = escape(text)
            .replace("\n", "</w:t><w:br/><w:t xml:space=\"preserve\">")

        return "<w:p>$pPr<w:r>$rPr<w:t xml:space=\"preserve\">$safe</w:t></w:r></w:p>"
    }

    /**
     * 图片段落：一张就居中放大；两张（最多两张）就横向排列，
     * 中间用两个全角空格隔开（约 1.5 个中文字符宽）。
     */
    private fun imagesParagraph(
        startIndex: Int,
        parts: List<Part>,
        total: Int,
        brief: Boolean = false
    ): String {
        if (parts.isEmpty()) return ""
        val count = parts.size
        val gapEmu = if (count >= 2) (0.30 * EMU_PER_INCH).toLong() else 0L
        val contentEmu = (CONTENT_WIDTH_IN * EMU_PER_INCH).toLong()

        // 单张时的最大宽度：条数越多越小；简报模式更小
        val singleCapIn = when {
            brief -> 2.4
            total >= 3 -> 2.6
            total == 2 -> 2.8
            else -> 3.3
        }
        val eachCap = if (count >= 2) {
            (contentEmu - gapEmu) / 2
        } else {
            (singleCapIn * EMU_PER_INCH).toLong()
        }
        val maxHeightIn = if (count >= 2) 3.0 else 3.6

        val runs = StringBuilder()
        parts.forEachIndexed { i, part ->
            val rid = 100 + startIndex + i + 1
            val byHeight = (maxHeightIn * EMU_PER_INCH * part.width / part.height).toLong()
            val cx = minOf(eachCap, byHeight)
            val cy = (cx.toDouble() * part.height / part.width).toLong()
            if (i > 0) runs.append(gapRun())
            runs.append(drawingRun(rid, cx, cy))
        }

        return "<w:p><w:pPr><w:spacing w:before=\"60\" w:after=\"60\"/>" +
            "<w:jc w:val=\"center\"/></w:pPr>$runs</w:p>"
    }

    private fun gapRun(spaces: Int = 2): String =
        "<w:r><w:rPr><w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:eastAsia=\"Microsoft YaHei\"/>" +
            "<w:sz w:val=\"21\"/></w:rPr><w:t xml:space=\"preserve\">" + "　".repeat(spaces) + "</w:t></w:r>"

    private fun drawingRun(rid: Int, cx: Long, cy: Long): String = buildString {
        append("<w:r><w:drawing>")
        append("<wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">")
        append("<wp:extent cx=\"$cx\" cy=\"$cy\"/>")
        append("<wp:docPr id=\"$rid\" name=\"Picture $rid\"/>")
        append("<a:graphic>")
        append("<a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">")
        append("<pic:pic>")
        append("<pic:nvPicPr>")
        append("<pic:cNvPr id=\"$rid\" name=\"image$rid.jpg\"/>")
        append("<pic:cNvPicPr/>")
        append("</pic:nvPicPr>")
        append("<pic:blipFill>")
        append("<a:blip r:embed=\"rId$rid\"/>")
        append("<a:stretch><a:fillRect/></a:stretch>")
        append("</pic:blipFill>")
        append("<pic:spPr>")
        append("<a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm>")
        append("<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom>")
        append("</pic:spPr>")
        append("</pic:pic>")
        append("</a:graphicData>")
        append("</a:graphic>")
        append("</wp:inline>")
        append("</w:drawing></w:r>")
    }

    private fun pageBreak(): String =
        "<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>"

    // ---------------------------------------------------------------- 图片

    private fun prepareImage(file: File, index: Int, targetAspect: Float? = null): Part? {
        return try {
            val bitmap = ImageOps.loadUpright(file, MAX_IMAGE_WIDTH, targetAspect) ?: return null
            val buffer = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 82, buffer)
            val part = Part(
                name = "media/image$index.jpg",
                bytes = buffer.toByteArray(),
                width = bitmap.width,
                height = bitmap.height
            )
            bitmap.recycle()
            part
        } catch (t: Throwable) {
            null
        }
    }

    /** 两张图宽高比差得多时，统一裁成 4:3 横向，排版更整齐 */
    private fun uniformAspect(files: List<File>): Float? {
        if (files.size != 2) return null
        val a = ImageOps.aspectOf(files[0]) ?: return null
        val b = ImageOps.aspectOf(files[1]) ?: return null
        return if (kotlin.math.abs(a - b) > 0.15f) 4f / 3f else null
    }

    // ---------------------------------------------------------------- 包结构

    private fun put(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun contentTypes(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Default Extension="jpg" ContentType="image/jpeg"/>
<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
</Types>"""

    private fun rootRels(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>"""

    private fun documentRels(parts: List<Part>): String {
        val rels = StringBuilder()
        rels.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
        rels.append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">\n")
        parts.forEachIndexed { i, part ->
            val rid = "rId${100 + i + 1}"
            rels.append("<Relationship Id=\"$rid\" ")
            rels.append("Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" ")
            rels.append("Target=\"${part.name}\"/>\n")
        }
        rels.append("</Relationships>")
        return rels.toString()
    }

    private fun documentXml(body: String): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document
    xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
    xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
    xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture">
<w:body>
$body
<w:sectPr>
<w:pgSz w:w="11906" w:h="16838"/>
<w:pgMar w:top="1418" w:right="1418" w:bottom="1418" w:left="1985" w:header="851" w:footer="992" w:gutter="0"/>
</w:sectPr>
</w:body>
</w:document>"""

    private fun escape(text: String): String = buildString {
        text.forEach { c ->
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                else -> append(c)
            }
        }
    }
}
