package com.layerbit.sheaf.pdf

import android.graphics.Bitmap
import java.io.File

/**
 * The write side of the PDF layer.
 *
 * [PdfEngine] reads and rasterises; this one restructures and writes.
 *
 * They are separate interfaces rather than one wide one, because they have genuinely different
 * implementations and different costs. The viewer wants the platform renderer, which is fast
 * and allocates little but cannot write anything. Every operation here needs PDFBox, which can
 * write anything but is far heavier to hold a document open in.
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

    /**
     * Writes an AES-256 encrypted copy. [userPassword] is the one needed to open it.
     *
     * With [restrictOnly] the copy opens for anyone and only the permission flags apply: the
     * user password is empty and the owner password is random and thrown away, which is what
     * "restrict printing and copying" has always meant in PDF.
     */
    @Throws(PdfException::class)
    fun setPassword(
        input: PdfInput,
        userPassword: String,
        output: File,
        permissions: DocumentPermissions = DocumentPermissions(),
        restrictOnly: Boolean = false
    )

    /** Writes an unencrypted copy. [input] must carry the correct password. */
    @Throws(PdfException::class)
    fun removePassword(input: PdfInput, output: File)

    /**
     * Writes a copy with an invisible text layer laid over the existing pages.
     *
     * This is what "make searchable" means. The scan still looks exactly as it did, and a
     * reader can now select, copy and find the words - because there is real text sitting at
     * zero opacity in the same places as the ink.
     *
     * The text itself comes from an [OcrEngine], run by the caller. This method does the
     * coordinate conversion and the writing, and knows nothing about how the words were read.
     */
    @Throws(PdfException::class)
    fun addTextLayer(
        input: PdfInput,
        layers: Map<Int, List<TextPlacement>>,
        output: File,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    )

    /** The words already in a document, page by page. Empty for a scan with no text layer. */
    @Throws(PdfException::class)
    fun extractText(input: PdfInput, pages: List<Int>): Map<Int, String>

    /** Pages containing [query], with a snippet around the first match on each. */
    @Throws(PdfException::class)
    fun search(input: PdfInput, query: String): List<SearchHit>

    /** The document's own table of contents, flattened. Empty when it has none. */
    @Throws(PdfException::class)
    fun readOutline(input: PdfInput): List<OutlineEntry>

    // ---- P3: marking up ----

    /** Stamps repeating text across every page. */
    @Throws(PdfException::class)
    fun watermark(input: PdfInput, spec: WatermarkSpec, output: File, onProgress: (Int, Int) -> Unit = { _, _ -> })

    /** Numbers the pages. */
    @Throws(PdfException::class)
    fun addPageNumbers(input: PdfInput, spec: PageNumberSpec, output: File, onProgress: (Int, Int) -> Unit = { _, _ -> })

    /**
     * Trims the visible area of each page, and optionally puts the result on a standard size.
     *
     * Cropping changes the CropBox rather than deleting content: what falls outside stops
     * being displayed or printed but is still in the file. That is how every PDF tool crops,
     * and it is why cropping is NOT a way to hide anything. Use [redact] for that.
     */
    @Throws(PdfException::class)
    fun cropPages(input: PdfInput, spec: CropSpec, output: File, onProgress: (Int, Int) -> Unit = { _, _ -> })

    /**
     * Permanently removes what is inside the marked areas.
     *
     * Genuinely removes it. A black rectangle drawn over text leaves the text in the file,
     * selectable and copyable by anyone who opens it in a different reader - which is how
     * redaction failures reach the news. See the implementation for what this costs.
     */
    @Throws(PdfException::class)
    fun redact(input: PdfInput, areas: Map<Int, List<PageArea>>, dpi: Int, output: File, onProgress: (Int, Int) -> Unit = { _, _ -> })

    /** Draws an image onto one page and flattens it into the content. */
    @Throws(PdfException::class)
    fun stampImage(input: PdfInput, stamp: ImageStamp, output: File)

    /**
     * Writes visible lines of text onto pages.
     *
     * This is as close to editing a PDF as a PDF gets. A document is not a word processor file:
     * there are no paragraphs to retype, only glyphs at coordinates. What can honestly be
     * offered is putting new text at a chosen spot, and - with [TextNote.cover] - laying a patch
     * of paper over what was there first, which together is how a wrong date or a blank field
     * gets fixed.
     *
     * Flattened into the page content rather than added as a form field or an annotation, for
     * the same reason [stampImage] is: an annotation is something the next reader can drag away.
     */
    @Throws(PdfException::class)
    fun addText(input: PdfInput, notes: List<TextNote>, output: File)

    /**
     * Draws the reader's own marks into the pages.
     *
     * A highlight is a translucent block of colour laid over the text, and a pen stroke is a
     * line. Both are drawn into the page content rather than added as PDF annotation objects,
     * which is the same decision [stampImage] makes for a signature and for the same reason:
     * an annotation is something the next reader's app can move, hide or delete, and a mark
     * somebody made on a document should still be there when it is opened somewhere else.
     *
     * The cost is that marks cannot be peeled off again afterwards. The UI says so, and the
     * original file is untouched regardless - every operation here writes a new document.
     */
    @Throws(PdfException::class)
    fun annotate(
        input: PdfInput,
        marks: Map<Int, List<PageMark>>,
        output: File,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    )

    /**
     * Lays a line of text along the top or the bottom of every page.
     *
     * The same machinery as page numbers, with the numbering taken out and placeholders put
     * in, because "Confidential - page 3 of 40 - 2 October" is one stamp rather than three
     * tools. See [StampSpec] for what can go in the text.
     */
    @Throws(PdfException::class)
    fun stampHeaderFooter(
        input: PdfInput,
        spec: StampSpec,
        output: File,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    )

    /**
     * The fillable fields in a document, in the order the form declares them.
     *
     * Empty for the overwhelming majority of PDFs: a form has to have been built as one. A
     * scan of a printed form has no fields, which is why the UI says so rather than showing
     * an empty list - and why Add text exists for that case.
     */
    @Throws(PdfException::class)
    fun readFormFields(input: PdfInput): List<FormField>

    /**
     * Fills a form and writes a copy.
     *
     * @param values keyed by [FormField.name]. A field not in the map is left as it was.
     * @param flatten draws the values into the page and removes the fields, which is what
     *   makes a filled form behave like a signed piece of paper rather than a document the
     *   next person can retype. Irreversible by design.
     */
    @Throws(PdfException::class)
    fun fillForm(input: PdfInput, values: Map<String, String>, flatten: Boolean, output: File)

    /**
     * Puts pages into a document at a chosen point.
     *
     * Either the pages of [insert], or [blankPages] empty sheets the size of the first page.
     * The two are one operation because they are the same question - what goes at page
     * twelve - and splitting them would mean two tools that each do half of it.
     */
    @Throws(PdfException::class)
    fun insertPages(
        input: PdfInput,
        insert: PdfInput?,
        blankPages: Int,
        atIndex: Int,
        output: File,
        /** Pages to take out, with the new ones going where the first of them was. */
        replace: List<Int> = emptyList()
    )

    // ---- P4: power tools ----

    /**
     * Places several source pages onto each output sheet.
     *
     * The sheet is turned whichever way shows the pages biggest: upright pages go side by
     * side on a sideways sheet, wide slides one above the other. Pages run across, then down.
     */
    @Throws(PdfException::class)
    fun nUp(
        input: PdfInput,
        perSheet: Int,
        paper: PageSpec.Size,
        output: File,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    )

    /**
     * Splits so each part stays under [maxBytes], by measuring.
     *
     * Saving is the only honest way to know a part's size: shared fonts and images are
     * copied into each part, so pages do not add up. Each part is found by doubling its
     * length until it is too big, then halving back, so a 300-page file needs a few dozen
     * trial saves rather than 300.
     *
     * @return the page groups. Writing them is the caller's job, through [extractPages].
     */
    @Throws(PdfException::class)
    fun measureSizeSplit(
        input: PdfInput,
        maxBytes: Long,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): List<List<Int>>

    /** Pulls out the embedded images at their original resolution. */
    @Throws(PdfException::class)
    fun extractImages(input: PdfInput, outputDir: File, baseName: String, onProgress: (Int, Int) -> Unit = { _, _ -> }): List<File>

    /** Rewrites the document information dictionary. */
    @Throws(PdfException::class)
    fun writeMetadata(input: PdfInput, metadata: DocumentMetadata, output: File)

    // ---- P5: the Foolscap set ----

    /**
     * Takes out what a file gives away: the details, hidden XMP copies of them, scripts and
     * the actions that run when it opens, attached files and comments - each only when
     * [spec] asks. Reports what was found so the result can say so.
     */
    @Throws(PdfException::class)
    fun cleanDocument(input: PdfInput, spec: CleanSpec, output: File): CleanReport

    /**
     * Sets what a reader calls each page: i, ii, iii for the front matter, then 1, 2, 3.
     *
     * Labels change what a reader shows in its page box, not what is printed on the page. A
     * null spec removes them, so readers go back to plain numbers from the cover.
     */
    @Throws(PdfException::class)
    fun setPageLabels(input: PdfInput, spec: PageLabelSpec?, output: File)

    /**
     * Gives pages a background colour, behind everything already on them.
     *
     * Drawn under the existing content rather than over it, so text and drawings stay on
     * top. A scanned page is one solid picture, so the colour will not show through it; the
     * UI says so.
     */
    @Throws(PdfException::class)
    fun setBackground(
        input: PdfInput,
        colour: Int,
        pages: List<Int>,
        output: File,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    )

    /** The files a document carries inside it, by name. */
    @Throws(PdfException::class)
    fun readAttachments(input: PdfInput): List<AttachmentInfo>

    /**
     * Puts files inside a PDF, where they travel with it.
     *
     * Carried byte for byte and never opened. A name already in use gets a number, so a
     * reader's attachment list can show both.
     */
    @Throws(PdfException::class)
    fun attachFiles(input: PdfInput, files: List<Pair<String, File>>, output: File)

    /**
     * Where [pattern] matches in the text, by page index, as fractions of the page the reader
     * sees. [accept] can turn a match down after the fact - a run of digits that is not a card
     * number by its check digit, say - which a regular expression cannot do on its own.
     */
    @Throws(PdfException::class)
    fun findAreas(input: PdfInput, pattern: Regex, accept: (String) -> Boolean = { true }): Map<Int, List<PageArea>>

    /** Every word on the pages with where it sits, in reading order, for comparing. */
    @Throws(PdfException::class)
    fun placedWords(input: PdfInput, onProgress: (Int, Int) -> Unit = { _, _ -> }): List<PlacedWord>

    /**
     * Covers each match of [pattern] and writes [replacement] in its place, in Helvetica
     * sized to the old words. Returns how many were changed.
     *
     * The nearest thing to editing a PDF that a PDF honestly allows: a document holds glyphs
     * at coordinates rather than paragraphs, so the line is patched, not reflowed.
     */
    @Throws(PdfException::class)
    fun replaceText(input: PdfInput, pattern: Regex, replacement: String, output: File, onProgress: (Int, Int) -> Unit = { _, _ -> }): Int

    /**
     * Writes receipts from nothing: a title, a line under it, a table of labelled rows and
     * some closing lines, two to an A4 page. Returns the page count.
     */
    @Throws(PdfException::class)
    fun writeReceipts(receipts: List<ReceiptSpec>, output: File): Int

    /**
     * The lines of text on every page, with their size, weight and place, for turning a
     * document back into paragraphs. A scanned page gives an empty list.
     */
    @Throws(PdfException::class)
    fun pageLines(input: PdfInput, onProgress: (Int, Int) -> Unit = { _, _ -> }): List<List<TextLine>>

    /**
     * Lays a [FlowDocument] out on A4 pages: headings, paragraphs, lists, tables, pictures,
     * in the phone's own fonts where Helvetica cannot set the text. Returns the page count.
     */
    @Throws(PdfException::class)
    fun layoutFlow(document: FlowDocument, output: File, onProgress: (Int, Int) -> Unit = { _, _ -> }): Int

    /**
     * Makes an archive copy in the PDF/A-2b manner: an sRGB profile, XMP that says so and
     * mirrors the details, a file ID, printable annotations, no scripts, attachments or
     * password. Reports what it took out and how many fonts are not inside the file.
     */
    @Throws(PdfException::class)
    fun makeArchival(input: PdfInput, output: File): ArchivalReport

    /** Puts fillable fields on the pages. Returns the names they were given. */
    @Throws(PdfException::class)
    fun addFormFields(input: PdfInput, fields: List<NewField>, output: File): List<String>

    /**
     * Signs a document with a .pfx or .p12 certificate, the way a reader's signature panel
     * checks. A locked document is signed unlocked: a lock would have to be redone around
     * the signature, which breaks it.
     */
    @Throws(PdfException::class)
    fun signWithCertificate(input: PdfInput, certificate: File, password: String, spec: SignSpec, output: File): SignReport

    /** The comments and notes on the pages, in page order, as a reader lists them. */
    @Throws(PdfException::class)
    fun readComments(input: PdfInput): List<Comment>

    /** Writes every attached file out into [outputDir], as it was. */
    @Throws(PdfException::class)
    fun extractAttachments(input: PdfInput, outputDir: File): List<File>

    /**
     * Replaces the document's own bookmarks (its outline) with [entries].
     *
     * Nesting follows [OutlineEntry.depth]. An empty list takes every bookmark out.
     */
    @Throws(PdfException::class)
    fun writeOutline(input: PdfInput, entries: List<OutlineEntry>, output: File)

    /** The pictures page [pageIndex] draws itself, as fractions of the page the reader sees. */
    @Throws(PdfException::class)
    fun findPictures(input: PdfInput, pageIndex: Int): List<PageArea>

    /**
     * Moves, resizes, takes out or swaps pictures already on pages, keyed by page index. Each
     * edit names the picture by where it was; one that cannot be matched is counted as missed.
     */
    @Throws(PdfException::class)
    fun editPictures(input: PdfInput, edits: Map<Int, List<PictureEdit>>, output: File): PictureReport

    /** The lines Edit text can change on each of [pages]. A scan's page says it is one and offers none. */
    @Throws(PdfException::class)
    fun editableLines(input: PdfInput, pages: List<Int>): Map<Int, EditablePage>

    /** Paints over old words and writes new ones. Returns how many changes were made. */
    @Throws(PdfException::class)
    fun editText(input: PdfInput, edits: List<TextEdit>, output: File): Int
}

