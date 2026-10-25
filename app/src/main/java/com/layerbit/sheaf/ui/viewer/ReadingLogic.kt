package com.layerbit.sheaf.ui.viewer

import com.layerbit.sheaf.pdf.PageArea
import com.layerbit.sheaf.pdf.PageLink
import com.layerbit.sheaf.pdf.PageWord
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The arithmetic behind the viewer's reading features, kept apart from the composables for
 * the reason [PageTiling] is: none of it needs Android, all of it is easy to get subtly wrong,
 * and a mistake here looks like a broken screen rather than a broken sum. Everything in this
 * file works in page fractions - 0 to 1 across and down the page as displayed - which is the
 * space the engine reports words, links and search matches in.
 */

/** How the pages are laid out on screen. */
enum class ReadingMode(val label: String) {
    CONTINUOUS("One long scroll"),
    SINGLE("A page at a time"),
    TWO_UP("Two pages side by side");

    companion object {
        /** The stored name back to a mode; anything unknown or missing is the default. */
        fun fromName(name: String?): ReadingMode = entries.firstOrNull { it.name == name } ?: CONTINUOUS
    }
}

/** The colour of the paper. Night is the old photographic inversion; the others are gentler. */
enum class PaperTint(val label: String) {
    DAY("Day"),
    SEPIA("Sepia"),
    DARK_PAPER("Dark paper"),
    NIGHT("Night");

    val isDark: Boolean get() = this == DARK_PAPER || this == NIGHT

    companion object {
        fun fromName(name: String?): PaperTint = entries.firstOrNull { it.name == name } ?: DAY
    }
}

// ---- two-up spreads ----

internal object Spreads {

    /**
     * Pages paired for side-by-side reading: 0 and 1, 2 and 3, and the last alone if the count
     * is odd. With [coverAlone] the first page stands by itself and the pairs start at 1, as a
     * printed book opens - left page even, right page odd.
     */
    fun pairs(pageCount: Int, coverAlone: Boolean = false): List<List<Int>> {
        if (pageCount <= 0) return emptyList()
        val out = mutableListOf<List<Int>>()
        var start = 0
        if (coverAlone) {
            out += listOf(0)
            start = 1
        }
        while (start < pageCount) {
            out += if (start + 1 < pageCount) listOf(start, start + 1) else listOf(start)
            start += 2
        }
        return out
    }

    /** Which spread holds [page]. */
    fun spreadOf(page: Int, pageCount: Int, coverAlone: Boolean = false): Int {
        if (pageCount <= 0) return 0
        val p = page.coerceIn(0, pageCount - 1)
        return if (coverAlone) {
            if (p == 0) 0 else (p - 1) / 2 + 1
        } else {
            p / 2
        }
    }
}

/**
 * Where a page step from the keyboard lands. In two-up a step is a whole spread, so the
 * reader never sees half of the pair they just looked at again.
 */
internal fun steppedPage(current: Int, forward: Boolean, mode: ReadingMode, pageCount: Int): Int {
    if (pageCount <= 0) return 0
    val step = if (mode == ReadingMode.TWO_UP) 2 else 1
    // In two-up the current page is always the left of its spread, so stepping from it by two
    // lands on the left of the next spread rather than its right.
    val base = if (mode == ReadingMode.TWO_UP) current - current % 2 else current
    return (if (forward) base + step else base - step).coerceIn(0, pageCount - 1)
}

// ---- text selection ----

/**
 * A run of selected words on one page. [anchor] is where the long-press landed and [focus]
 * where the selection has been dragged to; either may be the earlier of the two.
 */
data class TextSelection(
    val pageIndex: Int,
    val words: List<PageWord>,
    val anchor: Int,
    val focus: Int
) {
    val first: Int get() = minOf(anchor, focus)
    val last: Int get() = maxOf(anchor, focus)
    val text: String get() = SelectionMath.textOf(words, first, last)
}

internal object SelectionMath {

    /**
     * The word under a finger, or the nearest one within [slop] page fractions of it.
     *
     * A long-press rarely lands inside a word's box exactly - the box is the glyphs' height,
     * and a fingertip is a lot taller than body text - so the nearest word wins as long as it
     * is close enough to be what was meant. A press in an empty margin selects nothing.
     */
    fun wordAt(words: List<PageWord>, x: Float, y: Float, slop: Float = 0.03f): Int? {
        var best: Int? = null
        var bestDistance = Float.MAX_VALUE
        words.forEachIndexed { index, word ->
            val distance = distanceTo(word.area, x, y)
            if (distance < bestDistance) {
                bestDistance = distance
                best = index
            }
        }
        return best?.takeIf { bestDistance <= slop }
    }

