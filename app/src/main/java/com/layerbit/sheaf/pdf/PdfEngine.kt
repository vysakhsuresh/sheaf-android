package com.layerbit.sheaf.pdf

import android.graphics.Bitmap
import android.graphics.Rect
import com.layerbit.sheaf.R
import com.layerbit.sheaf.text.Strings
import java.io.Closeable
import java.io.File

/**
 * The seam every PDF library in this app sits behind.
 *
 * There is no single Android PDF library that does everything Sheaf needs at a licence it can
 * use. The platform renderer is free and reliable but cannot restructure a file; PDFBox can
 * restructure anything but is slow and memory-hungry to render with; the libraries that do
 * both well are AGPL and therefore unusable here. So the app owns this interface and the
 * libraries are implementation details behind it.
 *
 * P0 ships exactly one implementation, [PlatformPdfEngine], built on android.graphics.pdf.
 * That is deliberate: it proves the seam with zero third-party surface, and it gives every
 * later engine a working reference to be checked against. P1 adds a PDFBox-backed engine for
 * structural work; a QPDF-backed one may follow for lossless operations.
 *
 * Nothing outside the `pdf` package may import a PDF library type. If a caller needs
 * something this interface cannot express, the fix is to widen the interface, never to reach
 * around it - the day that rule is broken, swapping an engine stops being possible.
 */
interface PdfEngine {

    /** What this engine can actually do. Callers check before offering an operation. */
    val capabilities: EngineCapabilities

    /**
     * Opens a document for reading.
     *
     * @param file a file in Sheaf's own storage. Never the user's original - see
     *   [com.layerbit.sheaf.files.DocumentStore]. Engines may memory-map or hold a file
     *   descriptor open for the lifetime of the returned document.
     * @param password for an encrypted document, or null.
     * @throws PdfException and nothing else. Engines translate their library's own failures;
     *   callers handle one hierarchy regardless of which engine answered.
     */
    @Throws(PdfException::class)
    fun open(file: File, password: String? = null): PdfDocument
}

/**
 * An open document. Closing it releases the file descriptor and any native handle, so every
 * caller uses it inside `use { }`. Not thread-safe: the underlying renderers serialise page
 * access internally at best, so one document belongs to one coroutine at a time.
 */
interface PdfDocument : Closeable {

    val pageCount: Int

    /** Page dimensions in PostScript points (1/72 inch), which is how PDF itself measures. */
    fun pageSize(index: Int): PageSize

    /**
     * Renders one page into a bitmap no wider than [targetWidthPx].
     *
     * The width is a ceiling rather than an exact size: the page's own aspect ratio decides
     * the height, and [BitmapBudget] may hand back something smaller if the request would
     * not fit in memory. Never assume the result is the size you asked for.
     */
    @Throws(PdfException::class)
    fun renderPage(index: Int, targetWidthPx: Int): Bitmap

    /**
     * [renderPage], offering a bitmap for it to draw into.
     *
     * [reuse] is taken only when it is unrecycled, mutable, ARGB_8888, and exactly the size
     * this render settles on - which is not always the size that was asked for, since the
     * [BitmapBudget] clamp may shrink it. A bitmap that fails any of those tests is ignored
     * rather than complained about: a pool handing back something stale should cost an
     * allocation, not a page.
     *
     * Ownership does not move in either direction. The engine never recycles what it was
     * handed, because the caller may still be drawing it, so a caller whose bitmap was not
     * taken still owns a usable bitmap. Equally, an engine with no interest in pooling
     * satisfies this by rendering fresh - a non-null [reuse] is an offer and never a
     * promise, and the only way to know which bitmap came back is to compare identity.
     */
    @Throws(PdfException::class)
    fun renderPage(index: Int, targetWidthPx: Int, reuse: Bitmap?): Bitmap =
        renderPage(index, targetWidthPx)