/** What [PdfSurgeon.cleanDocument] takes out. Everything on by default. */
data class CleanSpec(
    val metadata: Boolean = true,
    val scripts: Boolean = true,
    val attachments: Boolean = true,
    val comments: Boolean = true
)

/** What a clean found and removed, so the result can list it. */
data class CleanReport(
    val details: Int = 0,
    val xmp: Int = 0,
    val scripts: Int = 0,
    val attachments: Int = 0,
    val comments: Int = 0,
    val thumbnails: Int = 0,
    val privateData: Int = 0
) {
    val total: Int get() = details + xmp + scripts + attachments + comments + thumbnails + privateData
}

/** How pages are labelled: a run of front pages in one style, then the rest in another. */
data class PageLabelSpec(
    /** How many pages at the front are numbered i, ii, iii (a cover, contents). 0 for none. */
    val frontPages: Int = 0,
    val frontStyle: Style = Style.ROMAN_LOWER,
    val style: Style = Style.DECIMAL,
    /** What the main numbers start at. */
    val startAt: Int = 1,
    /** What comes before each main number: "A-". */
    val prefix: String = ""
) {
    enum class Style(val label: String) {
        DECIMAL("1, 2, 3"),
        ROMAN_UPPER("I, II, III"),
        ROMAN_LOWER("i, ii, iii"),
        ALPHA_UPPER("A, B, C"),
        ALPHA_LOWER("a, b, c")
    }
}

