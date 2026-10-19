package com.layerbit.sheaf

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.layerbit.sheaf.ops.CompressOp
import com.layerbit.sheaf.ops.MetadataOp
import com.layerbit.sheaf.ops.OpOutcome
import com.layerbit.sheaf.pdf.CompressionLevel
import com.layerbit.sheaf.pdf.DocumentMetadata
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
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
class CompressAndDetailsTest {

    @Before fun setUp() = PDFBoxResourceLoader.init(Harness.app)

    /** Three pages, each a lossless picture of noise under a line of real text: several MB. */
    private fun heavy(): File {
        val f = File(Harness.dir, "heavy.pdf")
        val rnd = Random(3)
        PDDocument().use { doc ->
            repeat(3) { p ->
                val page = PDPage(PDRectangle.A4); doc.addPage(page)
                val bmp = Bitmap.createBitmap(900, 900, Bitmap.Config.ARGB_8888)
                val px = IntArray(900 * 900) { Color.rgb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)) }
                bmp.setPixels(px, 0, 900, 0, 0, 900, 900)
                PDPageContentStream(doc, page).use { s ->
                    s.drawImage(LosslessFactory.createFromImage(doc, bmp), 50f, 200f, 495f, 495f)
                    s.beginText(); s.setFont(PDType1Font.HELVETICA, 14f); s.newLineAtOffset(60f, 120f); s.showText("Statement page ${p + 1} total 4520"); s.endText()
                }
            }
            doc.save(f)
        }
        return f
    }

    @Test
    fun picturesGetUnderASizeAndKeepTheWords() {
        val src = heavy()
        assertTrue("heavy enough: ${src.length()}", src.length() > 3 * 1024 * 1024)
        val target = 600L * 1024
        val o = Harness.run(CompressOp(CompressionLevel.BALANCED, targetBytes = target, allowPictures = true), Harness.pdf(src)).single()
        assertTrue("$o", o is OpOutcome.Produced)
        o as OpOutcome.Produced
        assertTrue("size ${o.file.sizeBytes}", o.file.sizeBytes <= target)
        val text = PDDocument.load(o.file.file).use { PDFTextStripper().getText(it) }
        assertTrue(text, text.contains("Statement page 2 total 4520"))
        assertTrue(o.detail!!, o.detail!!.contains("pictures"))
    }

    private fun titled(name: String, title: String): File {
        val f = File(Harness.dir, name)
        PDDocument().use { doc ->
            doc.addPage(PDPage()); doc.documentInformation.title = title; doc.documentInformation.author = "Old"
            doc.save(f)
        }
        return f
    }

    @Test
    fun severalFilesKeepTheirOwnTitles() {
        val a = titled("a.pdf", "Lease")
        val b = titled("b.pdf", "Invoice")
        val outs = Harness.run(MetadataOp(DocumentMetadata(title = null, author = "Anjali", subject = null, keywords = null)), Harness.pdf(a), Harness.pdf(b))
        val infos = outs.map { (it as OpOutcome.Produced).file.file }.map { PDDocument.load(it).use { d -> d.documentInformation.title to d.documentInformation.author } }
        assertEquals(listOf("Lease" to "Anjali", "Invoice" to "Anjali"), infos)
    }
}
