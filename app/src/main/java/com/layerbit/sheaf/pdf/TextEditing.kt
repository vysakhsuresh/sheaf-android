package com.layerbit.sheaf.pdf

import android.graphics.PointF
import com.tom_roush.pdfbox.contentstream.PDFGraphicsStreamEngine
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImage
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** The look of a run of type, near enough to choose a built-in font to stand in for it. */
enum class TypeFamily { SANS, SERIF, MONO }

/**
 * A line of words on a page - or one cell of a table row - that Edit text can change.
 *
 * Everything is a fraction of the page as the reader sees it, origin top-left, except
 * [sizePoints], which is the type size in points.
 */
data class EditableLine(
    val text: String,
    /** The box round the words: from just above the tallest letter to just below the baseline. */
    val area: PageArea,
    /** How far down the page the words sit on. */
    val baseline: Float,
    val sizePoints: Float,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val family: TypeFamily = TypeFamily.SANS,
    /** Numbers in a column line up on the right, and a new number has to as well. */
    val alignRight: Boolean = false,
    /** How far a longer replacement may run before it meets a table rule or a neighbour. */
    val roomLeft: Float = area.left,
    val roomRight: Float = area.left + area.width,
    /** The document's own font for these words, when they are all in one. */
    val fontName: String? = null,
    /** Every character the page already draws in that font: the only ones it is sure to have. */
    val fontChars: String = "",
    /** On a read scan: each word and its box, so only the words that change are repainted. */
    val words: List<Pair<String, PageArea>> = emptyList()
)

/** What a page offers to Edit text. A scan offers nothing until it has been read. */
data class EditablePage(
    val lines: List<EditableLine>,
    val scanned: Boolean,
    /** The page as the reader sees it, in points. */
    val widthPoints: Float = 0f,
    val heightPoints: Float = 0f
)

/** One change, ready to write: paint over, then write the new words. */
data class TextEdit(
    val pageIndex: Int,
    /** Painted in [paper] first. */
    val cover: PageArea,
    val paper: Int,
    val ink: Int,
    /** Empty takes the words out and writes nothing. */
    val text: String,
    /** Where the new words start, or for right-aligned words, where they end. */
    val left: Float,
    val right: Float,
    val baseline: Float,
    val sizePoints: Float,
    val bold: Boolean,
    val italic: Boolean,
    val family: TypeFamily,
    val alignRight: Boolean,
    val roomLeft: Float,
    val roomRight: Float,
    val fontName: String?,
    val fontChars: String
)

/**
 * Finding the lines on a page, and writing changed ones back.
 *
 * Old words are painted over in the page's own paper colour and the new ones written where
 * they stood: in the document's own font where it has every letter, otherwise the nearest
 * built-in face, and squeezed a little - never below 85% - to stay inside a table cell, then
 * made smaller if that is still not enough. The old words stay in the file underneath, which
 * the screen says, and Remove areas is for taking words out for good.
 */
internal object TextEditing {

    private val NUMERIC = Regex("""^[-+(₹$]?\s?[\d,]*\.?\d+\)?(\s?(Cr|Dr|CR|DR))?$""")
    private val MONO = Regex("mono|courier|consol|menlo|typewriter", RegexOption.IGNORE_CASE)
    private val SANS = Regex(
        "sans|arial|helvet|calibri|verdana|tahoma|segoe|roboto|grotesk|gothic|futura|frutiger|univers|lato|open ?sans|noto ?sans|dejavu ?sans|liberation ?sans|trebuchet|myriad|candara|corbel|aptos",
        RegexOption.IGNORE_CASE
    )
    private val SERIF = Regex("times|serif|georgia|garamond|cambria|roman|book ?antiqua|palatino|constantia|baskerville|bodoni|minion", RegexOption.IGNORE_CASE)

    fun familyOf(fontName: String?): TypeFamily {
        val name = fontName?.substringAfter('+').orEmpty()
        return when {
            MONO.containsMatchIn(name) -> TypeFamily.MONO
            SANS.containsMatchIn(name) -> TypeFamily.SANS
            SERIF.containsMatchIn(name) -> TypeFamily.SERIF
            else -> TypeFamily.SANS
        }
    }

