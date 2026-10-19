package com.layerbit.sheaf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.layerbit.sheaf.ops.OpOutcome
import com.layerbit.sheaf.ops.PhotoPixels
import com.layerbit.sheaf.ops.ResizePhotoOp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
class ResizePhotoTest {

    /** A camera-sized picture of noise: the hardest thing there is to make small. */
    private fun photo(w: Int, h: Int, orientation: Int = 1, gps: Boolean = false): File {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val rnd = Random(7)
        val px = IntArray(w)
        for (y in 0 until h) {
            for (x in 0 until w) px[x] = Color.rgb((x * 255 / w + rnd.nextInt(60)) and 0xFF, (y * 255 / h) and 0xFF, rnd.nextInt(256))
            bmp.setPixels(px, 0, w, 0, y, w, 1)
        }
        val f = File(Harness.dir, "photo-$w-$h-$orientation.jpg")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bmp.recycle()
        val exif = ExifInterface(f.path)
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
        if (gps) exif.setLatLong(9.93, 76.26)
        exif.saveAttributes()
        return f
    }

    private fun produced(outcomes: List<OpOutcome>): OpOutcome.Produced {
        assertEquals(1, outcomes.size)
        val o = outcomes.single()
        assertTrue("not produced: $o", o is OpOutcome.Produced)
        return o as OpOutcome.Produced
    }

    private fun size(f: File) = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(f.path, it) }.let { it.outWidth to it.outHeight }

    @Test
    fun bigPhotoUnder50kbTurnedUpright() {
        val src = photo(4000, 3000, orientation = 6, gps = true)
        val out = produced(Harness.run(ResizePhotoOp(50f), Harness.image(src)))
        assertTrue("size ${out.file.sizeBytes}", out.file.sizeBytes < 50_000)
        val (w, h) = size(out.file.file)
        assertTrue("upright $w x $h", h > w)
        assertTrue(out.detail!!, out.detail!!.contains("turned upright") && out.detail!!.contains("location"))
        assertEquals("photo-4000-3000-6 (under 50 KB).jpg", out.file.displayName)
        assertTrue("no gps left", ExifInterface(out.file.file.path).latLong == null)
    }

    @Test
    fun signatureUnder20kbStaysWhiteBehind() {
        val bmp = Bitmap.createBitmap(1200, 400, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 4f; style = Paint.Style.STROKE; color = Color.BLACK }
        for (i in 0 until 30) c.drawLine(50f + i * 35, 300f, 80f + i * 35, 80f + (i % 5) * 30, p)
        val f = File(Harness.dir, "sign.png"); f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val out = produced(Harness.run(ResizePhotoOp(20f), Harness.image(f, "image/png")))
        assertTrue(out.file.sizeBytes < 20_000)
        val back = BitmapFactory.decodeFile(out.file.file.path)
        assertTrue("white corner", Color.red(back.getPixel(2, 2)) > 240)
        assertTrue(out.detail!!, out.detail!!.contains("now a JPEG"))
    }

    @Test
    fun passportShape() {
        val out = produced(Harness.run(ResizePhotoOp(50f, PhotoPixels.Passport), Harness.image(photo(1200, 1600))))
        assertEquals(413 to 531, size(out.file.file))
        assertTrue(out.file.sizeBytes < 50_000)
    }

    @Test
    fun exactPixelsNeverShrunk() {
        val out = produced(Harness.run(ResizePhotoOp(3f, PhotoPixels.Exact(800, 800)), Harness.image(photo(1600, 1200))))
        assertEquals(800 to 800, size(out.file.file))
        assertTrue(out.detail!!, out.detail!!.contains("too small for 800 × 800"))
    }

    @Test
    fun smallCleanJpegIsLeftAlone() {
        val bmp = Bitmap.createBitmap(200, 150, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val f = File(Harness.dir, "small.jpg"); f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        val o = Harness.run(ResizePhotoOp(50f), Harness.image(f)).single()
        assertTrue("$o", o is OpOutcome.Skipped && o.reason.contains("Nothing to change"))
    }

    @Test
    fun refusesTinyLimitAndNonImages() {
        val f = File(Harness.dir, "not.jpg").apply { writeText("hello") }
        assertTrue(Harness.run(ResizePhotoOp(1f), Harness.image(f)).single() is OpOutcome.Failed)
        val o = Harness.run(ResizePhotoOp(50f), Harness.image(f)).single()
        assertTrue("$o", o is OpOutcome.Failed && (o as OpOutcome.Failed).reason.contains("isn't an image"))
    }
}
