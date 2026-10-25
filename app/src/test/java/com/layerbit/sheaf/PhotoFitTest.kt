package com.layerbit.sheaf

import com.layerbit.sheaf.ops.PhotoFit
import com.layerbit.sheaf.ops.PhotoPixels
import com.layerbit.sheaf.text.Strings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhotoFitTest {
    @Test fun sizesNeverRoundUp() {
        assertEquals("49 KB", PhotoFit.sizeText(49_999))
        assertEquals("4.5 KB", PhotoFit.sizeText(4_560))
        assertEquals("3.2 MB", PhotoFit.sizeText(3_249_000))
        assertEquals("0.1 KB", PhotoFit.sizeText(12))
    }

    @Test fun limits() {
        assertEquals("50 KB", PhotoFit.limitText(50f))
        assertEquals("19.5 KB", PhotoFit.limitText(19.5f))
        assertEquals(Strings.get(R.string.engine_photo_min_size), PhotoFit.problem(1f, PhotoPixels.Keep))
        assertNull(PhotoFit.problem(2f, PhotoPixels.Exact(16, 4096)))
    }

    @Test fun passportIsCutFromTheMiddle() {
        val p = PhotoFit.plan(3000, 4000, PhotoPixels.Passport)
        assertEquals(413, p.outW); assertEquals(531, p.outH)
        assertEquals(3000, p.cropW)
        assert(kotlin.math.abs((3000 * 531.0 / 413).toInt() - p.cropH) <= 1)
        assertEquals((4000 - p.cropH) / 2, p.cropY)
    }

    @Test fun smallPassportIsNotEnlarged() {
        val p = PhotoFit.plan(300, 400, PhotoPixels.Passport)
        assertEquals(p.cropW, p.outW)
    }

    @Test fun keepCapsPixels() {
        val p = PhotoFit.plan(8000, 6000, PhotoPixels.Keep)
        assert(p.capped && p.outW.toLong() * p.outH <= PhotoFit.MAX_PIXELS)
    }

    @Test fun stem() {
        assertEquals("Market", PhotoFit.stem("Market.jpeg"))
        assertEquals("Image", PhotoFit.stem(".png"))
    }

    @Test fun jpegStripKeepsScanAndIcc() {
        // SOI, APP1(Exif), APP2(ICC), SOF0 (3 comps), SOS + data, EOI
        val app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), 0, 8) + "Exif\u0000\u0000".toByteArray()
        val icc = byteArrayOf(0xFF.toByte(), 0xE2.toByte(), 0, 14) + "ICC_PROFILE\u0000".toByteArray()
        val sof = byteArrayOf(0xFF.toByte(), 0xC0.toByte(), 0, 11, 8, 0, 1, 0, 1, 3, 1, 0x11, 0)
        val sos = byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0, 2, 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())
        val soi = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
        val stripped = PhotoFit.stripJpeg(soi + app1 + icc + sof + sos)!!
        assertEquals((soi + icc + sof + sos).toList(), stripped.toList())
    }
}
