package com.layerbit.sheaf.pdf

import android.graphics.Bitmap

/**
 * Reading the words out of a picture of a page.
 *
 * A seam for the same reason [PdfEngine] is one: the recogniser is a choice that may change.
 * Today it is ML Kit's bundled model, which ships inside the APK and therefore works with no
 * network - the unbundled build downloads its model at runtime and would break the one promise
 * this app makes. Tesseract would be the swap if that ever stopped being true.
 *
 * Coordinates come back in the pixel space of the bitmap that was handed in. Converting those
 * to PDF's own coordinate space happens in one place, [PdfSurgeon.addTextLayer], because that
 * conversion flips the vertical axis and is exactly the kind of arithmetic that goes wrong
 * when it is written twice.
 */
interface OcrEngine {

    /** True when the recogniser is usable on this device. */
    val available: Boolean

    /**
     * Reads [bitmap] and returns the lines found in it.
     *
     * @throws PdfException.Unsupported if recognition is unavailable.
     */
    suspend fun read(bitmap: Bitmap): OcrResult
}

/**
 * What was found on one page.
 *
 * Lines rather than blocks or words.
 *
 * A block is too coarse for a searchable layer: selecting a phrase would select a whole
 * paragraph. A word is too fine, because word-level placement accumulates rounding error
 * across a line, and the invisible text drifts away from the ink underneath it.
 */
data class OcrResult(
    val lines: List<OcrLine>
) {
    val text: String get() = lines.joinToString("\n") { it.text }
    val isEmpty: Boolean get() = lines.isEmpty()
}

/** One line of recognised text, positioned in the source bitmap's pixel space. */
data class OcrLine(
    val text: String,
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float
)
