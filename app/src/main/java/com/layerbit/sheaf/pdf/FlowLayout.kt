package com.layerbit.sheaf.pdf

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject

/**
 * Lays a [FlowDocument] out on A4 pages.
 *
 * A straightforward typesetter: paragraphs wrap word by word, headings are bigger and bold
 * with room above them, lists get a bullet or a number and an indent, tables get equal
 * columns with ruled cells and break between rows, pictures are scaled to the column. A
 * page ends when the next line would not fit. Nothing clever, which is what makes it
 * predictable, and it uses the phone's own fonts for any script Helvetica cannot set.
 */
internal class FlowLayout(
    private val doc: PDDocument,
    private val onProgress: (Int, Int) -> Unit
) {
    private val fonts = SystemFonts(doc)
    private val pageBox = PDRectangle.A4
    private val margin = 56f
    private val contentWidth = pageBox.width - margin * 2f
    private var stream: PDPageContentStream? = null
    private var cursor = 0f
    private var pages = 0

    /** A word with the font that sets it, ready to be measured and placed. */
    private class Word(val text: String, val font: PDFont, val size: Float, val width: Float)

    fun layout(document: FlowDocument): Int {
        val total = document.blocks.size
        newPage()
        for ((i, block) in document.blocks.withIndex()) {
            when (block) {
                is Block.Paragraph -> paragraph(block)
                is Block.Table -> table(block)
                is Block.Picture -> picture(block)
                is Block.PageBreak -> newPage()
            }
            onProgress(i + 1, total)
        }
        stream?.close()
        stream = null
        return pages
    }

    private fun newPage() {
        stream?.close()
        val page = PDPage(pageBox)
        doc.addPage(page)
        stream = PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true)
        pages += 1
        cursor = pageBox.height - margin
    }

    /** Makes room for [height] points, going to a new page if the current one is full. */
    private fun need(height: Float) {
        if (cursor - height < margin && cursor < pageBox.height - margin - 1f) newPage()
    }

    private fun paragraph(p: Block.Paragraph) {
        val size = when (p.heading) {
            1 -> 20f
            2 -> 16f
            3 -> 13f
            else -> p.size.coerceIn(7f, 36f)
        }
        val leading = size * 1.3f
        val spaceBefore = when (p.heading) {
            1 -> 18f
            2 -> 14f
            3 -> 10f
            else -> 0f
        }
        val indent = if (p.list != null) 14f + p.list.level * 18f else 0f
        val width = contentWidth - indent
        val words = wordsOf(p, size)
        val lines = wrap(words, width)
        if (lines.isEmpty()) {
            cursor -= leading * 0.6f
            return
        }

        if (cursor < pageBox.height - margin - 1f) cursor -= spaceBefore
        need(leading * minOf(lines.size, 2))

        for ((n, line) in lines.withIndex()) {
            need(leading)
            val lineWidth = line.sumOf { it.width.toDouble() }.toFloat() + spaceWidth(size) * (line.size - 1).coerceAtLeast(0)
            var x = margin + indent + when (p.align) {
                Block.Align.CENTRE -> (width - lineWidth) / 2f
                Block.Align.RIGHT -> width - lineWidth
                else -> 0f
            }.coerceAtLeast(0f)
            val baseline = cursor - size
            val s = stream ?: return
            if (n == 0 && p.list != null) {
                val mark = if (p.list.ordered) "${p.list.number}." else "•"
                val markFont = fonts.fontFor(mark, false, false)
                show(s, markFont, size, margin + indent - 12f, baseline, fonts.settable(markFont, mark))
            }
            for (word in line) {
                show(s, word.font, word.size, x, baseline, word.text)
                x += word.width + spaceWidth(size)
            }
            cursor -= leading
        }
        cursor -= leading * 0.35f
    }

    private fun wordsOf(p: Block.Paragraph, size: Float): List<Word> {
        val out = mutableListOf<Word>()
        for (run in p.runs) {
            val bold = run.bold || p.heading > 0
            val runSize = if (run.size > 0f && p.heading == 0) run.size.coerceIn(7f, 36f) else size
            for (raw in run.text.replace('\t', ' ').split(Regex("\\s+"))) {
                if (raw.isEmpty()) continue
                val font = fonts.fontFor(raw, bold, run.italic)
                val text = fonts.settable(font, raw)
                if (text.isEmpty()) continue
                out += Word(text, font, runSize, widthOf(font, runSize, text))
            }
        }
        return out
    }

    private fun wrap(words: List<Word>, width: Float): List<List<Word>> {
        val lines = mutableListOf<List<Word>>()
        var line = mutableListOf<Word>()
        var used = 0f
        for (word in words) {
            val space = if (line.isEmpty()) 0f else spaceWidth(word.size)
            if (line.isNotEmpty() && used + space + word.width > width) {
                lines += line
                line = mutableListOf()
                used = 0f
            }
            line += word
            used += (if (line.size > 1) spaceWidth(word.size) else 0f) + word.width
        }
        if (line.isNotEmpty()) lines += line
        return lines
    }

    private fun table(t: Block.Table) {
        val columns = t.columns
        if (columns == 0) return
        val size = 9.5f
        val leading = size * 1.3f
        val pad = 4f
        // Columns share the width by how much text they carry, within reason: a column of
        // dates does not get as much room as a column of descriptions.
        val weights = (0 until columns).map { c ->
            t.rows.maxOf { row -> row.getOrNull(c)?.length ?: 0 }.coerceIn(4, 40).toFloat()
        }
        val totalWeight = weights.sum()
        val widths = weights.map { contentWidth * it / totalWeight }

        cursor -= 6f
        for ((r, row) in t.rows.withIndex()) {
            val bold = r < t.headerRows
            val cells = (0 until columns).map { c ->
                val text = row.getOrNull(c).orEmpty()
                val words = text.split('\n').flatMap { line ->
                    val ws = wordsOf(Block.Paragraph(listOf(Block.Run(line, bold = bold)), size = size), size)
                    if (ws.isEmpty()) listOf(emptyList()) else wrap(ws, widths[c] - pad * 2f)
                }
                words
            }
            val rowHeight = (cells.maxOf { it.size }.coerceAtLeast(1)) * leading + pad * 2f
            need(rowHeight)
            val s = stream ?: return
            val top = cursor
            var x = margin
            s.setLineWidth(0.5f)
            s.setStrokingColor(0.6f, 0.6f, 0.6f)
            for (c in 0 until columns) {
                s.addRect(x, top - rowHeight, widths[c], rowHeight)
                s.stroke()
                var baseline = top - pad - size
                for (line in cells[c]) {
                    var wx = x + pad
                    for (word in line) {
                        show(s, word.font, word.size, wx, baseline, word.text)
                        wx += word.width + spaceWidth(size)
                    }
                    baseline -= leading
                }
                x += widths[c]
            }
            cursor = top - rowHeight
        }
        cursor -= 10f
    }

    private fun picture(p: Block.Picture) {
        val image = runCatching {
            PDImageXObject.createFromByteArray(doc, p.bytes, if (p.jpeg) "picture.jpg" else "picture.png")
        }.getOrNull() ?: return
        var width = if (p.widthPoints > 0f) p.widthPoints else image.width.toFloat()
        var height = if (p.heightPoints > 0f) p.heightPoints else image.height.toFloat()
        val maxHeight = pageBox.height - margin * 2f - 4f
        if (width > contentWidth) { height *= contentWidth / width; width = contentWidth }
        if (height > maxHeight) { width *= maxHeight / height; height = maxHeight }
        need(height + 6f)
        val s = stream ?: return
        s.drawImage(image, margin + (contentWidth - width) / 2f, cursor - height, width, height)
        cursor -= height + 8f
    }

    private fun show(s: PDPageContentStream, font: PDFont, size: Float, x: Float, y: Float, text: String) {
        if (text.isEmpty()) return
        runCatching {
            s.beginText()
            s.setFont(font, size)
            s.newLineAtOffset(x, y)
            s.showText(text)
            s.endText()
        }.onFailure {
            // The one case a system font can still refuse: a glyph it listed but cannot
            // embed. Helvetica sets what it can; the text is not lost with the whole line.
            runCatching {
                s.endText()
                s.beginText()
                s.setFont(com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA, size)
                s.newLineAtOffset(x, y)
                s.showText(text.filter { it.code in 32..126 || it.code in 160..255 })
                s.endText()
            }
        }
    }

    private fun widthOf(font: PDFont, size: Float, text: String): Float =
        runCatching { font.getStringWidth(text) / 1000f * size }.getOrDefault(text.length * size * 0.5f)

    private fun spaceWidth(size: Float): Float = size * 0.28f
}
