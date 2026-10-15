package com.layerbit.sheaf.pdf

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [OcrEngine] on ML Kit's bundled Latin recogniser.
 *
 * BUNDLED, not the "-unbundled" artifact. The model is inside the APK, so recognition works
 * with no network and no Play Services module download. Swapping to the unbundled build would
 * silently break the app's central promise, which is why the dependency carries a comment
 * saying so and why this class names it again here.
 *
 * Latin script only for now. Deja and Abhyas showed the shape of adding more - each extra
 * script is another several megabytes and another entry in the recogniser list - and the right
 * moment to pay that is when someone asks, not in advance.
 */
class MlKitOcrEngine : OcrEngine {

    private val latin: TextRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    // Devanagari is a second bundled model, a few megabytes more in the APK, for the scripts
    // most of this app's users read: Hindi, Marathi, Nepali. It reads Latin letters on the
    // same page too, which is what a bilingual form needs.
    private val devanagari: TextRecognizer by lazy {
        TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
    }

    override val available: Boolean = true

    override suspend fun read(bitmap: Bitmap, script: OcrScript): OcrResult {
        if (bitmap.width <= 0 || bitmap.height <= 0) return OcrResult(emptyList())
        val recogniser = when (script) {
            OcrScript.LATIN -> latin
            OcrScript.DEVANAGARI -> devanagari
        }

        // Rotation is zero because the bitmap arrives already upright: it was either rendered
        // from a PDF page, which has no orientation of its own, or straightened by the scanner.
        val image = InputImage.fromBitmap(bitmap, 0)

        val text = suspendCancellableCoroutine { continuation ->
            recogniser.process(image)
                .addOnSuccessListener { result -> continuation.resume(result) }
                .addOnFailureListener { error ->
                    continuation.resumeWithException(
                        PdfException.Unsupported("the text recogniser could not read this page", error)
                    )
                }
        }

        val lines = buildList {
            for (block in text.textBlocks) {
                for (line in block.lines) {
                    val box = line.boundingBox ?: continue
                    val content = line.text.trim()
                    if (content.isEmpty()) continue
                    add(
                        OcrLine(
                            text = content,
                            left = box.left.toFloat(),
                            top = box.top.toFloat(),
                            width = box.width().toFloat(),
                            height = box.height().toFloat()
                        )
                    )
                }
            }
        }
        return OcrResult(lines)
    }
}
