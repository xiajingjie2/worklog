package com.worklog.app.export

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.worklog.app.data.Entry
import com.worklog.app.util.ImageOps
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 手写 OOXML 生成 .xlsx（采集数据表），零第三方库。
 *
 * 结构：标题行（合并 A1:E1）→ 表头（序号 / 地址 / 照片 / 空 / 空）→ 每条记录一行，
 * 照片以图片形式锚定在「照片」列的单元格内，最多两张并排。
 */
object XlsxWriter {

    private const val EMU_PER_PX = 9525L
    private const val PHOTO_COL_EMU = 1_780_000L   // C 列宽度（约 26 字符）
    private const val MAX_SIDE = 640

    private class Img(val name: String, val bytes: ByteArray)

    fun write(target: File, title: String, entries: List<Entry>) {
        pendingRels.clear()
        val images = mutableListOf<Img>()
        val drawingAnchors = StringBuilder()

        val maxPhotos = entries.maxOfOrNull { imageSources(it).take(2).size } ?: 1
        val photoColChars = if (maxPhotos >= 2) 30 else 18
        val photoColEmu = ((photoColChars * 7 + 5) * 9525).toLong()

        val sheet = StringBuilder()
        sheet.append("<row r=\"1\" ht=\"30\" customHeight=\"1\">")
        sheet.append(textCell("A1", title, 2))
        sheet.append("</row>")

        sheet.append("<row r=\"2\" ht=\"24\" customHeight=\"1\">")
        sheet.append(textCell("A2", "序号", 1))
        sheet.append(textCell("B2", "地址", 1))
        sheet.append(textCell("C2", "照片", 1))
        sheet.append(textCell("D2", "", 1))
        sheet.append(textCell("E2", "", 1))
        sheet.append("</row>")

        entries.sortedBy { it.time }.forEachIndexed { index, entry ->
            val row = 3 + index
            val (addr, detail) = splitRecord(entry)
            sheet.append("<row r=\"$row\" ht=\"110\" customHeight=\"1\">")
            sheet.append(textCell("A$row", (index + 1).toString(), 1))
            sheet.append(textCell("B$row", addr, 1))
            sheet.append("<c r=\"C$row\" s=\"1\"/>")
            sheet.append(textCell("D$row", detail, 1))
            sheet.append(textCell("E$row", "", 1))
            sheet.append("</row>")

            val files = imageSources(entry)
            val count = files.size
            val gap = 128_000L   // 约一个中文字符宽
            files.forEachIndexed { i, file ->
                val img = prepareImage(file, images.size + 1) ?: return@forEachIndexed
                images.add(img)
                val rel = "rId${100 + images.size}"
                val fromOff = if (count >= 2 && i > 0) photoColEmu / 2 + gap / 2 else 0L
                val toOff = if (count >= 2 && i == 0) photoColEmu / 2 - gap / 2 else photoColEmu

                drawingAnchors.append(anchor(row - 1, fromOff, toOff, images.size))
                drawingRelsAdd(rel, img.name)
            }
        }

        val sheetXml = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
            append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" ")
            append("xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">")
            append("<cols>")
            append("<col min=\"1\" max=\"1\" width=\"6\" customWidth=\"1\"/>")
            append("<col min=\"2\" max=\"2\" width=\"26\" customWidth=\"1\"/>")
            append("<col min=\"3\" max=\"3\" width=\"$photoColChars\" customWidth=\"1\"/>")
            append("<col min=\"4\" max=\"4\" width=\"26\" customWidth=\"1\"/>")
            append("<col min=\"5\" max=\"5\" width=\"10\" customWidth=\"1\"/>")
            append("</cols>")
            append("<sheetData>").append(sheet).append("</sheetData>")
            append("<mergeCells count=\"1\"><mergeCell ref=\"A1:E1\"/></mergeCells>")
            append("<drawing r:id=\"rId1\"/>")
            append("</worksheet>")
        }

