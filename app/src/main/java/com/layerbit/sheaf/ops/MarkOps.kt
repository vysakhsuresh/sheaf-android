package com.layerbit.sheaf.ops

import androidx.annotation.StringRes
import com.layerbit.sheaf.R
import com.layerbit.sheaf.files.SheafFile
import com.layerbit.sheaf.pdf.CropSpec
import com.layerbit.sheaf.pdf.ImageStamp
import com.layerbit.sheaf.pdf.PageArea
import com.layerbit.sheaf.pdf.PageMark
import com.layerbit.sheaf.pdf.PageSelection
import com.layerbit.sheaf.pdf.PageNumberSpec
import com.layerbit.sheaf.pdf.PdfException
import com.layerbit.sheaf.pdf.PdfInput
import com.layerbit.sheaf.pdf.StampSpec
import com.layerbit.sheaf.pdf.TextNote
import com.layerbit.sheaf.pdf.WatermarkSpec
import com.layerbit.sheaf.text.Strings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Lays text across the pages - DRAFT, CONFIDENTIAL, a name - once or all over. */
class WatermarkOp(private val spec: WatermarkSpec, private val pageSpec: String = "") : Op {

    override val tool = ToolId.WATERMARK
    override val title = Strings.get(R.string.engine_title_watermark, spec.text)
    override val arity = Op.Arity.EachIndependently

    override suspend fun run(
        inputs: List<SheafFile>,
        context: OpContext,
        onProgress: (Progress) -> Unit
    ): List<OpOutcome> = withContext(Dispatchers.IO) {
        if (spec.text.isBlank()) {
            return@withContext listOf(OpOutcome.Failed("", Strings.get(R.string.engine_watermark_no_text)))
        }
        eachFile(inputs, context, onProgress, "watermarked", R.string.engine_progress_watermark) { input, pdfInput, output ->
            val total = context.surgeon.inspect(pdfInput).pageCount
            val pages = if (pageSpec.isBlank()) emptyList() else PageSelection.parse(pageSpec, total)
            if (pageSpec.isNotBlank() && pages.isEmpty()) {
                throw PdfException.Io(Strings.plural(R.plurals.engine_pages_typed_not_in_file, total))
            }
            var done = 0
            context.surgeon.watermark(pdfInput, spec.copy(pages = pages), output) { _, count ->
                context.checkCancelled(); done = count
            }
            val which = describeWhich(pages, total)
            val how = if (spec.layout == WatermarkSpec.Layout.TILED) R.string.engine_watermark_detail_tiled else R.string.engine_watermark_detail
            "${input.baseName} watermarked.pdf" to Strings.get(how, spec.text, which)
        }
    }
}

/**
 * Numbers the pages, including Bates numbering for legal bundles.
 *
 * A Bates run continues across the files given: a bundle of three exhibits numbered
 * ACME-000001 onwards has to come out as one unbroken sequence, which is the point of it.
 * Plain page numbers start again in each file, because each file is its own document.
 */
class PageNumberOp(private val spec: PageNumberSpec, private val continueAcrossFiles: Boolean = false) : Op {

    override val tool = ToolId.PAGE_NUMBERS
    override val title = Strings.get(if (spec.padTo > 0) R.string.engine_title_bates else R.string.engine_title_page_numbers)
    override val arity = Op.Arity.EachIndependently

    /** A Bates run is one sequence across the files, so it has to see them all in one call. */
    override val runsFileByFile get() = !continueAcrossFiles

    override suspend fun run(
        inputs: List<SheafFile>,
        context: OpContext,
        onProgress: (Progress) -> Unit
    ): List<OpOutcome> = withContext(Dispatchers.IO) {
        var next = spec.startAt
        eachFile(inputs, context, onProgress, "numbered", R.string.engine_progress_numbering) { input, pdfInput, output ->
            val from = if (continueAcrossFiles) next else spec.startAt
            var pages = 0
            context.surgeon.addPageNumbers(pdfInput, spec.copy(startAt = from), output) { _, total ->
                context.checkCancelled(); pages = total
            }
            val numbered = (pages - spec.skipFirst).coerceAtLeast(0)
            next = from + numbered
            val first = spec.prefix + (if (spec.padTo > 0) from.toString().padStart(spec.padTo, '0') else from.toString())
            val last = spec.prefix + (if (spec.padTo > 0) (from + numbered - 1).toString().padStart(spec.padTo, '0') else (from + numbered - 1).toString())
            "${input.baseName} numbered.pdf" to
                if (numbered <= 0) Strings.get(R.string.engine_numbered_none) else Strings.plural(R.plurals.engine_numbered_detail, pages, first, last, numbered, pages)
        }
    }
}

