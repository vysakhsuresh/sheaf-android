package com.layerbit.sheaf.pdf

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.IOException

/**
 * [PdfEngine] on android.graphics.pdf.PdfRenderer - the renderer built into Android.
 *
 * Chosen as the first implementation because it adds nothing to the APK, has no licence to
 * worry about, and is already on every device Sheaf runs on. It renders and it measures, and
 * that is all: it cannot open an encrypted file, cannot write one, and cannot touch the page
 * tree. Those arrive in P1 behind this same interface.
 *
 * Its real value is as a reference. When the PDFBox engine lands, its output for a page can
 * be compared against this one, and a disagreement is a bug in the new engine rather than a
 * question about which of two unverified implementations is right.
 */
class PlatformPdfEngine(context: Context) : PdfEngine {

    private val maxHeapBytes: Long = run {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        // getMemoryClass() is in megabytes, and is the limit for a normal (non-large-heap)
        // process. Sheaf does not ask for a large heap: it would raise this number while
        // making the system more likely to kill the app outright under pressure.
        am.memoryClass.toLong() * 1024L * 1024L
    }

    override val capabilities = EngineCapabilities(
        render = true,
        restructure = false,
        // PdfRenderer throws SecurityException on an encrypted file and offers no way to
        // supply a password. This is the single biggest reason a second engine is needed.
        encryptedInput = false,
        write = false,
        textExtraction = false
    )

    override fun open(file: File, password: String?): PdfDocument {
        if (password != null) {
            throw PdfException.Unsupported("the platform renderer cannot open encrypted documents")
        }
        if (!file.isFile) {
            throw PdfException.Io("${file.name} is not a file")
        }

        var descriptor: ParcelFileDescriptor? = null
        try {
            descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(descriptor)
            return PlatformPdfDocument(renderer, descriptor, maxHeapBytes)
        } catch (e: SecurityException) {
            descriptor?.closeQuietly()
            throw PdfException.PasswordRequired(e)
        } catch (e: IOException) {
            descriptor?.closeQuietly()
            // PdfRenderer reports "file not in PDF format or corrupted" as a plain IOException,
            // indistinguishable from a genuine read error. Corrupt is the more useful guess:
            // the file was readable enough to open a descriptor on a moment ago.
            throw PdfException.Corrupt(e.message ?: "unreadable", e)
        } catch (e: Exception) {
            descriptor?.closeQuietly()
            throw PdfException.Corrupt(e.message ?: "could not be opened", e)
        }
    }
}

private class PlatformPdfDocument(
    private val renderer: PdfRenderer,
    private val descriptor: ParcelFileDescriptor,
    private val maxHeapBytes: Long
) : PdfDocument {

    override val pageCount: Int = renderer.pageCount

    override fun pageSize(index: Int): PageSize {
        checkIndex(index)
        // PdfRenderer allows one open page at a time, so this opens and closes rather than
        // caching. Callers that need every size walk the document once and keep the results.
        return try {
            renderer.openPage(index).use { page ->
                PageSize(page.width.toFloat(), page.height.toFloat())
            }
        } catch (e: Exception) {
            throw PdfException.Corrupt("page ${index + 1} could not be measured", e)
        }
    }

    override fun renderPage(index: Int, targetWidthPx: Int): Bitmap {
        checkIndex(index)
        try {
            renderer.openPage(index).use { page ->
                val aspect = if (page.height <= 0) 1f else page.width.toFloat() / page.height.toFloat()
                val width = BitmapBudget.clampWidth(targetWidthPx, aspect, maxHeapBytes)
                val height = BitmapBudget.heightFor(width, aspect)

                val bitmap = try {
                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                } catch (e: OutOfMemoryError) {
                    // Caught deliberately. The budget makes this rare, not impossible - another
                    // part of the app may have taken the heap between the clamp and here - and
                    // an OOM while rendering one page of a batch should fail that page, not
                    // take the process down.
                    throw PdfException.OutOfMemory("page ${index + 1} at ${width}x$height", e)
                }

                // PdfRenderer composites onto whatever is already in the bitmap and a fresh
                // one is transparent, so an unpainted page renders as text on nothing and
                // turns unreadable the moment it is drawn on a dark background. Every page
                // gets its white sheet first.
                Canvas(bitmap).drawColor(Color.WHITE)

                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                return bitmap
            }
        } catch (e: PdfException) {
            throw e
        } catch (e: Exception) {
            throw PdfException.Corrupt("page ${index + 1} could not be rendered", e)
        }
    }

    private fun checkIndex(index: Int) {
        if (index !in 0 until pageCount) {
            throw PdfException.Io("page ${index + 1} does not exist in a $pageCount-page document")
        }
    }

    override fun close() {
        // Order matters: PdfRenderer holds the descriptor and closing it the other way round
        // leaves a native handle pointing at a closed fd.
        runCatching { renderer.close() }
        descriptor.closeQuietly()
    }
}

private fun ParcelFileDescriptor.closeQuietly() {
    runCatching { close() }
}