/** A file carried inside a document. */
data class AttachmentInfo(val name: String, val sizeBytes: Long)

/** A field to put on a page: what kind, what it is called, and where. */
data class NewField(
    val kind: Kind,
    val name: String,
    val pageIndex: Int,
    val area: PageArea,
    /** For a drop-down: what can be picked. */
    val options: List<String> = emptyList()
) {
    enum class Kind(val label: String) {
        TEXT("Text box"),
        MULTILINE("Paragraph box"),
        CHECK("Tick box"),
        CHOICE("Drop-down list")
    }
}

/** A word and where it sits on its page, as fractions of the page the reader sees. */
data class PlacedWord(val text: String, val pageIndex: Int, val area: PageArea)

/** One receipt to lay out. Generic on purpose: the words are the op's, the layout is here. */
data class ReceiptSpec(
    val title: String,
    val subtitle: String,
    /** Label and value, one row each. */
    val rows: List<Pair<String, String>>,
    /** Lines under the table: a signature line, a note. */
    val footer: List<String>
)

/** A comment somebody left on a page: a sticky note, a highlight with a remark, a stamp. */
data class Comment(
    val pageIndex: Int,
    /** What kind, in words: "Note", "Highlight", "Stamp". */
    val kind: String,
    val author: String?,
    val text: String,
    /** When, as the file wrote it, tidied to "2024-03-05 14:20" where it can be. */
    val date: String?
)