/**
 * Trims the margins, and optionally puts every page on one standard size.
 *
 * Cropping hides; it does not remove. What falls outside the new edge is still in the file and
 * comes back if someone crops it the other way. That is how PDF cropping works everywhere, and
 * it is why this is not a way to take something out of a document - [RedactOp] is.
 */
class CropOp(private val spec: CropSpec, private val pageSpec: String = "") : Op {

    override val tool = ToolId.CROP
    override val title = Strings.get(if (spec.auto) R.string.engine_title_remove_margins else R.string.engine_title_crop)
    override val arity = Op.Arity.EachIndependently

    override suspend fun run(
        inputs: List<SheafFile>,
        context: OpContext,
        onProgress: (Progress) -> Unit
    ): List<OpOutcome> = withContext(Dispatchers.IO) {
        if (!spec.doesAnything) {
            return@withContext listOf(OpOutcome.Failed("", Strings.get(R.string.engine_crop_nothing)))
        }
        eachFile(inputs, context, onProgress, "cropped", R.string.engine_progress_cropping) { input, pdfInput, output ->
            val total = context.surgeon.inspect(pdfInput).pageCount
            val pages = if (pageSpec.isBlank()) emptyList() else PageSelection.parse(pageSpec, total)
            if (pageSpec.isNotBlank() && pages.isEmpty()) {
                throw PdfException.Io(Strings.plural(R.plurals.engine_pages_typed_not_in_file, total))
            }
            context.surgeon.cropPages(pdfInput, spec.copy(pages = pages), output) { _, _ ->
                context.checkCancelled()
            }
            val which = describeWhich(pages, total)
            val paper = spec.resizeTo?.label
            var detail = when {
                spec.auto -> Strings.get(R.string.engine_crop_auto, which)
                spec.trims -> Strings.get(R.string.engine_crop_trimmed, spec.describeTrims(), which)
                else -> which.replaceFirstChar { it.uppercase() }
            }
            if (paper != null) {
                val put = if (spec.auto || spec.trims) R.string.engine_crop_then_paper else R.string.engine_crop_on_paper
                detail = Strings.get(put, detail, paper)
                if (!spec.enlarge) detail = Strings.get(R.string.engine_crop_real_size, detail)
            }
            val name = if (paper != null && !spec.auto && !spec.trims) {
                "${input.baseName} on $paper.pdf"
            } else {
                "${input.baseName} cropped.pdf"
            }
            name to detail
        }
    }

    /** "5 mm all round", or "top 10 mm, left 5 mm" when the edges differ. */
    private fun CropSpec.describeTrims(): String {
        val edges = listOf(
            R.string.engine_crop_edge_top to topMm, R.string.engine_crop_edge_right to rightMm,
            R.string.engine_crop_edge_bottom to bottomMm, R.string.engine_crop_edge_left to leftMm
        )
        if (edges.all { it.second == topMm }) return Strings.get(R.string.engine_crop_all_round, topMm.mm())
        return edges.filter { it.second > 0f }
            .joinToString(Strings.get(R.string.engine_list_separator)) { (edge, mm) -> Strings.get(edge, mm.mm()) }
    }

    private fun Float.mm(): String = if (this == this.toInt().toFloat()) "${toInt()} mm" else "%.1f mm".format(this)
}

/**
 * Takes marked areas out of a document permanently.
 *
 * The marked pages are rendered to an image with the boxes painted on, and that image replaces
 * the page. The text underneath is not covered, it is gone - it was never written into the new
 * page. A rectangle drawn on top would leave it selectable in any other reader, which is how
 * redaction failures reach the news.
 *
 * The cost is stated plainly in the UI: a redacted page becomes a picture, so its text stops
 * being selectable and searchable. Only marked pages are affected.
 */
