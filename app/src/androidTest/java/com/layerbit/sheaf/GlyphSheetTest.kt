package com.layerbit.sheaf

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.layerbit.sheaf.ui.icons.Glyph
import com.layerbit.sheaf.ui.icons.drawGlyph
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Draws every moving icon at six moments of its action into one contact sheet, so the drawings
 * can be looked at without opening the app. Also a guard: no drawing may throw at any phase.
 * The sheet lands in the app's external files folder as glyphs.png.
 */
@RunWith(AndroidJUnit4::class)
class GlyphSheetTest {

    @Test
    fun everyGlyphDrawsAtEveryPhase() {
        val cell = 120
        val phases = listOf(0f, 0.15f, 0.3f, 0.5f, 0.7f, 0.9f)
        val glyphs = Glyph.entries
        val sheet = ImageBitmap(cell * phases.size, cell * glyphs.size)
        val canvas = Canvas(sheet)
        val accent = Color(0xFFE62E80)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, canvas, Size(sheet.width.toFloat(), sheet.height.toFloat())) {
            drawRect(Color(0xFF0B1220))
            for ((row, glyph) in glyphs.withIndex()) {
                for ((col, phase) in phases.withIndex()) {
                    translate(col * cell + 12f, row * cell + 12f) {
                        drawRoundRect(accent.copy(alpha = 0.18f), Offset(-8f, -8f), Size(cell - 8f, cell - 8f), androidx.compose.ui.geometry.CornerRadius(28f))
                        val inner = (cell - 24).toFloat()
                        val sub = CanvasDrawScope()
                        // Each cell is its own square, the size the glyph draws itself to.
                        drawContext.canvas.save()
                        drawContext.canvas.clipRect(0f, 0f, inner, inner)
                        sub.draw(Density(1f), LayoutDirection.Ltr, drawContext.canvas, Size(inner, inner)) {
                            drawGlyph(glyph, accent, phase, phase)
                        }
                        drawContext.canvas.restore()
                    }
                }
            }
        }
        val dir = Harness.app.getExternalFilesDir(null)!!
        val out = File(dir, "glyphs.png")
        out.outputStream().use { sheet.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(out.length() > 0)
    }
}