    /**
     * The word a handle being dragged should snap to: the nearest one, however far. A handle
     * dragged into the margin should still mean the end of the line beside it, not nothing.
     *
     * Vertical distance counts for more than horizontal, so dragging along a line between two
     * words picks one on that line rather than a word that happens to be closer on the next.
     */
    fun nearestWord(words: List<PageWord>, x: Float, y: Float): Int? {
        if (words.isEmpty()) return null
        var best = 0
        var bestDistance = Float.MAX_VALUE
        words.forEachIndexed { index, word ->
            val a = word.area
            val dx = when {
                x < a.left -> a.left - x
                x > a.left + a.width -> x - (a.left + a.width)
                else -> 0f
            }
            val dy = when {
                y < a.top -> a.top - y
                y > a.top + a.height -> y - (a.top + a.height)
                else -> 0f
            }
            val distance = dx + dy * 4f
            if (distance < bestDistance) {
                bestDistance = distance
                best = index
            }
        }
        return best
    }

    /**
     * The selected words as text. A word that starts a new line - its top well below the
     * last word's - is joined with a line break rather than a space, so a copied paragraph
     * keeps its lines and a copied address stays an address.
     */
    fun textOf(words: List<PageWord>, first: Int, last: Int): String {
        if (words.isEmpty()) return ""
        val from = first.coerceIn(0, words.lastIndex)
        val to = last.coerceIn(from, words.lastIndex)
        return buildString {
            for (i in from..to) {
                if (i > from) append(if (startsNewLine(words[i - 1].area, words[i].area)) '\n' else ' ')
                append(words[i].text)
            }
        }
    }

    /**
     * One box per line of the selection, rather than one per word, so the highlight reads as
     * a band through the text instead of a row of beads with gaps at every space.
     */
    fun lineBoxes(words: List<PageWord>, first: Int, last: Int): List<PageArea> {
        if (words.isEmpty()) return emptyList()
        val from = first.coerceIn(0, words.lastIndex)
        val to = last.coerceIn(from, words.lastIndex)
        val out = mutableListOf<PageArea>()
        var current = words[from].area
        for (i in from + 1..to) {
            val next = words[i].area
            current = if (startsNewLine(words[i - 1].area, next) || next.left < current.left) {
                out += current
                next
            } else {
                union(current, next)
            }
        }
        out += current
        return out
    }

    private fun startsNewLine(previous: PageArea, next: PageArea): Boolean {
        val shift = abs((next.top + next.height / 2f) - (previous.top + previous.height / 2f))
        return shift > maxOf(previous.height, next.height) * 0.6f
    }

    private fun union(a: PageArea, b: PageArea): PageArea {
        val left = minOf(a.left, b.left)
        val top = minOf(a.top, b.top)
        val right = maxOf(a.left + a.width, b.left + b.width)
        val bottom = maxOf(a.top + a.height, b.top + b.height)
        return PageArea(left, top, right - left, bottom - top)
    }
}

/** Distance from a point to a box, zero inside it. */
private fun distanceTo(area: PageArea, x: Float, y: Float): Float {
    val dx = when {
        x < area.left -> area.left - x
        x > area.left + area.width -> x - (area.left + area.width)
        else -> 0f
    }
    val dy = when {
        y < area.top -> area.top - y
        y > area.top + area.height -> y - (area.top + area.height)
        else -> 0f
    }
    return hypot(dx, dy)
}

// ---- links ----

/**
 * The link under a tap, or null. Links are often drawn tight round a word, so a tap a little
 * outside one still counts; where two are within reach the one the tap is actually inside, or
 * failing that the closest, wins.
 */
internal fun linkAt(links: List<PageLink>, x: Float, y: Float, slop: Float = 0.012f): PageLink? =
    links.map { it to distanceTo(it.area, x, y) }
        .filter { it.second <= slop }
        .minByOrNull { it.second }
        ?.first

// ---- reading comfort ----

/**
 * The colour matrix a page is drawn through, in the 4x5 layout Android and Compose both use
 * (RGB in 0 to 255, the fifth column an offset in the same units), or null when the page
 * should be drawn exactly as rendered.
 *
 * Applied at draw time, never rendered in: changing the tint or the brightness is then
 * instant, costs no memory, and one cached render serves every combination.
 *
 * [brightness] runs from [MIN_BRIGHTNESS] to 1 and scales the result towards black. It dims
 * the page only - the system brightness is the reader's own business and stays as it was.
 */
