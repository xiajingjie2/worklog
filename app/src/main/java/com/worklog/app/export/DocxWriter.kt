package com.worklog.app.export

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.worklog.app.data.Entry
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
    private const val CONTENT_WIDTH = 2_743_200L // 图片 3 英寸宽，约正文一半
    private const val MAX_IMAGE_WIDTH = 1600

    private class Part(
        val name: String,     // 例如 media/image1.jpg
        val bytes: ByteArray,
        val width: Int,
        val height: Int
    )

    fun write(target: File, title: String, subtitle: String, entries: List<Entry>) {
        val parts = mutableListOf<Part>()
        val body = StringBuilder()

        body.append(paragraph(title, size = 40, bold = true, align = "center", color = "1F2937"))
        if (subtitle.isNotBlank()) {
            body.append(paragraph(subtitle, size = 18, align = "center", color = "6B7280"))
        }
        body.append(paragraph("", size = 18))

        entries.sortedBy { it.time }.forEachIndexed { index, entry ->
            body.append(
                paragraph(
                    heading(entry, index + 1),
                    size = 22,
                    bold = true,
                    color = "111827",
                    spaceBefore = 240
                )
            )
            if (entry.note.isNotBlank()) {
                body.append(paragraph(entry.note, size = 21, color = "374151"))
            }

            val source = imageSource(entry)
            val part = source?.let { prepareImage(it, parts.size + 1) }
            if (part != null) {
                parts.add(part)
                val rid = "rId${100 + parts.size}"
                val cy = (CONTENT_WIDTH.toDouble() * part.height / part.width).toLong()
                body.append(imageParagraph(rid, CONTENT_WIDTH, cy, parts.size))
                if (entry.isVideo) {
                    body.append(
                        paragraph("（视频记录的封面帧）", size = 18, color = "9CA3AF", align = "center")
                    )
                }
            } else if (entry.isVideo) {
                body.append(paragraph("（视频记录）", size = 18, color = "9CA3AF"))
            }
            body.append(paragraph("", size = 14))
        }

        if (entries.isEmpty()) {
            body.append(paragraph("（本日没有记录）", size = 22, align = "center", color = "9CA3AF"))
        }

        ZipOutputStream(BufferedOutputStream(FileOutputStream(target))).use { zip ->
            put(zip, "[Content_Types].xml", contentTypes().toByteArray(Charsets.UTF_8))
            put(zip, "_rels/.rels", rootRels().toByteArray(Charsets.UTF_8))
            put(zip, "word/document.xml", documentXml(body.toString()).toByteArray(Charsets.UTF_8))
            put(zip, "word/_rels/document.xml.rels", documentRels(parts).toByteArray(Charsets.UTF_8))
            parts.forEach { put(zip, "word/${it.name}", it.bytes) }
        }
    }

    // ---------------------------------------------------------------- 内容

    private fun heading(entry: Entry, index: Int): String {
        val time = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(entry.time))
        val lat = entry.lat
        val lng = entry.lng
        val place = entry.place?.takeIf { it.isNotBlank() }
            ?: if (lat != null && lng != null) {
                String.format(Locale.US, "%.5f, %.5f", lat, lng)
            } else {
                null
            }
        val prefix = "%02d.".format(Locale.CHINA, index)
        return if (place != null) "$prefix  $time　$place" else "$prefix  $time"
    }

    private fun imageSource(entry: Entry): File? {
        val candidate = if (entry.isVideo) entry.thumb else entry.path
        if (candidate.isNullOrBlank()) return null
        val file = File(candidate)
        return if (file.exists() && file.length() > 0) file else null
    }

    // ---------------------------------------------------------------- 段落

    private fun paragraph(
        text: String,
        size: Int = 22,
        bold: Boolean = false,
        align: String? = null,
        color: String? = null,
        spaceBefore: Int = 80
    ): String {
        val pPr = StringBuilder("<w:pPr>")
        // 注意：OOXML 的 pPr 子元素有顺序要求，spacing 必须在 jc 之前
        pPr.append("<w:spacing w:before=\"$spaceBefore\" w:after=\"80\" w:line=\"340\" w:lineRule=\"auto\"/>")
        if (align != null) pPr.append("<w:jc w:val=\"$align\"/>")
        pPr.append("</w:pPr>")

        val rPr = StringBuilder("<w:rPr>")
        rPr.append("<w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:eastAsia=\"Microsoft YaHei\"/>")
        if (bold) rPr.append("<w:b/>")
        if (color != null) rPr.append("<w:color w:val=\"$color\"/>")
        rPr.append("<w:sz w:val=\"$size\"/><w:szCs w:val=\"$size\"/>")
        rPr.append("</w:rPr>")

        val safe = escape(text)
            .replace("\n", "</w:t><w:br/><w:t xml:space=\"preserve\">")

        return "<w:p>$pPr<w:r>$rPr<w:t xml:space=\"preserve\">$safe</w:t></w:r></w:p>"
    }

    private fun imageParagraph(rid: String, cx: Long, cy: Long, id: Int): String = buildString {
        append("<w:p><w:pPr>")
        append("<w:spacing w:before=\"120\" w:after=\"120\"/>")
        append("<w:jc w:val=\"center\"/></w:pPr>")
        append("<w:r><w:drawing>")
        append("<wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">")
        append("<wp:extent cx=\"$cx\" cy=\"$cy\"/>")
        append("<wp:docPr id=\"$id\" name=\"Picture $id\"/>")
        append("<a:graphic>")
        append("<a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">")
        append("<pic:pic>")
        append("<pic:nvPicPr>")
        append("<pic:cNvPr id=\"$id\" name=\"image$id.jpg\"/>")
        append("<pic:cNvPicPr/>")
        append("</pic:nvPicPr>")
        append("<pic:blipFill>")
        append("<a:blip r:embed=\"$rid\"/>")
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
        append("</w:drawing></w:r></w:p>")
    }

    // ---------------------------------------------------------------- 图片

    private fun prepareImage(file: File, index: Int): Part? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sample = 1
            while (bounds.outWidth / sample > MAX_IMAGE_WIDTH) sample *= 2

            val bitmap = BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sample }
            ) ?: return null

            val scaled = if (bitmap.width > MAX_IMAGE_WIDTH) {
                val h = (bitmap.height.toDouble() * MAX_IMAGE_WIDTH / bitmap.width)
                    .toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(bitmap, MAX_IMAGE_WIDTH, h, true)
                    .also { bitmap.recycle() }
            } else {
                bitmap
            }

            val buffer = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 82, buffer)
            val part = Part(
                name = "media/image$index.jpg",
                bytes = buffer.toByteArray(),
                width = scaled.width,
                height = scaled.height
            )
            scaled.recycle()
            part
        } catch (t: Throwable) {
            null
        }
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
