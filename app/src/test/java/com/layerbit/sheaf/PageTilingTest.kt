package com.layerbit.sheaf

import androidx.compose.ui.geometry.Rect
import com.layerbit.sheaf.pdf.BitmapBudget
import com.layerbit.sheaf.ui.viewer.PageTiling
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sums behind the viewer's sharp layer.
 *
 * Two things can go wrong here and neither announces itself. A tile placed wrong looks like
 * a bug in the renderer rather than in a multiplication; a tile sized wrong looks like
 * nothing at all until a phone runs out of memory on a page it used to show. Both are
 * arithmetic, so both are tested here against the shapes a real viewer produces.
 */
class PageTilingTest {

    // A 1080 x 2280 phone with a portrait A4 page zoomed to 2.5x. The page is 1032 px wide
    // at 1x, so 2580 px wide here, and the list is scrolled to the middle of it.
    private val screen = Rect(0f, 0f, 1080f, 2280f)
    private val a4 = 595.3f / 841.9f

    private val pageWidth = 2580f
    private val pageHeight = pageWidth / a4

    /** The page as the list has it: wider than the screen, and running off the top. */
    private val page = Rect(-400f, -1500f, -400f + pageWidth, -1500f + pageHeight)

    /** The space the tile is cut from: three whole render steps of the 1032 px base. */
    private val fullWidth = 1032 * 3
    private val fullHeight = BitmapBudget.heightFor(fullWidth, a4)

    @Test
    fun `the tile covers everything that is on the screen`() {
        val request = PageTiling.requestFor(page, screen, fullWidth, fullHeight)
        assertNotNull(request)
        request!!

        // Back to screen pixels, and then to window coordinates, which is where the claim
        // can actually be checked: the tile must reach past every visible edge of the page.
        val scaleX = page.width / fullWidth
        val scaleY = page.height / fullHeight
        val left = page.left + request.left * scaleX
        val top = page.top + request.top * scaleY
        val right = page.left + request.right * scaleX
        val bottom = page.top + request.bottom * scaleY

        val visible = page.intersect(screen)
        assertTrue("left $left should be at or before ${visible.left}", left <= visible.left + 1f)
        assertTrue("top $top should be at or above ${visible.top}", top <= visible.top + 1f)
        assertTrue("right $right should reach ${visible.right}", right >= visible.right - 1f)
        assertTrue("bottom $bottom should reach ${visible.bottom}", bottom >= visible.bottom - 1f)
    }

    @Test
    fun `the tile costs a screenful rather than a page`() {
        val request = PageTiling.requestFor(page, screen, fullWidth, fullHeight)!!
        val tileBytes = BitmapBudget.byteCost(request.outWidthPx, request.outHeightPx)
        val wholePageBytes = BitmapBudget.byteCost(fullWidth, fullHeight)

        // The whole point. A 3x A4 page is tens of megabytes; the tile is the screen plus
        // the overscan on each side, whatever the zoom.
        val screenBytes = BitmapBudget.byteCost(screen.width.toInt(), screen.height.toInt())
        assertTrue(
            "tile is $tileBytes bytes against a screen's $screenBytes",
            tileBytes < screenBytes * 2
        )
        assertTrue(
            "tile is $tileBytes bytes against the page's $wholePageBytes",
            tileBytes < wholePageBytes / 3
        )
    }

    @Test
    fun `the tile stays inside the page`() {
        val request = PageTiling.requestFor(page, screen, fullWidth, fullHeight)!!
        assertTrue(request.left >= 0)
        assertTrue(request.top >= 0)
        assertTrue(request.right <= fullWidth)
        assertTrue(request.bottom <= fullHeight)
        assertTrue(request.right > request.left)
        assertTrue(request.bottom > request.top)
    }

    @Test
    fun `a small scroll does not ask for a different tile`() {
        val first = PageTiling.requestFor(page, screen, fullWidth, fullHeight)
        // A few pixels, well inside the snapping grid. Without snapping this would be a
        // fresh render of very nearly the same pixels.
        val nudged = page.translate(0f, -3f)
        assertEquals(first, PageTiling.requestFor(nudged, screen, fullWidth, fullHeight))
    }

    @Test
    fun `a scroll past the grid does ask for a different tile`() {
        val first = PageTiling.requestFor(page, screen, fullWidth, fullHeight)
        val moved = page.translate(0f, -(PageTiling.SNAP_PX * 4))
        val second = PageTiling.requestFor(moved, screen, fullWidth, fullHeight)
        assertNotNull(second)
        assertTrue("the tile should have moved down the page", second!!.top > first!!.top)
    }

    @Test
    fun `a page below the screen is not rendered at all`() {
        val below = Rect(0f, 3000f, pageWidth, 3000f + pageHeight)
        assertNull(PageTiling.requestFor(below, screen, fullWidth, fullHeight))
    }

    @Test
    fun `a page just peeping over the edge is not worth a tile`() {
        // Two pixels of it are on the screen. Rendering those costs the same as rendering
        // the ones that matter, and nobody can read two pixels.
        val peeping = Rect(0f, screen.bottom - 2f, pageWidth, screen.bottom - 2f + pageHeight)
        assertNull(PageTiling.requestFor(peeping, screen, fullWidth, fullHeight))
    }

    @Test
    fun `a page smaller than the screen is tiled whole`() {
        val small = Rect(100f, 100f, 100f + 600f, 100f + 600f / a4)
        val request = PageTiling.requestFor(small, screen, fullWidth, fullHeight)!!

        // Everything of it is visible, so overscan and snapping can only run into the page
        // edges - and the clamp is what has to stop them going past.
        assertEquals(0, request.left)
        assertEquals(0, request.top)
        assertEquals(fullWidth, request.right)
        assertEquals(fullHeight, request.bottom)
    }

    @Test
    fun `a page not laid out yet is not rendered`() {
        assertNull(PageTiling.requestFor(Rect.Zero, screen, fullWidth, fullHeight))
        assertNull(PageTiling.requestFor(page, Rect.Zero, fullWidth, fullHeight))
        assertNull(PageTiling.requestFor(page, screen, 0, 0))
    }

    @Test
    fun `the tile is positioned where the page is, not where the screen is`() {
        // The same patch of screen, with the page scrolled to two different places behind
        // it, must name two different patches of the page.
        val high = PageTiling.requestFor(page, screen, fullWidth, fullHeight)!!
        val low = PageTiling.requestFor(
            page.translate(0f, -1000f), screen, fullWidth, fullHeight
        )!!
        assertTrue(low.top > high.top)
        assertEquals(high.outWidthPx, low.outWidthPx)
    }
}
