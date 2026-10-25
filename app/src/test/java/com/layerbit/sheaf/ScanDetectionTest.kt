package com.layerbit.sheaf

import com.layerbit.sheaf.scan.AutoCaptureTracker
import com.layerbit.sheaf.scan.LumaSampler
import com.layerbit.sheaf.scan.PageDetector
import com.layerbit.sheaf.scan.Quad
import com.layerbit.sheaf.scan.QuadGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

class ScanDetectionTest {

    /** A bright quadrilateral at [corners] (pixels) over dark noise, optionally with "text". */
    private fun synthetic(
        w: Int,
        h: Int,
        corners: FloatArray,
        seed: Int = 1,
        paper: Int = 205,
        desk: Int = 50,
        noise: Int = 22,
        text: Boolean = false
    ): IntArray {
        val rnd = Random(seed)
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val inside = inside(corners, x + 0.5f, y + 0.5f)
            var v = if (inside) paper else desk
            v += rnd.nextInt(-noise, noise + 1)
            out[y * w + x] = v.coerceIn(0, 255)
        }
        if (text) {
            // Rows of short dark dashes inside the page, like lines of type seen from afar.
            val cx = corners.filterIndexed { i, _ -> i % 2 == 0 }.average().toFloat()
            val cy = corners.filterIndexed { i, _ -> i % 2 == 1 }.average().toFloat()
            for (line in -4..4) for (word in -5..5) {
                val px = (cx + word * 9).toInt()
                val py = (cy + line * 8).toInt()
                for (dy in 0..2) for (dx in 0..5) {
                    val x = px + dx
                    val y = py + dy
                    if (x in 0 until w && y in 0 until h && inside(corners, x + 0.5f, y + 0.5f)) out[y * w + x] = 40
                }
            }
        }
        return out
    }

    private fun inside(p: FloatArray, x: Float, y: Float): Boolean {
        var sign = 0
        for (i in 0 until 4) {
            val ax = p[2 * i]; val ay = p[2 * i + 1]
            val bx = p[2 * ((i + 1) % 4)]; val by = p[2 * ((i + 1) % 4) + 1]
            val c = (bx - ax) * (y - ay) - (by - ay) * (x - ax)
            val s = if (c >= 0) 1 else -1
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

    private fun rotatedRect(cx: Float, cy: Float, halfW: Float, halfH: Float, degrees: Double): FloatArray {
        val r = Math.toRadians(degrees)
        val c = cos(r).toFloat(); val s = sin(r).toFloat()
        val local = floatArrayOf(-halfW, -halfH, halfW, -halfH, halfW, halfH, -halfW, halfH)
        return FloatArray(8) { i ->
            val x = local[i - i % 2]; val y = local[i - i % 2 + 1]
            if (i % 2 == 0) cx + x * c - y * s else cy + x * s + y * c
        }
    }

    private fun assertQuadNear(expectedPx: FloatArray, found: Quad?, w: Int, h: Int, tolerancePx: Float = 2.5f) {
        assertNotNull("No page found", found)
        val expected = QuadGeometry.order(FloatArray(8) { i -> if (i % 2 == 0) expectedPx[i] / w else expectedPx[i] / h })
        for (i in 0 until 4) {
            val dx = (found!!.x(i) - expected[2 * i]) * w
            val dy = (found.y(i) - expected[2 * i + 1]) * h
            assertTrue("Corner $i off by ${hypot(dx, dy)} px: $found", hypot(dx, dy) <= tolerancePx)
        }
    }

    @Test fun findsRotatedPageOnDarkNoise() {
        val w = 320; val h = 240
        val page = rotatedRect(160f, 120f, 95f, 70f, 12.0)
        val found = PageDetector().detect(synthetic(w, h, page), w, h)
        assertQuadNear(page, found, w, h)
    }

    @Test fun findsPerspectivePageWithText() {
        val w = 240; val h = 320
        val page = floatArrayOf(52f, 40f, 196f, 58f, 214f, 290f, 30f, 270f)
        val found = PageDetector().detect(synthetic(w, h, page, seed = 7, text = true), w, h)
        assertQuadNear(page, found, w, h)
    }

    @Test fun findsLowContrastPage() {
        val w = 320; val h = 240
        val page = rotatedRect(150f, 125f, 110f, 80f, -6.0)
        val found = PageDetector().detect(synthetic(w, h, page, seed = 3, paper = 150, desk = 110, noise = 10), w, h)
        assertQuadNear(page, found, w, h, tolerancePx = 3f)
    }

    @Test fun findsPageWhenClutterTouchesIt() {
        val w = 320; val h = 240
        val page = rotatedRect(170f, 120f, 90f, 75f, 4.0)
        val img = synthetic(w, h, page, seed = 11)
        // A bright pen lying against the page's left edge and running off the frame.
        for (y in 110..118) for (x in 0..80) img[y * w + x] = 230
        val found = PageDetector().detect(img, w, h)
        assertQuadNear(page, found, w, h, tolerancePx = 3f)
    }

    @Test fun reusesBuffersAcrossFrames() {
        val w = 320; val h = 240
        val detector = PageDetector()
        val a = rotatedRect(160f, 120f, 95f, 70f, 8.0)
        val b = rotatedRect(150f, 130f, 100f, 80f, -10.0)
        assertQuadNear(a, detector.detect(synthetic(w, h, a, seed = 2), w, h), w, h)
        assertQuadNear(b, detector.detect(synthetic(w, h, b, seed = 5), w, h), w, h)
    }

    @Test fun noPageInPureNoise() {
        val w = 320; val h = 240
        val rnd = Random(9)
        val img = IntArray(w * h) { 100 + rnd.nextInt(-30, 31) }
        assertNull(PageDetector().detect(img, w, h))
    }

    @Test fun noPageWhenItRunsOffTheFrame() {
        val w = 320; val h = 240
        // Covers the whole left side: there is no left edge to find.
        val page = floatArrayOf(-40f, 30f, 250f, 30f, 250f, 210f, -40f, 210f)
        assertNull(PageDetector().detect(synthetic(w, h, page, seed = 4), w, h))
    }

    @Test fun fastEnoughForEveryFrame() {
        val w = 320; val h = 240
        val img = synthetic(w, h, rotatedRect(160f, 120f, 95f, 70f, 12.0), text = true)
        val detector = PageDetector()
        repeat(20) { detector.detect(img, w, h) } // warm the JIT
        val start = System.nanoTime()
        val runs = 30
        repeat(runs) { detector.detect(img, w, h) }
        val ms = (System.nanoTime() - start) / 1e6 / runs
        // A desktop JVM is several times faster than a phone; this guards against an
        // accidental quadratic, not the phone's exact budget.
        assertTrue("Detection took $ms ms", ms < 40.0)
    }

    @Test fun samplerAveragesTheCropOfAStridedPlane() {
        val rowStride = 16
        val plane = ByteArray(rowStride * 8)
        for (y in 0 until 8) for (x in 0 until 12) plane[y * rowStride + x] = (if (x >= 4) 200 else 10).toByte()
        val sampler = LumaSampler(maxSide = 4)
        sampler.sample(plane, rowStride, 1, left = 4, top = 0, cropWidth = 8, cropHeight = 8)
        assertEquals(4, sampler.width)
        assertEquals(4, sampler.height)
        assertTrue(sampler.pixels.take(16).all { it == 200 })
    }

    @Test fun ordersCornersClockwiseFromTopLeft() {
        val shuffled = floatArrayOf(0.9f, 0.8f, 0.1f, 0.1f, 0.15f, 0.85f, 0.95f, 0.05f)
        val o = QuadGeometry.order(shuffled)
        assertEquals(listOf(0.1f, 0.1f, 0.95f, 0.05f, 0.9f, 0.8f, 0.15f, 0.85f), o.toList())
    }

    @Test fun rotatesSensorQuadUpright() {
        // A point near the sensor frame's top left; the sensor is turned 90 degrees clockwise
        // to be upright, so that point ends up near the top right.
        val q = Quad(floatArrayOf(0.1f, 0.2f, 0.6f, 0.2f, 0.6f, 0.9f, 0.1f, 0.9f))
        val up = QuadGeometry.rotateUpright(q, 90)
        assertEquals(0.1f, up.x(0), 1e-5f); assertEquals(0.1f, up.y(0), 1e-5f)
        assertEquals(0.8f, up.x(1), 1e-5f); assertEquals(0.1f, up.y(1), 1e-5f)
        assertEquals(0.8f, up.x(2), 1e-5f); assertEquals(0.6f, up.y(2), 1e-5f)
        val back = QuadGeometry.rotateUpright(QuadGeometry.rotateUpright(up, 180), 90)
        // 90 + 180 + 90 is a full turn.
        assertTrue(back.maxCornerDistance(q) < 1e-5f)
    }

    @Test fun fillCenterCropsTheLongerWay() {
        // A 4:3 upright image in a 9:16 view: the height fills, the sides spill off.
        val q = Quad(floatArrayOf(0.5f, 0f, 1f, 0f, 1f, 1f, 0.5f, 1f))
        val px = QuadGeometry.fillCenter(q, 3f, 4f, 900f, 1600f)
        // Scale is 400 (1600 / 4); the image is 1200 wide, so 150 spills off each side.
        assertEquals(450f, px[0], 0.01f)
        assertEquals(1050f, px[2], 0.01f)
        assertEquals(1600f, px[5], 0.01f)
    }

    @Test fun rectangleTest() {
        assertTrue(QuadGeometry.isNearlyRectangular(floatArrayOf(10f, 10f, 200f, 12f, 199f, 300f, 9f, 298f)))
        assertFalse(QuadGeometry.isNearlyRectangular(floatArrayOf(40f, 10f, 160f, 10f, 200f, 300f, 0f, 300f)))
    }

    private fun quad(dx: Float = 0f) = Quad(floatArrayOf(0.1f + dx, 0.1f, 0.9f + dx, 0.1f, 0.9f + dx, 0.9f, 0.1f + dx, 0.9f))

    @Test fun autoCaptureFiresAfterAHeldSecondOnce() {
        val t = AutoCaptureTracker()
        val sig = IntArray(64) { 120 }
        var fired = 0
        var time = 0L
        while (time <= 3000) {
            // A slight tremor, well inside the tolerance.
            val s = t.update(quad(if (time % 66 == 0L) 0.004f else 0f), sig, time)
            if (s.fire) { fired++; assertTrue(time in 1000..1100) }
            time += 33
        }
        assertEquals("Holding still over one page takes it once", 1, fired)
    }

    @Test fun autoCaptureWaitsWhileMoving() {
        val t = AutoCaptureTracker()
        var time = 0L
        var maxProgress = 0f
        while (time <= 2000) {
            val s = t.update(quad(time / 33 * 0.01f % 0.08f), null, time)
            assertFalse(s.fire)
            maxProgress = maxOf(maxProgress, s.progress)
            time += 33
        }
        assertTrue(maxProgress < 0.5f)
    }

    @Test fun autoCaptureRearmsOnANewPage() {
        val t = AutoCaptureTracker()
        val first = IntArray(64) { 120 }
        val second = IntArray(64) { if (it % 2 == 0) 100 else 140 }
        var time = 0L
        var fires = 0
        while (time <= 4000) {
            // The next sheet goes down in the same place two seconds in.
            val s = t.update(quad(), if (time < 2000) first else second, time)
            if (s.fire) fires++
            time += 33
        }
        assertEquals(2, fires)
    }

    @Test fun autoCaptureIgnoresSmallPages() {
        val t = AutoCaptureTracker()
        val small = Quad(floatArrayOf(0.4f, 0.4f, 0.6f, 0.4f, 0.6f, 0.6f, 0.4f, 0.6f))
        var time = 0L
        while (time <= 2000) { assertFalse(t.update(small, null, time).fire); time += 33 }
    }

    @Test fun signatureNoticesAChangedPage() {
        val a = IntArray(64 * 64) { 128 }
        val b = IntArray(64 * 64) { i -> if ((i / 64) % 16 < 8) 90 else 160 }
        val sa = IntArray(64); val sb = IntArray(64)
        PageDetector.signature(a, 64, 64, sa)
        PageDetector.signature(b, 64, 64, sb)
        assertEquals(0f, PageDetector.signatureDistance(sa, sa), 0f)
        assertTrue(PageDetector.signatureDistance(sa, sb) > 20f)
    }
}