class RedactOp(
    private val areas: Map<Int, List<PageArea>>,
    private val dpi: Int = DEFAULT_DPI
) : Op {

    override val tool = ToolId.REDACT
    override val title = Strings.get(R.string.engine_title_redact)
    override val arity = Op.Arity.ExactlyOne

    override suspend fun run(
        inputs: List<SheafFile>,
        context: OpContext,
        onProgress: (Progress) -> Unit
    ): List<OpOutcome> = withContext(Dispatchers.IO) {
        val input = inputs.firstOrNull()
            ?: return@withContext listOf(OpOutcome.Failed("", Strings.get(R.string.engine_no_document)))
        if (areas.values.all { it.isEmpty() }) {
            return@withContext listOf(
                OpOutcome.Failed(input.displayName, Strings.get(R.string.engine_redact_nothing))
            )
        }

        val pdfInput = PdfInput(input.file, context.passwords[input.file.path])
        val output = context.workspace.newOutput(input.baseName, "redacted")

        try {
            context.surgeon.redact(pdfInput, areas, dpi, output) { done, total ->
                context.checkCancelled()
                onProgress(Progress(done, total, Strings.get(R.string.engine_progress_redacting, done, total)))
            }
        } catch (e: PdfException) {
            output.delete()
            return@withContext listOf(OpOutcome.Failed(input.displayName, e.message ?: Strings.get(R.string.engine_failed)))
        }

        val boxes = areas.values.sumOf { it.size }
        val pages = areas.count { it.value.isNotEmpty() }
        listOf(
            OpOutcome.Produced(
                SheafFile(output, "${input.baseName} redacted.pdf", SheafFile.Origin.Derived("redacted")),
                Strings.get(
                    R.string.engine_redact_detail,
                    Strings.plural(R.plurals.engine_redact_areas, boxes),
                    Strings.plural(R.plurals.engine_redact_pages, pages)
                )
            )
        )
    }

    companion object {
        /** 200 DPI keeps a redacted page readable without doubling the size of the file. */
        const val DEFAULT_DPI = 200
    }
}

/**
 * Draws a signature onto a page and flattens it into the content.
 *
 * Flattened rather than added as an annotation, deliberately. An annotation can be moved or
 * deleted by whoever opens the file next, which is not what anyone means by signing something.
 *
 * This is not a digital signature and the UI says so. It is the same thing as printing a
 * document, signing it and scanning it back - done without the printer, and without the file
 * leaving the phone.
 */
class SignOp(private val stamp: ImageStamp) : Op {

    override val tool = ToolId.SIGN
    override val title = Strings.get(R.string.engine_title_sign)
    override val arity = Op.Arity.ExactlyOne

    override suspend fun run(
        inputs: List<SheafFile>,
        context: OpContext,
        onProgress: (Progress) -> Unit
    ): List<OpOutcome> = withContext(Dispatchers.IO) {
        val input = inputs.firstOrNull()
            ?: return@withContext listOf(OpOutcome.Failed("", Strings.get(R.string.engine_no_document)))
        if (!stamp.image.isFile) {
            return@withContext listOf(OpOutcome.Failed(input.displayName, Strings.get(R.string.engine_sign_nothing)))
        }

        onProgress(Progress(0, 1, Strings.get(R.string.engine_progress_placing_signature)))
        val pdfInput = PdfInput(input.file, context.passwords[input.file.path])
        val output = context.workspace.newOutput(input.baseName, "signed")

        try {
            context.surgeon.stampImage(pdfInput, stamp, output)
        } catch (e: PdfException) {
            output.delete()
            return@withContext listOf(OpOutcome.Failed(input.displayName, e.message ?: Strings.get(R.string.engine_failed)))
        }

        onProgress(Progress(1, 1, Strings.get(R.string.engine_done)))
        listOf(
            OpOutcome.Produced(
                SheafFile(output, "${input.baseName} signed.pdf", SheafFile.Origin.Derived("signed")),
                Strings.get(R.string.engine_signed_on_page, stamp.pageIndex + 1)
            )
        )
    }
}

/**
 * Puts a picture on a page: a logo, a stamp, a photo, a scanned signature.
 *
 * The same mechanism as [SignOp], flattened into the page for the same reason, offered as its
 * own tool because "Sign" is not where anyone looks to put a company logo on a letterhead.
 * The first file is the PDF and the second is the image.
 */
class AddImageOp(private val anchor: ImageStamp.Anchor, private val widthFraction: Float, private val page: Int) : Op {

    override val tool = ToolId.ADD_IMAGE
    override val title = Strings.get(R.string.engine_title_add_image)
    override val arity = Op.Arity.Together(min = 2)

