package com.layerbit.sheaf.scan

/**
 * Decides when a page has been held still long enough to photograph by itself.
 *
 * Fed one detection per viewfinder frame. The page counts as steady while every corner stays
 * within [tolerance] of where it was when the steadiness began; once that has lasted [holdMs]
 * it fires. A hand still moving into place resets the clock, which is what stops the shutter
 * going off mid-gesture.
 *
 * After firing it will not fire again until something has changed - the page left the frame
 * for a moment, moved, or what is on it changed (a page turned, the next sheet laid down) -
 * and never sooner than [cooldownMs]. Without that, holding the phone over one page would
 * photograph it every second. The state outlives the viewfinder on purpose: coming back from
 * the corner editor with the same page still under the camera must not take it again.
 *
 * Pure state and arithmetic, with time passed in, so a test can drive it frame by frame.
 * Not thread-safe; the caller serialises access.
 */
class AutoCaptureTracker(
    private val holdMs: Long = 1000L,
    private val tolerance: Float = 0.03f,
    private val minArea: Float = 0.18f,
    /** A frame or two without a detection is noise, not the page leaving. */
    private val graceMs: Long = 300L,
    /** How long the page must be gone to count as taken away. */
    private val goneMs: Long = 700L,
    private val cooldownMs: Long = 1500L,
    /** Change in the brightness pattern, in [PageDetector.signatureDistance] units, that counts as a different page. */
    private val newPageDifference: Float = 14f
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
    private var firedAt = Long.MIN_VALUE
    private var firedQuad: Quad? = null
    private var firedSignature: IntArray? = null
    private var lostSince = Long.MIN_VALUE
    private var changed = false

    fun update(quad: Quad?, signature: IntArray?, nowMs: Long): Status {
        if (!armed) {
            // The change is noted whenever it happens, but only acted on after the cooldown.
            if (!changed) changed = noticeChange(quad, signature, nowMs)
            if (!changed || nowMs - firedAt < cooldownMs) return IDLE
            armed = true
            anchor = null
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
            disarm(quad, signature, nowMs)
            return Status(1f, true)
        }
        return Status(progress, false)
    }

    /**
     * Called after a picture is taken by hand, so auto-capture does not take the same page
     * again a moment later.
     */
    fun captured(quad: Quad?, signature: IntArray?, nowMs: Long) = disarm(quad, signature, nowMs)

    private fun disarm(quad: Quad?, signature: IntArray?, nowMs: Long) {
        armed = false
        changed = false
        firedAt = nowMs
        firedQuad = quad
        firedSignature = signature?.copyOf()
        lostSince = Long.MIN_VALUE
        anchor = null
    }

    private fun noticeChange(quad: Quad?, signature: IntArray?, nowMs: Long): Boolean {
        if (quad == null) {
            if (lostSince == Long.MIN_VALUE) lostSince = nowMs
            return nowMs - lostSince > goneMs
        }
        lostSince = Long.MIN_VALUE
        val before = firedQuad ?: return true
        if (quad.maxCornerDistance(before) > tolerance * 2f) return true
        val oldSignature = firedSignature
        return signature != null && oldSignature != null &&
            PageDetector.signatureDistance(signature, oldSignature) > newPageDifference
    }

    private fun progress(nowMs: Long): Float =
        ((nowMs - anchorTime).toFloat() / holdMs).coerceIn(0f, 1f)

    private companion object {
        val IDLE = Status(0f, false)
    }
}