    fun lines(doc: PDDocument, index: Int): EditablePage {
        val page = doc.getPage(index)
        val shown = ShownPage(page)
        val collector = Collector()
        collector.sortByPosition = true
        collector.startPage = index + 1
        collector.endPage = index + 1
        runCatching { collector.getText(doc) }
        val rules = runCatching { RuleFinder(page, shown).also { it.processPage(page) }.rules }.getOrDefault(emptyList())
        val pictureCover = runCatching { PictureSurgery.calls(page, PictureSurgery.tokens(page)).maxOfOrNull { it.area.width * it.area.height } ?: 0f }
            .getOrDefault(0f)

        // Only text running left to right along the page as it is read.
        val glyphs = collector.glyphs.filter { !it.unicode.isNullOrBlank() }
        val direction = glyphs.groupingBy { it.dir }.eachCount().maxByOrNull { it.value }?.key
        val upright = glyphs.filter { it.dir == direction }
        val visible = upright.filter { it.font != null }
        val scanned = pictureCover >= 0.8f && (visible.isEmpty() || collector.invisibleShare >= 0.8f)
        if (scanned) return EditablePage(emptyList(), true, shown.width, shown.height)

        // Which characters each font already draws on this page.
        val charsByFont = HashMap<String, MutableSet<Char>>()
        for (g in upright) {
            val name = runCatching { g.font?.name }.getOrNull() ?: continue
            charsByFont.getOrPut(name) { mutableSetOf() }.addAll(g.unicode.toList())
        }

        val W = shown.width
        val H = shown.height
        fun sizeOf(g: TextPosition) = (if (g.fontSizeInPt > 0.5f) g.fontSizeInPt else g.heightDir).coerceAtLeast(1f)

        // Rows: glyphs whose baselines are within four tenths of the largest type in the row.
        val rows = mutableListOf<MutableList<TextPosition>>()
        for (g in upright.sortedWith(compareBy({ it.yDirAdj }, { it.xDirAdj }))) {
            val row = rows.lastOrNull()
            if (row != null && abs(row.first().yDirAdj - g.yDirAdj) < max(row.maxOf { sizeOf(it) }, sizeOf(g)) * 0.4f) {
                row += g
            } else {
                rows += mutableListOf(g)
            }
        }

        val out = mutableListOf<EditableLine>()
        for (row in rows) {
            row.sortBy { it.xDirAdj }
            // Cells: a gap wider than nine tenths of the type, or a table rule between.
            val segments = mutableListOf<MutableList<TextPosition>>()
            for (g in row) {
                val seg = segments.lastOrNull()
                if (seg == null) { segments += mutableListOf(g); continue }
                val prev = seg.last()
                val prevEnd = prev.xDirAdj + prev.widthDirAdj
                val gap = g.xDirAdj - prevEnd
                val size = max(sizeOf(prev), sizeOf(g))
                val ruleAt = g.yDirAdj - 0.35f * size
                val ruled = rules.any { r ->
                    val x = r.x * W
                    x > prevEnd - 0.5f && x < g.xDirAdj + 0.5f && ruleAt / H in r.top..r.bottom
                }
                if (gap > size * 0.9f || ruled) segments += mutableListOf(g) else seg += g
            }
            val built = segments.map { seg -> build(seg, ::sizeOf, W, H) }
            for ((i, line) in built.withIndex()) {
                val (text, x0, x1, size, baseline, first) = line
                if (text.isBlank()) continue
                val margin = max(12f, min(W * 0.1f, row.minOf { it.xDirAdj }))
                var left = min(x0, margin)
                var right = max(x1, W - margin)
                for (r in rules) {
                    val x = r.x * W
                    val mid = (baseline - size * 0.35f) / H
                    if (mid !in r.top..r.bottom) continue
                    if (x <= x0 + 0.5f) left = max(left, x + size * 0.25f)
                    if (x >= x1 - 0.5f) right = min(right, x - size * 0.25f)
                }
                built.getOrNull(i - 1)?.let { left = max(left, it.x1 + 0.5f * size) }
                built.getOrNull(i + 1)?.let { right = min(right, it.x0 - 0.5f * size) }
                left = min(left, x0)
                right = max(right, x1)

                val fonts = segments[i].mapNotNull { runCatching { it.font?.name }.getOrNull() }.distinct()
                val fontName = fonts.singleOrNull()
                val name = fonts.firstOrNull().orEmpty()
                val bold = Regex("bold|black|heavy|semibold|demi", RegexOption.IGNORE_CASE).containsMatchIn(name) ||
                    runCatching { first.font?.fontDescriptor?.isForceBold == true || (first.font?.fontDescriptor?.fontWeight ?: 0f) >= 600f }.getOrDefault(false)
                val italic = Regex("italic|oblique", RegexOption.IGNORE_CASE).containsMatchIn(name) ||
                    runCatching { first.font?.fontDescriptor?.isItalic == true }.getOrDefault(false)
                out += EditableLine(
                    text = text,
                    area = PageArea(x0 / W, (baseline - 0.95f * size) / H, (x1 - x0) / W, 1.23f * size / H),
                    baseline = baseline / H,
                    sizePoints = size,
                    bold = bold,
                    italic = italic,
                    family = familyOf(name),
                    alignRight = NUMERIC.matches(text.trim()),
                    roomLeft = left / W,
                    roomRight = right / W,
                    fontName = fontName,
                    fontChars = fontName?.let { charsByFont[it]?.joinToString("") }.orEmpty()
                )
            }
        }
        return EditablePage(out, false, shown.width, shown.height)
    }

