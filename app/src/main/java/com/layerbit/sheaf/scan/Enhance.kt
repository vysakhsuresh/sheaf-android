package com.layerbit.sheaf.scan

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Evens out the light across a photographed page and makes the paper white.
 *
 * A phone held over a page casts its own shadow, a desk lamp lights one corner more than the
 * other, and indoor light is yellow. A single contrast curve cannot fix any of that, because
 * the paper is a different brightness in different places. So the paper's brightness is
 * estimated everywhere, and each pixel is divided by it: paper becomes white wherever it is,
 * and ink keeps its darkness relative to the paper round it.
 *
 * The estimate is per tile, per channel. Each tile's brightest value is the paper there (ink is
 * darker than paper, never brighter). A grey-level closing over the tile grid - largest of the
 * neighbours, then smallest - fills in tiles that were all ink, such as a heading or a small
 * picture, and a blur smooths the grid so tile edges never show. Dividing per channel is also
 * the white balance: the paper under yellow light has a high red and a low blue, and dividing
 * by those brings it to neutral.
 *
 * Works in horizontal bands so a 2400-pixel page never needs a second full-size pixel array:
 * [accumulate] every band, [finish] once, then [apply] every band.
 */
class ShadowNormaliser(private val width: Int, private val height: Int) {

    private val tile = max(8, max(width, height) / 48)
    private val tilesX = (width + tile - 1) / tile
    private val tilesY = (height + tile - 1) / tile
    private val bg = Array(3) { IntArray(tilesX * tilesY) }
    private val tileMin = IntArray(tilesX * tilesY) { 255 }
    private var black = 0f

    fun accumulate(pixels: IntArray, y0: Int, rows: Int) {
        for (r in 0 until rows) {
            val y = y0 + r
            val ty = y / tile
            val base = r * width
            for (x in 0 until width) {
                val p = pixels[base + x]
                val t = ty * tilesX + x / tile
                val red = (p shr 16) and 0xFF
                val green = (p shr 8) and 0xFF
                val blue = p and 0xFF
                if (red > bg[0][t]) bg[0][t] = red
                if (green > bg[1][t]) bg[1][t] = green
                if (blue > bg[2][t]) bg[2][t] = blue
                val luma = (red * 77 + green * 150 + blue * 29) shr 8
                if (luma < tileMin[t]) tileMin[t] = luma
            }
        }
    }

    fun finish() {
        for (c in 0 until 3) {
            var grid = bg[c]
            grid = filter(grid, CLOSE_RADIUS, true)
            grid = filter(grid, CLOSE_RADIUS, false)
            grid = boxBlur(grid)
            grid = boxBlur(grid)

            // Never divide by a background darker than about half the paper. A large photo on
            // the page reads as dark background, and dividing it by itself would bleach it.
            val sorted = grid.copyOf().also { it.sort() }
            val paper = max(1, sorted[(sorted.size * 0.9f).toInt().coerceAtMost(sorted.size - 1)])
            val floor = max(1, (paper * 0.45f).toInt())
            for (i in grid.indices) grid[i] = max(grid[i], floor)
            bg[c] = grid
        }

        // The black point: the darkest ink, relative to its paper, among the tiles that have
        // any. Stretching to it makes faint pencil and grey toner read as properly dark.
        val luma = IntArray(tileMin.size) { i ->
            val paper = (bg[0][i] * 77 + bg[1][i] * 150 + bg[2][i] * 29) shr 8
            (tileMin[i] * 255 / max(1, paper)).coerceIn(0, 255)
        }
        luma.sort()
        black = min(luma[(luma.size * 0.05f).toInt()], MAX_BLACK).toFloat()
    }