    override suspend fun run(
        inputs: List<SheafFile>,
        context: OpContext,
        onProgress: (Progress) -> Unit
    ): List<OpOutcome> = withContext(Dispatchers.IO) {
        val document = inputs.firstOrNull { it.isPdf }
            ?: return@withContext listOf(OpOutcome.Failed("", Strings.get(R.string.engine_pick_pdf_first)))
        val image = inputs.firstOrNull { it.isImage }
            ?: return@withContext listOf(OpOutcome.Failed(document.displayName, Strings.get(R.string.engine_add_image_missing)))

        onProgress(Progress(0, 1, Strings.get(R.string.engine_progress_placing, image.displayName)))
        val pdfInput = PdfInput(document.file, context.passwords[document.file.path])
        val output = context.workspace.newOutput(document.baseName, "with-image")

        try {
            context.surgeon.stampImage(
                pdfInput,
                ImageStamp(
                    image = image.file,
                    pageIndex = (page - 1).coerceAtLeast(0),
                    anchor = anchor,
                    widthFraction = widthFraction
                ),
                output
            )
        } catch (e: PdfException) {
            output.delete()
            return@withContext listOf(OpOutcome.Failed(document.displayName, e.message ?: Strings.get(R.string.engine_failed)))
        }

        onProgress(Progress(1, 1, Strings.get(R.string.engine_done)))
        listOf(
            OpOutcome.Produced(
                SheafFile(output, "${document.baseName} with image.pdf", SheafFile.Origin.Derived("with-image")),
                Strings.get(R.string.engine_image_placed, image.displayName, page)
            )
        )
    }
}

/**
 * Draws the reader's highlights and pen strokes into the pages.
 *
 * Into the content, not as annotation objects. An annotation is something the next reader's
 * app can move, hide or delete, and a mark somebody made on a document should still be there
 * when it is opened somewhere else - which is the same call [SignOp] makes about a signature.
 *
 * The cost is that the marks cannot be peeled off again, and the UI says so. The document the
 * user chose is untouched either way: this writes a new one.
 *
 * [editable] is the exception the user can choose in Settings ("Save marks as editable
 * annotations"): the marks go in as real annotations with their own appearances, for people
 * who will keep working on the document in Acrobat or another reader.
 */
class AnnotateOp(
    private val marks: Map<Int, List<PageMark>>,
    /** Write the marks as annotations other readers can edit, rather than into the page. */
    private val editable: Boolean = false
) : Op {

    override val tool = ToolId.ANNOTATE
    override val title = Strings.get(R.string.engine_title_annotate)
    override val arity = Op.Arity.ExactlyOne

    override suspend fun run(
        inputs: List<SheafFile>,
        context: OpContext,
        onProgress: (Progress) -> Unit
    ): List<OpOutcome> = withContext(Dispatchers.IO) {
        val input = inputs.firstOrNull()
            ?: return@withContext listOf(OpOutcome.Failed("", Strings.get(R.string.engine_no_document)))
        if (marks.values.all { it.isEmpty() }) {
            return@withContext listOf(
                OpOutcome.Failed(input.displayName, Strings.get(R.string.engine_annotate_nothing))
            )
        }

        val pdfInput = PdfInput(input.file, context.passwords[input.file.path])
        val output = context.workspace.newOutput(input.baseName, "marked")

        try {
            context.surgeon.annotate(pdfInput, marks, output, editable = editable, onProgress = { done, total ->
                context.checkCancelled()
                onProgress(Progress(done, total, Strings.get(R.string.engine_progress_drawing, done, total)))
            })
        } catch (e: PdfException) {
            output.delete()
            return@withContext listOf(OpOutcome.Failed(input.displayName, e.message ?: Strings.get(R.string.engine_failed)))
        }

        val drawn = marks.values.sumOf { it.size }
        val pages = marks.count { it.value.isNotEmpty() }
        listOf(
            OpOutcome.Produced(
                SheafFile(output, "${input.baseName} marked.pdf", SheafFile.Origin.Derived("marked")),
                Strings.get(
                    R.string.engine_marks_on_pages,
                    Strings.plural(R.plurals.engine_marks, drawn),
                    Strings.plural(R.plurals.engine_count_pages, pages)
                )
            )
        )
    }
}

/**
 * Runs a line along the top or the bottom of every page.
 *
 * Page numbers are the same mechanism with the format fixed, and this is deliberately not
 * folded into that tool: "Confidential" at the top of every page of a contract and "page 3 of
 * 40" at the bottom are two different jobs that people do at different moments, and one
 * screen that did both would ask eight questions to answer either.
 */
class HeaderFooterOp(private val spec: StampSpec) : Op {

    override val tool = ToolId.HEADER_FOOTER
    override val title = Strings.get(R.string.engine_title_header_footer)
    override val arity = Op.Arity.EachIndependently