    private data class Built(val text: String, val x0: Float, val x1: Float, val size: Float, val baseline: Float, val first: TextPosition)

    private fun build(seg: List<TextPosition>, sizeOf: (TextPosition) -> Float, W: Float, H: Float): Built {
        val text = StringBuilder()
        var prevEnd = Float.NaN
        for (g in seg) {
            if (!prevEnd.isNaN()) {
                val gap = g.xDirAdj - prevEnd
                if (gap > sizeOf(g) * 0.15f && text.isNotEmpty() && !text.endsWith(' ') && !g.unicode.startsWith(" ")) text.append(' ')
            }
            text.append(g.unicode)
            prevEnd = g.xDirAdj + g.widthDirAdj
        }
        val size = seg.groupingBy { Math.round(sizeOf(it) * 2f) / 2f }.eachCount().maxByOrNull { it.value }?.key ?: sizeOf(seg.first())
        return Built(
            text.toString().replace(Regex("\\s+"), " ").trim(),
            seg.minOf { it.xDirAdj },
            seg.maxOf { it.xDirAdj + it.widthDirAdj },
            size,
            seg.maxOf { it.yDirAdj },
            seg.first()
        )
    }

    /** Writes the edits for one page. Returns how many were made. */
    fun write(doc: PDDocument, page: PDPage, edits: List<TextEdit>, lettering: Lettering): Int {
        if (edits.isEmpty()) return 0
        val shown = ShownPage(page)
        val W = shown.width
        val H = shown.height
        val fonts = pageFonts(page)
        var made = 0
        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { s ->
            s.saveGraphicsState()
            s.transform(shown.viewMatrix())
            for (e in edits) {
                s.saveGraphicsState()
                s.setNonStrokingColor(e.paper.r(), e.paper.g(), e.paper.b())
                s.addRect(e.cover.left * W, H - (e.cover.top + e.cover.height) * H, e.cover.width * W, e.cover.height * H)
                s.fill()
                made += 1
                val text = e.text.replace(Regex("[\\r\\n\\t]+"), " ").trim()
                if (text.isNotEmpty()) {
                    val y = H - e.baseline * H
                    val room = if (e.alignRight) (e.right - e.roomLeft) * W else (e.roomRight - e.left) * W
                    val own = e.fontName?.let { name -> fonts[name] }?.takeIf { font -> canSet(font, text, e.fontChars) }
                    val font: PDFont? = own ?: if (Lettering.isPlain(text)) builtIn(e.family, e.bold, e.italic) else null
                    if (font != null) {
                        val natural = runCatching { font.getStringWidth(text) / 1000f * e.sizePoints }.getOrDefault(text.length * e.sizePoints * 0.5f)
                        val (squeeze, scale) = fit(natural, room)
                        val drawn = natural * squeeze / 100f * scale
                        val x = if (e.alignRight) e.right * W - drawn else e.left * W
                        s.setNonStrokingColor(e.ink.r(), e.ink.g(), e.ink.b())
                        s.beginText()
                        s.setFont(font, e.sizePoints * scale)
                        if (squeeze != 100f) s.setHorizontalScaling(squeeze)
                        s.newLineAtOffset(x, y)
                        s.showText(text)
                        s.endText()
                    } else {
                        // Malayalam, Hindi, Arabic: shaped by the phone, made smaller if it must.
                        val natural = lettering.width(text, e.sizePoints, e.bold)
                        val scale = if (room > 0f && natural > room) max(0.5f, room / natural) else 1f
                        val drawn = natural * scale
                        val x = if (e.alignRight) e.right * W - drawn else e.left * W
                        lettering.draw(s, text, e.sizePoints * scale, x, y, e.ink or (0xFF shl 24), e.bold)
                    }
                }
                s.restoreGraphicsState()
            }
            s.restoreGraphicsState()
        }
        return made
    }

