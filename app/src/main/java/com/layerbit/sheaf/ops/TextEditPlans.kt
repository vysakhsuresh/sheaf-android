package com.layerbit.sheaf.ops

import com.layerbit.sheaf.pdf.EditableLine
import com.layerbit.sheaf.pdf.OcrLine
import com.layerbit.sheaf.pdf.PageArea
import com.layerbit.sheaf.pdf.TextEdit
import com.layerbit.sheaf.pdf.TypeFamily
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The arithmetic of Edit text that does not need a PDF: lines on a read scan, which words of
 * a changed scan line to repaint, the page's own paper and ink colours, and whole-word
 * replacing. Kept apart so the JVM tests can reach all of it.
 */
object TextEditPlans {

    /** What to paint over and what to write, for one changed line. */
    data class Plan(val paintFrom: Float, val paintTo: Float, val start: Float, val right: Float, val text: String, val bold: Boolean)

    private val TALL = Regex("""[A-Z0-9bdfhklti!?#$%&@/\\|()\[\]{}'"]""")
    private val DEEP = Regex("""[gjpqy,;()\[\]{}|@$]""")
    private val EDGE = Regex("""^[|¦\[\]]+|[|¦\[\]]+$""")

    /**
     * Editable lines from what the recogniser read on a scan, in [bitmapW] × [bitmapH] pixels
     * of a page [pageH] points tall. [boldWord] says whether a word's ink is heavier than
     * the page's usual, by its box.
     */
    fun scanLines(
        ocr: List<OcrLine>,
        bitmapW: Int,
        bitmapH: Int,
        pageH: Float,
        boldWord: (PageArea) -> Boolean = { false }
    ): List<EditableLine> {
        data class Raw(val words: List<Pair<String, PageArea>>, val em: Float, val baseline: Float)

        val raws = ocr.mapNotNull { line ->
            val source = line.words.ifEmpty { listOf(line) }
            val words = source.mapNotNull { w ->
                val text = w.text.replace(EDGE, "")
                if (text.isEmpty() || text in setOf("I", "l", "1", "!") && line.words.size > 1 && w.width < w.height * 0.25f) return@mapNotNull null
                // Trimmed characters take their share of the box with them.
                val cut = w.text.length - text.length
                val share = if (w.text.isNotEmpty()) cut.toFloat() / w.text.length else 0f
                val lead = w.text.indexOf(text).coerceAtLeast(0).toFloat() / w.text.length.coerceAtLeast(1)
                text to PageArea(
                    (w.left + w.width * lead) / bitmapW,
                    w.top / bitmapH,
                    w.width * (1f - share) / bitmapW,
                    w.height / bitmapH
                )
            }
            if (words.isEmpty()) return@mapNotNull null
            val text = words.joinToString(" ") { it.first }
            val h = line.height
            val tall = TALL.containsMatchIn(text)
            val deep = DEEP.containsMatchIn(text)
            val em = h / ((if (tall) 0.73f else 0.53f) + (if (deep) 0.21f else 0f))
            val baseline = line.top + h - (if (deep) 0.21f * em else 0f)
            Raw(words, em, baseline)
        }
        if (raws.isEmpty()) return emptyList()
        val margin = max(0.02f, min(0.1f, raws.minOf { it.words.first().second.left }))
        return raws.map { raw ->
            val x0 = raw.words.first().second.left
            val x1 = raw.words.last().second.let { it.left + it.width }
            val sizeW = raw.em / bitmapW
            var left = min(x0, margin)
            var right = max(x1, 1f - margin)
            for (other in raws) {
                if (other === raw || abs(other.baseline - raw.baseline) >= min(other.em, raw.em) * 0.4f) continue
                val ox0 = other.words.first().second.left
                val ox1 = other.words.last().second.let { it.left + it.width }
                if (ox1 <= x0) left = max(left, ox1 + 0.5f * sizeW)
                if (ox0 >= x1) right = min(right, ox0 - 0.5f * sizeW)
            }
            val bolds = raw.words.count { boldWord(it.second) }
            val top = (raw.baseline - 0.95f * raw.em) / bitmapH
            EditableLine(
                text = raw.words.joinToString(" ") { it.first },
                area = PageArea(x0, top, x1 - x0, 1.23f * raw.em / bitmapH),
                baseline = raw.baseline / bitmapH,
                sizePoints = raw.em / bitmapH * pageH,
                bold = bolds * 2 > raw.words.size,
                family = TypeFamily.SANS,
                roomLeft = min(left, x0),
                roomRight = max(right, x1),
                words = raw.words
            )
        }
    }

    /**
     * Which words of a changed scan line to repaint. Only the run that changed is painted
     * over - the rest of the line stays as scanned - unless the new words will not fit the
     * gap they leave, or leave too big a hole; then everything from the change to the end of
     * the line is rewritten. [measure] gives a text's width as a fraction of the page width.
     */
    fun planScan(line: EditableLine, text: String, sizeW: Float, measure: (String) -> Float, boldWord: (Int) -> Boolean = { line.bold }): Plan? {
        val old = line.words
        if (old.isEmpty()) return null
        val fresh = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        var p = 0
        while (p < old.size && p < fresh.size && old[p].first == fresh[p]) p++
        var s = 0
        while (s < old.size - p && s < fresh.size - p && old[old.size - 1 - s].first == fresh[fresh.size - 1 - s]) s++
        if (p == old.size && p == fresh.size) return null
        val space = 0.28f * sizeW
        if (old.size - s == p || fresh.size - s == p) s = 0
        fun x0(i: Int) = old[i].second.left
        fun x1(i: Int) = old[i].second.let { it.left + it.width }
        val start = if (p < old.size) x0(p) else (if (p > 0) x1(p - 1) else line.roomLeft) + space
        val changed = (p until old.size - s).toList()
        val bold = if (changed.isEmpty()) boldWord((p - 1).coerceAtLeast(0)) else changed.count(boldWord) * 2 > changed.size
        if (s > 0) {
            val middle = fresh.subList(p, fresh.size - s).joinToString(" ")
            val right = x0(old.size - s) - space
            val width = measure(middle)
            val hole = right - start - width
            if (width * 0.85f <= right - start && !(hole > 1.2f * sizeW && s <= 3)) {
                return Plan(start, x1(old.size - s - 1), start, right, middle, bold)
            }
        }
        return Plan(start, max(start, x1(old.size - 1)), start, line.roomRight, fresh.drop(p).joinToString(" "), bold)
    }

