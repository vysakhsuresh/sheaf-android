package com.layerbit.sheaf

import com.layerbit.sheaf.scan.Deskew
import com.layerbit.sheaf.scan.IdCardLayout
import com.layerbit.sheaf.scan.ShadowNormaliser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.tan
import kotlin.random.Random

class ScanEnhanceTest {

    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    /**
     * Yellowish paper lit from the left, dark on the right, with lines of ink across it.
     * Returns the pixels and which pixels are ink.
     */
    private fun shadowedPage(w: Int, h: Int): Pair<IntArray, BooleanArray> {
        val ink = BooleanArray(w * h)
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val light = 1f - 0.55f * x / w // half the light gone by the right edge
            val isInk = (y % 40) in 10..14 && (x % 30) < 22 && x > 20 && x < w - 20
            ink[y * w + x] = isInk
            val paperR = 235; val paperG = 220; val paperB = 170
            val f = if (isInk) 0.25f else 1f
            px[y * w + x] = argb((paperR * f * light).toInt(), (paperG * f * light).toInt(), (paperB * f * light).toInt())
        }
        return px to ink
    }

    private fun lum(p: Int) = (((p shr 16) and 0xFF) * 77 + ((p shr 8) and 0xFF) * 150 + (p and 0xFF) * 29) shr 8

    @Test fun shadowRemovalMakesPaperEvenAndNeutral() {
        val w = 600; val h = 400
        val (px, ink) = shadowedPage(w, h)
        val n = ShadowNormaliser(w, h)
        // In bands, as the bitmap path does.
        val band = 64
        var y = 0
        while (y < h) {
            val rows = minOf(band, h - y)
            n.accumulate(px.copyOfRange(y * w, (y + rows) * w), y, rows)
            y += rows
        }
        n.finish()
        y = 0
        while (y < h) {
            val rows = minOf(band, h - y)
            val chunk = px.copyOfRange(y * w, (y + rows) * w)
            n.apply(chunk, y, rows)
            System.arraycopy(chunk, 0, px, y * w, chunk.size)
            y += rows
        }

        var paperMin = 255; var inkMax = 0; var worstCast = 0
        for (i in px.indices) {
            val p = px[i]
            if (ink[i]) inkMax = maxOf(inkMax, lum(p)) else {
                paperMin = minOf(paperMin, lum(p))
                val r = (p shr 16) and 0xFF; val b = p and 0xFF
                worstCast = maxOf(worstCast, abs(r - b))
            }
        }
        // Before: paper went from 235 down to about 105 across the page.
        assertTrue("Paper should be near white everywhere, darkest was $paperMin", paperMin >= 228)
        assertTrue("Paper should be neutral, worst red-blue gap $worstCast", worstCast <= 12)
        assertTrue("Ink should stay dark, lightest was $inkMax", inkMax <= 90)
    }

    /** Lines of dashes at [degrees], dark on white. */
    private fun tiltedText(w: Int, h: Int, degrees: Double, seed: Int = 1): IntArray {
        val rnd = Random(seed)
        val out = IntArray(w * h) { 235 + rnd.nextInt(-8, 9) }
        val slope = tan(Math.toRadians(degrees))
        var baseline = 60
        while (baseline < h - 60) {
            var x = 60
            while (x < w - 60) {
                val word = 20 + rnd.nextInt(30)
                for (dx in 0 until word) for (dy in 0 until 9) {
                    val xx = x + dx
                    val yy = (baseline + dy + (xx - w / 2) * slope).toInt()
                    if (yy in 0 until h && xx in 0 until w && rnd.nextFloat() < 0.6f) out[yy * w + xx] = 30
                }
                x += word + 12
            }
            baseline += 26
        }
        return out
    }

    @Test fun deskewFindsTheTiltOfTextLines() {
        for (angle in listOf(3.0, -2.2, 4.5, -0.8)) {
            val found = Deskew.estimateAngle(tiltedText(800, 1000, angle), 800, 1000)
            assertEquals("Angle $angle", angle.toFloat(), found, 0.15f)
        }
    }

    @Test fun deskewLeavesStraightTextAlone() {
        assertEquals(0f, Deskew.estimateAngle(tiltedText(800, 1000, 0.0), 800, 1000), 0f)
    }

    @Test fun deskewLeavesAPageWithoutTextAlone() {
        val rnd = Random(4)
        // A smooth photo-like gradient with noise: no lines to align.
        val img = IntArray(600 * 800) { i -> ((i % 600) / 3 + (i / 600) / 5 + rnd.nextInt(-20, 21)).coerceIn(0, 255) }
        assertEquals(0f, Deskew.estimateAngle(img, 600, 800), 0f)
    }

    @Test fun idCardSidesSitInTheMiddleOfEachHalfAtRealSize() {
        val w = IdCardLayout.pageWidth
        val h = IdCardLayout.pageHeight
        assertEquals(1654, w)
        assertEquals(2339, h)
        val front = IdCardLayout.slot(0, portraitCard = false)
        val back = IdCardLayout.slot(1, portraitCard = false)
        val cardW = front[2] - front[0]
        val cardH = front[3] - front[1]
        assertEquals(85.60f / 53.98f, cardW.toFloat() / cardH, 0.01f)
        assertEquals(674, cardW) // 85.6 mm at 200 dpi
        assertEquals(w / 2f, (front[0] + front[2]) / 2f, 1f)
        assertEquals(h / 4f, (front[1] + front[3]) / 2f, 1f)
        assertEquals(h * 3 / 4f, (back[1] + back[3]) / 2f, 1f)
        assertTrue(front[3] < h / 2 && back[1] > h / 2)
        val portrait = IdCardLayout.slot(0, portraitCard = true)
        assertEquals(cardW, portrait[3] - portrait[1])
    }
}
