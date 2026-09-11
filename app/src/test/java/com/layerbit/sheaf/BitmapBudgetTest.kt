package com.layerbit.sheaf

import com.layerbit.sheaf.pdf.BitmapBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The clamp is the thing standing between this app and the crash that kills apps in this
 * category, so it is tested against the documents that actually cause it rather than against
 * round numbers.
 */
class BitmapBudgetTest {

    private val smallHeap = 64L * 1024 * 1024   // a cheap phone
    private val largeHeap = 512L * 1024 * 1024  // a flagship

    private val a4Portrait = 595.3f / 841.9f

    @Test
    fun `a modest request is returned unchanged`() {
        assertEquals(1080, BitmapBudget.clampWidth(1080, a4Portrait, largeHeap))
    }

    @Test
    fun `an A0 poster at 300 DPI is clamped rather than allocated`() {
        // A0 is 2384 x 3370 points. At 300 DPI that is 9933 x 14041 px - 557 MB as ARGB_8888,
        // which is more than the whole heap on most devices.
        val requested = BitmapBudget.widthForDpi(2384f, 300)
        assertTrue("expected a large request, got $requested", requested > 9000)

        val aspect = 2384f / 3370f
        val width = BitmapBudget.clampWidth(requested, aspect, smallHeap)
        val height = BitmapBudget.heightFor(width, aspect)
        val cost = BitmapBudget.byteCost(width, height)

        assertTrue("clamped to ${width}x$height = $cost bytes", cost <= smallHeap / 4 + ONE_ROW)
        assertTrue(width < requested)
    }

    @Test
    fun `a very tall page is limited by the dimension cap, not only by heap`() {
        // A receipt from a thermal printer: narrow and extremely long.
        val aspect = 200f / 6000f
        val width = BitmapBudget.clampWidth(4000, aspect, largeHeap)
        val height = BitmapBudget.heightFor(width, aspect)
        assertTrue("height $height exceeded the texture cap", height <= BitmapBudget.MAX_DIMENSION_PX)
    }

    @Test
    fun `a degenerate page size does not produce a zero or negative bitmap`() {
        for (aspect in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val width = BitmapBudget.clampWidth(1080, aspect, smallHeap)
            assertTrue("width was $width for aspect $aspect", width >= 1)
            assertTrue(BitmapBudget.heightFor(width, aspect) >= 1)
        }
    }

    @Test
    fun `a zero or negative request still yields a drawable bitmap`() {
        assertEquals(1, BitmapBudget.clampWidth(0, a4Portrait, smallHeap))
        assertEquals(1, BitmapBudget.clampWidth(-100, a4Portrait, smallHeap))
    }

    @Test
    fun `DPI conversion follows the PDF points-per-inch definition`() {
        // 595.3 points is 8.27 inches. At 72 DPI a point is a pixel; at 300 it is 2480 px,
        // which is the number every scanner and print shop quotes for A4.
        assertEquals(595, BitmapBudget.widthForDpi(595.3f, 72))
        assertEquals(2480, BitmapBudget.widthForDpi(595.3f, 300))
    }

    private companion object {
        /** One row of slack, since the clamp works in whole pixels and rounds down. */
        const val ONE_ROW = 8192L * 4
    }
}