    /** The edits to write for [line] changed to [text] on page [page], in [paper] and [ink]. */
    fun edits(page: Int, line: EditableLine, text: String, paper: Int, ink: Int, aspect: Float, measure: (String) -> Float): List<TextEdit> {
        val clean = text.replace(Regex("[\\r\\n\\t]+"), " ").replace(Regex(" {2,}"), " ").trim()
        if (clean == line.text.trim()) return emptyList()
        val a = line.area
        if (line.words.isNotEmpty()) {
            // The planner works across the page; a word's height down it is turned with the page's shape.
            val plan = planScan(line, clean, a.height / 1.23f * aspect, measure) ?: return emptyList()
            val from = max(0f, plan.paintFrom - 0.003f)
            val to = min(1f, plan.paintTo + 0.003f)
            return listOf(
                TextEdit(
                    page, PageArea(from, a.top, max(0.002f, to - from), a.height), paper, ink, plan.text,
                    plan.start, plan.right, line.baseline, line.sizePoints, plan.bold, false, TypeFamily.SANS,
                    false, plan.start, plan.right, null, ""
                )
            )
        }
        val x0 = max(0f, max(line.roomLeft, a.left - 0.004f))
        val x1 = min(1f, min(line.roomRight, a.left + a.width + 0.004f))
        return listOf(
            TextEdit(
                page, PageArea(x0, a.top, max(0.002f, x1 - x0), a.height), paper, ink, clean,
                a.left, a.left + a.width, line.baseline, line.sizePoints, line.bold, line.italic, line.family,
                line.alignRight, line.roomLeft, line.roomRight, line.fontName, line.fontChars
            )
        )
    }

    /**
     * The page's paper and ink inside [area] of an ARGB picture: the commonest colour, and
     * the darkest, pushed to full depth because anti-aliasing greys a thin stroke.
     */
    fun colours(pixels: IntArray, width: Int, height: Int, area: PageArea): Pair<Int, Int> {
        val x0 = (area.left * width).toInt().coerceIn(0, width - 1)
        val x1 = ((area.left + area.width) * width).toInt().coerceIn(x0 + 1, width)
        val y0 = (area.top * height).toInt().coerceIn(0, height - 1)
        val y1 = ((area.top + area.height) * height).toInt().coerceIn(y0 + 1, height)
        val bins = HashMap<Int, IntArray>()
        var darkest = -1
        var darkestSum = Int.MAX_VALUE
        for (y in y0 until y1) for (x in x0 until x1) {
            val c = pixels[y * width + x]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val key = ((r shr 3) shl 10) or ((g shr 3) shl 5) or (b shr 3)
            val bin = bins.getOrPut(key) { IntArray(4) }
            bin[0] += 1; bin[1] += r; bin[2] += g; bin[3] += b
            if (r + g + b < darkestSum) { darkestSum = r + g + b; darkest = c }
        }
        val top = bins.values.maxByOrNull { it[0] } ?: return WHITE to INK
        val paper = rgb(top[1] / top[0], top[2] / top[0], top[3] / top[0])
        return paper to trueInk(paper, if (darkest == -1) INK else darkest)
    }

    private fun lum(c: Int) = 0.2126 * ((c shr 16) and 0xFF) / 255 + 0.7152 * ((c shr 8) and 0xFF) / 255 + 0.0722 * (c and 0xFF) / 255

    fun trueInk(paper: Int, seen: Int): Int {
        if (lum(paper) - lum(seen) < 0.15) return seen or (0xFF shl 24)
        val r = (seen shr 16) and 0xFF
        val g = (seen shr 8) and 0xFF
        val b = seen and 0xFF
        if ((maxOf(r, g, b) - minOf(r, g, b)) / 255.0 < 0.08) return if (lum(seen) < 0.2) seen or (0xFF shl 24) else INK
        fun push(p: Int, s: Int) = (p - (p - s) * 1.8).toInt().coerceIn(0, 255)
        return rgb(push((paper shr 16) and 0xFF, r), push((paper shr 8) and 0xFF, g), push(paper and 0xFF, b))
    }

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    const val WHITE = 0xFFFFFFFF.toInt()
    const val INK = 0xFF141419.toInt()

    /** Whole words only, so "5,000.00" is not found inside "15,000.00". */
    fun wholeWords(find: String, matchCase: Boolean): Regex =
        Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(find.trim()) + "(?![\\p{L}\\p{N}])", if (matchCase) emptySet() else setOf(RegexOption.IGNORE_CASE))

    fun replaceIn(text: String, find: String, with: String, matchCase: Boolean): String =
        wholeWords(find, matchCase).replace(text, Regex.escapeReplacement(with)).replace(Regex(" {2,}"), " ").trim()
}
