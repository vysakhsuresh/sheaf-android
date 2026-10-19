package com.layerbit.sheaf.pdf

import android.graphics.Paint
import android.graphics.Typeface
import com.tom_roush.pdfbox.multipdf.LayerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.util.Matrix
import java.io.ByteArrayOutputStream
import java.io.Closeable
import kotlin.math.ceil

/**
 * Writes a line of text in any script onto a page.
 *
 * Helvetica covers Latin and nothing else, and PDFBox does not shape: handed Hindi or
 * Malayalam it either refuses or sets the letters one by one with every conjunct broken. The
 * phone already has a text engine that shapes every script it can display, and a PDF writer
 * built on it - [android.graphics.pdf.PdfDocument]. A line that Helvetica cannot set is laid
 * out by that engine on a page of its own, exactly the line's size, and the page is brought
 * into the document as a form and drawn where the line belongs. The words stay vector, stay
 * selectable, and are spelt the way the reader typed them.
 *
 * Plain Latin keeps going through Helvetica, which is smaller and is what every tool here was
 * tested with.
 *
 * The throwaway documents stay open until [close], because a form imported from one shares
 * its streams with the source until the target is saved.
 */
internal class Lettering(private val doc: PDDocument) : Closeable {

    /** A shaped line, ready to draw. Measurements in points. */
    class Shaped(
        val form: PDFormXObject,
        val width: Float,
        /** From the form's bottom edge up to the line's baseline. */
        val baseline: Float
    )

    private val sources = mutableListOf<PDDocument>()
    private val invisible = InvisibleText(doc)
    private val shaped = HashMap<String, Shaped>()

    fun width(text: String, size: Float, bold: Boolean = false): Float =
        if (isPlain(text)) {
            runCatching { helvetica(bold).getStringWidth(text) / 1000f * size }
                .getOrDefault(text.length * size * 0.5f)
        } else {
            paint(size, bold, 0xFF000000.toInt()).measureText(text)
        }

    /**
     * Draws [text] with the left end of its baseline at ([x], [y]) in the stream's current
     * space, so any transform the caller has set - a page's quarter turn, a watermark's angle -
     * applies to it as it would to Helvetica. Call it outside a text object.
     *
     * [colour] is packed ARGB; its alpha is the line's opacity.
     */
    fun draw(
        stream: PDPageContentStream,
        text: String,
        size: Float,
        x: Float,
        y: Float,
        colour: Int,
        bold: Boolean = false,
        /** False for the second and later copies of the same words, so a search finds them once. */
        searchable: Boolean = true
    ) {
        if (text.isEmpty()) return
        if (isPlain(text)) {
            stream.setNonStrokingColor(colour.red(), colour.green(), colour.blue())
            stream.beginText()
            try {
                stream.setFont(helvetica(bold), size)
                stream.newLineAtOffset(x, y)
                stream.showText(text)
            } finally {
                stream.endText()
            }
            return
        }
        val line = shape(text, size, colour, bold) ?: return
        stream.saveGraphicsState()
        if (searchable) invisible.write(stream, text, x, y, line.width, size)
        stream.transform(Matrix.getTranslateInstance(x - PAD, y - line.baseline))
        stream.drawForm(line.form)
        stream.restoreGraphicsState()
    }

    /** The shaped form for a line, made once per text, size, colour and weight. */
    fun shape(text: String, size: Float, colour: Int, bold: Boolean = false): Shaped? {
        val key = "$size|$colour|$bold|$text"
        shaped[key]?.let { return it }
        val made = runCatching { make(text, size, colour, bold) }.getOrNull() ?: return null
        shaped[key] = made
        return made
    }

    private fun make(text: String, size: Float, colour: Int, bold: Boolean): Shaped {
        val paint = paint(size, bold, colour)
        val metrics = paint.fontMetrics
        val width = paint.measureText(text)
        // Points, which is what PdfDocument's page size and canvas units both are. The pad
        // keeps an overhanging stroke - a Malayalam vowel sign, an italic tail - inside.
        val pageWidth = ceil(width + PAD * 2).toInt().coerceAtLeast(1)
        val pageHeight = ceil(metrics.descent - metrics.ascent + PAD * 2).toInt().coerceAtLeast(1)

        val bytes = ByteArrayOutputStream()
        val pdf = android.graphics.pdf.PdfDocument()
        try {
            val page = pdf.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create())
            // Outlines, not text: the shaped glyphs come back out of a PDF in the order they
            // are drawn, which for Malayalam and Hindi is not the order they are spelt, so
            // the words a reader finds are written separately, invisibly, over them.
            val outline = android.graphics.Path()
            paint.getTextPath(text, 0, text.length, PAD, PAD - metrics.ascent, outline)
            page.canvas.drawPath(outline, paint)
            pdf.finishPage(page)
            pdf.writeTo(bytes)
        } finally {
            pdf.close()
        }

        val source = PDDocument.load(bytes.toByteArray())
        sources += source
        val form = LayerUtility(doc).importPageAsForm(source, 0)
        // The canvas's y runs down from the top; the baseline sits PAD - ascent below it.
        return Shaped(form, width, pageHeight - (PAD - metrics.ascent))
    }

    private fun paint(size: Float, bold: Boolean, colour: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        color = colour
    }

    private fun helvetica(bold: Boolean) = if (bold) PDType1Font.HELVETICA_BOLD else PDType1Font.HELVETICA

    override fun close() {
        sources.forEach { runCatching { it.close() } }
        sources.clear()
        shaped.clear()
    }

    companion object {
        private const val PAD = 2f

        /** Whether Helvetica's WinAnsi encoding can set every character of [text]. */
        fun isPlain(text: String): Boolean =
            text.all { it.code in 32..126 || it.code in 160..255 }

        private fun Int.red() = ((this shr 16) and 0xFF) / 255f
        private fun Int.green() = ((this shr 8) and 0xFF) / 255f
        private fun Int.blue() = (this and 0xFF) / 255f
    }
}
