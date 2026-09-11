package com.layerbit.sheaf.scan

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import kotlin.math.hypot

/**
 * Turning a photograph of a page into something that looks like a scan.
 *
 * Two steps, deliberately separate. [flatten] fixes the geometry - a page photographed at an
 * angle is a trapezium, and mapping its four corners back onto a rectangle makes it a page
 * again. [applyFilter] fixes the tone, which is what makes a phone photo of white paper stop
 * looking grey and shadowed.
 *
 * NO AUTOMATIC EDGE DETECTION. Finding the page outline reliably needs OpenCV or a hand-rolled
 * contour pass, and a corner detector that is right most of the time is worse than none: the
 * times it is wrong, it is wrong silently and crops away someone's signature. So Sheaf shows
 * four handles on a sensible default rectangle and lets the person confirm. That is honest,
 * it is quick, and it never quietly destroys a page. Automatic detection can land later, with
 * manual adjustment still there underneath it.
 */
object PageProcessor {

    /** The four corners of the page within a captured image, in the image's pixel space. */
    data class Corners(
        val topLeft: Point,
        val topRight: Point,
        val bottomRight: Point,
        val bottomLeft: Point
    ) {
        fun asFloatArray(): FloatArray = floatArrayOf(
            topLeft.x, topLeft.y,
            topRight.x, topRight.y,
            bottomRight.x, bottomRight.y,
            bottomLeft.x, bottomLeft.y
        )

        /** Longest edge along each axis, which is what the flattened page should measure. */
        fun outputSize(): Pair<Int, Int> {
            val top = distance(topLeft, topRight)
            val bottom = distance(bottomLeft, bottomRight)
            val left = distance(topLeft, bottomLeft)
            val right = distance(topRight, bottomRight)
            return maxOf(top, bottom).toInt().coerceAtLeast(1) to
                maxOf(left, right).toInt().coerceAtLeast(1)
        }

        companion object {
            /**
             * A rectangle inset from the edges, as the starting point for adjustment.
             *
             * Eight percent, because people frame a page with a margin of desk around it. It
             * is a guess that is easy to correct, not a detection that pretends to be right.
             */
            fun default(width: Int, height: Int, inset: Float = 0.08f): Corners {
                val dx = width * inset
                val dy = height * inset
                return Corners(
                    topLeft = Point(dx, dy),
                    topRight = Point(width - dx, dy),
                    bottomRight = Point(width - dx, height - dy),
                    bottomLeft = Point(dx, height - dy)
                )
            }
        }
    }

    data class Point(val x: Float, val y: Float)

    private fun distance(a: Point, b: Point) = hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()).toFloat()

    /**
     * Maps the quadrilateral at [corners] onto a rectangle.
     *
     * setPolyToPoly with four points is a full perspective transform, which is exactly what
     * undoing a camera angle needs - an affine transform cannot do it, because the near edge
     * of a tilted page is genuinely wider than the far edge.
     *
     * @return a new bitmap. The source is left alone for the caller to recycle or reuse.
     */
    fun flatten(source: Bitmap, corners: Corners): Bitmap {
        val (width, height) = corners.outputSize()

        val destination = floatArrayOf(
            0f, 0f,
            width.toFloat(), 0f,
            width.toFloat(), height.toFloat(),
            0f, height.toFloat()
        )

        val matrix = Matrix()
        if (!matrix.setPolyToPoly(corners.asFloatArray(), 0, destination, 0, 4)) {
            // Degenerate quad - three corners dragged onto one spot, say. Returning the source
            // unchanged is better than returning a blank bitmap.
            return source.copy(source.config ?: Bitmap.Config.ARGB_8888, false) ?: source
        }

        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(output).apply {
            drawColor(Color.WHITE)
            drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        }
        return output
    }

    enum class Filter(val label: String) {
        /** Leave the photograph as it is. Right for anything with pictures on it. */
        ORIGINAL("Photo"),

        /** Drains the colour. Kinder on file size, still shows shading. */
        GREYSCALE("Grey"),

        /** Hard contrast. This is the one that makes a phone photo look like a scan. */
        DOCUMENT("Document")
    }

    /** Applies [filter], returning a new bitmap. */
    fun applyFilter(source: Bitmap, filter: Filter): Bitmap {
        if (filter == Filter.ORIGINAL) {
            return source.copy(source.config ?: Bitmap.Config.ARGB_8888, false) ?: source
        }

        val matrix = ColorMatrix().apply { setSaturation(0f) }
        if (filter == Filter.DOCUMENT) {
            // Steepen the response around mid-grey: paper goes to white, ink stays dark, and
            // the grey cast of indoor light disappears. The translate term is what shifts the
            // midpoint - without it, raising contrast alone just darkens everything.
            val contrast = 2.4f
            val shift = -(0.5f * 255f * (contrast - 1f))
            matrix.postConcat(
                ColorMatrix(
                    floatArrayOf(
                        contrast, 0f, 0f, 0f, shift,
                        0f, contrast, 0f, 0f, shift,
                        0f, 0f, contrast, 0f, shift,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
        }

        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        Canvas(output).drawBitmap(
            source,
            0f,
            0f,
            Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(matrix) }
        )
        return output
    }

    /**
     * Shrinks a capture to something a phone can hold several of.
     *
     * A modern sensor produces a 12-megapixel image, which is 48 MB as ARGB_8888. Ten scanned
     * pages held at that size is half a gigabyte and a certain crash. 2400 on the long edge is
     * about 200 DPI on A4, which is past what the recogniser gains anything from.
     */
    fun downscale(source: Bitmap, maxDimension: Int = MAX_CAPTURE_DIMENSION): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= maxDimension) return source

        val scale = maxDimension.toFloat() / longest
        val width = (source.width * scale).toInt().coerceAtLeast(1)
        val height = (source.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, width, height, true)
    }

    const val MAX_CAPTURE_DIMENSION = 2400
}