        ZipOutputStream(BufferedOutputStream(FileOutputStream(target))).use { zip ->
            put(zip, "[Content_Types].xml", contentTypes())
            put(zip, "_rels/.rels", rootRels())
            put(zip, "xl/workbook.xml", workbook())
            put(zip, "xl/_rels/workbook.xml.rels", workbookRels())
            put(zip, "xl/styles.xml", stylesXml())
            put(zip, "xl/worksheets/sheet1.xml", sheetXml)
            put(zip, "xl/worksheets/_rels/sheet1.xml.rels", sheetRels())
            put(zip, "xl/drawings/drawing1.xml", drawingXml(drawingAnchors.toString()))
            put(zip, "xl/drawings/_rels/drawing1.xml.rels", drawingRels())
            images.forEach { put(zip, "xl/media/${it.name}", it.bytes) }
        }
    }

    // ---------------------------------------------------------------- 图片

    private fun prepareImage(file: File, index: Int): Img? {
        return try {
            val bitmap = ImageOps.loadUpright(file, MAX_SIDE, 4f / 3f) ?: return null
            val buffer = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 82, buffer)
            bitmap.recycle()
            Img("image$index.jpg", buffer.toByteArray())
        } catch (t: Throwable) {
            null
        }
    }

    private fun imageSources(entry: Entry): List<File> {
        val candidates = if (entry.isVideo) listOfNotNull(entry.thumb) else entry.paths.take(2)
        return candidates.map { File(it) }.filter { it.exists() && it.length() > 0 }
    }

    // ---------------------------------------------------------------- 单元格

    private fun textCell(ref: String, value: String, style: Int = 0): String {
        val s = if (style > 0) " s=\"$style\"" else ""
        return "<c r=\"$ref\"$s t=\"inlineStr\"><is><t xml:space=\"preserve\">${esc(value)}</t></is></c>"
    }

    private fun placeOf(entry: Entry): String {
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
        val fallback = placeOf(entry)
        if (text.isEmpty()) return fallback to ""
        val idx = text.indexOfFirst { it == '，' || it == ',' || it == '：' || it == ':' }
        if (idx < 0) return text to ""
        val head = text.substring(0, idx).trim().ifBlank { fallback }
        val tail = text.substring(idx + 1).trim()
        return head to tail
    }

    private fun anchor(row: Int, fromOff: Long, toOff: Long, id: Int): String = buildString {
        append("<xdr:twoCellAnchor editAs=\"oneCell\">")
        append("<xdr:from><xdr:col>2</xdr:col><xdr:colOff>$fromOff</xdr:colOff>")
        append("<xdr:row>$row</xdr:row><xdr:rowOff>47625</xdr:rowOff></xdr:from>")
        append("<xdr:to><xdr:col>2</xdr:col><xdr:colOff>$toOff</xdr:colOff>")
        append("<xdr:row>$row</xdr:row><xdr:rowOff>1100000</xdr:rowOff></xdr:to>")
        append("<xdr:pic>")
        append("<xdr:nvPicPr><xdr:cNvPr id=\"$id\" name=\"Picture $id\"/><xdr:cNvPicPr/></xdr:nvPicPr>")
        append("<xdr:blipFill><a:blip xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" r:embed=\"rId${100 + id}\"/>")
        append("<a:stretch xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\"><a:fillRect/></a:stretch></xdr:blipFill>")
        append("<xdr:spPr><a:xfrm xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\">")
        append("<a:off x=\"0\" y=\"0\"/><a:ext cx=\"${toOff - fromOff}\" cy=\"1000000\"/></a:xfrm>")
        append("<a:prstGeom xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" prst=\"rect\"><a:avLst/></a:prstGeom>")
        append("</xdr:spPr>")
        append("</xdr:pic>")
        append("<xdr:clientData/>")
        append("</xdr:twoCellAnchor>")
    }

    // ---------------------------------------------------------------- 包结构

    private val relPrefix = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    private fun put(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

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
<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
<Override PartName="/xl/drawings/drawing1.xml" ContentType="application/vnd.openxmlformats-officedocument.drawing+xml"/>
<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
</Types>"""

    private fun rootRels(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="$relPrefix/officeDocument" Target="xl/workbook.xml"/>
</Relationships>"""

    private fun workbook(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="$relPrefix">
<sheets><sheet name="采集数据" sheetId="1" r:id="rId1"/></sheets>
</workbook>"""

    private fun workbookRels(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="$relPrefix/worksheet" Target="worksheets/sheet1.xml"/>
<Relationship Id="rId2" Type="$relPrefix/styles" Target="styles.xml"/>
</Relationships>"""

    private fun stylesXml(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<fonts count="2">
<font><sz val="11"/><name val="宋体"/></font>
<font><b/><sz val="14"/><name val="宋体"/></font>
</fonts>
<fills count="2">
<fill><patternFill patternType="none"/></fill>
<fill><patternFill patternType="gray125"/></fill>
</fills>
<borders count="2">
<border><left/><right/><top/><bottom/><diagonal/></border>
<border>
<left style="thin"><color rgb="FF808080"/></left>
<right style="thin"><color rgb="FF808080"/></right>
<top style="thin"><color rgb="FF808080"/></top>
<bottom style="thin"><color rgb="FF808080"/></bottom>
<diagonal/>
</border>
</borders>
<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
<cellXfs count="3">
<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
<xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="center" wrapText="1"/></xf>
<xf numFmtId="0" fontId="1" fillId="0" borderId="1" xfId="0" applyFont="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="center" wrapText="1"/></xf>
</cellXfs>
</styleSheet>"""

    private fun sheetRels(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="$relPrefix/drawing" Target="../drawings/drawing1.xml"/>
</Relationships>"""

    private val pendingRels = mutableListOf<Pair<String, String>>()

    private fun drawingRelsAdd(rel: String, name: String) {
        pendingRels.add(rel to name)
    }

    private fun drawingRels(): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
        sb.append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">\n")
        pendingRels.forEach { (rel, name) ->
            sb.append("<Relationship Id=\"$rel\" Type=\"$relPrefix/image\" Target=\"../media/$name\"/>\n")
        }
        sb.append("</Relationships>")
        return sb.toString()
    }

    private fun drawingXml(anchors: String): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
        append("<xdr:wsDr xmlns:xdr=\"http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing\" ")
        append("xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" ")
        append("xmlns:r=\"$relPrefix\">")
        append(anchors)
        append("</xdr:wsDr>")
    }

    private fun esc(value: String): String = buildString {
        value.forEach { c ->
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
