package com.layerbit.sheaf.scan

/**
 * Decides when a page has been held still long enough to photograph by itself.
 *
 * Fed one detection per viewfinder frame. The page counts as steady while every corner stays
 * within [tolerance] of where it was when the steadiness began; once that has lasted [holdMs]
 * it fires. A hand still moving into place resets the clock, which is what stops the shutter
 * going off mid-gesture.
 *
 * After firing it will not fire again until something has changed - the page left the frame,
 * moved, or what is printed on it changed (a page turned, the next sheet laid down). Without
 * that, holding the phone over one page would photograph it every second.
 *
 * Pure state and arithmetic, with time passed in, so a test can drive it frame by frame.
 */
class AutoCaptureTracker(
    private val holdMs: Long = 1000L,
    private val tolerance: Float = 0.03f,
    private val minArea: Float = 0.18f,
    /** A frame or two without a detection is noise, not the page leaving. */
    private val graceMs: Long = 300L,
    /** Mean brightness change, in levels, that counts as a different page. */
    private val newPageDifference: Float = 7f
) {
    data class Status(
        /** 0 when nothing is steady, rising to 1 as the hold completes. */
        val progress: Float,
        /** True on exactly the frame that should take the picture. */
        val fire: Boolean
    )

    private var anchor: Quad? = null
    private var anchorTime = 0L
    private var lastSeen = Long.MIN_VALUE
    private var armed = true
    private var firedQuad: Quad? = null
    private var firedSignature: IntArray? = null
    private var lostSince = Long.MIN_VALUE

    fun update(quad: Quad?, signature: IntArray?, nowMs: Long): Status {
        if (!armed) {
            if (shouldRearm(quad, signature, nowMs)) {
                armed = true
                anchor = null
            } else {
                return IDLE
            }
        }

        if (quad == null || quad.area() < minArea) {
            if (lastSeen != Long.MIN_VALUE && nowMs - lastSeen <= graceMs && anchor != null) {
                return Status(progress(nowMs), false)
            }
            anchor = null
            return IDLE
        }
        lastSeen = nowMs

        val held = anchor
        if (held == null || quad.maxCornerDistance(held) > tolerance) {
            anchor = quad
            anchorTime = nowMs
            return Status(0f, false)
        }

        val progress = progress(nowMs)
        if (progress >= 1f) {
            armed = false
            firedQuad = quad
            firedSignature = signature?.copyOf()
            lostSince = Long.MIN_VALUE
            anchor = null
            return Status(1f, true)
        }
        return Status(progress, false)
    }

    /**
     * Called after a picture is taken by hand, so auto-capture does not take the same page
     * again a moment later.
     */
    fun captured(quad: Quad?, signature: IntArray?) {
        armed = false
        firedQuad = quad
        firedSignature = signature?.copyOf()
        lostSince = Long.MIN_VALUE
        anchor = null
    }

    fun reset() {
        armed = true
        anchor = null
        firedQuad = null
        firedSignature = null
    }

    private fun shouldRearm(quad: Quad?, signature: IntArray?, nowMs: Long): Boolean {
        if (quad == null) {
            if (lostSince == Long.MIN_VALUE) lostSince = nowMs
            return nowMs - lostSince > graceMs
        }
        lostSince = Long.MIN_VALUE
        val before = firedQuad ?: return true
        if (quad.maxCornerDistance(before) > tolerance * 2f) return true
        val oldSignature = firedSignature
        if (signature != null && oldSignature != null &&
            PageDetector.signatureDistance(signature, oldSignature) > newPageDifference
        ) return true
        return false
    }

    private fun progress(nowMs: Long): Float =
        ((nowMs - anchorTime).toFloat() / holdMs).coerceIn(0f, 1f)

    private companion object {
        val IDLE = Status(0f, false)
    }
}
