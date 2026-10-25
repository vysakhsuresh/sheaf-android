package com.layerbit.sheaf.scan

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Finds the outline of a page in a small greyscale image.
 *
 * Pure Kotlin on int buffers, with no OpenCV and no Android types: it runs on every
 * viewfinder frame, it runs once more on the still capture, and it runs in a JVM test.
 *
 * The approach, in order:
 *  1. Blur, then a Sobel gradient. The blur stops paper grain and sensor noise reading as
 *     edges; the gradient is where brightness changes, which is where a page meets a desk.
 *  2. Keep the strongest gradients, with hysteresis: strong pixels seed an edge, weaker ones
 *     next to them extend it. That keeps a page edge in one piece where the contrast dips.
 *     The threshold adapts to each frame, so a dim kitchen and a bright office both work.
 *  3. Thicken the edges by a pixel to close small gaps, then look at connected regions two
 *     ways - the edge pixels themselves (the outline of the page), and the regions the edges
 *     enclose that do not reach the frame's border (the paper inside the outline). The first
 *     is right when the page is a clean outline; the second when clutter touches the page from
 *     outside. Each gives a convex hull, cut down to the four-sided shape that fits it best.
 *  4. Score each four-sided shape by how much of its perimeter actually lies on an edge, so a
 *     shape that only looks like a page because the hull is convex loses to one that is
 *     traced on all four sides. The winner is refined by fitting a line to the strongest
 *     gradient along each side, and its corners are where those lines meet.
 *
 * A frame where nothing scores well returns null rather than a guess. The caller falls back
 * to the inset rectangle, which the person adjusts.
 *
 * One instance reuses its buffers between calls, which is the point of it being a class: the
 * viewfinder calls this thirty times a second and must not allocate a frame's worth each time.
 * Not thread-safe; give each thread its own.
 */
class PageDetector {

    private var w = 0
    private var h = 0
    private var tmp = IntArray(0)
    private var blur = IntArray(0)
    private var mag = IntArray(0)
    private var edge = ByteArray(0)
    private var closed = ByteArray(0)
    private var label = IntArray(0)
    private var queue = IntArray(0)
    private var rowMin = IntArray(0)
    private var rowMax = IntArray(0)
    private var hullIn = FloatArray(0)
    private val histogram = IntArray(MAG_BINS)

    private var lowThreshold = 0
    private var highThreshold = 0

    /**
     * @param luma brightness 0..255, row-major, [width] by [height], at most a few hundred
     *   pixels on the long side. Larger works but costs time for no gain in accuracy.
     * @return the page outline normalised to 0..1 of the image, or null when no page-shaped
     *   outline was found.
     */
    fun detect(luma: IntArray, width: Int, height: Int): Quad? {
        if (width < 16 || height < 16) return null
        ensure(width, height)
        gaussian(luma)
        sobel()
        chooseThresholds()
        hysteresis(highThreshold, lowThreshold)
        dilate()

        val best = findBest() ?: return null
        val refined = refine(best) ?: best
        val out = FloatArray(8)
        for (i in 0 until 4) {
            // Pixel indices name pixel centres, which sit half a pixel in from the pixel's edge.
            out[2 * i] = ((refined[2 * i] + 0.5f) / w).coerceIn(0f, 1f)
            out[2 * i + 1] = ((refined[2 * i + 1] + 0.5f) / h).coerceIn(0f, 1f)
        }
        return Quad(QuadGeometry.order(out))
    }

    private fun ensure(width: Int, height: Int) {
        if (width == w && height == h) return
        w = width
        h = height
        val n = w * h
        tmp = IntArray(n)
        blur = IntArray(n)
        mag = IntArray(n)
        edge = ByteArray(n)
        closed = ByteArray(n)
        label = IntArray(n)
        queue = IntArray(n)
        rowMin = IntArray(h)
        rowMax = IntArray(h)
        hullIn = FloatArray(4 * h + 8)
    }

