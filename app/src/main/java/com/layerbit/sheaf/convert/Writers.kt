package com.layerbit.sheaf.convert

import com.layerbit.sheaf.pdf.Block
import com.layerbit.sheaf.pdf.FlowDocument
import com.layerbit.sheaf.text.Strings
import java.io.File

/**
 * The writers: a [FlowDocument] as a Word file, a spreadsheet, an EPUB book or a web page.
 *
 * Each writes the smallest package the format's readers all open. Word's own built-in
 * styles are used rather than formatting every paragraph by hand, so the result behaves
 * like a document somebody typed: the navigation pane shows the headings and changing a
 * style changes every paragraph that uses it.
 */
object Writers {

    const val MIME_DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    const val MIME_XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    const val MIME_EPUB = "application/epub+zip"
    const val MIME_HTML = "text/html"

    // ------------------------------------------------------------------ Word

    fun docx(doc: FlowDocument, output: File) {
        ZipWriter(output).use { zip ->
            zip.put(
                "[Content_Types].xml",
                Xml.DECLARATION +
                    "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                    "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                    "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                    "<Default Extension=\"png\" ContentType=\"image/png\"/>" +
                    "<Default Extension=\"jpeg\" ContentType=\"image/jpeg\"/>" +
                    "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
                    "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>" +
                    "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>" +
                    "</Types>"
            )
            zip.put(
                "_rels/.rels",
                Xml.DECLARATION +
                    "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                    "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>" +
                    "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/>" +
                    "</Relationships>"
            )
            zip.put("docProps/core.xml", coreProperties(doc.title))

            val media = mutableListOf<Pair<String, ByteArray>>()
            val rels = StringBuilder()
            rels.append(Xml.DECLARATION)
                .append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
                .append("<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>")

            val body = StringBuilder()
            var listNumber = 0
            var pictureCount = 0
            for (block in doc.blocks) {
                when (block) {
                    is Block.Paragraph -> {
                        if (block.list?.ordered == true) listNumber = block.list.number else if (block.list == null) listNumber = 0
                        body.append(paragraphXml(block))
                    }
                    is Block.Table -> body.append(tableXml(block))
                    is Block.PageBreak -> body.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>")
                    is Block.Picture -> {
                        pictureCount += 1
                        val name = "image$pictureCount.${if (block.jpeg) "jpeg" else "png"}"
                        val rid = "rIdImg$pictureCount"
                        media += name to block.bytes
                        rels.append("<Relationship Id=\"$rid\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/$name\"/>")
                        body.append(pictureXml(rid, pictureCount, block))
                    }
                }
            }
            rels.append("</Relationships>")
            zip.put("word/_rels/document.xml.rels", rels.toString())
            for ((name, bytes) in media) zip.put("word/media/$name", bytes)

            zip.put(
                "word/document.xml",
                Xml.DECLARATION +
                    "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" " +
                    "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" " +
                    "xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" " +
                    "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" " +
                    "xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">" +
                    "<w:body>" + body +
                    "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>" +
                    "<w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\" w:header=\"708\" w:footer=\"708\" w:gutter=\"0\"/>" +
                    "</w:sectPr></w:body></w:document>"
            )
            zip.put("word/styles.xml", STYLES)
        }
    }

    private fun paragraphXml(p: Block.Paragraph): String {
        val props = StringBuilder()
        if (p.heading in 1..3) props.append("<w:pStyle w:val=\"Heading${p.heading}\"/>")
        else if (p.list != null) props.append("<w:pStyle w:val=\"ListParagraph\"/>")
        when (p.align) {
            Block.Align.CENTRE -> props.append("<w:jc w:val=\"center\"/>")
            Block.Align.RIGHT -> props.append("<w:jc w:val=\"right\"/>")
            Block.Align.JUSTIFY -> props.append("<w:jc w:val=\"both\"/>")
            Block.Align.LEFT -> Unit
        }
        if (p.list != null) {
            val indent = 360 + p.list.level * 360
            props.append("<w:ind w:left=\"$indent\" w:hanging=\"360\"/>")
        }
        val runs = StringBuilder()
        if (p.list != null) {
            val mark = if (p.list.ordered) "${p.list.number}.\t" else "•\t"
            runs.append(runXml(Block.Run(mark), p.size))
        }
        for (run in p.runs) runs.append(runXml(run, p.size))
        return "<w:p><w:pPr>$props</w:pPr>$runs</w:p>"
    }