/**
 * Text laid across a page.
 *
 * The size is worked out per page rather than given: DRAFT and CONFIDENTIAL should both cross
 * the page, and the same point size on an ID card and on a poster would be wrong on both.
 */
data class WatermarkSpec(
    val text: String,
    /** 0 to 1. Below about 0.5 stays readable underneath. */
    val opacity: Float = 0.22f,
    val layout: Layout = Layout.ONCE_DIAGONAL,
    /** Packed ARGB. Grey unless asked: red for VOID or COPY, blue for a company name. */
    val colour: Int = 0xFF6B6B75.toInt(),
    /** Zero-based page indices; empty means every page. */
    val pages: List<Int> = emptyList()
) {
    enum class Layout(val label: String) {
        ONCE_DIAGONAL("Once, diagonally"),
        STRAIGHT("Once, straight across"),
        TILED("Repeated all over")
    }
}

data class PageNumberSpec(
    val position: Position = Position.BOTTOM_CENTRE,
    /** "{n}" becomes the number, "{total}" the page count. */
    val format: String = "{n}",
    /** What the first numbered page is called. */
    val startAt: Int = 1,
    /** Pages before this are left unnumbered - a cover, a title page. */
    val skipFirst: Int = 0,
    val fontSize: Float = 10f,
    /** Zero-pads to this width, for Bates numbering. Zero means no padding. */
    val padTo: Int = 0,
    /** What comes before a Bates number: "ACME-". */
    val prefix: String = ""
) {
    enum class Position { TOP_LEFT, TOP_CENTRE, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_CENTRE, BOTTOM_RIGHT }
}