    fun apply(pixels: IntArray, y0: Int, rows: Int) {
        val scale = 255f / (255f - black)
        for (r in 0 until rows) {
            val y = y0 + r
            // Tile centres are the sample points; interpolate between the two rows of them.
            val fy = ((y + 0.5f) / tile - 0.5f).coerceIn(0f, (tilesY - 1).toFloat())
            val ty0 = fy.toInt()
            val ty1 = min(ty0 + 1, tilesY - 1)
            val wy = fy - ty0
            val base = r * width
            for (x in 0 until width) {
                val fx = ((x + 0.5f) / tile - 0.5f).coerceIn(0f, (tilesX - 1).toFloat())
                val tx0 = fx.toInt()
                val tx1 = min(tx0 + 1, tilesX - 1)
                val wx = fx - tx0
                val i00 = ty0 * tilesX + tx0
                val i01 = ty0 * tilesX + tx1
                val i10 = ty1 * tilesX + tx0
                val i11 = ty1 * tilesX + tx1
                val p = pixels[base + x]
                var out = p and 0xFF000000.toInt()
                for (c in 0 until 3) {
                    val g = bg[c]
                    val top = g[i00] + (g[i01] - g[i00]) * wx
                    val bottom = g[i10] + (g[i11] - g[i10]) * wx
                    val background = top + (bottom - top) * wy
                    val shift = 16 - 8 * c
                    val value = (p shr shift) and 0xFF
                    val flat = value * 255f / background
                    val levelled = ((flat - black) * scale).roundToInt().coerceIn(0, 255)
                    out = out or (levelled shl shift)
                }
                pixels[base + x] = out
            }
        }
    }

    private fun filter(src: IntArray, radius: Int, takeMax: Boolean): IntArray {
        val out = IntArray(src.size)
        for (ty in 0 until tilesY) for (tx in 0 until tilesX) {
            var v = if (takeMax) 0 else 255
            for (ny in max(0, ty - radius)..min(tilesY - 1, ty + radius)) {
                for (nx in max(0, tx - radius)..min(tilesX - 1, tx + radius)) {
                    val s = src[ny * tilesX + nx]
                    v = if (takeMax) max(v, s) else min(v, s)
                }
            }
            out[ty * tilesX + tx] = v
        }
        return out
    }

    private fun boxBlur(src: IntArray): IntArray {
        val out = IntArray(src.size)
        for (ty in 0 until tilesY) for (tx in 0 until tilesX) {
            var sum = 0
            var n = 0
            for (ny in max(0, ty - 1)..min(tilesY - 1, ty + 1)) {
                for (nx in max(0, tx - 1)..min(tilesX - 1, tx + 1)) {
                    sum += src[ny * tilesX + nx]; n++
                }
            }
            out[ty * tilesX + tx] = sum / n
        }
        return out
    }

    private companion object {
        const val CLOSE_RADIUS = 2
        /** Never treat anything lighter than this as black, or a page of faint text vanishes. */
        const val MAX_BLACK = 70
    }
}

/**
 * Finds how far the lines of text on a page are tilted.
 *
 * Projection profiles: for each candidate angle, count the dark pixels along lines at that
 * angle. When the angle matches the text, whole lines of type fall into a few rows and the
 * gaps between lines fall into empty ones, so the counts are as uneven as they get. The sum of
 * squared counts measures that unevenness, and the angle with the largest sum is the tilt.
 *
 * Searched coarsely over plus or minus [MAX_DEGREES] and then finely round the best, which
 * is a few dozen passes over the dark pixels of a small image - well under a tenth of a
 * second.
 */
object Deskew {

    const val MAX_DEGREES = 5f

