package com.layerbit.sheaf

import com.layerbit.sheaf.pdf.LinkGeometry
import com.layerbit.sheaf.pdf.PageArea
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a link's rectangle lands on the page the reader sees.
 *
 * PDF measures up from the bottom-left of the crop box, before the page is turned; the viewer
 * measures down from the top-left, after. A link drawn a quarter-turn out is a link that
 * cannot be tapped, so each turn is checked at a corner.
 */
class LinkGeometryTest {

    // A US Letter page whose crop box is offset from the origin, as many generators write it.
    private val box = floatArrayOf(10f, 20f, 622f, 812f) // 612 x 792

    /** A link in the top-left corner: 61.2 points wide, 79.2 tall. */
    private val topLeft = floatArrayOf(10f, 732.8f, 71.2f, 812f)

    private fun assertArea(expected: PageArea, actual: PageArea?) {
        requireNotNull(actual)
        assertEquals(expected.left, actual.left, 1e-4f)
        assertEquals(expected.top, actual.top, 1e-4f)
        assertEquals(expected.width, actual.width, 1e-4f)
        assertEquals(expected.height, actual.height, 1e-4f)
    }

    @Test
    fun anUnturnedPageFlipsOnlyTheVerticalAxis() {
        assertArea(PageArea(0f, 0f, 0.1f, 0.1f), LinkGeometry.normalise(topLeft, box, 0))
    }

    @Test
    fun aQuarterTurnPutsTheTopLeftCornerTopRight() {
        assertArea(PageArea(0.9f, 0f, 0.1f, 0.1f), LinkGeometry.normalise(topLeft, box, 90))
    }

    @Test
    fun aHalfTurnPutsItBottomRight() {
        assertArea(PageArea(0.9f, 0.9f, 0.1f, 0.1f), LinkGeometry.normalise(topLeft, box, 180))
    }

    @Test
    fun threeQuartersPutsItBottomLeft() {
        assertArea(PageArea(0f, 0.9f, 0.1f, 0.1f), LinkGeometry.normalise(topLeft, box, 270))
        // And a negative turn is the same turn.
        assertArea(PageArea(0f, 0.9f, 0.1f, 0.1f), LinkGeometry.normalise(topLeft, box, -90))
    }

    @Test
    fun cornersGivenTheWrongWayRoundAreSorted() {
        val reversed = floatArrayOf(71.2f, 812f, 10f, 732.8f)
        assertArea(PageArea(0f, 0f, 0.1f, 0.1f), LinkGeometry.normalise(reversed, box, 0))
    }

    @Test
    fun aLinkOutsideThePageOrOfNoSizeIsDropped() {
        assertNull(LinkGeometry.normalise(floatArrayOf(700f, 900f, 800f, 950f), box, 0))
        assertNull(LinkGeometry.normalise(floatArrayOf(50f, 50f, 50f, 80f), box, 0))
        assertNull(LinkGeometry.normalise(topLeft, floatArrayOf(0f, 0f, 0f, 0f), 0))
    }

    @Test
    fun onlyBrowserMailAndPhoneLinksAreHandedOn() {
        assertTrue(LinkGeometry.isSafeWebUri("https://example.org/a?b=c"))
        assertTrue(LinkGeometry.isSafeWebUri("HTTP://EXAMPLE.ORG"))
        assertTrue(LinkGeometry.isSafeWebUri("mailto:someone@example.org"))
        assertTrue(LinkGeometry.isSafeWebUri("tel:+441234567890"))
        assertFalse(LinkGeometry.isSafeWebUri("javascript:alert(1)"))
        assertFalse(LinkGeometry.isSafeWebUri("file:///sdcard/secret.pdf"))
        assertFalse(LinkGeometry.isSafeWebUri("intent://scan/#Intent;scheme=zxing;end"))
        assertFalse(LinkGeometry.isSafeWebUri("content://contacts/people"))
        assertFalse(LinkGeometry.isSafeWebUri("no scheme at all"))
    }
}
