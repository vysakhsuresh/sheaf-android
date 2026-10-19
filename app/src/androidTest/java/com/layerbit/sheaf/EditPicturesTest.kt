package com.layerbit.sheaf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.layerbit.sheaf.ops.EditPicturesOp
import com.layerbit.sheaf.ops.OpOutcome
import com.layerbit.sheaf.pdf.PageArea
import com.layerbit.sheaf.pdf.PdfInput
import com.layerbit.sheaf.pdf.PictureEdit
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class EditPicturesTest {

    @Before fun setUp() = PDFBoxResourceLoader.init(Harness.app)

    private fun solid(colour: Int) = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888).apply { eraseColor(colour) }

    /** A4 with red at (100,600)-(300,750) and blue at (350,100)-(500,250), in points, and a line of text. */
    private fun twoPictures(rotation: Int = 0): File {
        val f = File(Harness.dir, "pics-$rotation.pdf")
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4).also { it.rotation = rotation }
            doc.addPage(page)
            PDPageContentStream(doc, page).use { s ->
                s.drawImage(LosslessFactory.createFromImage(doc, solid(Color.RED)), 100f, 600f, 200f, 150f)
                s.saveGraphicsState()
                s.transform(com.tom_roush.pdfbox.util.Matrix(1f, 0f, 0f, 1f, 350f, 100f))
                s.drawImage(LosslessFactory.createFromImage(doc, solid(Color.BLUE)), 0f, 0f, 150f, 150f)
                s.restoreGraphicsState()
                s.beginText(); s.setFont(PDType1Font.HELVETICA, 12f); s.newLineAtOffset(100f, 400f); s.showText("Stays as text"); s.endText()
            }
            doc.save(f)
        }
        return f
    }

    private fun near(a: PageArea, left: Float, top: Float, w: Float, h: Float) =
        abs(a.left - left) < 0.01f && abs(a.top - top) < 0.01f && abs(a.width - w) < 0.01f && abs(a.height - h) < 0.01f

    @Test
    fun findsBothPicturesWhereTheyShow() {
        val found = Harness.app.surgeon.findPictures(PdfInput(twoPictures()), 0)
        assertEquals(2, found.size)
        assertTrue("red $found", found.any { near(it, 100 / 595.28f, (841.89f - 750) / 841.89f, 200 / 595.28f, 150 / 841.89f) })
        assertTrue("blue $found", found.any { near(it, 350 / 595.28f, (841.89f - 250) / 841.89f, 150 / 595.28f, 150 / 841.89f) })
    }

    @Test
    fun moveRemoveAndSwap() {
        val src = twoPictures()
        val found = Harness.app.surgeon.findPictures(PdfInput(src), 0)
        val red = found.first { it.top < 0.5f }
        val blue = found.first { it.top > 0.5f }
        // Red to the bottom-left corner, blue out.
        val to = PageArea(0.05f, 0.75f, red.width, red.height)
        val out = Harness.run(
            EditPicturesOp(mapOf(0 to listOf(PictureEdit(red, PictureEdit.Action.Move(to)), PictureEdit(blue, PictureEdit.Action.Remove)))),
            Harness.pdf(src)
        ).single() as OpOutcome.Produced
        assertEquals("1 picture taken out, 1 picture moved", out.detail)
        val bmp = render(out.file.file)
        assertTrue("red at its new place", isRed(bmp.at(0.05f + red.width / 2, 0.75f + red.height / 2)))
        assertTrue("old place empty", isWhite(bmp.at(red.left + red.width / 2, red.top + red.height / 2)))
        assertTrue("blue gone", isWhite(bmp.at(blue.left + blue.width / 2, blue.top + blue.height / 2)))
        assertTrue("text untouched", PDDocument.load(out.file.file).use { PDFTextStripper().getText(it) }.contains("Stays as text"))
    }

    @Test
    fun swapFitsInsideTheOldBox() {
        val src = twoPictures()
        val red = Harness.app.surgeon.findPictures(PdfInput(src), 0).first { it.top < 0.5f }
        val green = File(Harness.dir, "green.png").apply { outputStream().use { solid(Color.GREEN).compress(Bitmap.CompressFormat.PNG, 100, it) } }
        val out = Harness.run(EditPicturesOp(mapOf(0 to listOf(PictureEdit(red, PictureEdit.Action.Replace(green))))), Harness.pdf(src)).single() as OpOutcome.Produced
        val bmp = render(out.file.file)
        assertTrue("green in the box", isGreen(bmp.at(red.left + red.width / 2, red.top + red.height / 2)))
    }

    @Test
    fun sidewaysPageMovesTheRightWay() {
        val src = twoPictures(rotation = 90)
        val found = Harness.app.surgeon.findPictures(PdfInput(src), 0)
        assertEquals(2, found.size)
        val pic = found.first()
        val to = PageArea(0.02f, 0.02f, pic.width, pic.height)
        val out = Harness.run(EditPicturesOp(mapOf(0 to listOf(PictureEdit(pic, PictureEdit.Action.Move(to))))), Harness.pdf(src)).single() as OpOutcome.Produced
        val again = Harness.app.surgeon.findPictures(PdfInput(out.file.file), 0)
        assertTrue("landed at $again", again.any { near(it, 0.02f, 0.02f, pic.width, pic.height) })
    }

    @Test
    fun unmatchedEditSaysSo() {
        val src = twoPictures()
        val o = Harness.run(EditPicturesOp(mapOf(0 to listOf(PictureEdit(PageArea(0.9f, 0.9f, 0.05f, 0.05f), PictureEdit.Action.Remove)))), Harness.pdf(src)).single()
        assertTrue("$o", o is OpOutcome.Failed && o.reason.contains("could not be found"))
    }

    private fun render(f: File): Bitmap = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
        PdfRenderer(fd).use { r -> r.openPage(0).use { p ->
            Bitmap.createBitmap(p.width, p.height, Bitmap.Config.ARGB_8888).also {
                it.eraseColor(Color.WHITE); p.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
        } }
    }
    private fun Bitmap.at(x: Float, y: Float) = getPixel((x * width).toInt().coerceIn(0, width - 1), (y * height).toInt().coerceIn(0, height - 1))
    private fun isRed(c: Int) = Color.red(c) > 200 && Color.green(c) < 60 && Color.blue(c) < 60
    private fun isGreen(c: Int) = Color.green(c) > 200 && Color.red(c) < 60
    private fun isWhite(c: Int) = Color.red(c) > 240 && Color.green(c) > 240 && Color.blue(c) > 240
}