/**
 * How to crop.
 *
 * Millimetres off each edge AS THE READER SEES THE PAGE, whatever way round it is stored; or
 * [auto], which trims each page to just outside its words and pictures; and optionally every
 * page put on one paper size afterwards.
 */
data class CropSpec(
    val topMm: Float = 0f,
    val rightMm: Float = 0f,
    val bottomMm: Float = 0f,
    val leftMm: Float = 0f,
    /** Trim to the ink: the white margins go, with two millimetres of room left round it. */
    val auto: Boolean = false,
    /** Put every page on this size afterwards, scaling to fit. Null leaves sizes alone. */
    val resizeTo: PageSpec.Size? = null,
    /** With [resizeTo]: false leaves a page smaller than the paper at its real size. */
    val enlarge: Boolean = true,
    /** Zero-based page indices; empty means every page. */
    val pages: List<Int> = emptyList()
) {
    val trims: Boolean get() = topMm > 0f || rightMm > 0f || bottomMm > 0f || leftMm > 0f
    val doesAnything: Boolean get() = trims || auto || resizeTo != null
}

/** A rectangle on a page, normalised to the page box, origin top-left, 0 to 1. */
data class PageArea(val left: Float, val top: Float, val width: Float, val height: Float)

/** An image to place on one page. */
data class ImageStamp(
    val image: File,
    val pageIndex: Int,
    val anchor: Anchor,
    /** Width as a fraction of the page width. Height follows the image's own proportions. */
    val widthFraction: Float = 0.3f,
    val marginFraction: Float = 0.06f
) {
    enum class Anchor {
        TOP_LEFT, TOP_CENTRE, TOP_RIGHT,
        MIDDLE_LEFT, MIDDLE_CENTRE, MIDDLE_RIGHT,
        BOTTOM_LEFT, BOTTOM_CENTRE, BOTTOM_RIGHT
    }
}

