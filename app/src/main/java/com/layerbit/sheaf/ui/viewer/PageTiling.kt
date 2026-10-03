package com.layerbit.sheaf.ui.viewer

import androidx.compose.ui.geometry.Rect
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Works out which part of a page the viewer should render sharply, and where to put it.
 *
 * Split out of the screen it serves because it is arithmetic and nothing else - no
 * composition, no Android types - and because getting it wrong is both easy and invisible:
 * a tile placed two pixels out looks like a rendering bug in the engine, not like a sum.
 * Here it is covered by ordinary JVM unit tests, the same bargain [BitmapBudget] makes.
 */
internal object PageTiling {

    /**
     * How far past the visible region a tile is cut, in screen pixels.
     *
     * Covers the scroll that stops just short of a tile edge, and the rounding between a
     * page's laid-out size and its rendered one. Small, because tiles are only cut once the
     * view has settled, so there is no fling for a wide margin to try to stay ahead of.
     */
    const val OVERSCAN_PX = 48f

    /**
     * The grid tile edges snap to, in screen pixels.
     *
     * Without it, every settle at a slightly different offset is a different request and so
     * a fresh render of very nearly the same pixels. With it, a tile survives any scroll
     * that lands inside the same cell.
     */
    const val SNAP_PX = 48f

    /** Below this many screen pixels a page is a sliver on its way on or off, not a tile. */
    const val MIN_PX = 8f

    /**
     * The patch of [page] that is on the screen, as something worth rendering - or null when
     * none of it is.
     *
     * Measured in screen pixels and converted to page pixels at the end, because every
     * ceiling that matters here is a screen ceiling: the overscan absorbs a small scroll,
     * the snapping stops a one-pixel move from being a whole new tile, and the output has to
     * stay near a screenful however far the page has been zoomed. Expressed in page pixels
     * all three would move with the zoom, and the last would stop holding.
     *
     * @param page the whole page's rectangle in window coordinates, including the part of it
     *   that is off the screen.
     * @param viewport the visible rectangle of the list, in the same coordinates.
     * @param fullWidthPx the width the page would be rendered at whole, which is the
     *   coordinate space `PdfDocument.renderTile` reads the returned rectangle in.
     * @param fullHeightPx the matching height, from `BitmapBudget.heightFor`.
     */
    fun requestFor(
        page: Rect,
        viewport: Rect,
        fullWidthPx: Int,
        fullHeightPx: Int
    ): TileRequest? {
        if (page.width <= 0f || page.height <= 0f) return null
        if (viewport.width <= 0f || viewport.height <= 0f) return null
        if (fullWidthPx <= 0 || fullHeightPx <= 0) return null

        // intersect returns an inverted rectangle where there is no overlap at all, so this
        // is the test for "off screen" as well as for "too small to be worth a render".
        val visible = page.intersect(viewport)
        if (visible.width < MIN_PX || visible.height < MIN_PX) return null

        // Page-local screen pixels: 0,0 is the page's top left corner, wherever it has
        // scrolled to. Snapped outwards, so the region can only grow to the grid and never
        // shrink below what is actually visible.
        val left = snapDown(visible.left - page.left - OVERSCAN_PX).coerceAtLeast(0f)
        val top = snapDown(visible.top - page.top - OVERSCAN_PX).coerceAtLeast(0f)
        val right = snapUp(visible.right - page.left + OVERSCAN_PX).coerceAtMost(page.width)
        val bottom = snapUp(visible.bottom - page.top + OVERSCAN_PX).coerceAtMost(page.height)
        if (right - left < MIN_PX || bottom - top < MIN_PX) return null

        // One rendered pixel per device pixel. More would be invisible; less is the blur
        // this whole arrangement exists to take off the screen.
        val outWidthPx = (right - left).roundToInt().coerceIn(1, MAX_OUT_PX)
        val outHeightPx = (bottom - top).roundToInt().coerceIn(1, MAX_OUT_PX)

        // The axes are scaled separately: heightFor truncates, so a page's full-scale shape
        // is not quite its on-screen shape, and a tile at the foot of a long page would
        // otherwise sit a pixel or two off the text it is meant to be covering.
        val scaleX = fullWidthPx / page.width
        val scaleY = fullHeightPx / page.height

        return TileRequest(
            fullWidthPx = fullWidthPx,
            fullHeightPx = fullHeightPx,
            left = (left * scaleX).roundToInt().coerceIn(0, fullWidthPx),
            top = (top * scaleY).roundToInt().coerceIn(0, fullHeightPx),
            right = (right * scaleX).roundToInt().coerceIn(0, fullWidthPx),
            bottom = (bottom * scaleY).roundToInt().coerceIn(0, fullHeightPx),
            outWidthPx = outWidthPx,
            outHeightPx = outHeightPx
        )
    }

    private fun snapDown(value: Float): Float = floor(value / SNAP_PX) * SNAP_PX

    private fun snapUp(value: Float): Float = -snapDown(-value)

    /**
     * The same ceiling [com.layerbit.sheaf.pdf.BitmapBudget] puts on a whole page, repeated
     * here rather than imported, because this is a ceiling on an output size the budget never
     * sees. Nothing on a phone comes close; it is here so that a page laid out at an absurd
     * size by some future bug cannot ask for a bitmap no GPU will take.
     */
    private const val MAX_OUT_PX = 8192
}

/**
 * A region of a page to render, in the space `PdfDocument.renderTile` defines: pixels of the
 * page as it would be rendered whole at [fullWidthPx], top left origin, y down.
 *
 * A data class because equality is what drives the work. The tile is re-rendered when, and
 * only when, one of these stops matching the last - which is the reason the edges are
 * snapped to a grid before they ever get here.
 */
internal data class TileRequest(
    val fullWidthPx: Int,
    val fullHeightPx: Int,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val outWidthPx: Int,
    val outHeightPx: Int
)
