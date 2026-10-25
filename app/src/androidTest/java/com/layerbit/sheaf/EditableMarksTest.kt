package com.layerbit.sheaf

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.layerbit.sheaf.ops.AnnotateOp
import com.layerbit.sheaf.ops.OpOutcome
import com.layerbit.sheaf.pdf.PageArea
import com.layerbit.sheaf.pdf.PageMark
import com.layerbit.sheaf.pdf.PagePoint
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Mark up with "Save marks as editable annotations" on, and off. */
@RunWith(AndroidJUnit4::class)
class EditableMarksTest {

    @Before fun setUp() = PDFBoxResourceLoader.init(Harness.app)

    private fun blank(rotation: Int = 0): File {
        val f = File(Harness.dir, "blank-$rotation.pdf")
        PDDocument().use { doc ->
            doc.addPage(PDPage(PDRectangle.A4).also { it.rotation = rotation })
            doc.save(f)
        }
        return f
    }

    private val yellow = 0xFFFFD400.toInt()
    private val red = 0xFFE0301E.toInt()

    private val marks = mapOf(
        0 to listOf(
            PageMark.Highlight(PageArea(0.1f, 0.1f, 0.5f, 0.03f), yellow),
            PageMark.Ink(listOf(PagePoint(0.2f, 0.5f), PagePoint(0.4f, 0.55f), PagePoint(0.6f, 0.5f)), red, 3f),
            PageMark.Shape(PageMark.Shape.Kind.BOX, PageArea(0.1f, 0.7f, 0.3f, 0.1f), red),
            PageMark.Shape(PageMark.Shape.Kind.UNDERLINE, PageArea(0.5f, 0.7f, 0.3f, 0.02f), red),
            PageMark.Line(PagePoint(0.1f, 0.9f), PagePoint(0.8f, 0.9f), red, arrow = true),
            PageMark.Note(PagePoint(0.6f, 0.2f), "Check this figure", red)
        )
    )

    private fun produced(editable: Boolean, rotation: Int = 0): File {
        val outcome = Harness.run(AnnotateOp(marks, editable = editable), Harness.pdf(blank(rotation))).single()
        assertTrue("expected a file, got $outcome", outcome is OpOutcome.Produced)
        return (outcome as OpOutcome.Produced).file.file
    }

    @Test
    fun editableMarksAreAnnotationsWithAppearances() {
        PDDocument.load(produced(editable = true)).use { doc ->
            val annotations = doc.getPage(0).annotations
            assertEquals(marks.getValue(0).size, annotations.size)
            assertEquals(
                listOf("Highlight", "Ink", "Square", "Underline", "Line", "FreeText"),
                annotations.map { it.subtype }
            )
            // Every one carries a normal appearance, which is what a reader that only paints
            // appearances needs to show it at all.
            annotations.forEach { assertNotNull("${it.subtype} has no appearance", it.normalAppearanceStream) }
            // And the page content was not touched: nothing was flattened.
            assertTrue(doc.getPage(0).contents == null || doc.getPage(0).contents.available() == 0)
        }
    }

    @Test
    fun flattenedMarksAddNoAnnotations() {
        PDDocument.load(produced(editable = false)).use { doc ->
            assertEquals(0, doc.getPage(0).annotations.size)
        }
    }

    @Test
    fun editableMarksLandInsideATurnedPage() {
        PDDocument.load(produced(editable = true, rotation = 90)).use { doc ->
            val box = doc.getPage(0).mediaBox
            doc.getPage(0).annotations.forEach { a ->
                val r = a.rectangle
                assertTrue("${a.subtype} at $r is off the page", r.lowerLeftX >= box.lowerLeftX - 30 && r.upperRightX <= box.upperRightX + 30)
                assertTrue("${a.subtype} at $r is off the page", r.lowerLeftY >= box.lowerLeftY - 30 && r.upperRightY <= box.upperRightY + 30)
            }
        }
    }
}