    private fun runXml(run: Block.Run, paragraphSize: Float): String {
        val size = if (run.size > 0f) run.size else paragraphSize
        val props = StringBuilder()
        if (run.bold) props.append("<w:b/>")
        if (run.italic) props.append("<w:i/>")
        if (size > 0f) props.append("<w:sz w:val=\"${Math.round(size * 2)}\"/>")
        val pieces = run.text.split('\n')
        val text = pieces.mapIndexed { i, piece ->
            (if (i > 0) "<w:br/>" else "") +
                piece.split('\t').mapIndexed { k, part ->
                    (if (k > 0) "<w:tab/>" else "") + "<w:t xml:space=\"preserve\">${Xml.text(part)}</w:t>"
                }.joinToString("")
        }.joinToString("")
        return "<w:r><w:rPr>$props</w:rPr>$text</w:r>"
    }

    private fun tableXml(table: Block.Table): String {
        val columns = table.columns.coerceAtLeast(1)
        val width = 9026 / columns
        val sb = StringBuilder()
        sb.append("<w:tbl><w:tblPr><w:tblStyle w:val=\"TableGrid\"/><w:tblW w:w=\"0\" w:type=\"auto\"/></w:tblPr><w:tblGrid>")
        repeat(columns) { sb.append("<w:gridCol w:w=\"$width\"/>") }
        sb.append("</w:tblGrid>")
        table.rows.forEachIndexed { r, row ->
            sb.append("<w:tr>")
            if (r < table.headerRows) sb.append("<w:trPr><w:tblHeader/></w:trPr>")
            for (c in 0 until columns) {
                val cell = row.getOrNull(c).orEmpty()
                sb.append("<w:tc><w:tcPr><w:tcW w:w=\"$width\" w:type=\"dxa\"/></w:tcPr>")
                for (line in cell.split('\n')) {
                    sb.append("<w:p>").append(runXml(Block.Run(line, bold = r < table.headerRows), 10f)).append("</w:p>")
                }
                sb.append("</w:tc>")
            }
            sb.append("</w:tr>")
        }
        sb.append("</w:tbl><w:p/>")
        return sb.toString()
    }

    private fun pictureXml(rid: String, n: Int, picture: Block.Picture): String {
        // Word measures drawings in EMUs: 12700 to the point. A picture wider than the text
        // column is scaled down to fit it.
        val maxWidth = 451f
        val scale = if (picture.widthPoints > maxWidth) maxWidth / picture.widthPoints else 1f
        val cx = Math.round(picture.widthPoints * scale * 12700)
        val cy = Math.round(picture.heightPoints * scale * 12700)
        return "<w:p><w:r><w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">" +
            "<wp:extent cx=\"$cx\" cy=\"$cy\"/><wp:docPr id=\"$n\" name=\"Picture $n\"/>" +
            "<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">" +
            "<pic:pic><pic:nvPicPr><pic:cNvPr id=\"$n\" name=\"Picture $n\"/><pic:cNvPicPr/></pic:nvPicPr>" +
            "<pic:blipFill><a:blip r:embed=\"$rid\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>" +
            "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr>" +
            "</pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>"
    }

    private fun coreProperties(title: String): String =
        Xml.DECLARATION +
            "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:dcterms=\"http://purl.org/dc/terms/\" " +
            "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">" +
            "<dc:title>${Xml.text(title)}</dc:title><dc:creator>Sheaf</dc:creator>" +
            "</cp:coreProperties>"

