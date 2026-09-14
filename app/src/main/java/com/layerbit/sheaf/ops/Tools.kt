package com.layerbit.sheaf.ops

/**
 * Every tool the app offers, and what the home screen needs to draw it.
 *
 * Adding a tool is one entry here and one [Op] implementation. If it ever costs more than
 * that, the seam is wrong and the fix is to widen [Op] or [com.layerbit.sheaf.pdf.PdfSurgeon]
 * rather than to special-case the new tool in the UI.
 *
 * [group] exists because a flat list of a dozen tools stops being a menu and becomes a wall.
 * The groups are by what someone is trying to do, not by which library implements them.
 */
enum class ToolId(
    val title: String,
    val summary: String,
    val input: InputKind,
    val group: Group
) {
    SCAN(
        "Scan a document",
        "Photograph pages and straighten them",
        InputKind.CAMERA,
        Group.READ
    ),
    OCR(
        "Make searchable",
        "Read the text in a scan so you can search and copy it",
        InputKind.SEVERAL_PDFS,
        Group.READ
    ),
    EXTRACT_TEXT(
        "Extract text",
        "Pull the words out of a PDF as plain text",
        InputKind.ONE_PDF,
        Group.READ
    ),

    MERGE(
        "Merge",
        "Join several PDFs into one, in the order you choose",
        InputKind.SEVERAL_PDFS,
        Group.PAGES
    ),
    SPLIT(
        "Split",
        "Break one PDF into several smaller files",
        InputKind.ONE_PDF,
        Group.PAGES
    ),
    EXTRACT(
        "Extract pages",
        "Keep only the pages you pick",
        InputKind.ONE_PDF,
        Group.PAGES
    ),
    ORGANISE(
        "Organise pages",
        "Reorder, rotate and delete pages",
        InputKind.ONE_PDF,
        Group.PAGES
    ),

    IMAGES_TO_PDF(
        "Images to PDF",
        "Turn photos and scans into a document",
        InputKind.IMAGES,
        Group.CONVERT
    ),
    PDF_TO_IMAGES(
        "PDF to images",
        "Save pages as PNG or JPEG",
        InputKind.ONE_PDF,
        Group.CONVERT
    ),
    COMPRESS(
        "Compress",
        "Make a PDF smaller for email or upload",
        InputKind.SEVERAL_PDFS,
        Group.CONVERT
    ),

    WATERMARK(
        "Watermark",
        "Lay DRAFT or a name across every page",
        InputKind.SEVERAL_PDFS,
        Group.MARK
    ),
    PAGE_NUMBERS(
        "Page numbers",
        "Number the pages, Bates style if you need it",
        InputKind.SEVERAL_PDFS,
        Group.MARK
    ),
    SIGN(
        "Sign",
        "Draw your signature and place it on a page",
        InputKind.ONE_PDF,
        Group.MARK
    ),
    ADD_TEXT(
        "Add text",
        "Type onto a page, and cover what is there if you need to",
        InputKind.ONE_PDF,
        Group.MARK
    ),
    CROP(
        "Crop pages",
        "Trim the margins or change the page size",
        InputKind.SEVERAL_PDFS,
        Group.MARK
    ),

    N_UP(
        "Pages per sheet",
        "Put two or four pages on each sheet for printing",
        InputKind.SEVERAL_PDFS,
        Group.CONVERT
    ),
    SPLIT_BY_SIZE(
        "Split by size",
        "Cut a file into parts small enough to email",
        InputKind.ONE_PDF,
        Group.PAGES
    ),
    EXTRACT_IMAGES(
        "Extract images",
        "Pull out the pictures already inside a document",
        InputKind.ONE_PDF,
        Group.CONVERT
    ),

    REDACT(
        "Remove areas",
        "Take something out of a page permanently",
        InputKind.ONE_PDF,
        Group.PROTECT
    ),
    METADATA(
        "Document details",
        "Edit or strip the title, author and hidden details",
        InputKind.SEVERAL_PDFS,
        Group.PROTECT
    ),

    SET_PASSWORD(
        "Add a password",
        "Encrypt a PDF so it needs a password to open",
        InputKind.SEVERAL_PDFS,
        Group.PROTECT
    ),
    REMOVE_PASSWORD(
        "Remove a password",
        "Save an unlocked copy of a PDF you can open",
        InputKind.SEVERAL_PDFS,
        Group.PROTECT
    );

    enum class InputKind {
        ONE_PDF,
        SEVERAL_PDFS,
        IMAGES,

        /** Takes no file at all - it makes one with the camera. */
        CAMERA
    }

    /** How the home screen groups the tools. Order here is the order on screen. */
    enum class Group(val title: String) {
        READ("Read and capture"),
        PAGES("Pages"),
        MARK("Mark up"),
        CONVERT("Convert and print"),
        PROTECT("Protect")
    }

    /**
     * What the button says.
     *
     * A button repeating the screen's own title tells nobody what pressing it does - "Document
     * details" on the Document details screen was the clearest case. Every one of these is a
     * verb.
     */
    val action: String
        get() = when (this) {
            SCAN -> "Scan"
            OCR -> "Make it searchable"
            EXTRACT_TEXT -> "Extract the text"
            MERGE -> "Merge into one file"
            SPLIT -> "Split it up"
            EXTRACT -> "Keep these pages"
            ORGANISE -> "Save the new order"
            IMAGES_TO_PDF -> "Make a PDF"
            PDF_TO_IMAGES -> "Save as images"
            COMPRESS -> "Compress it"
            WATERMARK -> "Add the watermark"
            PAGE_NUMBERS -> "Add the numbers"
            SIGN -> "Place the signature"
            ADD_TEXT -> "Write it on"
            CROP -> "Crop it"
            N_UP -> "Lay it out"
            SPLIT_BY_SIZE -> "Split by size"
            EXTRACT_IMAGES -> "Pull the images out"
            REDACT -> "Remove them for good"
            METADATA -> "Save the details"
            SET_PASSWORD -> "Protect it"
            REMOVE_PASSWORD -> "Unlock it"
        }

    val acceptsMultiple: Boolean
        get() = input == InputKind.SEVERAL_PDFS || input == InputKind.IMAGES

    val mimeFilter: Array<String>
        get() = when (input) {
            InputKind.IMAGES -> arrayOf("image/*")
            else -> arrayOf("application/pdf")
        }
}
