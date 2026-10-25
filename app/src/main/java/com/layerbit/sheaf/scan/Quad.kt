package com.layerbit.sheaf.scan

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/**
 * A page outline as four corners, each normalised to 0..1 of the image it was found in.
 *
 * Normalised rather than in pixels because the same outline is used at three sizes: the small
 * analysis frame it was found in, the viewfinder it is drawn over, and the full capture it
 * becomes the crop for. Pixels would tie it to whichever of those came first.
 *
 * The order is always top left, top right, bottom right, bottom left, which is the order
 * [PageProcessor.Corners] and the perspective transform both expect.
 */
class Quad(val points: FloatArray) {
    init {
        require(points.size == 8) { "A quad has four corners" }
    }

    fun x(i: Int) = points[2 * i]
    fun y(i: Int) = points[2 * i + 1]

    /** Area as a fraction of the image, by the shoelace formula. */
    fun area(): Float = QuadGeometry.polygonArea(points, 4)

    /** The furthest any corner of this quad is from the matching corner of [other]. */
    fun maxCornerDistance(other: Quad): Float {
        var worst = 0f
        for (i in 0 until 4) {
            worst = max(worst, hypot(x(i) - other.x(i), y(i) - other.y(i)))
        }
        return worst
    }

    /** In the pixel space of an image [width] by [height]. */
    fun scaled(width: Float, height: Float): FloatArray =
        FloatArray(8) { i -> if (i % 2 == 0) points[i] * width else points[i] * height }

    override fun toString(): String = points.joinToString(prefix = "Quad(", postfix = ")") { "%.3f".format(it) }
}

/** Plain geometry on corner lists. No Android types, so all of it runs in a JVM test. */
object QuadGeometry {

    /**
     * Puts four points in top left, top right, bottom right, bottom left order.
     *
     * Sorting by angle round the centre gives the clockwise order (y points down, so a rising
     * atan2 turns clockwise on screen); the corner nearest the origin then starts the list.
     * Detection produces corners in whatever order the hull happened to visit them.
     */
    fun order(points: FloatArray): FloatArray {
        val cx = (points[0] + points[2] + points[4] + points[6]) / 4f
        val cy = (points[1] + points[3] + points[5] + points[7]) / 4f
        val indices = (0 until 4).sortedBy { atan2(points[2 * it + 1] - cy, points[2 * it] - cx) }
        val start = indices.indices.minBy { points[2 * indices[it]] + points[2 * indices[it] + 1] }
        val out = FloatArray(8)
        for (k in 0 until 4) {
            val src = indices[(start + k) % 4]
            out[2 * k] = points[2 * src]
            out[2 * k + 1] = points[2 * src + 1]
        }
        return out
    }

    /**
     * Turns a quad found in the sensor's orientation into the upright one people see.
     *
     * The camera hands frames over as the sensor reads them, usually landscape, along with how
     * far they must turn clockwise to be upright. Turning the four corners is far cheaper than
     * turning the pixels, so detection runs on the frame as it comes and only the answer turns.
     */
    fun rotateUpright(quad: Quad, rotationDegrees: Int): Quad {
        val p = quad.points
        val out = FloatArray(8)
        for (i in 0 until 4) {
            val x = p[2 * i]
            val y = p[2 * i + 1]
            val (nx, ny) = when (((rotationDegrees % 360) + 360) % 360) {
                90 -> (1f - y) to x
                180 -> (1f - x) to (1f - y)
                270 -> y to (1f - x)
                else -> x to y
            }
            out[2 * i] = nx
            out[2 * i + 1] = ny
        }
        return Quad(order(out))
    }

    /**
     * Where an upright, normalised quad lands on a view that shows its source image scaled to
     * fill and centred - PreviewView's FILL_CENTER.
     *
     * Fill means the image is scaled until it covers the view, so the longer way round spills
     * off both edges. Drawing the quad without this would put it in the right place only when
     * the view happened to have the camera's exact aspect ratio.
     */
    fun fillCenter(quad: Quad, sourceWidth: Float, sourceHeight: Float, viewWidth: Float, viewHeight: Float): FloatArray {
        val scale = max(viewWidth / sourceWidth, viewHeight / sourceHeight)
        val dx = (viewWidth - sourceWidth * scale) / 2f
        val dy = (viewHeight - sourceHeight * scale) / 2f
        return FloatArray(8) { i ->
            if (i % 2 == 0) quad.points[i] * sourceWidth * scale + dx
            else quad.points[i] * sourceHeight * scale + dy
        }
    }

    fun polygonArea(xy: FloatArray, n: Int): Float {
        var sum = 0.0
        for (i in 0 until n) {
            val j = (i + 1) % n
            sum += xy[2 * i].toDouble() * xy[2 * j + 1] - xy[2 * j].toDouble() * xy[2 * i + 1]
        }
        return abs(sum / 2.0).toFloat()
    }

    /** True when every turn round the four corners goes the same way. */
    fun isConvex(p: FloatArray): Boolean {
        var sign = 0
        for (i in 0 until 4) {
            val a = i
            val b = (i + 1) % 4
            val c = (i + 2) % 4
            val cross = (p[2 * b] - p[2 * a]) * (p[2 * c + 1] - p[2 * b + 1]) -
                (p[2 * b + 1] - p[2 * a + 1]) * (p[2 * c] - p[2 * b])
            val s = if (cross > 0f) 1 else if (cross < 0f) -1 else 0
            if (s == 0) return false
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

    /** The four interior angles in degrees, corner by corner. */
    fun interiorAngles(p: FloatArray): FloatArray = FloatArray(4) { i ->
        val prev = (i + 3) % 4
        val next = (i + 1) % 4
        val ax = p[2 * prev] - p[2 * i]
        val ay = p[2 * prev + 1] - p[2 * i + 1]
        val bx = p[2 * next] - p[2 * i]
        val by = p[2 * next + 1] - p[2 * i + 1]
        val len = sqrt((ax * ax + ay * ay).toDouble()) * sqrt((bx * bx + by * by).toDouble())
        if (len == 0.0) 0f
        else Math.toDegrees(acos(((ax * bx + ay * by) / len).coerceIn(-1.0, 1.0))).toFloat()
    }

    /**
     * Whether a crop is close enough to a rectangle that no perspective was corrected.
     *
     * That is the case where text can still sit at a slant after flattening - a default crop
     * box, or a page photographed square-on but turned on the desk - and where straightening
     * by the text lines is both useful and safe.
     */
    fun isNearlyRectangular(p: FloatArray, toleranceDegrees: Float = 4f): Boolean =
        interiorAngles(p).all { abs(it - 90f) <= toleranceDegrees }
}