    private val STYLES = Xml.DECLARATION +
        "<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
        "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:cs=\"Calibri\"/><w:sz w:val=\"22\"/><w:lang w:val=\"en-IN\"/></w:rPr></w:rPrDefault>" +
        "<w:pPrDefault><w:pPr><w:spacing w:after=\"120\" w:line=\"276\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>" +
        "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:qFormat/></w:style>" +
        "<w:style w:type=\"paragraph\" w:styleId=\"Heading1\"><w:name w:val=\"heading 1\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/>" +
        "<w:pPr><w:keepNext/><w:spacing w:before=\"360\" w:after=\"120\"/><w:outlineLvl w:val=\"0\"/></w:pPr><w:rPr><w:b/><w:sz w:val=\"36\"/></w:rPr></w:style>" +
        "<w:style w:type=\"paragraph\" w:styleId=\"Heading2\"><w:name w:val=\"heading 2\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/>" +
        "<w:pPr><w:keepNext/><w:spacing w:before=\"240\" w:after=\"80\"/><w:outlineLvl w:val=\"1\"/></w:pPr><w:rPr><w:b/><w:sz w:val=\"28\"/></w:rPr></w:style>" +
        "<w:style w:type=\"paragraph\" w:styleId=\"Heading3\"><w:name w:val=\"heading 3\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/>" +
        "<w:pPr><w:keepNext/><w:spacing w:before=\"200\" w:after=\"60\"/><w:outlineLvl w:val=\"2\"/></w:pPr><w:rPr><w:b/><w:sz w:val=\"24\"/></w:rPr></w:style>" +
        "<w:style w:type=\"paragraph\" w:styleId=\"ListParagraph\"><w:name w:val=\"List Paragraph\"/><w:basedOn w:val=\"Normal\"/><w:qFormat/><w:pPr><w:ind w:left=\"720\"/><w:contextualSpacing/></w:pPr></w:style>" +
        "<w:style w:type=\"table\" w:default=\"1\" w:styleId=\"TableNormal\"><w:name w:val=\"Normal Table\"/><w:tblPr><w:tblInd w:w=\"0\" w:type=\"dxa\"/><w:tblCellMar><w:top w:w=\"0\" w:type=\"dxa\"/><w:left w:w=\"108\" w:type=\"dxa\"/><w:bottom w:w=\"0\" w:type=\"dxa\"/><w:right w:w=\"108\" w:type=\"dxa\"/></w:tblCellMar></w:tblPr></w:style>" +
        "<w:style w:type=\"table\" w:styleId=\"TableGrid\"><w:name w:val=\"Table Grid\"/><w:basedOn w:val=\"TableNormal\"/><w:tblPr><w:tblBorders>" +
        "<w:top w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/><w:left w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/>" +
        "<w:bottom w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/><w:right w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/>" +
        "<w:insideH w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/><w:insideV w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/>" +
        "</w:tblBorders></w:tblPr></w:style></w:styles>"

    // ------------------------------------------------------------------ Excel