    /** A 5-tap binomial blur, separable, with the edge pixels repeated outward. */
    private fun gaussian(src: IntArray) {
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val a = src[row + max(x - 2, 0)]
                val b = src[row + max(x - 1, 0)]
                val c = src[row + x]
                val d = src[row + min(x + 1, w - 1)]
                val e = src[row + min(x + 2, w - 1)]
                tmp[row + x] = a + 4 * b + 6 * c + 4 * d + e
            }
        }
        for (y in 0 until h) {
            val r0 = max(y - 2, 0) * w
            val r1 = max(y - 1, 0) * w
            val r2 = y * w
            val r3 = min(y + 1, h - 1) * w
            val r4 = min(y + 2, h - 1) * w
            for (x in 0 until w) {
                blur[r2 + x] = (tmp[r0 + x] + 4 * tmp[r1 + x] + 6 * tmp[r2 + x] + 4 * tmp[r3 + x] + tmp[r4 + x]) shr 8
            }
        }
    }

    /** |gx| + |gy| of the 3x3 Sobel operator. A step of contrast C reads as about 4C. */
    private fun sobel() {
        java.util.Arrays.fill(mag, 0)
        for (y in 1 until h - 1) {
            var i = y * w + 1
            for (x in 1 until w - 1) {
                val tl = blur[i - w - 1]
                val t = blur[i - w]
                val tr = blur[i - w + 1]
                val l = blur[i - 1]
                val r = blur[i + 1]
                val bl = blur[i + w - 1]
                val b = blur[i + w]
                val br = blur[i + w + 1]
                val gx = (tr + 2 * r + br) - (tl + 2 * l + bl)
                val gy = (bl + 2 * b + br) - (tl + 2 * t + tr)
                mag[i] = abs(gx) + abs(gy)
                i++
            }
        }
    }

    /**
     * Thresholds that follow the frame rather than fixed numbers.
     *
     * A fixed threshold is wrong somewhere: too high in dim light, where a page edge is a soft
     * step, and too low on a grainy sensor or a textured desk. Most of a frame is not an edge,
     * so the median gradient is a fair measure of the frame's noise; the weak threshold sits a
     * few times above it, which is what stops noise joining up into one frame-wide "edge".
     * The strong threshold is whichever is higher of twice that and the gradient only the
     * strongest few percent of pixels reach.
     */
    private fun chooseThresholds() {
        java.util.Arrays.fill(histogram, 0)
        val n = w * h
        for (i in 0 until n) histogram[min(mag[i], MAG_BINS - 1)]++

        var count = 0
        var median = 0
        while (median < MAG_BINS - 1) {
            count += histogram[median]
            if (count >= n / 2) break
            median++
        }

        val target = (n * EDGE_FRACTION).toInt()
        count = 0
        var level = MAG_BINS - 1
        while (level > 0) {
            count += histogram[level]
            if (count >= target) break
            level--
        }

        lowThreshold = max(MIN_LOW, median * NOISE_MULTIPLE)
        highThreshold = max(level, lowThreshold * 2)
    }

    private fun hysteresis(high: Int, low: Int) {
        java.util.Arrays.fill(edge, 0)
        for (start in 0 until w * h) {
            if (edge[start].toInt() != 0 || mag[start] < high) continue
            edge[start] = 1
            var tail = 0
            queue[tail++] = start
            while (tail > 0) {
                val i = queue[--tail]
                val y = i / w
                val x = i - y * w
                for (dy in -1..1) {
                    val ny = y + dy
                    if (ny < 0 || ny >= h) continue
                    for (dx in -1..1) {
                        val nx = x + dx
                        if (nx < 0 || nx >= w) continue
                        val j = ny * w + nx
                        if (edge[j].toInt() == 0 && mag[j] >= low) {
                            edge[j] = 1
                            queue[tail++] = j
                        }
                    }
                }
            }
        }
    }

    /** A 3x3 dilation, so an edge broken by a pixel or two of low contrast joins up again. */
    private fun dilate() {
        java.util.Arrays.fill(closed, 0)
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (edge[y * w + x].toInt() == 0) continue
                for (ny in max(y - 1, 0)..min(y + 1, h - 1)) {
                    val row = ny * w
                    for (nx in max(x - 1, 0)..min(x + 1, w - 1)) closed[row + nx] = 1
                }
            }
        }
    }

    /** Labels both kinds of region, turns each plausible one into a quad, keeps the best. */
    private fun findBest(): FloatArray? {
        java.util.Arrays.fill(label, 0)
        val n = w * h
        val minSpanX = (w * MIN_SPAN).toInt()
        val minSpanY = (h * MIN_SPAN).toInt()
        var nextLabel = 1
        var best: FloatArray? = null
        var bestScore = 0f

        for (start in 0 until n) {
            if (label[start] != 0) continue
            val isEdge = closed[start].toInt() != 0
            val id = nextLabel++
            label[start] = id
            var head = 0
            var tail = 0
            queue[tail++] = start
            var minX = w
            var maxX = -1
            var minY = h
            var maxY = -1
            var touchesBorder = false
            while (head < tail) {
                val i = queue[head++]
                val y = i / w
                val x = i - y * w
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                if (x == 0 || y == 0 || x == w - 1 || y == h - 1) touchesBorder = true
                // The outline is traced 8-connected, so a diagonal edge stays one piece; the
                // paper inside is filled 4-connected, so it cannot leak through a diagonal gap.
                for (k in 0 until if (isEdge) 8 else 4) {
                    val nx = x + NX[k]
                    val ny = y + NY[k]
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val j = ny * w + nx
                    if (label[j] == 0 && (closed[j].toInt() != 0) == isEdge) {
                        label[j] = id
                        queue[tail++] = j
                    }
                }
            }

            if (maxX - minX < minSpanX || maxY - minY < minSpanY) continue
            // The region round the outside of the page touches the frame's border, and its
            // hull is the whole frame. Only enclosed regions can be the paper.
            if (!isEdge && (touchesBorder || tail < n * MIN_AREA)) continue

            val quad = quadFromPixels(tail, minY, maxY) ?: continue
            val score = score(quad)
            if (score > bestScore) {
                bestScore = score
                best = quad
            }
        }
        return best
    }

    /** The convex hull of the region in queue[0, count), reduced to four sides. */
    private fun quadFromPixels(count: Int, minY: Int, maxY: Int): FloatArray? {
        for (y in minY..maxY) {
            rowMin[y] = Int.MAX_VALUE
            rowMax[y] = Int.MIN_VALUE
        }
        for (k in 0 until count) {
            val i = queue[k]
            val y = i / w
            val x = i - y * w
            if (x < rowMin[y]) rowMin[y] = x
            if (x > rowMax[y]) rowMax[y] = x
        }
        // Only each row's two ends can be on the hull, which cuts tens of thousands of pixels
        // to a few hundred points before the hull is taken.
        var m = 0
        for (y in minY..maxY) {
            if (rowMin[y] == Int.MAX_VALUE) continue
            hullIn[2 * m] = rowMin[y].toFloat(); hullIn[2 * m + 1] = y.toFloat(); m++
            if (rowMax[y] != rowMin[y]) {
                hullIn[2 * m] = rowMax[y].toFloat(); hullIn[2 * m + 1] = y.toFloat(); m++
            }
        }
        val hull = convexHull(hullIn, m)
        if (hull.size < 8) return null
        val hullArea = QuadGeometry.polygonArea(hull, hull.size / 2)
        val quad = reduceToQuad(hull) ?: return null

        if (!QuadGeometry.isConvex(quad)) return null
        val area = QuadGeometry.polygonArea(quad, 4)
        if (area < w * h * MIN_AREA) return null
        // A hull far from four-sided - a blob, a circle, a page with a hand across it - is
        // not a page outline however it is cut down.
        if (hullArea / area < 0.85f || hullArea / area > 1.15f) return null
        if (QuadGeometry.interiorAngles(quad).any { it < MIN_ANGLE || it > 180f - MIN_ANGLE }) return null
        val slack = max(w, h) * 0.05f
        for (i in 0 until 4) {
            if (quad[2 * i] < -slack || quad[2 * i] > w - 1 + slack) return null
            if (quad[2 * i + 1] < -slack || quad[2 * i + 1] > h - 1 + slack) return null
        }
        // A side running along the frame's own border is the frame, not a page edge: it is
        // what noise or a busy desk that fills the picture traces out.
        val band = 3f
        for (i in 0 until 4) {
            val ax = quad[2 * i]
            val ay = quad[2 * i + 1]
            val bx = quad[2 * ((i + 1) % 4)]
            val by = quad[2 * ((i + 1) % 4) + 1]
            if ((ax <= band && bx <= band) || (ax >= w - 1 - band && bx >= w - 1 - band) ||
                (ay <= band && by <= band) || (ay >= h - 1 - band && by >= h - 1 - band)
            ) return null
        }
        return quad
    }

    /**
     * Area, weighted by how well the perimeter is traced by real edges.
     *
     * Every side has to be mostly on an edge, not just the total: three strong sides and a
     * fourth invented by the hull is how a page half off the frame would otherwise win.
     */
    private fun score(q: FloatArray): Float {
        var total = 0
        var hits = 0
        var weakest = 1f
        for (s in 0 until 4) {
            val ax = q[2 * s]
            val ay = q[2 * s + 1]
            val bx = q[2 * ((s + 1) % 4)]
            val by = q[2 * ((s + 1) % 4) + 1]
            val len = hypot(bx - ax, by - ay)
            val samples = max(8, (len / 2f).toInt())
            var sideHits = 0
            for (k in 0 until samples) {
                val t = 0.05f + 0.9f * k / (samples - 1)
                val x = (ax + (bx - ax) * t).roundToInt()
                val y = (ay + (by - ay) * t).roundToInt()
                if (edgeNear(x, y, 2)) sideHits++
            }
            total += samples
            hits += sideHits
            weakest = min(weakest, sideHits.toFloat() / samples)
        }
        val support = hits.toFloat() / total
        if (weakest < MIN_SIDE_SUPPORT || support < MIN_SUPPORT) return 0f
        return QuadGeometry.polygonArea(q, 4) * support * support
    }

    private fun edgeNear(x: Int, y: Int, radius: Int): Boolean {
        for (ny in max(y - radius, 0)..min(y + radius, h - 1)) {
            val row = ny * w
            for (nx in max(x - radius, 0)..min(x + radius, w - 1)) {
                if (closed[row + nx].toInt() != 0) return true
            }
        }
        return false
    }

    /**
     * Moves each side onto the strongest gradient near it, then re-finds the corners.
     *
     * The hull sits on the outside of a thickened edge band, or the inside of the paper region,
     * so it is a pixel or two off either way. A line fitted through the gradient peaks along a
     * side is where the page's edge really is, and the corner is where two such lines cross -
     * which also puts a corner in the right place when the corner itself is dog-eared or
     * hidden under a thumb.
     */
    private fun refine(q: FloatArray): FloatArray? {
        val lines = Array(4) { FloatArray(4) } // point x, point y, direction x, direction y
        val xs = FloatArray(512)
        val ys = FloatArray(512)
        for (s in 0 until 4) {
            val ax = q[2 * s]
            val ay = q[2 * s + 1]
            val bx = q[2 * ((s + 1) % 4)]
            val by = q[2 * ((s + 1) % 4) + 1]
            val len = hypot(bx - ax, by - ay)
            if (len < 4f) return null
            val dx = (bx - ax) / len
            val dy = (by - ay) / len
            val nx = -dy
            val ny = dx
            val samples = min(xs.size, max(10, (len / 2f).toInt()))
            var found = 0
            for (k in 0 until samples) {
                val t = 0.1f + 0.8f * k / (samples - 1)
                val sx = ax + (bx - ax) * t
                val sy = ay + (by - ay) * t
                var bestMag = 0
                var bestD = 0f
                var d = -REFINE_REACH
                while (d <= REFINE_REACH) {
                    val px = (sx + nx * d).roundToInt()
                    val py = (sy + ny * d).roundToInt()
                    if (px in 1 until w - 1 && py in 1 until h - 1) {
                        val m = mag[py * w + px]
                        if (m > bestMag) { bestMag = m; bestD = d }
                    }
                    d += 1f
                }
                if (bestMag >= lowThreshold) {
                    xs[found] = sx + nx * bestD
                    ys[found] = sy + ny * bestD
                    found++
                }
            }
            val line = lines[s]
            if (found >= samples / 3 && found >= 5) {
                fitLine(xs, ys, found, line)
            } else {
                line[0] = ax; line[1] = ay; line[2] = dx; line[3] = dy
            }
        }
        val out = FloatArray(8)
        for (i in 0 until 4) {
            // Corner i is where the side ending at it meets the side starting from it.
            val a = lines[(i + 3) % 4]
            val b = lines[i]
            val cross = a[2] * b[3] - a[3] * b[2]
            if (abs(cross) < 1e-3f) return null
            val t = ((b[0] - a[0]) * b[3] - (b[1] - a[1]) * b[2]) / cross
            out[2 * i] = a[0] + a[2] * t
            out[2 * i + 1] = a[1] + a[3] * t
            if (hypot(out[2 * i] - q[2 * i], out[2 * i + 1] - q[2 * i + 1]) > max(w, h) * 0.03f + 2f) return null
        }
        return if (QuadGeometry.isConvex(out)) out else null
    }

    companion object {
        private const val MAG_BINS = 1024
        /** About the share of a frame a page outline plus its text takes up. */
        private const val EDGE_FRACTION = 0.06f
        /** A brightness step of about 5 levels, after the blur: the least that is ever an edge. */
        private const val MIN_LOW = 20
        private const val NOISE_MULTIPLE = 3
        /** Each side of a page spans at least this share of the frame. */
        private const val MIN_SPAN = 0.25f
        private const val MIN_AREA = 0.10f
        private const val MIN_ANGLE = 35f
        private const val MIN_SIDE_SUPPORT = 0.55f
        private const val MIN_SUPPORT = 0.72f
        private const val REFINE_REACH = 4f

        private val NX = intArrayOf(1, -1, 0, 0, 1, 1, -1, -1)
        private val NY = intArrayOf(0, 0, 1, -1, 1, -1, 1, -1)

        /**
         * Andrew's monotone chain over points already sorted by y then x.
         *
         * The detector produces them row by row, so they arrive sorted; the chain works in any
         * consistent lexicographic order, not only the usual x-then-y one.
         */
        internal fun convexHull(xy: FloatArray, n: Int): FloatArray {
            if (n < 3) return FloatArray(0)
            val hull = FloatArray(4 * n + 4)
            var k = 0
            fun cross(ox: Float, oy: Float, ax: Float, ay: Float, bx: Float, by: Float) =
                (ax - ox) * (by - oy) - (ay - oy) * (bx - ox)
            for (i in 0 until n) {
                while (k >= 2 && cross(hull[2 * k - 4], hull[2 * k - 3], hull[2 * k - 2], hull[2 * k - 1], xy[2 * i], xy[2 * i + 1]) <= 0f) k--
                hull[2 * k] = xy[2 * i]; hull[2 * k + 1] = xy[2 * i + 1]; k++
            }
            val lower = k + 1
            for (i in n - 2 downTo 0) {
                while (k >= lower && cross(hull[2 * k - 4], hull[2 * k - 3], hull[2 * k - 2], hull[2 * k - 1], xy[2 * i], xy[2 * i + 1]) <= 0f) k--
                hull[2 * k] = xy[2 * i]; hull[2 * k + 1] = xy[2 * i + 1]; k++
            }
            return hull.copyOf(2 * (k - 1))
        }

        /**
         * Cuts a convex polygon down to four corners, one step at a time, each step the one
         * that changes the area least.
         *
         * Two kinds of step. Dropping a corner shaves a sliver off, which is right for the
         * many near-straight hull points along a side. Dropping a side and extending its two
         * neighbours until they meet adds a sliver, which is right at a page's corner: a
         * rounded or folded corner makes the hull cut across it, and extending the sides puts
         * the corner back where the paper's edges say it is.
         */
        internal fun reduceToQuad(hull: FloatArray): FloatArray? {
            var n = hull.size / 2
            if (n < 4) return null
            val px = FloatArray(n) { hull[2 * it] }
            val py = FloatArray(n) { hull[2 * it + 1] }
            while (n > 4) {
                var bestCost = Float.MAX_VALUE
                var bestIndex = -1
                var bestIsEdge = false
                var bestX = 0f
                var bestY = 0f
                for (i in 0 until n) {
                    val prev = (i + n - 1) % n
                    val next = (i + 1) % n
                    val after = (i + 2) % n
                    val drop = triangleArea(px[prev], py[prev], px[i], py[i], px[next], py[next])
                    if (drop < bestCost) {
                        bestCost = drop; bestIndex = i; bestIsEdge = false
                    }
                    // Extend prev->i and after->next until they meet beyond the side i..next.
                    val d1x = px[i] - px[prev]
                    val d1y = py[i] - py[prev]
                    val d2x = px[next] - px[after]
                    val d2y = py[next] - py[after]
                    val denom = d1x * d2y - d1y * d2x
                    if (abs(denom) > 1e-6f) {
                        val t = ((px[after] - px[prev]) * d2y - (py[after] - py[prev]) * d2x) / denom
                        val u = ((px[after] - px[prev]) * d1y - (py[after] - py[prev]) * d1x) / denom
                        if (t > 1f && u > 1f) {
                            val ix = px[prev] + d1x * t
                            val iy = py[prev] + d1y * t
                            val add = triangleArea(px[i], py[i], ix, iy, px[next], py[next])
                            if (add < bestCost) {
                                bestCost = add; bestIndex = i; bestIsEdge = true; bestX = ix; bestY = iy
                            }
                        }
                    }
                }
                if (bestIndex < 0) return null
                if (bestIsEdge) {
                    // Corner i moves to the meeting point and corner next goes.
                    px[bestIndex] = bestX
                    py[bestIndex] = bestY
                    val remove = (bestIndex + 1) % n
                    removeAt(px, py, remove, n)
                } else {
                    removeAt(px, py, bestIndex, n)
                }
                n--
            }
            return floatArrayOf(px[0], py[0], px[1], py[1], px[2], py[2], px[3], py[3])
        }

        private fun removeAt(px: FloatArray, py: FloatArray, index: Int, n: Int) {
            System.arraycopy(px, index + 1, px, index, n - index - 1)
            System.arraycopy(py, index + 1, py, index, n - index - 1)
        }

        private fun triangleArea(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float) =
            abs((bx - ax) * (cy - ay) - (by - ay) * (cx - ax)) / 2f

        /** Total least squares: the line through the points' centre along their main spread. */
        private fun fitLine(xs: FloatArray, ys: FloatArray, n: Int, out: FloatArray) {
            var mx = 0.0
            var my = 0.0
            for (i in 0 until n) { mx += xs[i]; my += ys[i] }
            mx /= n; my /= n
            var sxx = 0.0
            var syy = 0.0
            var sxy = 0.0
            for (i in 0 until n) {
                val dx = xs[i] - mx
                val dy = ys[i] - my
                sxx += dx * dx; syy += dy * dy; sxy += dx * dy
            }
            val angle = 0.5 * kotlin.math.atan2(2 * sxy, sxx - syy)
            out[0] = mx.toFloat()
            out[1] = my.toFloat()
            out[2] = kotlin.math.cos(angle).toFloat()
            out[3] = kotlin.math.sin(angle).toFloat()
        }

        /**
         * An 8 by 8 grid of average brightness, cheap enough to take every frame.
         *
         * Auto-capture compares it with the one taken at the last capture: turning a page or
         * swapping a sheet changes it even when the new page lands exactly where the old one
         * was, so the scanner knows there is something new to take.
         */
        fun signature(luma: IntArray, width: Int, height: Int, out: IntArray) {
            java.util.Arrays.fill(out, 0)
            val counts = IntArray(64)
            for (y in 0 until height) {
                val gy = y * 8 / height
                for (x in 0 until width) {
                    val cell = gy * 8 + x * 8 / width
                    out[cell] += luma[y * width + x]
                    counts[cell]++
                }
            }
            for (i in 0 until 64) if (counts[i] > 0) out[i] /= counts[i]
        }

        /** Mean absolute difference between two signatures, in brightness levels. */
        fun signatureDistance(a: IntArray, b: IntArray): Float {
            var sum = 0
            for (i in 0 until 64) sum += abs(a[i] - b[i])
            return sum / 64f
        }
    }
}