    /**
     * How to fit words of [natural] width into [room]: squeeze them sideways, never below
     * 85%, then make them smaller, never below half. Never stretched.
     */
    fun fit(natural: Float, room: Float): Pair<Float, Float> {
        if (!(room > 0f) || natural <= room) return 100f to 1f
        val squeeze = max(85f, kotlin.math.floor(100f * room / natural))
        val squeezed = natural * squeeze / 100f
        val scale = if (squeezed > room) max(0.5f, room / squeezed) else 1f
        return squeeze to scale
    }

    /** Whether the document's own font is sure to draw every letter of [text]. */
    private fun canSet(font: PDFont, text: String, chars: String): Boolean {
        val needed = text.filterNot { it == ' ' }
        if (needed.any { it !in chars }) return false
        return runCatching { font.encode(text); true }.getOrDefault(false)
    }

    private fun pageFonts(page: PDPage): Map<String, PDFont> {
        val out = HashMap<String, PDFont>()
        val resources = page.resources ?: return out
        for (key in resources.fontNames) {
            val font = runCatching { resources.getFont(key) }.getOrNull() ?: continue
            out.putIfAbsent(font.name ?: continue, font)
        }
        return out
    }

    private fun builtIn(family: TypeFamily, bold: Boolean, italic: Boolean): PDFont = when (family) {
        TypeFamily.SANS -> listOf(PDType1Font.HELVETICA, PDType1Font.HELVETICA_BOLD, PDType1Font.HELVETICA_OBLIQUE, PDType1Font.HELVETICA_BOLD_OBLIQUE)
        TypeFamily.SERIF -> listOf(PDType1Font.TIMES_ROMAN, PDType1Font.TIMES_BOLD, PDType1Font.TIMES_ITALIC, PDType1Font.TIMES_BOLD_ITALIC)
        TypeFamily.MONO -> listOf(PDType1Font.COURIER, PDType1Font.COURIER_BOLD, PDType1Font.COURIER_OBLIQUE, PDType1Font.COURIER_BOLD_OBLIQUE)
    }[(if (bold) 1 else 0) + (if (italic) 2 else 0)]

    private fun Int.r() = ((this shr 16) and 0xFF) / 255f
    private fun Int.g() = ((this shr 8) and 0xFF) / 255f
    private fun Int.b() = (this and 0xFF) / 255f

