package com.layerbit.sheaf.pdf

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import com.layerbit.sheaf.R
import com.layerbit.sheaf.text.Strings
import java.io.File
import java.io.IOException
import kotlin.math.max

/**
 * [PdfEngine] on android.graphics.pdf.PdfRenderer - the renderer built into Android.
 *
 * Chosen as the first implementation because it adds nothing to the APK, has no licence to
 * worry about, and is already on every device Sheaf runs on. It renders and it measures, and
 * that is all: it cannot open an encrypted file, cannot write one, and cannot touch the page
 * tree. Those arrive in P1 behind this same interface.
 *
 * What it does have beyond measuring and rendering is a clip-and-transform render in native
 * code, which is why [PdfDocument.renderTile] costs nothing extra here: pdfium was always
 * able to rasterise a region, the app simply was not asking it to.
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
            throw PdfException.Unsupported(Strings.get(R.string.engine_err_platform_encrypted))
        }
        if (!file.isFile) {
            throw PdfException.Io(Strings.get(R.string.engine_err_not_a_file, file.name))
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
            throw PdfException.Corrupt(e.message ?: Strings.get(R.string.engine_err_unreadable), e)
        } catch (e: Exception) {
            descriptor?.closeQuietly()
            throw PdfException.Corrupt(e.message ?: Strings.get(R.string.engine_err_could_not_be_opened), e)
        }
    }
}

/**
 * One PdfRenderer and the descriptor it was opened on.
 *
 * PdfRenderer will have exactly one page open at a time - openPage throws while another page
 * is unclosed - and it is not thread-safe: two renders at once, or a close arriving while a
 * render is in flight, are undefined rather than merely slow. Every method here therefore
 * opens a page, uses it and closes it within the call, and callers serialise the calls
 * themselves. The viewer does that with a lock it also closes the document under, which is
 * what makes the ordering in [close] safe to rely on.
 */
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
            throw PdfException.Corrupt(Strings.get(R.string.engine_err_page_not_measured, index + 1), e)
        }
    }

    override fun renderPage(index: Int, targetWidthPx: Int): Bitmap =
        renderPage(index, targetWidthPx, null)

    override fun renderPage(index: Int, targetWidthPx: Int, reuse: Bitmap?): Bitmap {
        checkIndex(index)
        try {
            renderer.openPage(index).use { page ->
                val aspect = if (page.height <= 0) 1f else page.width.toFloat() / page.height.toFloat()
                val width = BitmapBudget.clampWidth(targetWidthPx, aspect, maxHeapBytes)
                val height = BitmapBudget.heightFor(width, aspect)
                val bitmap = sheetFor(reuse, width, height, index)

                // A null transform tells PdfRenderer to stretch the page across the whole
                // bitmap, which is why the size above is derived from the page's own aspect
                // ratio: anything else would distort it.
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                return opaque(bitmap)
            }
        } catch (e: PdfException) {
            throw e
        } catch (e: Exception) {
            throw PdfException.Corrupt(Strings.get(R.string.engine_err_page_not_rendered, index + 1), e)
        }
    }

    override fun renderTile(
        index: Int,
        fullWidthPx: Int,
        tile: Rect,
        outWidthPx: Int,
        outHeightPx: Int,
        reuse: Bitmap?
    ): Bitmap {
        checkIndex(index)
        // These are all caller arithmetic rather than anything about the file, and every one
        // of them would otherwise reach the native renderer as a division by zero or an
        // inverted matrix. Reported the same way a bad page index is, so a caller handling
        // one hierarchy keeps handling one hierarchy.
        if (fullWidthPx <= 0) {
            throw PdfException.Io("a tile cannot be cut from a page rendered ${fullWidthPx}px wide")
        }
        if (tile.isEmpty) {
            throw PdfException.Io("tile ${tile.toShortString()} on page ${index + 1} is empty")
        }
        if (outWidthPx <= 0 || outHeightPx <= 0) {
            throw PdfException.Io("a tile cannot be rendered at ${outWidthPx}x$outHeightPx")
        }

        try {
            renderer.openPage(index).use { page ->
                val aspect = if (page.height <= 0) 1f else page.width.toFloat() / page.height.toFloat()
                val fullHeightPx = BitmapBudget.heightFor(fullWidthPx, aspect)
                val bitmap = sheetFor(reuse, outWidthPx, outHeightPx, index)

                // Three stages, applied in this order because each is expressed in the space
                // the one before it produced. Points to full-scale pixels first, taking x and
                // y separately so that the result matches a whole-page render at fullWidthPx
                // exactly - heightFor truncates, so the two axes are not quite the same
                // factor, and a tile at the foot of the page would otherwise drift. Then the
                // tile's own origin to zero. Then whatever scale makes the region fill the
                // output, which is the saving: the full-scale page is only ever a coordinate
                // system here, never a buffer.
                val transform = Matrix().apply {
                    setScale(
                        fullWidthPx.toFloat() / max(1, page.width),
                        fullHeightPx.toFloat() / max(1, page.height)
                    )
                    postTranslate(-tile.left.toFloat(), -tile.top.toFloat())
                    postScale(
                        outWidthPx.toFloat() / tile.width(),
                        outHeightPx.toFloat() / tile.height()
                    )
                }

                // The clip stays null, meaning the whole bitmap: the transform has already
                // put the wanted region there, and PdfRenderer clips to the destination
                // anyway, so content either side of the tile is dropped in native code
                // instead of being rasterised into pixels nobody sees.
                page.render(bitmap, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                return opaque(bitmap)
            }
        } catch (e: PdfException) {
            throw e
        } catch (e: Exception) {
            throw PdfException.Corrupt(Strings.get(R.string.engine_err_page_not_rendered, index + 1), e)
        }
    }

    /**
     * A white [width] x [height] bitmap to render into, [candidate] itself where it can be.
     *
     * PdfRenderer composites onto whatever is already in the bitmap, and a fresh one is
     * transparent, so an unpainted page renders as text on nothing and turns unreadable the
     * moment it is drawn on a dark background. Every page gets its white sheet first, and
     * that same fill is what makes a pooled bitmap clean enough to draw on again.
     *
     * A candidate is refused on any doubt. ARGB_8888 is the only config PdfRenderer accepts;
     * an immutable or recycled bitmap cannot be drawn into at all, and a hardware one reads
     * as immutable here; a mismatched size would silently rescale the page. None of those is
     * worth failing a page over when allocating is the answer.
     */
    private fun sheetFor(candidate: Bitmap?, width: Int, height: Int, index: Int): Bitmap {
        val pooled = candidate?.takeIf {
            !it.isRecycled && it.isMutable && it.config == Bitmap.Config.ARGB_8888 &&
                it.width == width && it.height == height
        }

        val bitmap = if (pooled != null) pooled else try {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            // Caught deliberately. The budget makes this rare but not impossible: another
            // part of the app may have taken the heap between the clamp and here. An OOM
            // while rendering one page of a batch should fail that page rather than take the
            // process down.
            throw PdfException.OutOfMemory(Strings.get(R.string.engine_err_oom_page_size, index + 1, width, height), e)
        }

        bitmap.eraseColor(Color.WHITE)
        return bitmap
    }

    /**
     * Marks a rendered bitmap opaque, which it is: it was filled white before anything was
     * drawn, and source-over onto an opaque sheet cannot produce a transparent pixel. Saying
     * so lets the framework skip blending when compositing, and a viewer scrolling a page
     * composites it on every frame.
     */
    private fun opaque(bitmap: Bitmap): Bitmap {
        bitmap.setHasAlpha(false)
        return bitmap
    }

    private fun checkIndex(index: Int) {
        if (index !in 0 until pageCount) {
            throw PdfException.Io(Strings.plural(R.plurals.engine_err_page_out_of_range, pageCount, index + 1, pageCount))
        }
    }

    override fun close() {
        // Order matters: PdfRenderer holds the descriptor and closing it the other way round
        // leaves a native handle pointing at a closed fd. The descriptor goes in a finally so
        // that a renderer which will not close cannot cost a file descriptor as well - that
        // one leaks for the life of the process where a native handle does not.
        try {
            renderer.close()
        } catch (e: Exception) {
            // The only thing that provokes this is a close racing a render, which is a caller
            // ordering bug rather than anything about the file; it used to disappear here,
            // which is precisely why it went unnoticed. There is nobody to report it to - the
            // document is already gone from wherever it was being read - so it is logged.
            Log.w(TAG, "renderer would not close; releasing its descriptor anyway", e)
        } finally {
            descriptor.closeQuietly()
        }
    }

    private companion object {
        const val TAG = "PlatformPdfEngine"
    }
}

private fun ParcelFileDescriptor.closeQuietly() {
    runCatching { close() }
}