/**
 * Shrinks a camera frame's brightness plane to the size the detector wants, by averaging.
 *
 * Takes the bytes of the Y plane as the camera lays them out - each row [rowStride] bytes
 * long, which is often wider than the image - and only reads the part inside the crop
 * rectangle, which is the part the viewfinder shows. Writes into a buffer it keeps, so a
 * frame costs no allocation.
 */
class LumaSampler(private val maxSide: Int = 320) {
    var width = 0
        private set
    var height = 0
        private set
    var pixels = IntArray(0)
        private set

    fun sample(
        plane: ByteArray,
        rowStride: Int,
        pixelStride: Int,
        left: Int,
        top: Int,
        cropWidth: Int,
        cropHeight: Int
    ) {
        val factor = max(1, (max(cropWidth, cropHeight) + maxSide - 1) / maxSide)
        width = max(1, cropWidth / factor)
        height = max(1, cropHeight / factor)
        if (pixels.size < width * height) pixels = IntArray(width * height)
        val area = factor * factor
        for (y in 0 until height) {
            for (x in 0 until width) {
                var sum = 0
                val sx = left + x * factor
                for (dy in 0 until factor) {
                    var index = (top + y * factor + dy) * rowStride + sx * pixelStride
                    for (dx in 0 until factor) {
                        sum += plane[index].toInt() and 0xFF
                        index += pixelStride
                    }
                }
                pixels[y * width + x] = sum / area
            }
        }
    }
}