data class DocumentMetadata(
    val title: String? = null,
    val author: String? = null,
    val subject: String? = null,
    val keywords: String? = null,
    /** Clears everything, including the producer and the creation dates. */
    val stripAll: Boolean = false
)

/**
 * A line of text to place on a page, positioned relative to the page box.
 *
 * NORMALISED, ORIGIN TOP-LEFT, 0 TO 1. Deliberately not pixels and not PDF points. The caller
 * works in the pixel space of whatever it rendered; PDF works in points from the bottom-left.
 * Having each caller convert would mean the vertical flip is written in several places, so it
 * is written once, in the implementation of [PdfSurgeon.addTextLayer].
 */
data class TextPlacement(
    val text: String,
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float
)

/**
 * A line - or a few lines - of visible text to lay on one page.
 *
 * NORMALISED, ORIGIN TOP-LEFT, like every other position in this file. [left] and [top] mark
 * the top-left corner of the first line, which is where the tap that placed it landed.
 *
 * [sizePoints] is in PDF points rather than anything screen-shaped, because that is what the
 * document is measured in: 12 points here is 12 points beside the text already on the page.
 */
data class TextNote(
    val pageIndex: Int,
    val text: String,
    val left: Float,
    val top: Float,
    val sizePoints: Float = 12f,
    /** Packed ARGB, so the UI can hand over a colour without a PDF type of its own. */
    val colour: Int = 0xFF000000.toInt(),
    /** Paints paper under the line first, so the note can replace what is already there. */
    val cover: Boolean = false
)

/**
 * What a reader may do with an encrypted document.
 *
 * Every flag defaults to allowed, which is what an encrypted document meant before this
 * existed. Turning one off is a request to the reader, not a lock: the PDF specification says
 * a conforming reader should honour these, and the bits are inside a file the recipient's
 * password opens. Sheaf says that in the UI rather than implying the document is enforcing
 * anything.
 *
 * Extraction for accessibility is deliberately not offered and never disabled. Turning it off
 * is how a document stops working with a screen reader, and no legitimate use of this app
 * needs that.
 */
data class DocumentPermissions(
    val allowPrinting: Boolean = true,
    val allowCopying: Boolean = true,
    val allowChanges: Boolean = true
)

/**
 * A line along the top or the bottom of every page.
 *
 * Four placeholders are substituted as each page is stamped:
 *  - `{page}` the page's own number
 *  - `{total}` how many pages there are
 *  - `{date}` today, as the device formats a medium date
 *  - `{name}` the document's name, without the extension
 */
data class StampSpec(
    val headerText: String = "",
    val footerText: String = "",
    val align: Align = Align.CENTRE,
    val fontSize: Float = 9f,
    /** Leaves a cover page unstamped, which is the usual reason to want it. */
    val skipFirst: Int = 0,
    val marginPoints: Float = 28f,
    /** Resolves `{name}`. Passed in because the engine does not know what a file is called. */
    val documentName: String = ""
) {
    enum class Align { LEFT, CENTRE, RIGHT }
}

/**
 * One fillable field in a form.
 *
 * [name] is the fully qualified name the PDF uses and is what [PdfSurgeon.fillForm] takes;
 * [label] is the human one the form's author wrote, when they wrote one. They are different
 * strings surprisingly often - a field called "topmostSubform[0].Page1[0].f1_01[0]" is
 * labelled "Your first name".
 */