internal fun pageColourMatrix(tint: PaperTint, brightness: Float): FloatArray? {
    val b = brightness.coerceIn(MIN_BRIGHTNESS, 1f)
    val base: FloatArray = when (tint) {
        PaperTint.DAY -> if (b >= 0.999f) return null else identity()
        // Multiplied by a warm paper colour: white becomes cream, black stays black, and
        // colour in a chart is warmed rather than lost.
        PaperTint.SEPIA -> diagonal(0xF4 / 255f, 0xE8 / 255f, 0xD0 / 255f, 0f, 0f, 0f)
        // A softened inversion: white paper becomes a warm charcoal and black ink a soft
        // off-white, which is easier on the eyes for long reading than pure white on black.
        PaperTint.DARK_PAPER -> diagonal(
            (DARK_PAPER_RGB[0] - DARK_INK_RGB[0]) / 255f,
            (DARK_PAPER_RGB[1] - DARK_INK_RGB[1]) / 255f,
            (DARK_PAPER_RGB[2] - DARK_INK_RGB[2]) / 255f,
            DARK_INK_RGB[0].toFloat(),
            DARK_INK_RGB[1].toFloat(),
            DARK_INK_RGB[2].toFloat()
        )
        // Straight photographic inversion, as night mode always was.
        PaperTint.NIGHT -> diagonal(-1f, -1f, -1f, 255f, 255f, 255f)
    }
    if (b < 0.999f) {
        // Every colour row, offset included, scaled; the alpha row left alone.
        for (row in 0 until 3) for (col in 0 until 5) base[row * 5 + col] *= b
    }
    return base
}

/** The darkest the page can be dimmed to. Lower than this and nothing on it can be read. */
internal const val MIN_BRIGHTNESS = 0.25f

/** Dark paper's sheet and ink, as 0 to 255 RGB. */
internal val DARK_PAPER_RGB = intArrayOf(0x24, 0x22, 0x20)
internal val DARK_INK_RGB = intArrayOf(0xDD, 0xD8, 0xCE)

private fun identity(): FloatArray = diagonal(1f, 1f, 1f, 0f, 0f, 0f)

private fun diagonal(r: Float, g: Float, b: Float, offR: Float, offG: Float, offB: Float) = floatArrayOf(
    r, 0f, 0f, 0f, offR,
    0f, g, 0f, 0f, offG,
    0f, 0f, b, 0f, offB,
    0f, 0f, 0f, 1f, 0f
)

// ---- cropping white margins ----

/**
 * The part of a page to show when white margins are cropped, as a page fraction, or null to
 * show the whole page.
 *
 * [ink] is what `inkBounds` found: left, top, right, bottom. A little white is kept round the
 * ink so the text does not touch the edge of the screen, and a page whose ink already fills
 * most of it is left alone - cropping two per cent off is a jump in the layout for nothing.
 * A blank page (null ink) is shown whole rather than cropped to a dot.
 */
internal fun marginCrop(ink: FloatArray?, keep: Float = 0.02f): PageArea? {
    if (ink == null || ink.size < 4) return null
    val left = (ink[0] - keep).coerceIn(0f, 1f)
    val top = (ink[1] - keep).coerceIn(0f, 1f)
    val right = (ink[2] + keep).coerceIn(0f, 1f)
    val bottom = (ink[3] + keep).coerceIn(0f, 1f)
    val width = right - left
    val height = bottom - top
    if (width <= 0.05f || height <= 0.05f) return null
    if (width > 0.95f && height > 0.95f) return null
    return PageArea(left, top, width, height)
}

/**
 * A page-fraction box re-expressed against a cropped view of the page, so the same search
 * marks, selection bands and link lines can be drawn whether the margins are showing or not.
 */
internal fun PageArea.within(crop: PageArea?): PageArea =
    if (crop == null || crop.width <= 0f || crop.height <= 0f) {
        this
    } else {
        PageArea(
            left = (left - crop.left) / crop.width,
            top = (top - crop.top) / crop.height,
            width = width / crop.width,
            height = height / crop.height
        )
    }

/** A point in a cropped view back to the whole page's fractions - the inverse of [within]. */
internal fun pagePoint(x: Float, y: Float, crop: PageArea?): Pair<Float, Float> =
    if (crop == null) x to y else (crop.left + x * crop.width) to (crop.top + y * crop.height)
