package com.layerbit.sheaf.pdf

import android.graphics.Bitmap
import java.io.File

/**
 * The write side of the PDF layer.
 *
 * [PdfEngine] reads and rasterises; this one restructures and writes. They are separate
 * interfaces rather than one wide one because they have genuinely different implementations
 * and different costs: the viewer wants the platform renderer, which is fast and allocates
 * little but cannot write anything, while every operation here needs PDFBox, which can write
 * anything but is far heavier to hold a document open in.
 *
 * Same rule as [PdfEngine]: no type from a PDF library crosses out of the `pdf` package. Every
 * method takes and returns files and plain data.
 *
 * All of these are blocking and belong on Dispatchers.IO. They throw [PdfException] and
 * nothing else.
 */
interface PdfSurgeon {

    /** Page count, page sizes and metadata, without holding the document open afterward. */
    @Throws(PdfException::class)
    fun inspect(input: PdfInput): DocumentInfo

    /** Concatenates [inputs] in order into [output]. */
    @Throws(PdfException::class)
    fun merge(inputs: List<PdfInput>, output: File, onProgress: (Int, Int) -> Unit = { _, _ -> })

    /**
     * Writes a new document containing only [pages], in the order given.
     *
     * The order is honored, so this is also how reordering works - hand it every page in the
     * order you want them. Deleting is the same call with the unwanted pages left out, which
     * is why there is no separate delete: fewer paths, fewer ways to corrupt a page tree.
     */
    @Throws(PdfException::class)
    fun extractPages(
        input: PdfInput,
        pages: List<Int>,
        rotations: Map<Int, Int> = emptyMap(),
        output: File
    )

    /** Rotates [pages] by [degrees] relative to their current rotation. Leaves others alone. */
    @Throws(PdfException::class)
    fun rotate(input: PdfInput, pages: Collection<Int>, degrees: Int, output: File)

    /** Builds a PDF from images, one image per page. */
    @Throws(PdfException::class)
    fun imagesToPdf(
        images: List<File>,
        spec: PageSpec,
        output: File,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    )

    /**
     * Rasterises pages to image files in [outputDir].
     *
     * @return the files written, in page order.
     */
    @Throws(PdfException::class)
    fun pdfToImages(
        input: PdfInput,
        pages: List<Int>,
        dpi: Int,
        format: ImageFormat,
        outputDir: File,
        baseName: String,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): List<File>

    /** Re-encodes embedded images downward. See [CompressionLevel]. */
    @Throws(PdfException::class)
    fun compress(
        input: PdfInput,
        level: CompressionLevel,
        output: File,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): CompressionResult

    /** Writes an AES-256 encrypted copy. [userPassword] is the one needed to open it. */
    @Throws(PdfException::class)
    fun setPassword(input: PdfInput, userPassword: String, output: File)

    /** Writes an unencrypted copy. [input] must carry the correct password. */
    @Throws(PdfException::class)
    fun removePassword(input: PdfInput, output: File)
}

/** A document to operate on, and the password to open it with if it has one. */
data class PdfInput(val file: File, val password: String? = null)

data class DocumentInfo(
    val pageCount: Int,
    val pageSizes: List<PageSize>,
    val isEncrypted: Boolean,
    val title: String?,
    val author: String?
)

/** Page geometry for a document being built from images. */
data class PageSpec(
    val size: Size,
    /** Margin in PostScript points. 36 is half an inch. */
    val marginPoints: Float = 36f,
    /** When true, a landscape image gets a landscape page rather than being shrunk to fit. */
    val matchImageOrientation: Boolean = true
) {
    enum class Size(val widthPoints: Float, val heightPoints: Float) {
        /** 210 x 297 mm. */
        A4(595.28f, 841.89f),

        /** 8.5 x 11 in. */
        LETTER(612f, 792f),

        /** The page takes the image's own proportions - no borders, nothing cropped. */
        FIT_IMAGE(0f, 0f)
    }
}

enum class ImageFormat(val extension: String, val mimeType: String) {
    PNG("png", "image/png"),
    JPEG("jpg", "image/jpeg")
}

/**
 * How hard to squeeze.
 *
 * Compression here means re-encoding the images inside the document, because in practice that
 * is where essentially all the bytes in a large PDF are - a scanned page is a photograph with
 * a PDF wrapper. Text and vector content is left untouched, so text stays selectable and sharp
 * at any zoom. A text-only document will barely shrink, and the result screen says so rather
 * than letting the user wonder why nothing happened.
 */
enum class CompressionLevel(val maxDimension: Int, val jpegQuality: Int, val label: String) {
    /** Visually indistinguishable on screen. Safe for anything you still need to read. */
    LIGHT(2400, 85, "Light"),

    /** Noticeably smaller. Fine for email and for sharing. */
    BALANCED(1600, 72, "Balanced"),

    /** Smallest. Photographs will show artefacts; scanned text stays legible. */
    STRONG(1100, 58, "Strong")
}

data class CompressionResult(
    val originalBytes: Long,
    val compressedBytes: Long,
    val imagesRecompressed: Int,
    val imagesSkipped: Int
) {
    val savedBytes: Long get() = (originalBytes - compressedBytes).coerceAtLeast(0)
    val savedFraction: Float
        get() = if (originalBytes <= 0) 0f else savedBytes.toFloat() / originalBytes

    /**
     * True when squeezing achieved essentially nothing, which happens on text-only documents
     * and on ones already compressed. The UI says so plainly instead of presenting a result
     * that looks like a failure.
     */
    val negligible: Boolean get() = savedFraction < 0.03f
}

/** Rendered page plus the index it came from, for previews. */
data class RenderedPage(val index: Int, val bitmap: Bitmap)
