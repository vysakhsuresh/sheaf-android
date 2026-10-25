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
 * The stages, in the order [process] runs them:
 *  1. [flatten] fixes the geometry - a page photographed at an angle is a trapezium, and
 *     mapping its four corners back onto a rectangle makes it a page again.
 *  2. [enhance] evens out shadows and makes the paper white (not for the Photo look, which
 *     promises to leave the picture alone).
 *  3. [deskew] straightens text that still slants after a square crop.
 *  4. [applyFilter] sets the final tone.
 *
 * Where the corners come from: [detectCorners] finds the page outline with [PageDetector], the
 * hand-rolled contour pass the viewfinder also runs live. It is a starting point, never the
 * last word - the person always sees four handles on the photo and confirms or moves them,
 * because a detector that is wrong silently crops away someone's signature. When nothing is
 * found the handles start on [Corners.default].
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

            /** A detected outline, normalised 0..1, in the pixels of an image this size. */
            fun fromQuad(quad: Quad, width: Int, height: Int): Corners {
                val p = quad.scaled(width.toFloat(), height.toFloat())
                fun at(i: Int) = Point(p[2 * i].coerceIn(0f, width.toFloat()), p[2 * i + 1].coerceIn(0f, height.toFloat()))
                return Corners(at(0), at(1), at(2), at(3))
            }
        }
    }

    data class Point(val x: Float, val y: Float)

    private fun distance(a: Point, b: Point) = hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()).toFloat()

    /**
     * Maps the quadrilateral at [corners] onto a rectangle.
     *
     * setPolyToPoly with four points is a full perspective transform, which is what undoing a
     * camera angle needs. An affine transform cannot do it, because the near edge of a tilted
     * page is genuinely wider than the far edge.
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

        /** Drains the color. Kinder on file size, still shows shading. */
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
     * Every stage, from the photo to the finished page.
     *
     * @return a new bitmap; [source] is left for the caller.
     */
    fun process(source: Bitmap, corners: Corners, filter: Filter): Bitmap {
        var page = flatten(source, corners)
        if (filter != Filter.ORIGINAL) enhance(page)
        if (QuadGeometry.isNearlyRectangular(corners.asFloatArray())) {
            val straight = deskew(page)
            if (straight !== page) {
                page.recycle()
                page = straight
            }
        }
        val filtered = applyFilter(page, filter)
        if (filtered !== page) page.recycle()
        return filtered
    }

    /**
     * Shadow removal and white balance, in place, a band of rows at a time.
     *
     * Bands because the page is up to 2400 pixels on its long side: a whole-page IntArray
     * beside the bitmap is another 20 MB, where a band of 128 rows is about one.
     */
    fun enhance(bitmap: Bitmap) {
        if (!bitmap.isMutable) return
        val w = bitmap.width
        val h = bitmap.height
        val normaliser = ShadowNormaliser(w, h)
        val band = IntArray(w * ENHANCE_BAND)
        var y = 0
        while (y < h) {
            val rows = minOf(ENHANCE_BAND, h - y)
            bitmap.getPixels(band, 0, w, 0, y, w, rows)
            normaliser.accumulate(band, y, rows)
            y += rows
        }
        normaliser.finish()
        y = 0
        while (y < h) {
            val rows = minOf(ENHANCE_BAND, h - y)
            bitmap.getPixels(band, 0, w, 0, y, w, rows)
            normaliser.apply(band, y, rows)
            bitmap.setPixels(band, 0, w, 0, y, w, rows)
            y += rows
        }
    }

    /**
     * Turns the page so its lines of text run level, or returns it as it was.
     *
     * The tilt is measured on a copy about a thousand pixels across, which is ample for a
     * twentieth of a degree and quick. The turn keeps the page's size and fills the slivers
     * it uncovers at the corners with white, the colour of the paper they replace.
     */
    fun deskew(page: Bitmap): Bitmap {
        val small = downscale(page, DESKEW_MEASURE_PX)
        val luma = luma(small)
        val angle = Deskew.estimateAngle(luma, small.width, small.height)
        if (small !== page) small.recycle()
        if (angle == 0f) return page

        val output = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
        Canvas(output).apply {
            drawColor(Color.WHITE)
            rotate(-angle, page.width / 2f, page.height / 2f)
            drawBitmap(page, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        }
        return output
    }

    /**
     * Looks for the page outline in a still photo.
     *
     * The same detector as the live viewfinder, on a copy shrunk to its working size: the
     * outline is a large shape and a few hundred pixels find it as well as twelve million.
     */
    fun detectCorners(photo: Bitmap): Corners? {
        val small = downscale(photo, DETECT_PX)
        val quad = PageDetector().detect(luma(small), small.width, small.height)
        if (small !== photo) small.recycle()
        return quad?.let { Corners.fromQuad(it, photo.width, photo.height) }
    }

    /**
     * Both sides of an ID card on one white A4 page, each at the card's real proportions.
     *
     * A card cropped taller than it is wide was photographed upright, so it goes on the page
     * upright; otherwise it lies landscape like a bank card.
     */
    fun composeIdCard(front: Bitmap, back: Bitmap): Bitmap {
        val page = Bitmap.createBitmap(IdCardLayout.pageWidth, IdCardLayout.pageHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(page)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        listOf(front, back).forEachIndexed { side, card ->
            val slot = IdCardLayout.slot(side, portraitCard = card.height > card.width)
            canvas.drawBitmap(
                card,
                null,
                android.graphics.Rect(slot[0], slot[1], slot[2], slot[3]),
                paint
            )
        }
        return page
    }

    private fun luma(bitmap: Bitmap): IntArray {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        for (i in pixels.indices) {
            val p = pixels[i]
            pixels[i] = (((p shr 16) and 0xFF) * 77 + ((p shr 8) and 0xFF) * 150 + (p and 0xFF) * 29) shr 8
        }
        return pixels
    }

    private const val ENHANCE_BAND = 128
    private const val DESKEW_MEASURE_PX = 1000
    private const val DETECT_PX = 320

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
