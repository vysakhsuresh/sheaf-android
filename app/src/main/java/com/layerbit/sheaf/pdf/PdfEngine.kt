package com.layerbit.sheaf.pdf

import android.graphics.Bitmap
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
        PdfException("This document is password-protected.", cause)

    /** Structurally broken beyond what the engine can recover. */
    class Corrupt(detail: String, cause: Throwable? = null) :
        PdfException("This file could not be read as a PDF: $detail", cause)

    /** Well-formed, but uses something this particular engine cannot handle. */
    class Unsupported(detail: String, cause: Throwable? = null) :
        PdfException("This document uses a feature Sheaf cannot handle yet: $detail", cause)

    /** Ran out of room. Distinct from Corrupt because retrying smaller can work. */
    class OutOfMemory(detail: String, cause: Throwable? = null) :
        PdfException("This document is too large to process on this device: $detail", cause)

    /** Reading or writing the file itself failed. */
    class Io(detail: String, cause: Throwable? = null) :
        PdfException("Could not read or write the file: $detail", cause)
}