    override suspend fun run(
        inputs: List<SheafFile>,
        context: OpContext,
        onProgress: (Progress) -> Unit
    ): List<OpOutcome> = withContext(Dispatchers.IO) {
        if (spec.headerText.isBlank() && spec.footerText.isBlank()) {
            return@withContext listOf(OpOutcome.Failed("", Strings.get(R.string.engine_hf_nothing)))
        }
        eachFile(inputs, context, onProgress, "stamped", R.string.engine_progress_stamping) { input, pdfInput, output ->
            var pages = 0
            // The document's own name resolves {name}, which the engine cannot know.
            context.surgeon.stampHeaderFooter(
                pdfInput,
                spec.copy(documentName = input.displayName),
                output
            ) { _, total ->
                context.checkCancelled(); pages = total
            }
            val where = when {
                spec.headerText.isNotBlank() && spec.footerText.isNotBlank() -> R.plurals.engine_hf_added_both
                spec.headerText.isNotBlank() -> R.plurals.engine_hf_added_header
                else -> R.plurals.engine_hf_added_footer
            }
            "${input.baseName} stamped.pdf" to
                Strings.plural(where, pages, pages - spec.skipFirst, pages)
        }
    }
}

/**
 * Writes a line of text onto a page, and optionally covers what was there first.
 *
 * The nearest thing to editing a PDF that a PDF honestly allows. A document holds glyphs at
 * coordinates rather than paragraphs, so there is no sentence to retype - but a wrong date can
 * be patched over and written again, and an empty field can be filled in.
 *
 * Flattened into the page like [SignOp], not added as an annotation, so it is part of the
 * document rather than a note the next reader can drag off it.
 */
class AddTextOp(private val note: TextNote) : Op {

    override val tool = ToolId.ADD_TEXT
    override val title = Strings.get(R.string.engine_title_add_text)
    override val arity = Op.Arity.ExactlyOne

    override suspend fun run(
        inputs: List<SheafFile>,
        context: OpContext,
        onProgress: (Progress) -> Unit
    ): List<OpOutcome> = withContext(Dispatchers.IO) {
        val input = inputs.firstOrNull()
            ?: return@withContext listOf(OpOutcome.Failed("", Strings.get(R.string.engine_no_document)))
        if (note.text.isBlank()) {
            return@withContext listOf(OpOutcome.Failed(input.displayName, Strings.get(R.string.engine_add_text_nothing)))
        }

        onProgress(Progress(0, 1, Strings.get(R.string.engine_progress_writing_on_page, note.pageIndex + 1)))
        val pdfInput = PdfInput(input.file, context.passwords[input.file.path])
        val output = context.workspace.newOutput(input.baseName, "edited")

        try {
            context.surgeon.addText(pdfInput, listOf(note), output)
        } catch (e: PdfException) {
            output.delete()
            return@withContext listOf(OpOutcome.Failed(input.displayName, e.message ?: Strings.get(R.string.engine_failed)))
        }

        onProgress(Progress(1, 1, Strings.get(R.string.engine_done)))
        val lines = note.text.lines().count { it.isNotBlank() }
        listOf(
            OpOutcome.Produced(
                SheafFile(output, "${input.baseName} edited.pdf", SheafFile.Origin.Derived("edited")),
                Strings.plural(R.plurals.engine_lines_added, lines, lines, note.pageIndex + 1).let {
                    if (note.cover) Strings.get(R.string.engine_lines_added_covering, it) else it
                }
            )
        )
    }
}

/**
 * The shape every one-in-one-out operation here shares.
 *
 * Each file is handled on its own and a failure is reported against that file rather than
 * thrown, so one unreadable document in a batch never discards the rest.
 */
internal suspend inline fun eachFile(
    inputs: List<SheafFile>,
    context: OpContext,
    noinline onProgress: (Progress) -> Unit,
    step: String,
    /** The progress line while a file is worked on: "Numbering %1$s". */
    @StringRes verb: Int,
    crossinline work: (SheafFile, PdfInput, File) -> Pair<String, String?>
): List<OpOutcome> {
    val outcomes = mutableListOf<OpOutcome>()

    inputs.forEachIndexed { index, input ->
        context.checkCancelled()
        onProgress(Progress(index, inputs.size, Strings.get(verb, input.displayName)))

        val output = context.workspace.newOutput(input.baseName, step)
        outcomes += try {
            val (name, detail) = work(input, PdfInput(input.file, context.passwords[input.file.path]), output)
            OpOutcome.Produced(SheafFile(output, name, SheafFile.Origin.Derived(step)), detail)
        } catch (e: PdfException) {
            output.delete()
            OpOutcome.Failed(input.displayName, e.message ?: Strings.get(R.string.engine_failed))
        }
    }

    onProgress(Progress(inputs.size, inputs.size, Strings.get(R.string.engine_done)))
    return outcomes
}

/** "12 pages" for the whole document, "pages 1-3, 7" for some of it. */
internal fun describeWhich(pages: List<Int>, total: Int): String =
    if (pages.isEmpty() || pages.size == total) Strings.plural(R.plurals.engine_count_pages, total)
    else Strings.plural(R.plurals.engine_pages_listed, pages.size, PageSelection.describe(pages))
