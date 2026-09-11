package com.layerbit.sheaf.ops

/**
 * Every tool the app offers, and what the home screen needs to draw it.
 *
 * Adding a tool is one entry here and one [Op] implementation. If it ever costs more than
 * that, the seam is wrong and the fix is to widen [Op] or [com.layerbit.sheaf.pdf.PdfSurgeon]
 * rather than to special-case the new tool in the UI.
 */
enum class ToolId(
    val title: String,
    val summary: String,
    val input: InputKind
) {
    MERGE(
        "Merge",
        "Join several PDFs into one, in the order you choose",
        InputKind.SEVERAL_PDFS
    ),
    SPLIT(
        "Split",
        "Break one PDF into several smaller files",
        InputKind.ONE_PDF
    ),
    EXTRACT(
        "Extract pages",
        "Keep only the pages you pick",
        InputKind.ONE_PDF
    ),
    ORGANISE(
        "Organise pages",
        "Reorder, rotate and delete pages",
        InputKind.ONE_PDF
    ),
    IMAGES_TO_PDF(
        "Images to PDF",
        "Turn photos and scans into a document",
        InputKind.IMAGES
    ),
    PDF_TO_IMAGES(
        "PDF to images",
        "Save pages as PNG or JPEG",
        InputKind.ONE_PDF
    ),
    COMPRESS(
        "Compress",
        "Make a PDF smaller for email or upload",
        InputKind.SEVERAL_PDFS
    ),
    SET_PASSWORD(
        "Add a password",
        "Encrypt a PDF so it needs a password to open",
        InputKind.SEVERAL_PDFS
    ),
    REMOVE_PASSWORD(
        "Remove a password",
        "Save an unlocked copy of a PDF you can open",
        InputKind.SEVERAL_PDFS
    );

    enum class InputKind {
        ONE_PDF,
        SEVERAL_PDFS,
        IMAGES
    }

    val acceptsMultiple: Boolean
        get() = input != InputKind.ONE_PDF

    val mimeFilter: Array<String>
        get() = when (input) {
            InputKind.IMAGES -> arrayOf("image/*")
            else -> arrayOf("application/pdf")
        }
}