    /**
     * @param luma brightness 0..255, row-major. About a thousand pixels across is plenty.
     * @return the tilt in degrees, positive when lines fall to the right (clockwise on screen),
     *   or 0 when the page has no clear lines of text - a photo, a blank page, a drawing.
     */
    fun estimateAngle(luma: IntArray, width: Int, height: Int): Float {
        val threshold = darkThreshold(luma)
        // The outer edge is skipped: a sliver of desk or the page's own shadow left by a loose
        // crop is a long dark line, and would win the vote over the text.
        val marginX = width / 20
        val marginY = height / 20
        var count = 0
        for (y in marginY until height - marginY) for (x in marginX until width - marginX) {
            if (luma[y * width + x] < threshold) count++
        }
        val inside = (width - 2 * marginX) * (height - 2 * marginY)
        if (count < 200 || count > inside * 0.35f) return 0f

        val xs = IntArray(count)
        val ys = IntArray(count)
        var k = 0
        for (y in marginY until height - marginY) for (x in marginX until width - marginX) {
            if (luma[y * width + x] < threshold) { xs[k] = x; ys[k] = y; k++ }
        }

        val diagonal = width + height
        val bins = IntArray(2 * diagonal + 4)
        fun score(degrees: Float): Double {
            java.util.Arrays.fill(bins, 0)
            val radians = Math.toRadians(degrees.toDouble())
            val c = cos(radians).toFloat()
            val s = sin(radians).toFloat()
            for (i in 0 until count) {
                // Distance across lines tilted by the angle: constant along one line of text.
                val r = (ys[i] * c - xs[i] * s).roundToInt() + diagonal
                bins[r]++
            }
            var sum = 0.0
            for (b in bins) if (b != 0) sum += b.toDouble() * b
            return sum
        }

        var best = 0f
        var bestScore = score(0f)
        val flat = bestScore
        var a = -MAX_DEGREES
        while (a <= MAX_DEGREES + 1e-3f) {
            val sc = score(a)
            if (sc > bestScore) { bestScore = sc; best = a }
            a += 0.25f
        }
        val centre = best
        var f = centre - 0.25f
        while (f <= centre + 0.25f + 1e-3f) {
            val sc = score(f)
            if (sc > bestScore) { bestScore = sc; best = f }
            f += 0.05f
        }

        // A tilt too small to see is left alone rather than resampled for nothing, and a win
        // that barely beats upright is noise, not lines of text.
        if (abs(best) < 0.3f || bestScore < flat * 1.08) return 0f
        return best
    }

    /** Otsu's threshold: the split that best separates ink from paper. */
    internal fun darkThreshold(luma: IntArray): Int {
        val histogram = IntArray(256)
        for (v in luma) histogram[v.coerceIn(0, 255)]++
        val total = luma.size.toDouble()
        var sum = 0.0
        for (i in 0 until 256) sum += i.toDouble() * histogram[i]
        var sumBelow = 0.0
        var weightBelow = 0.0
        var bestVariance = -1.0
        var best = 128
        for (t in 0 until 256) {
            weightBelow += histogram[t]
            if (weightBelow == 0.0) continue
            val weightAbove = total - weightBelow
            if (weightAbove == 0.0) break
            sumBelow += t.toDouble() * histogram[t]
            val meanBelow = sumBelow / weightBelow
            val meanAbove = (sum - sumBelow) / weightAbove
            val variance = weightBelow * weightAbove * (meanBelow - meanAbove) * (meanBelow - meanAbove)
            if (variance > bestVariance) { bestVariance = variance; best = t }
        }
        return best + 1
    }
}

/**
 * Where the two sides of an ID card go on one A4 page.
 *
 * ID-1 is the size of a bank card, a driving licence and most national ID cards: 85.60 by
 * 53.98 mm. Each side is drawn at exactly that size on an A4 sheet, so printing the page at
 * full size gives a copy the size of the card itself - which is what an office asking for "a
 * copy of both sides" expects. Front in the middle of the top half, back in the middle of the
 * bottom half.
 */
object IdCardLayout {
    const val A4_WIDTH_MM = 210f
    const val A4_HEIGHT_MM = 297f
    const val CARD_LONG_MM = 85.60f
    const val CARD_SHORT_MM = 53.98f

    /** 200 dots per inch: sharp in print, and a page JPEG of a few hundred kilobytes. */
    const val DPI = 200

    val pageWidth: Int get() = mmToPx(A4_WIDTH_MM)
    val pageHeight: Int get() = mmToPx(A4_HEIGHT_MM)

    /** left, top, right, bottom in page pixels, for the front (0) or back (1). */
    fun slot(side: Int, portraitCard: Boolean): IntArray {
        val cardW = mmToPx(if (portraitCard) CARD_SHORT_MM else CARD_LONG_MM)
        val cardH = mmToPx(if (portraitCard) CARD_LONG_MM else CARD_SHORT_MM)
        val centreY = if (side == 0) pageHeight / 4 else pageHeight * 3 / 4
        val left = (pageWidth - cardW) / 2
        val top = centreY - cardH / 2
        return intArrayOf(left, top, left + cardW, top + cardH)
    }

    private fun mmToPx(mm: Float): Int = (mm / 25.4f * DPI).roundToInt()
}