data class FormField(
    val name: String,
    val label: String?,
    val value: String,
    val kind: Kind,
    /** For a tick box, the value that means ticked. For a chooser, what can be chosen. */
    val options: List<String> = emptyList(),
    val readOnly: Boolean = false
) {
    enum class Kind { TEXT, TICK, CHOICE }
}

/**
 * A mark the reader drew on a page.
 *
 * Normalised to the page, origin top-left, like every other position in this file - the
 * canvas works in the pixels of whatever it rendered and the page is in points, and the
 * conversion is written once, inside the implementation.
 */
sealed interface PageMark {

    /** Packed ARGB. The alpha in it is ignored; a highlight has its own. */
    val colour: Int

    /** A block of colour over the words, the way a highlighter pen works. */
    data class Highlight(val area: PageArea, override val colour: Int) : PageMark

    /** A line drawn with a finger. Two points or more, or there is nothing to draw. */
    data class Ink(
        val points: List<PagePoint>,
        override val colour: Int,
        /** Line width in PDF points, so it is the same thickness on A4 as on a photo. */
        val widthPoints: Float = 2f
    ) : PageMark

    /** A mark on a dragged-out area: a rule under it, a line through it, a frame round it. */
    data class Shape(
        val kind: Kind,
        val area: PageArea,
        override val colour: Int,
        val widthPoints: Float = 1.5f
    ) : PageMark {
        enum class Kind { UNDERLINE, STRIKETHROUGH, BOX, CIRCLE }
    }

    /** A straight line between two points, with an arrowhead at the end if asked. */
    data class Line(
        val from: PagePoint,
        val to: PagePoint,
        override val colour: Int,
        val widthPoints: Float = 1.5f,
        val arrow: Boolean = false
    ) : PageMark

    /** Words at a point: a note on a pale yellow card, in the size given. */
    data class Note(
        val at: PagePoint,
        val text: String,
        override val colour: Int,
        val sizePoints: Float = 10f
    ) : PageMark

    /** A word in a frame, turned a little, the way a rubber stamp lands: APPROVED, PAID. */
    data class Stamp(
        val at: PagePoint,
        val text: String,
        override val colour: Int
    ) : PageMark
}

/** A point on a page, normalised, origin top-left. */
data class PagePoint(val x: Float, val y: Float)

/** A page that matched a search, and enough context to recognise it. */
data class SearchHit(
    val pageIndex: Int,
    val snippet: String,
    val matchCount: Int,
    /** Where each match sits on the page, so the viewer can draw a box round it. */
    val areas: List<PageArea> = emptyList()
)

/** One entry from the document's outline. */
data class OutlineEntry(
    val title: String,
    val pageIndex: Int,
    /** Nesting depth, zero for a top-level entry. */
    val depth: Int
)

/** A document to operate on, and the password to open it with if it has one. */
data class PdfInput(val file: File, val password: String? = null)

data class DocumentInfo(
    val pageCount: Int,
    val pageSizes: List<PageSize>,
    val isEncrypted: Boolean,
    val title: String?,
    val author: String?,
    val subject: String? = null,
    val keywords: String? = null,
    /** The app that wrote the file. Often a scanner's make and model. */
    val producer: String? = null
)

/** Page geometry for a document being built from images. */
data class PageSpec(
    val size: Size,
    /** Margin in PostScript points. 36 is half an inch. */
    val marginPoints: Float = 36f,
    /** When true, a landscape image gets a landscape page rather than being shrunk to fit. */
    val matchImageOrientation: Boolean = true
) {
    enum class Size(val widthPoints: Float, val heightPoints: Float, val label: String) {
        /** 210 x 297 mm. */
        A4(595.28f, 841.89f, "A4"),

        /** 8.5 x 11 in. */
        LETTER(612f, 792f, "Letter"),

        /** 8.5 x 14 in. */
        LEGAL(612f, 1008f, "Legal"),

        /** 297 x 420 mm. */
        A3(841.89f, 1190.55f, "A3"),

        /** 148 x 210 mm. */
        A5(419.53f, 595.28f, "A5"),

        /** The page takes the image's own proportions - no borders, nothing cropped. */
        FIT_IMAGE(0f, 0f, "Fit the image")
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