    /**
     * One sheet per page, every line a row and every cell a cell. Strings are written
     * inline, which every spreadsheet opens and which needs no shared-strings table. A cell
     * that is only a number is written as one, so sums work.
     */
    fun xlsx(doc: FlowDocument, output: File) {
        val sheets = mutableListOf<Pair<String, List<List<String>>>>()
        var rows = mutableListOf<List<String>>()
        var pageNumber = 1
        fun flush() {
            if (rows.isNotEmpty()) sheets += "Page $pageNumber" to rows
            rows = mutableListOf()
        }
        for (block in doc.blocks) {
            when (block) {
                is Block.Paragraph -> rows += listOf(block.text)
                is Block.Table -> rows += block.rows
                is Block.PageBreak -> { flush(); pageNumber += 1 }
                is Block.Picture -> Unit
            }
        }
        flush()
        if (sheets.isEmpty()) sheets += "Sheet1" to listOf(listOf(""))

        ZipWriter(output).use { zip ->
            val types = StringBuilder(Xml.DECLARATION)
                .append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
                .append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
                .append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
                .append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>")
                .append("<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>")
            sheets.indices.forEach { i ->
                types.append("<Override PartName=\"/xl/worksheets/sheet${i + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>")
            }
            types.append("</Types>")
            zip.put("[Content_Types].xml", types.toString())
            zip.put(
                "_rels/.rels",
                Xml.DECLARATION +
                    "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                    "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>" +
                    "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/>" +
                    "</Relationships>"
            )
            zip.put("docProps/core.xml", coreProperties(doc.title))

            val workbook = StringBuilder(Xml.DECLARATION)
                .append("<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>")
            val wbRels = StringBuilder(Xml.DECLARATION)
                .append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
            sheets.forEachIndexed { i, (name, _) ->
                workbook.append("<sheet name=\"${Xml.attr(name.take(31))}\" sheetId=\"${i + 1}\" r:id=\"rId${i + 1}\"/>")
                wbRels.append("<Relationship Id=\"rId${i + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet${i + 1}.xml\"/>")
            }
            workbook.append("</sheets></workbook>")
            wbRels.append("</Relationships>")
            zip.put("xl/workbook.xml", workbook.toString())
            zip.put("xl/_rels/workbook.xml.rels", wbRels.toString())

            sheets.forEachIndexed { i, (_, sheetRows) ->
                val sb = StringBuilder(Xml.DECLARATION)
                    .append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>")
                sheetRows.forEachIndexed { r, row ->
                    sb.append("<row r=\"${r + 1}\">")
                    row.forEachIndexed { c, value ->
                        val ref = "${columnName(c)}${r + 1}"
                        val number = value.trim().replace(",", "").toDoubleOrNull()
                        if (number != null && value.trim().matches(Regex("-?[\\d,]*\\.?\\d+"))) {
                            sb.append("<c r=\"$ref\"><v>${value.trim().replace(",", "")}</v></c>")
                        } else if (value.isNotEmpty()) {
                            sb.append("<c r=\"$ref\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${Xml.text(value)}</t></is></c>")
                        }
                    }
                    sb.append("</row>")
                }
                sb.append("</sheetData></worksheet>")
                zip.put("xl/worksheets/sheet${i + 1}.xml", sb.toString())
            }
        }
    }

    /** 0 is A, 25 is Z, 26 is AA. */
    fun columnName(index: Int): String {
        var n = index
        val sb = StringBuilder()
        do {
            sb.append('A' + n % 26)
            n = n / 26 - 1
        } while (n >= 0)
        return sb.reverse().toString()
    }

    // ------------------------------------------------------------------ HTML

    fun html(doc: FlowDocument, output: File) {
        output.writeText(htmlDocument(doc.title, bodyHtml(doc.blocks, null), standalone = true))
    }

