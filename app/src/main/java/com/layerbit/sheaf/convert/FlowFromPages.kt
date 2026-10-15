package com.layerbit.sheaf.convert

import com.layerbit.sheaf.pdf.Block
import com.layerbit.sheaf.pdf.FlowDocument
import com.layerbit.sheaf.pdf.TextLine

/**
 * Turns the lines read off a document's pages back into paragraphs, headings and tables.
 *
 * A PDF holds lines, not paragraphs, so this is reconstruction rather than reading: lines
 * that follow each other closely in the same size are one paragraph; a line noticeably
 * larger than the page's usual size is a heading; a run of lines that each cut into the
 * same number of cells is a table. Honest about what it is: a scanned page gives nothing,
 * and a two-column layout comes out in reading order, one column after the other.
 */
object FlowFromPages {

    fun build(title: String, pages: List<List<TextLine>>): FlowDocument {
        val blocks = mutableListOf<Block>()
        val bodySize = bodySizeOf(pages)
        pages.forEachIndexed { index, lines ->
            if (index > 0) blocks += Block.PageBreak
            blocks += blocksOf(lines, bodySize)
        }
        return FlowDocument(title, blocks)
    }

    /** The size most of the text is set in, which is what a heading is larger than. */
    private fun bodySizeOf(pages: List<List<TextLine>>): Float {
        val weights = HashMap<Int, Int>()
        for (lines in pages) for (line in lines) {
            val key = Math.round(line.sizePoints)
            weights[key] = (weights[key] ?: 0) + line.text.length
        }
        return weights.maxByOrNull { it.value }?.key?.toFloat() ?: 11f
    }

    private fun blocksOf(lines: List<TextLine>, bodySize: Float): List<Block> {
        val out = mutableListOf<Block>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.text.isBlank()) { i++; continue }

            // A table: three or more lines in a row with the same number of cells, two or more.
            val columns = line.cells.size
            if (columns >= 2) {
                var j = i
                while (j < lines.size && lines[j].cells.size == columns && lines[j].text.isNotBlank()) j++
                if (j - i >= 3) {
                    out += Block.Table(lines.subList(i, j).map { it.cells })
                    i = j
                    continue
                }
            }

            // A heading: larger than the body, on its own line or two.
            val heading = headingLevel(line.sizePoints, bodySize, line.bold)
            if (heading > 0) {
                var j = i + 1
                val text = StringBuilder(line.text)
                while (j < lines.size && lines[j].text.isNotBlank() && headingLevel(lines[j].sizePoints, bodySize, lines[j].bold) == heading &&
                    closeBelow(lines[j - 1], lines[j]) && j - i < 3
                ) {
                    text.append(' ').append(lines[j].text)
                    j++
                }
                out += Block.Paragraph(
                    runs = listOf(Block.Run(text.toString(), bold = true)),
                    heading = heading,
                    align = alignOf(line),
                    size = line.sizePoints
                )
                i = j
                continue
            }

            // A paragraph: lines of one size that follow each other closely. A line that
            // starts with a bullet starts a list item.
            val list = listMark(line.text)
            val runs = mutableListOf<Block.Run>()
            var j = i
            var previous: TextLine? = null
            while (j < lines.size) {
                val current = lines[j]
                if (current.text.isBlank()) break
                if (previous != null) {
                    if (!closeBelow(previous, current)) break
                    if (Math.abs(current.sizePoints - line.sizePoints) > 0.6f) break
                    if (headingLevel(current.sizePoints, bodySize, current.bold) > 0) break
                    if (listMark(current.text) != null) break
                    // A new paragraph often starts with an indent, or the previous line ended
                    // well short of the right edge.
                    if (current.left - previous.left > 0.02f) break
                    if (previous.left + previous.width < current.left + current.width - 0.15f && previous.text.lastOrNull() in END_MARKS) break
                }
                val text = if (j == i && list != null) line.text.drop(list.second) else current.text
                val piece = if (runs.isEmpty()) text else " $text"
                runs += Block.Run(piece.replace(Regex("\\s+"), " "), bold = current.bold, italic = current.italic)
                previous = current
                j++
            }
            out += Block.Paragraph(
                runs = mergeRuns(runs),
                align = alignOf(line),
                list = list?.first,
                size = line.sizePoints.coerceIn(6f, 48f)
            )
            i = j
        }
        return out
    }

    private fun headingLevel(size: Float, body: Float, bold: Boolean): Int = when {
        size >= body * 1.7f -> 1
        size >= body * 1.35f -> 2
        size >= body * 1.15f && bold -> 3
        else -> 0
    }

    private fun closeBelow(above: TextLine, below: TextLine): Boolean {
        val gap = below.top - (above.top + above.height)
        return gap < above.height * 0.9f && gap > -above.height * 0.5f
    }

    private fun alignOf(line: TextLine): Block.Align {
        val centre = line.left + line.width / 2f
        return when {
            line.width > 0.7f -> Block.Align.LEFT
            Math.abs(centre - 0.5f) < 0.03f && line.left > 0.15f -> Block.Align.CENTRE
            line.left > 0.55f -> Block.Align.RIGHT
            else -> Block.Align.LEFT
        }
    }

    /** A bullet or a number at the start of a line, and how many characters it takes. */
    private fun listMark(text: String): Pair<Block.ListMark, Int>? {
        val bullet = Regex("^[•◦▪·\\-–—*]\\s+").find(text)
        if (bullet != null) return Block.ListMark(ordered = false) to bullet.value.length
        val number = Regex("^(\\d{1,3})[.)]\\s+").find(text)
        if (number != null) return Block.ListMark(ordered = true, number = number.groupValues[1].toInt()) to number.value.length
        return null
    }

    private fun mergeRuns(runs: List<Block.Run>): List<Block.Run> {
        val out = mutableListOf<Block.Run>()
        for (run in runs) {
            val last = out.lastOrNull()
            if (last != null && last.bold == run.bold && last.italic == run.italic) {
                out[out.lastIndex] = last.copy(text = last.text + run.text)
            } else {
                out += run
            }
        }
        return out
    }

    private val END_MARKS = setOf('.', '!', '?', ':', ';', '।')
}
