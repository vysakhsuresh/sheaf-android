package com.layerbit.sheaf

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.layerbit.sheaf.ops.CompressOp
import com.layerbit.sheaf.ops.OpOutcome
import com.layerbit.sheaf.ops.PageNumberOp
import com.layerbit.sheaf.ops.RecipeOp
import com.layerbit.sheaf.ops.SetPasswordOp
import com.layerbit.sheaf.ops.ToolId
import com.layerbit.sheaf.ops.WatermarkOp
import com.layerbit.sheaf.pdf.CompressionLevel
import com.layerbit.sheaf.pdf.DocumentPermissions
import com.layerbit.sheaf.pdf.PageNumberSpec
import com.layerbit.sheaf.pdf.WatermarkSpec
import com.layerbit.sheaf.ui.tools.Recipe
import com.layerbit.sheaf.ui.tools.RecipeStep
import com.layerbit.sheaf.ui.tools.Recipes
import com.layerbit.sheaf.ui.tools.ToolConfig
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RecipeTest {

    @Before fun setUp() = PDFBoxResourceLoader.init(Harness.app)

    private fun pdf(name: String, pages: Int): File = File(Harness.dir, name).also { f ->
        PDDocument().use { d -> repeat(pages) { d.addPage(PDPage()) }; d.save(f) }
    }

    @Test
    fun keptAndReadBack() {
        val steps = listOf(
            RecipeStep(ToolId.COMPRESS, ToolConfig(compression = CompressionLevel.STRONG, compressToSize = true, compressTargetMb = 5)),
            RecipeStep(ToolId.WATERMARK, ToolConfig(watermarkText = "DRAFT", watermarkLayout = WatermarkSpec.Layout.TILED, pageSpec = "2-")),
            RecipeStep(ToolId.SET_PASSWORD, ToolConfig(newPassword = "secret", confirmPassword = "secret", allowPrinting = false))
        )
        val file = File(Harness.dir, "recipes.json")
        assertTrue(Recipes.write(file, listOf(Recipe("r1", "For the accountant", steps))))
        assertTrue("password never kept", !file.readText().contains("secret"))
        val back = Recipes.read(file).single()
        assertEquals("For the accountant", back.name)
        assertEquals(CompressionLevel.STRONG, back.steps[0].config.compression)
        assertEquals(5, back.steps[0].config.compressTargetMb)
        assertEquals(WatermarkSpec.Layout.TILED, back.steps[1].config.watermarkLayout)
        assertEquals("2-", back.steps[1].config.pageSpec)
        assertEquals(false, back.steps[2].config.allowPrinting)
        assertEquals("", back.steps[2].config.newPassword)
        assertTrue(back.needsPassword)
        assertEquals("Compress → Watermark → Add a password", back.summary)
    }

    @Test
    fun rules() {
        assertEquals("Give the recipe a name.", Recipes.problemWith(" ", listOf(RecipeStep(ToolId.MERGE, ToolConfig()))))
        assertEquals("Add at least one step.", Recipes.problemWith("x", emptyList()))
        assertTrue(Recipes.problemWith("x", listOf(RecipeStep(ToolId.PDF_TO_OFFICE, ToolConfig()), RecipeStep(ToolId.COMPRESS, ToolConfig())))!!.startsWith("Turning PDFs"))
        assertNull(Recipes.problemWith("x", listOf(RecipeStep(ToolId.COMPRESS, ToolConfig()), RecipeStep(ToolId.PDF_TO_OFFICE, ToolConfig()))))
    }

    @Test
    fun threeStepsOnTwoFiles() {
        val op = RecipeOp(
            "Numbered, marked, locked",
            listOf(
                CompressOp(CompressionLevel.BALANCED),
                PageNumberOp(PageNumberSpec(format = "Page {n}")),
                WatermarkOp(WatermarkSpec(text = "COPY"), pageSpec = ""),
                SetPasswordOp("pw", DocumentPermissions(), restrictOnly = false)
            )
        )
        val outs = Harness.run(op, Harness.pdf(pdf("one.pdf", 2)), Harness.pdf(pdf("two.pdf", 3)))
        assertEquals(outs.toString(), 2, outs.count { it is OpOutcome.Produced })
        for (o in outs.filterIsInstance<OpOutcome.Produced>()) {
            PDDocument.load(o.file.file, "pw").use { d ->
                assertTrue(d.isEncrypted)
                val t = PDFTextStripper().getText(d)
                assertTrue(t, t.contains("Page 1") && t.contains("COPY"))
            }
        }
    }
}