    private fun htmlDocument(title: String, body: String, standalone: Boolean): String = buildString {
        if (standalone) {
            append("<!DOCTYPE html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">")
            append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
        } else {
            append(Xml.DECLARATION)
            append("<!DOCTYPE html>\n<html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\"><head><meta charset=\"utf-8\"/>")
        }
        append("<title>").append(Xml.text(title)).append("</title>")
        if (standalone) append("<style>").append(CSS).append("</style>") else append("<link rel=\"stylesheet\" href=\"style.css\"/>")
        append("</head><body>").append(body).append("</body></html>")
    }

    private const val CSS = "body{max-width:42em;margin:2em auto;padding:0 1em;font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;line-height:1.5;color:#1b1b1f}" +
        "h1,h2,h3{line-height:1.25}table{border-collapse:collapse;margin:1em 0;width:100%}td,th{border:1px solid #c8c8cc;padding:.3em .5em;vertical-align:top}" +
        "th{background:#f1f1f3;text-align:left}hr.page{border:0;border-top:1px dashed #c8c8cc;margin:2em 0}img{max-width:100%;height:auto}" +
        ".c{text-align:center}.r{text-align:right}.j{text-align:justify}"

    private fun bodyHtml(blocks: List<Block>, imageNamer: ((Block.Picture) -> String)?): String {
        val sb = StringBuilder()
        var openList: Boolean? = null
        fun closeList() {
            if (openList == true) sb.append("</ol>") else if (openList == false) sb.append("</ul>")
            openList = null
        }
        for (block in blocks) {
            when (block) {
                is Block.Paragraph -> {
                    if (block.list != null) {
                        if (openList != block.list.ordered) { closeList(); openList = block.list.ordered; sb.append(if (block.list.ordered) "<ol>" else "<ul>") }
                        sb.append("<li>").append(runsHtml(block.runs)).append("</li>")
                    } else {
                        closeList()
                        val tag = if (block.heading in 1..3) "h${block.heading}" else "p"
                        val cls = when (block.align) {
                            Block.Align.CENTRE -> " class=\"c\""
                            Block.Align.RIGHT -> " class=\"r\""
                            Block.Align.JUSTIFY -> " class=\"j\""
                            Block.Align.LEFT -> ""
                        }
                        sb.append("<").append(tag).append(cls).append(">").append(runsHtml(block.runs)).append("</").append(tag).append(">")
                    }
                }
                is Block.Table -> {
                    closeList()
                    sb.append("<table>")
                    block.rows.forEachIndexed { r, row ->
                        sb.append("<tr>")
                        val cell = if (r < block.headerRows) "th" else "td"
                        for (c in 0 until block.columns) {
                            sb.append("<").append(cell).append(">").append(Xml.text(row.getOrNull(c).orEmpty()).replace("\n", "<br/>")).append("</").append(cell).append(">")
                        }
                        sb.append("</tr>")
                    }
                    sb.append("</table>")
                }
                is Block.PageBreak -> { closeList(); sb.append("<hr class=\"page\"/>") }
                is Block.Picture -> {
                    closeList()
                    val src = imageNamer?.invoke(block) ?: ("data:image/" + (if (block.jpeg) "jpeg" else "png") + ";base64," + android.util.Base64.encodeToString(block.bytes, android.util.Base64.NO_WRAP))
                    sb.append("<p><img src=\"").append(src).append("\" alt=\"\"/></p>")
                }
            }
        }
        closeList()
        return sb.toString()
    }

    private fun runsHtml(runs: List<Block.Run>): String = buildString {
        for (run in runs) {
            var text = Xml.text(run.text).replace("\n", "<br/>")
            if (run.bold) text = "<strong>$text</strong>"
            if (run.italic) text = "<em>$text</em>"
            append(text)
        }
    }

    // ------------------------------------------------------------------ EPUB

    /**
     * An EPUB 3 book. The text reflows, so the PDF's page breaks mean nothing here and are
     * dropped; the book is cut into chapters at its top-level headings instead, which is
     * where a reader expects a chapter to start.
     */
    fun epub(doc: FlowDocument, output: File) {
        val title = doc.title.ifBlank { "Document" }
        val chapters = mutableListOf<Pair<String, MutableList<Block>>>()
        for (block in doc.blocks) {
            if (block is Block.PageBreak) continue
            if (block is Block.Paragraph && block.heading == 1 || chapters.isEmpty()) {
                val name = (block as? Block.Paragraph)?.takeIf { it.heading == 1 }?.text?.trim()?.take(80) ?: title
                chapters += name to mutableListOf()
            }
            chapters.last().second += block
        }
        if (chapters.isEmpty()) chapters += title to mutableListOf()

        ZipWriter(output).use { zip ->
            zip.putStored("mimetype", "application/epub+zip")
            zip.put(
                "META-INF/container.xml",
                Xml.DECLARATION +
                    "<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">" +
                    "<rootfiles><rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/></rootfiles></container>"
            )
            zip.put("OEBPS/style.css", "body{line-height:1.5}\n$CSS")

            val manifest = StringBuilder()
            val spine = StringBuilder()
            val nav = StringBuilder()
            var pictures = 0
            chapters.forEachIndexed { i, (name, blocks) ->
                val file = "chapter${i + 1}.xhtml"
                val body = bodyHtml(blocks) { picture ->
                    pictures += 1
                    val imageName = "image$pictures.${if (picture.jpeg) "jpeg" else "png"}"
                    zip.put("OEBPS/$imageName", picture.bytes)
                    manifest.append("<item id=\"img$pictures\" href=\"$imageName\" media-type=\"image/${if (picture.jpeg) "jpeg" else "png"}\"/>")
                    imageName
                }
                zip.put("OEBPS/$file", htmlDocument(name, body, standalone = false))
                manifest.append("<item id=\"ch${i + 1}\" href=\"$file\" media-type=\"application/xhtml+xml\"/>")
                spine.append("<itemref idref=\"ch${i + 1}\"/>")
                nav.append("<li><a href=\"$file\">${Xml.text(name)}</a></li>")
            }
            zip.put(
                "OEBPS/nav.xhtml",
                Xml.DECLARATION +
                    "<html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\"><head><title>Contents</title></head>" +
                    "<body><nav epub:type=\"toc\"><h1>Contents</h1><ol>$nav</ol></nav></body></html>"
            )
            val id = "urn:uuid:" + java.util.UUID.randomUUID()
            val modified = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date())
            zip.put(
                "OEBPS/content.opf",
                Xml.DECLARATION +
                    "<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"bookid\">" +
                    "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">" +
                    "<dc:identifier id=\"bookid\">$id</dc:identifier><dc:title>${Xml.text(title)}</dc:title><dc:language>en</dc:language>" +
                    "<meta property=\"dcterms:modified\">$modified</meta></metadata>" +
                    "<manifest><item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>" +
                    "<item id=\"css\" href=\"style.css\" media-type=\"text/css\"/>$manifest</manifest>" +
                    "<spine>$spine</spine></package>"
            )
        }
    }
}

