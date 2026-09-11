package com.layerbit.sheaf.pdf

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The hard ceiling on how big a rendered page may be.
 *
 * Out-of-memory on a large or scanned document is the number one way an app in this category
 * dies, and it is not hypothetical. An A0 poster at 300 DPI is roughly 9900 x 14000 pixels,
 * which is 554 MB as ARGB_8888 - far past any heap Android will give a process.
 *
 * Rendering is therefore always clamped, and the clamp lives here rather than being re-derived
 * at each call site with slightly different arithmetic.
 *
 * Pure arithmetic on purpose: no Android types, so it is covered by ordinary JVM unit tests
 * rather than an instrumented run.
 */
object BitmapBudget {

    /** ARGB_8888. Sheaf never renders at a lower depth - banding on a scanned page is worse. */
    const val BYTES_PER_PIXEL = 4

    /**
     * Hardware canvases reject textures past this on a lot of GPUs, and a bitmap that cannot
     * be drawn is no more useful than one that cannot be allocated.
     */
    const val MAX_DIMENSION_PX = 8192

    /**
     * Share of the process heap a single page render may occupy.
     *
     * A quarter, not a half: the viewer holds neighboring pages, Compose holds the frame,
     * and the operation itself may hold a second page. Being able to allocate one bitmap is
     * not the same as the app surviving having allocated it.
     */
    const val HEAP_FRACTION = 0.25

    /**
     * Largest width in pixels that may be rendered for a page of this shape.
     *
     * @param requestedWidthPx what the caller would like.
     * @param aspectRatio page width divided by height.
     * @param maxHeapBytes the process's maximum heap, from ActivityManager.getMemoryClass().
     * @return a width at or below [requestedWidthPx], always at least 1.
     */
    fun clampWidth(requestedWidthPx: Int, aspectRatio: Float, maxHeapBytes: Long): Int {
        if (requestedWidthPx <= 0) return 1
        val ratio = if (aspectRatio.isFinite() && aspectRatio > 0f) aspectRatio else 1f

        // height = width / ratio, so bytes = width^2 / ratio * 4. Solve for width.
        val budgetBytes = (maxHeapBytes * HEAP_FRACTION).coerceAtLeast(1.0)
        val widthByHeap = sqrt(budgetBytes * ratio / BYTES_PER_PIXEL).toInt()

        // A very tall page hits the dimension cap on height long before it does on width.
        val widthByHeight = (MAX_DIMENSION_PX * ratio).toInt()

        val ceiling = min(min(widthByHeap, widthByHeight), MAX_DIMENSION_PX)
        return max(1, min(requestedWidthPx, ceiling))
    }

    /** Height that pairs with [width] for a page of this shape. Always at least 1. */
    fun heightFor(width: Int, aspectRatio: Float): Int {
        val ratio = if (aspectRatio.isFinite() && aspectRatio > 0f) aspectRatio else 1f
        return max(1, (width / ratio).toInt())
    }

    /** What a render at this size will actually cost, for logging and for pre-flight checks. */
    fun byteCost(width: Int, height: Int): Long =
        width.toLong() * height.toLong() * BYTES_PER_PIXEL

    /**
     * Pixel width that renders a page of [widthPoints] at [dpi]. PDF measures in points at
     * 72 to the inch, so this is the whole conversion: exporting at 300 DPI means 300/72.
     */
    fun widthForDpi(widthPoints: Float, dpi: Int): Int =
        max(1, (widthPoints * dpi / 72f).toInt())
}
