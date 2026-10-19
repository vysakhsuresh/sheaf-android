package com.layerbit.sheaf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.layerbit.sheaf.ops.EditTextOp
import com.layerbit.sheaf.ops.OpOutcome
import com.layerbit.sheaf.ops.TextEditPlans
import com.layerbit.sheaf.pdf.EditablePage
import com.layerbit.sheaf.pdf.PdfInput
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class EditTextTest {

    @Before fun setUp() = PDFBoxResourceLoader.init(Harness.app)

    private fun sample(): File {
        val f = File(Harness.dir, "edit-sample.pdf")
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4); doc.addPage(page)
            val roboto = PDType0Font.load(doc, File("/system/fonts/Roboto-Regular.ttf").takeIf { it.isFile } ?: File("/system/fonts/RobotoStatic-Regular.ttf"))
            PDPageContentStream(doc, page).use { s ->
                fun text(font: com.tom_roush.pdfbox.pdmodel.font.PDFont, size: Float, x: Float, y: Float, t: String) {
                    s.beginText(); s.setFont(font, size); s.newLineAtOffset(x, y); s.showText(t); s.endText()
                }
                text(PDType1Font.HELVETICA, 12f, 72f, 760f, "Invoice date: 12 March 2026")
                // A table row with a rule between its two cells.
                s.setLineWidth(0.8f); s.moveTo(300f, 690f); s.lineTo(300f, 720f); s.stroke()
                text(PDType1Font.HELVETICA_BOLD, 11f, 72f, 700f, "Rent for April")
                text(PDType1Font.HELVETICA, 11f, 440f, 700f, "18,000.00")
                // A cream band with a line on it.
                s.setNonStrokingColor(1f, 0.95f, 0.8f); s.addRect(60f, 630f, 480f, 30f); s.fill()
                s.setNonStrokingColor(0.1f, 0.1f, 0.5f)
                text(PDType1Font.TIMES_ROMAN, 14f, 72f, 640f, "Tenant: Anjali Menon")
                s.setNonStrokingColor(0f, 0f, 0f)
                text(roboto, 13f, 72f, 580f, "Paid in full")
            }
            doc.save(f)
        }
        return f
    }

    private fun page(f: File): EditablePage = Harness.app.surgeon.editableLines(PdfInput(f), listOf(0))[0]!!

    @Test
    fun findsLinesAndCells() {
        val p = page(sample())
        val texts = p.lines.map { it.text }
        assertTrue(texts.toString(), "Invoice date: 12 March 2026" in texts)
        assertTrue("cells split at the rule: $texts", "Rent for April" in texts && "18,000.00" in texts)
        val amount = p.lines.first { it.text == "18,000.00" }
        assertTrue("numbers align right", amount.alignRight)
        assertTrue("room stops at the rule", amount.roomLeft > 300f / 595.28f)
        val rent = p.lines.first { it.text == "Rent for April" }
        assertTrue("bold", rent.bold)
        assertTrue("serif", p.lines.first { it.text.startsWith("Tenant") }.family == com.layerbit.sheaf.pdf.TypeFamily.SERIF)
    }

    private fun run(f: File, changes: Map<String, String>): File {
        val p = page(f)
        val bmp = render(f)
        val px = IntArray(bmp.width * bmp.height); bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val edits = changes.flatMap { (old, new) ->
            val line = p.lines.first { it.text == old }
            val (paper, ink) = TextEditPlans.colours(px, bmp.width, bmp.height, line.area)
            TextEditPlans.edits(0, line, new, paper, ink, p.heightPoints / p.widthPoints) { t -> t.length * 0.5f * line.sizePoints / p.widthPoints }
        }
        val out = Harness.run(EditTextOp(edits), Harness.pdf(f)).single()
        assertTrue("$out", out is OpOutcome.Produced)
        return (out as OpOutcome.Produced).file.file
    }

    @Test
    fun changesAWordInTheDocumentsOwnFont() {
        val out = run(sample(), mapOf("Paid in full" to "Paid in fun"))
        // The last font the page's drawing sets is the one the new words are written in.
        val name = PDDocument.load(out).use { doc ->
            val page = doc.getPage(0)
            val parser = com.tom_roush.pdfbox.pdfparser.PDFStreamParser(page); parser.parse()
            val tokens = parser.tokens
            val at = tokens.indexOfLast { it is com.tom_roush.pdfbox.contentstream.operator.Operator && it.name == "Tf" }
            page.resources.getFont(tokens[at - 2] as com.tom_roush.pdfbox.cos.COSName).name
        }
        assertTrue("own font used: $name", name.contains("Roboto"))
        assertTrue(textOf(out).contains("Paid in fun"))
    }

    /** Every glyph, including ones drawn over others: the old words are painted over, not removed. */
    private fun textOf(f: File) = PDDocument.load(f).use { PDFTextStripper().apply { setSuppressDuplicateOverlappingText(false) }.getText(it) }

    @Test
    fun aLetterTheFontDoesNotHaveFallsBackToBuiltIn() {
        val out = run(sample(), mapOf("Paid in full" to "Paid in part"))
        val t = textOf(out)
        assertTrue(t, t.contains("Paid in part"))
    }

    @Test
    fun tableCellKeepsItsPaperAndInk() {
        val src = sample()
        val out = run(src, mapOf("18,000.00" to "19,500.00", "Tenant: Anjali Menon" to "Tenant: Anjali M. Nair"))
        val t = textOf(out)
        assertTrue(t, t.contains("19,500.00") && t.contains("Anjali M. Nair"))
        val bmp = render(out)
        // The cream band is still cream where the tenant line was repainted.
        val line = page(src).lines.first { it.text.startsWith("Tenant") }
        val c = bmp.getPixel(((line.area.left + line.area.width * 0.3f) * bmp.width).toInt(), ((line.area.top + line.area.height * 0.08f) * bmp.height).toInt())
        assertTrue("cream ${Integer.toHexString(c)}", Color.red(c) > 240 && Color.blue(c) in 170..230)
    }

    @Test
    fun hindiReplacement() {
        val out = run(sample(), mapOf("Rent for April" to "अप्रैल का किराया"))
        val t = textOf(out)
        assertTrue(t, t.contains("अप्रैल"))
    }

    @Test
    fun scanIsRecognisedAsOne() {
        val f = File(Harness.dir, "scan.pdf")
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4); doc.addPage(page)
            val photo = Bitmap.createBitmap(200, 280, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.LTGRAY) }
            PDPageContentStream(doc, page).use { s ->
                s.drawImage(com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(doc, photo), 0f, 0f, 595.28f, 841.89f)
            }
            doc.save(f)
        }
        val p = page(f)
        assertTrue(p.scanned)
        assertEquals(0, p.lines.size)
        assertNotNull(p)
    }

    @Test
    fun visual() {
        val out = run(sample(), mapOf("18,000.00" to "1,19,500.00", "Tenant: Anjali Menon" to "Tenant: Anjali M. Nair", "Rent for April" to "Rent for May", "Paid in full" to "Paid in fun"))
        val png = File(Harness.app.getExternalFilesDir("samples"), "edit.png")
        val bmp = render(out)
        png.outputStream().use { Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height / 3).compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun render(f: File): Bitmap = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
        PdfRenderer(fd).use { r -> r.openPage(0).use { p ->
            Bitmap.createBitmap(900, (900f * p.height / p.width).toInt(), Bitmap.Config.ARGB_8888).also {
                it.eraseColor(Color.WHITE); p.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
        } }
    }
}