    /**
     * Renders one rectangular region of a page into a bitmap of exactly [outWidthPx] by
     * [outHeightPx].
     *
     * The viewer's cost at high zoom is otherwise set by the page rather than by the screen.
     * Filling one screen at zoom 2.5 by rendering the whole page at the zoomed width
     * allocates, rasterises and uploads something like twenty-four times the pixels that
     * reach the display; a tile pays for what is visible, at the size it is visible at.
     *
     * The coordinate convention, which every caller has to agree on exactly: [tile] is in
     * pixels of the page as it would be rendered whole at [fullWidthPx], origin at the
     * page's top left, x to the right and y down. That space is [fullWidthPx] by
     * `BitmapBudget.heightFor(fullWidthPx, pageSize(index).aspectRatio)`, which is how
     * [renderPage] turns a width into a height - so tiles of one page tessellate exactly,
     * and at a width the budget would have allowed they fall on the same pixels a
     * whole-page render did.
     *
     * [fullWidthPx] is the zoomed width and is deliberately not clamped by [BitmapBudget]:
     * no bitmap of that size is ever allocated, which is the whole point. The output bitmap
     * is not clamped either, because the region must fill it exactly or the caller's
     * geometry is a lie; keeping the output down to screen size is the caller's half of
     * that bargain, and tiling is what makes it easy to keep.
     *
     * A [tile] reaching past the page edge is legal, and the overhang comes back white like
     * the rest of the sheet. The rect is read during the call and not retained.
     *
     * @param reuse a bitmap to draw into, on the terms [renderPage] states, except that the
     *   size it must match is simply [outWidthPx] by [outHeightPx].
     * @return the rendered tile, or null from an engine that has no tile path at all, in
     *   which case the caller falls back to [renderPage]. Real failures throw.
     */
    @Throws(PdfException::class)
    fun renderTile(
        index: Int,
        fullWidthPx: Int,
        tile: Rect,
        outWidthPx: Int,
        outHeightPx: Int,
        reuse: Bitmap? = null
    ): Bitmap? = null

    /**
     * Releases the file descriptor and whatever native handle the engine holds.
     *
     * Two things an engine owes the caller here. The descriptor is released even if the
     * engine's own handle refused to close, because a descriptor leaks for the life of the
     * process where a handle is only memory. And a refusal is reported rather than dropped:
     * the one thing that causes it is a close racing a render that is still in flight, and an
     * engine that hides it hides the caller's ordering bug with it.
     */
    override fun close()
}

/** Page dimensions in PostScript points. A4 is 595.3 x 841.9; US Letter is 612 x 792. */
data class PageSize(val widthPoints: Float, val heightPoints: Float) {
    val aspectRatio: Float get() = if (heightPoints <= 0f) 1f else widthPoints / heightPoints
    val isLandscape: Boolean get() = widthPoints > heightPoints
}

/**
 * Declared per engine so the UI can grey out a tool honestly instead of offering it and
 * failing. Every flag here is false for at least one engine Sheaf plans to ship, which is
 * why it exists at all.
 */
data class EngineCapabilities(
    /** Can rasterise pages. */
    val render: Boolean,
    /** Can change the page tree - merge, split, reorder, rotate. */
    val restructure: Boolean,
    /** Can open a password-protected document given the password. */
    val encryptedInput: Boolean,
    /** Can write a new document, rather than only read one. */
    val write: Boolean,
    /** Can read text content and its positions on the page. */
    val textExtraction: Boolean
)

/**
 * Every failure this app's PDF layer can produce. Callers switch on the type; a batch run
 * uses it to decide whether one bad file should be skipped with a reason or the whole job
 * abandoned.
 */
sealed class PdfException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** The file is encrypted and no password, or the wrong one, was supplied. */
    class PasswordRequired(cause: Throwable? = null) :
        PdfException(Strings.get(R.string.engine_err_password_required), cause)

    /** Structurally broken beyond what the engine can recover. */
    class Corrupt(detail: String, cause: Throwable? = null) :
        PdfException(Strings.get(R.string.engine_err_corrupt, detail), cause)

    /** Well-formed, but uses something this particular engine cannot handle. */
    class Unsupported(detail: String, cause: Throwable? = null) :
        PdfException(Strings.get(R.string.engine_err_unsupported, detail), cause)

    /** Ran out of room. Distinct from Corrupt because retrying smaller can work. */
    class OutOfMemory(detail: String, cause: Throwable? = null) :
        PdfException(Strings.get(R.string.engine_err_out_of_memory, detail), cause)

    /** Reading or writing the file itself failed. */
    class Io(detail: String, cause: Throwable? = null) :
        PdfException(Strings.get(R.string.engine_err_io, detail), cause)

    /**
     * The job cannot proceed as asked: nothing was selected, the page is not there, the
     * document is not in the state the operation needs. Nothing is wrong with the file or
     * the disk, so this one carries no prefix - the message is the whole explanation, and
     * the call site writes it as a finished sentence.
     */
    class Refused(message: String, cause: Throwable? = null) :
        PdfException(message, cause)
}