/** One slide: a picture of the page, or its lines as text boxes where they sat, on a page this size. */
data class SlideSpec(
    val widthPoints: Float,
    val heightPoints: Float,
    val picture: File?,
    val lines: List<com.layerbit.sheaf.pdf.TextLine>
)

/**
 * PowerPoint: one slide per page.
 *
 * The deck takes the first page's shape, so an A4 report makes an upright deck and a set
 * of slides exported to PDF comes back widescreen. Each slide is either a picture of the
 * page, which looks exactly right and cannot be edited, or the page's lines as text boxes
 * where they sat, which can. The package is the smallest PowerPoint opens: a master, a
 * layout and a theme are all required even when the slides use none of them.
 */
object PptxWriter {

    const val MIME_PPTX = "application/vnd.openxmlformats-officedocument.presentationml.presentation"

    private const val EMU_PER_POINT = 12700

    fun write(title: String, slides: List<SlideSpec>, output: File) {
        require(slides.isNotEmpty()) { Strings.get(com.layerbit.sheaf.R.string.engine_err_deck_empty) }
        val first = slides.first()
        val slideWidth = (first.widthPoints.coerceIn(72f, 4000f) * EMU_PER_POINT).toLong()
        val slideHeight = (first.heightPoints.coerceIn(72f, 4000f) * EMU_PER_POINT).toLong()

        ZipWriter(output).use { zip ->
            val types = StringBuilder(Xml.DECLARATION)
                .append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
                .append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
                .append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
                .append("<Default Extension=\"jpeg\" ContentType=\"image/jpeg\"/>")
                .append("<Default Extension=\"png\" ContentType=\"image/png\"/>")
                .append("<Override PartName=\"/ppt/presentation.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml\"/>")
                .append("<Override PartName=\"/ppt/slideMasters/slideMaster1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml\"/>")
                .append("<Override PartName=\"/ppt/slideLayouts/slideLayout1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml\"/>")
                .append("<Override PartName=\"/ppt/theme/theme1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.theme+xml\"/>")
                .append("<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>")
            slides.indices.forEach { i ->
                types.append("<Override PartName=\"/ppt/slides/slide${i + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/>")
            }
            types.append("</Types>")
            zip.put("[Content_Types].xml", types.toString())
            zip.put(
                "_rels/.rels",
                Xml.DECLARATION +
                    "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                    "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"ppt/presentation.xml\"/>" +
                    "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/>" +
                    "</Relationships>"
            )
            zip.put(
                "docProps/core.xml",
                Xml.DECLARATION +
                    "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\">" +
                    "<dc:title>${Xml.text(title)}</dc:title><dc:creator>Sheaf</dc:creator></cp:coreProperties>"
            )

            val presentation = StringBuilder(Xml.DECLARATION)
                .append("<p:presentation xmlns:a=\"$A\" xmlns:r=\"$R\" xmlns:p=\"$P\">")
                .append("<p:sldMasterIdLst><p:sldMasterId id=\"2147483648\" r:id=\"rId1\"/></p:sldMasterIdLst><p:sldIdLst>")
            val presentationRels = StringBuilder(Xml.DECLARATION)
                .append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
                .append("<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster\" Target=\"slideMasters/slideMaster1.xml\"/>")
                .append("<Relationship Id=\"rIdTheme\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme\" Target=\"theme/theme1.xml\"/>")
            slides.indices.forEach { i ->
                presentation.append("<p:sldId id=\"${256 + i}\" r:id=\"rId${i + 2}\"/>")
                presentationRels.append("<Relationship Id=\"rId${i + 2}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide\" Target=\"slides/slide${i + 1}.xml\"/>")
            }
            presentation.append("</p:sldIdLst><p:sldSz cx=\"$slideWidth\" cy=\"$slideHeight\"/><p:notesSz cx=\"6858000\" cy=\"9144000\"/></p:presentation>")
            presentationRels.append("</Relationships>")
            zip.put("ppt/presentation.xml", presentation.toString())
            zip.put("ppt/_rels/presentation.xml.rels", presentationRels.toString())

            zip.put("ppt/slideMasters/slideMaster1.xml", MASTER)
            zip.put(
                "ppt/slideMasters/_rels/slideMaster1.xml.rels",
                Xml.DECLARATION +
                    "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                    "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/>" +
                    "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme\" Target=\"../theme/theme1.xml\"/>" +
                    "</Relationships>"
            )
            zip.put("ppt/slideLayouts/slideLayout1.xml", LAYOUT)
            zip.put(
                "ppt/slideLayouts/_rels/slideLayout1.xml.rels",
                Xml.DECLARATION +
                    "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                    "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster\" Target=\"../slideMasters/slideMaster1.xml\"/>" +
                    "</Relationships>"
            )
            zip.put("ppt/theme/theme1.xml", THEME)

            slides.forEachIndexed { i, slide ->
                val n = i + 1
                val rels = StringBuilder(Xml.DECLARATION)
                    .append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
                    .append("<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/>")
                val shapes = StringBuilder()
                var id = 2
                val picture = slide.picture
                if (picture != null && picture.isFile) {
                    val extension = if (picture.name.endsWith(".png", true)) "png" else "jpeg"
                    val media = "image$n.$extension"
                    zip.put("ppt/media/$media", picture.readBytes())
                    rels.append("<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"../media/$media\"/>")
                    shapes.append("<p:pic><p:nvPicPr><p:cNvPr id=\"$id\" name=\"Page $n\"/><p:cNvPicPr><a:picLocks noChangeAspect=\"1\"/></p:cNvPicPr><p:nvPr/></p:nvPicPr>")
                        .append("<p:blipFill><a:blip r:embed=\"rId2\"/><a:stretch><a:fillRect/></a:stretch></p:blipFill>")
                        .append("<p:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$slideWidth\" cy=\"$slideHeight\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr></p:pic>")
                    id += 1
                } else {
                    // Text boxes where the lines sat, so the slide reads like the page and
                    // every line can be edited.
                    for (line in slide.lines) {
                        val text = line.text
                        if (text.isBlank()) continue
                        val x = (line.left * slideWidth).toLong()
                        val y = (line.top * slideHeight).toLong()
                        val w = (line.width * slideWidth).toLong().coerceAtLeast(EMU_PER_POINT * 20L)
                        val h = (line.height * slideHeight).toLong().coerceAtLeast(EMU_PER_POINT * 8L)
                        val size = Math.round(line.sizePoints.coerceIn(6f, 96f) * 100)
                        shapes.append("<p:sp><p:nvSpPr><p:cNvPr id=\"$id\" name=\"Line $id\"/><p:cNvSpPr txBox=\"1\"/><p:nvPr/></p:nvSpPr>")
                            .append("<p:spPr><a:xfrm><a:off x=\"$x\" y=\"$y\"/><a:ext cx=\"$w\" cy=\"$h\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom><a:noFill/></p:spPr>")
                            .append("<p:txBody><a:bodyPr wrap=\"none\" lIns=\"0\" tIns=\"0\" rIns=\"0\" bIns=\"0\" anchor=\"t\"/><a:lstStyle/><a:p>")
                            .append("<a:r><a:rPr lang=\"en-IN\" sz=\"$size\"${if (line.bold) " b=\"1\"" else ""}${if (line.italic) " i=\"1\"" else ""} dirty=\"0\"/><a:t>${Xml.text(text)}</a:t></a:r>")
                            .append("</a:p></p:txBody></p:sp>")
                        id += 1
                    }
                }
                rels.append("</Relationships>")
                zip.put("ppt/slides/_rels/slide$n.xml.rels", rels.toString())
                zip.put(
                    "ppt/slides/slide$n.xml",
                    Xml.DECLARATION +
                        "<p:sld xmlns:a=\"$A\" xmlns:r=\"$R\" xmlns:p=\"$P\"><p:cSld><p:spTree>" +
                        "<p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>" +
                        "<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>" +
                        shapes +
                        "</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>"
                )
            }
        }
    }

    private const val A = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val P = "http://schemas.openxmlformats.org/presentationml/2006/main"

    private val EMPTY_TREE = "<p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>" +
        "<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr></p:spTree></p:cSld>"

    private val MASTER = Xml.DECLARATION +
        "<p:sldMaster xmlns:a=\"$A\" xmlns:r=\"$R\" xmlns:p=\"$P\">$EMPTY_TREE" +
        "<p:clrMap bg1=\"lt1\" tx1=\"dk1\" bg2=\"lt2\" tx2=\"dk2\" accent1=\"accent1\" accent2=\"accent2\" accent3=\"accent3\" accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" hlink=\"hlink\" folHlink=\"folHlink\"/>" +
        "<p:sldLayoutIdLst><p:sldLayoutId id=\"2147483649\" r:id=\"rId1\"/></p:sldLayoutIdLst>" +
        "<p:txStyles><p:titleStyle><a:lvl1pPr/></p:titleStyle><p:bodyStyle><a:lvl1pPr/></p:bodyStyle><p:otherStyle><a:lvl1pPr/></p:otherStyle></p:txStyles></p:sldMaster>"

    private val LAYOUT = Xml.DECLARATION +
        "<p:sldLayout xmlns:a=\"$A\" xmlns:r=\"$R\" xmlns:p=\"$P\" type=\"blank\" preserve=\"1\">$EMPTY_TREE" +
        "<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>"

    private val THEME = Xml.DECLARATION +
        "<a:theme xmlns:a=\"$A\" name=\"Sheaf\"><a:themeElements>" +
        "<a:clrScheme name=\"Sheaf\"><a:dk1><a:sysClr val=\"windowText\" lastClr=\"000000\"/></a:dk1><a:lt1><a:sysClr val=\"window\" lastClr=\"FFFFFF\"/></a:lt1>" +
        "<a:dk2><a:srgbClr val=\"1F2A44\"/></a:dk2><a:lt2><a:srgbClr val=\"EEEEEE\"/></a:lt2><a:accent1><a:srgbClr val=\"2F5597\"/></a:accent1><a:accent2><a:srgbClr val=\"C55A11\"/></a:accent2>" +
        "<a:accent3><a:srgbClr val=\"7F7F7F\"/></a:accent3><a:accent4><a:srgbClr val=\"FFC000\"/></a:accent4><a:accent5><a:srgbClr val=\"4472C4\"/></a:accent5><a:accent6><a:srgbClr val=\"70AD47\"/></a:accent6>" +
        "<a:hlink><a:srgbClr val=\"0563C1\"/></a:hlink><a:folHlink><a:srgbClr val=\"954F72\"/></a:folHlink></a:clrScheme>" +
        "<a:fontScheme name=\"Sheaf\"><a:majorFont><a:latin typeface=\"Calibri\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:majorFont><a:minorFont><a:latin typeface=\"Calibri\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:minorFont></a:fontScheme>" +
        "<a:fmtScheme name=\"Sheaf\"><a:fillStyleLst><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:fillStyleLst>" +
        "<a:lnStyleLst><a:ln w=\"6350\"><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:ln><a:ln w=\"12700\"><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:ln><a:ln w=\"19050\"><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:ln></a:lnStyleLst>" +
        "<a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst>" +
        "<a:bgFillStyleLst><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:bgFillStyleLst>" +
        "</a:fmtScheme></a:themeElements><a:objectDefaults/><a:extraClrSchemeLst/></a:theme>"
}