    /** Every glyph the stripper places on the page, and how many of them are never seen. */
    private class Collector : PDFTextStripper() {
        val glyphs = mutableListOf<TextPosition>()
        private var shown = 0
        private var hidden = 0
        val invisibleShare: Float get() = if (shown + hidden == 0) 0f else hidden.toFloat() / (shown + hidden)

        override fun processTextPosition(text: TextPosition) {
            val mode = graphicsState?.textState?.renderingMode
            if (mode == com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode.NEITHER ||
                mode == com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode.NEITHER_CLIP
            ) {
                hidden += 1
                return
            }
            shown += 1
            super.processTextPosition(text)
        }

        override fun writeString(text: String, textPositions: List<TextPosition>) {
            glyphs += textPositions
        }
    }

    /** A thin upright line on the page: a table's column rule. Fractions of the page as seen. */
    data class Rule(val x: Float, val top: Float, val bottom: Float)

    /** Finds the vertical rules a page draws - stroked lines and thin filled bars. */
    private class RuleFinder(page: PDPage, private val shown: ShownPage) : PDFGraphicsStreamEngine(page) {
        val rules = mutableListOf<Rule>()
        private val segments = mutableListOf<Pair<PointF, PointF>>()
        private val rects = mutableListOf<Array<PointF>>()
        private var current = PointF()
        private var start = PointF()

        private fun view(x: Float, y: Float): PointF {
            val box = shown.box
            val (across, down) = when (shown.rotation) {
                90 -> (y - box.lowerLeftY) to (x - box.lowerLeftX)
                180 -> (box.upperRightX - x) to (y - box.lowerLeftY)
                270 -> (box.upperRightY - y) to (box.upperRightX - x)
                else -> (x - box.lowerLeftX) to (box.upperRightY - y)
            }
            return PointF(across, down)
        }

        override fun appendRectangle(p0: android.graphics.PointF, p1: android.graphics.PointF, p2: android.graphics.PointF, p3: android.graphics.PointF) {
            rects += arrayOf(view(p0.x, p0.y), view(p1.x, p1.y), view(p2.x, p2.y), view(p3.x, p3.y))
        }

        override fun moveTo(x: Float, y: Float) {
            current = view(x, y); start = current
        }

        override fun lineTo(x: Float, y: Float) {
            val next = view(x, y)
            segments += current to next
            current = next
        }

        override fun curveTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
            current = view(x3, y3)
        }

        override fun getCurrentPoint(): android.graphics.PointF = current
        override fun closePath() {
            segments += current to start
            current = start
        }

        override fun endPath() = clear()
        override fun strokePath() {
            for ((a, b) in segments) if (abs(a.x - b.x) <= 0.75f && abs(a.y - b.y) >= 4f) add(a.x, a.y, b.y)
            for (r in rects) thin(r, stroked = true)
            clear()
        }

        override fun fillPath(windingRule: android.graphics.Path.FillType?) {
            for (r in rects) thin(r, stroked = false)
            clear()
        }

        override fun fillAndStrokePath(windingRule: android.graphics.Path.FillType?) = strokePath()
        override fun clip(windingRule: android.graphics.Path.FillType?) = Unit
        override fun drawImage(pdImage: PDImage?) = Unit
        override fun shadingFill(shadingName: COSName?) = Unit

        private fun thin(r: Array<PointF>, stroked: Boolean) {
            val x0 = r.minOf { it.x }
            val x1 = r.maxOf { it.x }
            val y0 = r.minOf { it.y }
            val y1 = r.maxOf { it.y }
            val w = x1 - x0
            if (y1 - y0 >= 4f && (w <= 2.5f || stroked && w <= 0.75f)) add((x0 + x1) / 2f, y0, y1)
            if (stroked) {
                // A stroked box draws its two sides as rules too: a table cell's borders.
                if (y1 - y0 >= 4f) { add(x0, y0, y1); add(x1, y0, y1) }
            }
        }

        private fun add(x: Float, y0: Float, y1: Float) {
            rules += Rule(x / shown.width, min(y0, y1) / shown.height, max(y0, y1) / shown.height)
        }

        private fun clear() {
            segments.clear(); rects.clear()
        }
    }
}
