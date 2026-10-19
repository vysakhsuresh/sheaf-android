package com.layerbit.sheaf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.layerbit.sheaf.pdf.PageNumberSpec
import com.layerbit.sheaf.pdf.PdfBoxSurgeon
import com.layerbit.sheaf.pdf.PdfInput
import com.layerbit.sheaf.pdf.StampSpec
import com.layerbit.sheaf.pdf.TextNote
import com.layerbit.sheaf.pdf.TextPlacement
import com.layerbit.sheaf.pdf.WatermarkSpec
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Words in Indian scripts go onto pages as words: drawn where they should be, and found
 * again by a reader's search. Runs on the phone, because the shaping is the phone's.
 */
@RunWith(AndroidJUnit4::class)
class ScriptsTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "scripts-test").apply { mkdirs() }
    private val surgeon = PdfBoxSurgeon(dir)

    @Before
    fun setUp() = PDFBoxResourceLoader.init(context)

    @Test
    fun watermarkInHindiIsDrawnAndSearchable() {
        val out = file("wm.pdf")
        surgeon.watermark(PdfInput(blank()), WatermarkSpec(text = "गोपनीय", opacity = 0.5f), out)
        val t = textOf(out); assertTrue("watermark text: [$t]", t.filterNot { it.isWhitespace() }.contains("गोपनीय"))
        assertTrue("watermark ink ${inkFraction(out)}", inkFraction(out) > 0.002)
    }

    @Test
    fun tiledWatermarkInMalayalam() {
        val out = file("wm-tiled.pdf")
        surgeon.watermark(PdfInput(blank()), WatermarkSpec(text = "രഹസ്യം", opacity = 0.6f, layout = WatermarkSpec.Layout.TILED), out)
        assertTrue("tiled ink ${inkFraction(out)}", inkFraction(out) > 0.01)
    }

    @Test
    fun addTextInMalayalam() {
        val out = file("note.pdf")
        surgeon.addText(PdfInput(blank()), listOf(TextNote(0, "പേര്: വൈശാഖ്\nസ്ഥലം", 0.1f, 0.1f, 18f)), out)
        val text = textOf(out)
        assertTrue("note text: $text", text.contains("വൈശാഖ്"))
        assertTrue("note ink", inkFraction(out) > 0.0005)
    }

    @Test
    fun latinStillUsesHelvetica() {
        val out = file("latin.pdf")
        surgeon.addText(PdfInput(blank()), listOf(TextNote(0, "Hello world", 0.1f, 0.1f, 18f)), out)
        assertTrue(textOf(out).contains("Hello world"))
    }

    @Test
    fun pageNumbersAndHeaderInDevanagari() {
        val numbered = file("num.pdf")
        surgeon.addPageNumbers(PdfInput(blank(3)), PageNumberSpec(format = "पृष्ठ {n}"), numbered)
        val text = textOf(numbered)
        assertTrue("numbers: $text", text.contains("पृष्ठ 1") && text.contains("पृष्ठ 3"))
        val stamped = file("hf.pdf")
        surgeon.stampHeaderFooter(PdfInput(blank()), StampSpec(headerText = "किराया रसीद", footerText = "Page {page} of {total}"), stamped)
        val t2 = textOf(stamped)
        assertTrue("header: $t2", t2.contains("किराया") && t2.contains("Page 1 of 1"))
    }

    @Test
    fun invisibleLayerCarriesAnyScript() {
        val out = file("ocr.pdf")
        surgeon.addTextLayer(
            PdfInput(blank()),
            mapOf(0 to listOf(
                TextPlacement("नमस्ते दुनिया", 0.1f, 0.1f, 0.4f, 0.03f),
                TextPlacement("Invoice total", 0.1f, 0.2f, 0.4f, 0.03f),
                TextPlacement("മലയാളം", 0.1f, 0.3f, 0.2f, 0.03f)
            )),
            out
        )
        val text = textOf(out)
        assertTrue("hindi: $text", text.contains("नमस्ते"))
        assertTrue("latin: $text", text.contains("Invoice total"))
        assertTrue("malayalam: $text", text.contains("മലയാളം"))
        assertTrue("stays invisible", inkFraction(out) < 0.0001)
    }

    // ---- helpers ----

    private fun file(name: String) = File(dir, name).also { it.delete() }

    private fun blank(pages: Int = 1): File {
        val f = file("blank-$pages.pdf")
        PDDocument().use { doc ->
            repeat(pages) { doc.addPage(PDPage(PDRectangle.A4)) }
            doc.save(f)
        }
        return f
    }

    private fun textOf(f: File): String = PDDocument.load(f).use { PDFTextStripper().getText(it) }

    /** The share of page one that is not white, as the platform renderer draws it. */
    private fun inkFraction(f: File): Double {
        ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                renderer.openPage(0).use { page ->
                    val bmp = Bitmap.createBitmap(595, 842, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    var dark = 0
                    val px = IntArray(bmp.width * bmp.height)
                    bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                    for (p in px) if (Color.red(p) + Color.green(p) + Color.blue(p) < 600) dark++
                    return dark.toDouble() / px.size
                }
            }
        }
    }
}
