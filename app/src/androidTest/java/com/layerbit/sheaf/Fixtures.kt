package com.layerbit.sheaf

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Writes sample documents to the app's external files, for a person or a script to open. */
@RunWith(AndroidJUnit4::class)
class Fixtures {
    @Test
    fun write() {
        PDFBoxResourceLoader.init(Harness.app)
        val dir = Harness.app.getExternalFilesDir("fixtures")!!.apply { mkdirs() }
        // A rent invoice with a table, a coloured band and a logo.
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4); doc.addPage(page)
            val logo = Bitmap.createBitmap(120, 60, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(30, 90, 200)) }
            val photo = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(220, 120, 40)) }
            PDPageContentStream(doc, page).use { s ->
                s.drawImage(LosslessFactory.createFromImage(doc, logo), 420f, 760f, 120f, 60f)
                fun t(f: com.tom_roush.pdfbox.pdmodel.font.PDFont, size: Float, x: Float, y: Float, text: String) { s.beginText(); s.setFont(f, size); s.newLineAtOffset(x, y); s.showText(text); s.endText() }
                t(PDType1Font.HELVETICA_BOLD, 20f, 60f, 780f, "Rent Invoice")
                t(PDType1Font.HELVETICA, 12f, 60f, 740f, "Invoice date: 12 March 2026")
                t(PDType1Font.HELVETICA, 12f, 60f, 722f, "Tenant: Anjali Menon, Flat 4B, Kakkanad")
                s.setLineWidth(0.8f)
                for (y in listOf(700f, 676f, 652f, 628f)) { s.moveTo(60f, y); s.lineTo(540f, y); s.stroke() }
                s.moveTo(380f, 628f); s.lineTo(380f, 700f); s.stroke()
                t(PDType1Font.HELVETICA, 11f, 66f, 684f, "Rent for April"); t(PDType1Font.HELVETICA, 11f, 470f, 684f, "18,000.00")
                t(PDType1Font.HELVETICA, 11f, 66f, 660f, "Maintenance"); t(PDType1Font.HELVETICA, 11f, 478f, 660f, "2,500.00")
                t(PDType1Font.HELVETICA_BOLD, 11f, 66f, 636f, "Total due"); t(PDType1Font.HELVETICA_BOLD, 11f, 470f, 636f, "20,500.00")
                s.setNonStrokingColor(1f, 0.95f, 0.8f); s.addRect(60f, 560f, 480f, 34f); s.fill(); s.setNonStrokingColor(0f, 0f, 0f)
                t(PDType1Font.TIMES_ROMAN, 13f, 70f, 572f, "Paid by bank transfer on 5 April 2026")
                s.drawImage(LosslessFactory.createFromImage(doc, photo), 60f, 360f, 200f, 150f)
                t(PDType1Font.HELVETICA, 10f, 60f, 340f, "Photo of the meter reading. PAN ABCDE1234F")
            }
            doc.save(File(dir, "Rent invoice.pdf"))
        }
        // A three-page report.
        PDDocument().use { doc ->
            repeat(3) { p ->
                val page = PDPage(PDRectangle.A4); doc.addPage(page)
                PDPageContentStream(doc, page).use { s ->
                    s.beginText(); s.setFont(PDType1Font.HELVETICA, 12f); s.newLineAtOffset(60f, 760f)
                    s.showText("Quarterly report, page ${p + 1}. The tenancy continues for eleven months from April 2026.")
                    s.endText()
                }
            }
            doc.save(File(dir, "Report.pdf"))
        }
        // A photo, to resize.
        val bmp = Bitmap.createBitmap(2400, 1800, Bitmap.Config.ARGB_8888)
        val px = IntArray(2400) ; val rnd = kotlin.random.Random(1)
        for (y in 0 until 1800) { for (x in 0 until 2400) px[x] = Color.rgb((x / 10 + rnd.nextInt(40)) and 0xFF, (y / 8) and 0xFF, 150); bmp.setPixels(px, 0, 2400, 0, y, 2400, 1) }
        File(dir, "Passport photo.jpg").outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
    }
}
