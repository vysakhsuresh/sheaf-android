package com.layerbit.sheaf.convert

import com.layerbit.sheaf.R
import com.layerbit.sheaf.pdf.Block
import com.layerbit.sheaf.pdf.FlowDocument
import com.layerbit.sheaf.text.Strings
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.File

/**
 * The readers: a Word file, a spreadsheet or a plain text file into a [FlowDocument].
 *
 * Each takes what the model can hold and skips everything else without complaint: tracked
 * changes, comments, shapes it does not know. A file that came from anywhere should open.
 */
object Readers {

    fun forFile(file: File, displayName: String): FlowDocument? {
        val title = displayName.substringBeforeLast('.')
        return when (displayName.substringAfterLast('.', "").lowercase()) {
            "docx" -> docx(file, title)
            "xlsx" -> xlsx(file, title)
            "txt", "md", "csv" -> text(file, title)
            else -> null
        }
    }

    // ------------------------------------------------------------------ Word

    fun docx(file: File, title: String): FlowDocument {
        val xml = file.zipEntry("word/document.xml") ?: throw IllegalArgumentException(Strings.get(R.string.engine_err_not_word))
        val blocks = mutableListOf<Block>()
        val parser = newParser(xml)

        var paragraph: MutableList<Block.Run>? = null
        var heading = 0
        var align = Block.Align.LEFT
        var list: Block.ListMark? = null
        var pageBreakBefore = false
        var runBold = false
        var runItalic = false
        var runSize = 0f
        var runText: StringBuilder? = null
        var inRunProps = false
        var inParaProps = false
        var numbered = 0

        // Tables: rows of cells, each cell the text of its paragraphs.
        var table: MutableList<MutableList<String>>? = null
        var row: MutableList<String>? = null
        var cell: StringBuilder? = null

        fun endParagraph() {
            val runs = paragraph ?: return
            paragraph = null
            val text = runs.joinToString("") { it.text }
            if (cell != null) {
                if (cell!!.isNotEmpty()) cell!!.append('\n')
                cell!!.append(text)
                return
            }
            if (pageBreakBefore) blocks += Block.PageBreak
            if (text.isBlank() && runs.none { it.text.contains('\n') }) {
                return
            }
            blocks += Block.Paragraph(runs = runs, heading = heading, align = align, list = list)
        }

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "p" -> { paragraph = mutableListOf(); heading = 0; align = Block.Align.LEFT; list = null; pageBreakBefore = false }
                    "pPr" -> inParaProps = true
                    "rPr" -> inRunProps = true
                    "pStyle" -> if (inParaProps) {
                        val style = attr(parser, "val").orEmpty()
                        Regex("(?i)heading\\s*(\\d)").find(style)?.let { heading = it.groupValues[1].toInt().coerceIn(1, 3) }
                        if (style.equals("Title", true)) heading = 1
                        if (style.contains("List", true)) list = Block.ListMark(ordered = false)
                    }
                    "numPr" -> if (inParaProps) { numbered += 1; list = Block.ListMark(ordered = false) }
                    "ilvl" -> if (inParaProps && list != null) list = list!!.copy(level = attr(parser, "val")?.toIntOrNull() ?: 0)
                    "jc" -> if (inParaProps) align = when (attr(parser, "val")) {
                        "center" -> Block.Align.CENTRE
                        "right", "end" -> Block.Align.RIGHT
                        "both", "distribute" -> Block.Align.JUSTIFY
                        else -> Block.Align.LEFT
                    }
                    "pageBreakBefore" -> if (inParaProps) pageBreakBefore = attr(parser, "val") != "0"
                    "r" -> { runBold = false; runItalic = false; runSize = 0f; runText = StringBuilder() }
                    "b" -> if (inRunProps) runBold = attr(parser, "val") != "0"
                    "i" -> if (inRunProps) runItalic = attr(parser, "val") != "0"
                    "sz" -> if (inRunProps) runSize = (attr(parser, "val")?.toFloatOrNull() ?: 0f) / 2f
                    "t" -> runText?.append(parser.nextText())
                    "tab" -> runText?.append('\t')
                    "br" -> if (attr(parser, "type") == "page") {
                        endParagraph(); blocks += Block.PageBreak; paragraph = mutableListOf()
                    } else runText?.append('\n')
                    "tbl" -> table = mutableListOf()
                    "tr" -> row = mutableListOf()
                    "tc" -> cell = StringBuilder()
                }
            } else if (event == XmlPullParser.END_TAG) {
                when (parser.name) {
                    "pPr" -> inParaProps = false
                    "rPr" -> inRunProps = false
                    "r" -> {
                        val text = runText?.toString().orEmpty()
                        runText = null
                        if (text.isNotEmpty()) paragraph?.add(Block.Run(text, bold = runBold, italic = runItalic, size = runSize))
                    }
                    "p" -> endParagraph()
                    "tc" -> { row?.add(cell?.toString().orEmpty()); cell = null }
                    "tr" -> { row?.let { table?.add(it) }; row = null }
                    "tbl" -> { table?.let { if (it.isNotEmpty()) blocks += Block.Table(it) }; table = null }
                }
            }
            event = parser.next()
        }
        return FlowDocument(title, blocks)
    }

    // ------------------------------------------------------------------ Excel

    fun xlsx(file: File, title: String): FlowDocument {
        val shared = mutableListOf<String>()
        file.zipEntry("xl/sharedStrings.xml")?.let { xml ->
            val parser = newParser(xml)
            var current: StringBuilder? = null
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "si" -> current = StringBuilder()
                        "t" -> current?.append(parser.nextText())
                    }
                } else if (event == XmlPullParser.END_TAG && parser.name == "si") {
                    shared += current?.toString().orEmpty()
                    current = null
                }
                event = parser.next()
            }
        }

        // The sheets, in the workbook's order, by name.
        val targets = HashMap<String, String>()
        file.zipEntry("xl/_rels/workbook.xml.rels")?.let { xml ->
            val parser = newParser(xml)
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "Relationship") {
                    val id = parser.getAttributeValue(null, "Id").orEmpty()
                    val target = parser.getAttributeValue(null, "Target").orEmpty().removePrefix("/xl/").removePrefix("/")
                    targets[id] = if (target.startsWith("xl/")) target else "xl/$target"
                }
                event = parser.next()
            }
        }
        val sheets = mutableListOf<Pair<String, String>>()
        file.zipEntry("xl/workbook.xml")?.let { xml ->
            val parser = newParser(xml)
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "sheet") {
                    val name = parser.getAttributeValue(null, "name").orEmpty()
                    var rid: String? = null
                    for (i in 0 until parser.attributeCount) if (parser.getAttributeName(i) == "id") rid = parser.getAttributeValue(i)
                    val path = rid?.let { targets[it] }
                    if (path != null) sheets += name to path
                }
                event = parser.next()
            }
        }
        if (sheets.isEmpty()) {
            sheets += file.zipNames().filter { it.startsWith("xl/worksheets/sheet") && it.endsWith(".xml") }.sorted()
                .mapIndexed { i, path -> "Sheet ${i + 1}" to path }
        }
        if (sheets.isEmpty()) throw IllegalArgumentException(Strings.get(R.string.engine_err_not_spreadsheet))

        val blocks = mutableListOf<Block>()
        sheets.forEachIndexed { index, (name, path) ->
            val xml = file.zipEntry(path) ?: return@forEachIndexed
            val rows = readSheet(xml, shared)
            if (index > 0) blocks += Block.PageBreak
            if (sheets.size > 1 || name.isNotBlank()) blocks += Block.Paragraph(listOf(Block.Run(name, bold = true)), heading = 2)
            if (rows.isNotEmpty()) blocks += Block.Table(rows, headerRows = 1)
        }
        return FlowDocument(title, blocks)
    }

    private fun readSheet(xml: ByteArray, shared: List<String>): List<List<String>> {
        val parser = newParser(xml)
        val rows = mutableListOf<MutableList<String>>()
        var row: MutableList<String>? = null
        var column = 0
        var type: String? = null
        var value: StringBuilder? = null
        var inlineText: StringBuilder? = null
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "row" -> row = mutableListOf()
                    "c" -> {
                        val ref = parser.getAttributeValue(null, "r").orEmpty()
                        column = columnIndex(ref)
                        type = parser.getAttributeValue(null, "t")
                        value = null
                        inlineText = null
                    }
                    "v" -> value = StringBuilder(parser.nextText())
                    "is" -> inlineText = StringBuilder()
                    "t" -> inlineText?.append(parser.nextText())
                }
            } else if (event == XmlPullParser.END_TAG) {
                when (parser.name) {
                    "c" -> {
                        val text = when (type) {
                            "s" -> value?.toString()?.toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty()
                            "inlineStr" -> inlineText?.toString().orEmpty()
                            "b" -> if (value?.toString() == "1") "TRUE" else "FALSE"
                            else -> tidyNumber(value?.toString().orEmpty())
                        }
                        row?.let { r ->
                            while (r.size <= column) r += ""
                            r[column] = text
                        }
                    }
                    "row" -> { row?.let { if (it.any { c -> c.isNotBlank() }) rows += it }; row = null }
                }
            }
            event = parser.next()
        }
        val width = rows.maxOfOrNull { it.size } ?: 0
        for (r in rows) while (r.size < width) r += ""
        return rows
    }

    /** "C7" is column 2. A reference with no letters is the next column along. */
    private fun columnIndex(ref: String): Int {
        val letters = ref.takeWhile { it.isLetter() }.uppercase()
        if (letters.isEmpty()) return 0
        var n = 0
        for (ch in letters) n = n * 26 + (ch - 'A' + 1)
        return n - 1
    }

    /** "12.0" reads as 12; a long fraction is kept as it is. */
    private fun tidyNumber(raw: String): String {
        val d = raw.toDoubleOrNull() ?: return raw
        return if (d == Math.floor(d) && Math.abs(d) < 1e15) d.toLong().toString() else raw
    }

    // ------------------------------------------------------------------ Text

    /** Paragraphs separated by blank lines; a lone line in capitals, or under a rule, is a heading. */
    fun text(file: File, title: String): FlowDocument {
        val content = file.readText()
        val blocks = mutableListOf<Block>()
        val paragraphs = content.replace("\r\n", "\n").split(Regex("\n\\s*\n"))
        for (raw in paragraphs) {
            val lines = raw.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
            if (lines.isEmpty()) continue
            // A CSV is a table; so is a run of tab-separated lines.
            if (lines.size >= 2 && lines.all { it.contains('\t') }) {
                blocks += Block.Table(lines.map { it.split('\t') }, headerRows = 1)
                continue
            }
            if (lines.size >= 2 && file.name.endsWith(".csv", true) && lines.all { it.contains(',') }) {
                blocks += Block.Table(lines.map { csvCells(it) }, headerRows = 1)
                continue
            }
            val text = lines.joinToString(" ") { it.trim() }
            val heading = when {
                text.startsWith("# ") -> 1
                text.startsWith("## ") -> 2
                text.startsWith("### ") -> 3
                lines.size == 1 && text.length < 80 && text == text.uppercase() && text.any { it.isLetter() } -> 2
                else -> 0
            }
            val bullet = Regex("^[-*•]\\s+").find(text)
            blocks += Block.Paragraph(
                runs = listOf(Block.Run(text.removePrefix("### ").removePrefix("## ").removePrefix("# ").let { if (bullet != null) it.drop(bullet.value.length) else it }, bold = heading > 0)),
                heading = heading,
                list = if (bullet != null) Block.ListMark(ordered = false) else null
            )
        }
        return FlowDocument(title, blocks)
    }

    private fun csvCells(line: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                ch == '"' -> quoted = !quoted
                ch == ',' && !quoted -> { out += sb.toString(); sb.clear() }
                else -> sb.append(ch)
            }
            i++
        }
        out += sb.toString()
        return out
    }

    /**
     * An attribute by its local name, whatever namespace it carries. With namespaces on,
     * `w:val` is not found by asking for "val" in no namespace, which is how every heading
     * and page break went unnoticed until this was written.
     */
    private fun attr(parser: XmlPullParser, name: String): String? {
        for (i in 0 until parser.attributeCount) if (parser.getAttributeName(i) == name) return parser.getAttributeValue(i)
        return null
    }

    private fun newParser(xml: ByteArray): XmlPullParser {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        val parser = factory.newPullParser()
        parser.setInput(ByteArrayInputStream(xml), "UTF-8")
        return parser
    }
}
