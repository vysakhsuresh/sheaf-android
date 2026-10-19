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
import com.layerbit.sheaf.pdf.WatermarkSpec
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Writes rendered pages to the app's external files, for a person to look at. Never fails. */
@RunWith(AndroidJUnit4::class)
class VisualSample {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val out = context.getExternalFilesDir("samples")!!.apply { mkdirs() }

    @Test
    fun scripts() {
        PDFBoxResourceLoader.init(context)
        val s = PdfBoxSurgeon(context.cacheDir)
        val blank = File(out, "blank.pdf")
        PDDocument().use { it.addPage(PDPage(PDRectangle.A4)); it.save(blank) }
        val a = File(out, "a.pdf"); val b = File(out, "b.pdf"); val c = File(out, "c.pdf"); val d = File(out, "d.pdf")
        s.addText(PdfInput(blank), listOf(
            TextNote(0, "മലയാളം: സ്ത്രീ ക്ഷേത്രം പ്രാർത്ഥന", 0.08f, 0.08f, 22f),
            TextNote(0, "हिन्दी: क्षत्रिय प्रार्थना श्रीमान्", 0.08f, 0.16f, 22f),
            TextNote(0, "தமிழ்: க்ஷேத்திரம்  English: Hello", 0.08f, 0.24f, 22f)
        ), a)
        s.watermark(PdfInput(a), WatermarkSpec(text = "गोपनीय CONFIDENTIAL", opacity = 0.3f), b)
        s.addPageNumbers(PdfInput(b), PageNumberSpec(format = "പേജ് {n} / {total}"), c)
        s.stampHeaderFooter(PdfInput(c), StampSpec(headerText = "किराया रसीद — {date}"), d)
        render(d, File(out, "scripts.png"))
    }

    private fun render(pdf: File, png: File) {
        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { r -> r.openPage(0).use { p ->
                val bmp = Bitmap.createBitmap(1190, 1684, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                png.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } }
        }
    }
}
