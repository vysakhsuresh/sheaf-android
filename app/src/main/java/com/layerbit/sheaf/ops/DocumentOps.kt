package com.layerbit.sheaf.ops

import com.layerbit.sheaf.R
import com.layerbit.sheaf.files.SheafFile
import com.layerbit.sheaf.pdf.OutlineEntry
import com.layerbit.sheaf.pdf.PageLabelSpec
import com.layerbit.sheaf.pdf.PageSelection
import com.layerbit.sheaf.pdf.PdfException
import com.layerbit.sheaf.pdf.PdfInput
import com.layerbit.sheaf.pdf.labelFor
import com.layerbit.sheaf.text.Strings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The operations on what a document carries besides its pages: how a reader names them,
 * what sits behind them, the files inside it, and its table of contents.
 */

/**
 * Sets what a reader calls each page: i, ii, iii for the front matter, then 1, 2, 3.
 *
 * Nothing is printed on the page; this changes the page box of the reader. The thing it fixes
 * is a 300-page report whose "page 1" is physical page 9, so that typing 57 goes to 57.
 */
class PageLabelsOp(private val spec: PageLabelSpec?) : Op {

    override val tool = ToolId.PAGE_LABELS
    override val title = Strings.get(if (spec == null) R.string.engine_title_remove_labels else R.string.engine_title_labels)
    override val arity = Op.Arity.EachIndependently

    override suspend fun run(
        inputs: List<SheafFile>,
        context: OpContext,
        onProgress: (Progress) -> Unit
    ): List<OpOutcome> = withContext(Dispatchers.IO) {
        eachFile(inputs, context, onProgress, "labelled", R.string.engine_progress_labelling) { input, pdfInput, output ->
            val total = context.surgeon.inspect(pdfInput).pageCount
            context.surgeon.setPageLabels(pdfInput, spec, output)
            "${input.baseName} labelled.pdf" to describe(spec, total)
        }
    }

    private fun describe(spec: PageLabelSpec?, total: Int): String {
        if (spec == null) return Strings.get(R.string.engine_labels_removed)
        val front = spec.frontPages.coerceIn(0, total)
        val body = (total - front).coerceAtLeast(0)
        val frontPart = if (front > 0) {
            Strings.plural(R.plurals.engine_labels_front, front, spec.frontStyle.labelFor(1), spec.frontStyle.labelFor(front), front)
        } else null
        val bodyPart = if (body > 0) {
            val first = spec.prefix + spec.style.labelFor(spec.startAt)
            val last = spec.prefix + spec.style.labelFor(spec.startAt + body - 1)
            if (body == 1) first else Strings.get(R.string.engine_labels_range, first, last)
        } else null
        return when {
            frontPart != null && bodyPart != null -> Strings.get(R.string.engine_labels_then, frontPart, bodyPart)
            else -> frontPart ?: bodyPart.orEmpty()
        }
    }
}

/** Gives pages a background colour, behind everything already on them. */
class BackgroundOp(private val colour: Int, private val pageSpec: String = "") : Op {

    override val tool = ToolId.BACKGROUND
    override val title = Strings.get(R.string.engine_title_background)
    override val arity = Op.Arity.EachIndependently

    override suspend fun run(
        inputs: List<SheafFile>,
        context: OpContext,
        onProgress: (Progress) -> Unit
    ): List<OpOutcome> = withContext(Dispatchers.IO) {
        eachFile(inputs, context, onProgress, "background", R.string.engine_progress_colouring) { input, pdfInput, output ->
            val total = context.surgeon.inspect(pdfInput).pageCount
            val pages = if (pageSpec.isBlank()) emptyList() else PageSelection.parse(pageSpec, total)
            if (pageSpec.isNotBlank() && pages.isEmpty()) {
                throw PdfException.Io(Strings.plural(R.plurals.engine_pages_typed_not_in_file, total))
            }
            context.surgeon.setBackground(pdfInput, colour, pages, output) { _, _ -> context.checkCancelled() }
            val which = if (pages.isEmpty() || pages.size == total) {
                Strings.plural(R.plurals.engine_all_pages, total)
            } else {
                Strings.plural(R.plurals.engine_pages_listed, pages.size, PageSelection.describe(pages))
            }
            "${input.baseName} background.pdf" to Strings.get(R.string.engine_background_detail, which)
        }
    }
}

/**
 * Puts files inside a PDF, or takes them out.
 *
 * The first file chosen is the document; everything after it is attached as it is, byte for
 * byte, under its own name. Extracting writes every attached file out as its own result, so
 * the results screen can save or share each one.
 */
class AttachmentsOp(private val extract: Boolean) : Op {

    override val tool = ToolId.ATTACHMENTS
    override val title = Strings.get(if (extract) R.string.engine_title_extract_attachments else R.string.engine_title_attach)
    override val arity = Op.Arity.Together(min = 1)

    override suspend fun run(
        inputs: List<SheafFile>,
        context: OpContext,
        onProgress: (Progress) -> Unit
    ): List<OpOutcome> = withContext(Dispatchers.IO) {
        val base = inputs.firstOrNull()
            ?: return@withContext listOf(OpOutcome.Failed("", Strings.get(R.string.engine_no_document)))
        if (!base.isPdf) {
            return@withContext listOf(OpOutcome.Failed(base.displayName, Strings.get(R.string.engine_attach_pdf_first)))
        }
        val pdfInput = PdfInput(base.file, context.passwords[base.file.path])

        if (extract) {
            onProgress(Progress(0, 1, Strings.get(R.string.engine_progress_reading_attachments)))
            val dir = context.workspace.newOutput(base.baseName, "attachments", "dir")
            val files = try {
                context.surgeon.extractAttachments(pdfInput, dir)
            } catch (e: PdfException) {
                return@withContext listOf(OpOutcome.Failed(base.displayName, e.message ?: Strings.get(R.string.engine_failed)))
            }
            onProgress(Progress(1, 1, Strings.get(R.string.engine_done)))
            return@withContext files.map { file ->
                OpOutcome.Produced(
                    SheafFile(
                        file = file,
                        displayName = file.name,
                        origin = SheafFile.Origin.Derived("attachments"),
                        mimeType = mimeFor(file.name)
                    ),
                    Strings.get(R.string.engine_attached_to, base.displayName)
                )
            }
        }

        val attachments = inputs.drop(1)
        if (attachments.isEmpty()) {
            return@withContext listOf(
                OpOutcome.Failed(base.displayName, Strings.get(R.string.engine_attach_nothing))
            )
        }
        onProgress(Progress(0, 1, Strings.plural(R.plurals.engine_progress_attaching, attachments.size)))
        val output = context.workspace.newOutput(base.baseName, "attached")
        try {
            context.surgeon.attachFiles(pdfInput, attachments.map { it.displayName to it.file }, output)
        } catch (e: PdfException) {
            output.delete()
            return@withContext listOf(OpOutcome.Failed(base.displayName, e.message ?: Strings.get(R.string.engine_failed)))
        }
        onProgress(Progress(1, 1, Strings.get(R.string.engine_done)))
        listOf(
            OpOutcome.Produced(
                SheafFile(output, "${base.baseName} with attachments.pdf", SheafFile.Origin.Derived("attached")),
                if (attachments.size == 1) Strings.get(R.string.engine_attached_one, attachments.first().displayName)
                else Strings.plural(
                    R.plurals.engine_attached_many, attachments.size, attachments.size,
                    attachments.joinToString(Strings.get(R.string.engine_list_separator)) { it.displayName }
                )
            )
        )
    }

    private fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "pdf" -> SheafFile.MIME_PDF
        "png" -> SheafFile.MIME_PNG
        "jpg", "jpeg" -> SheafFile.MIME_JPEG
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "txt", "csv", "md" -> SheafFile.MIME_TEXT
        "json" -> "application/json"
        "xml" -> "application/xml"
        "zip" -> "application/zip"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "ppt" -> "application/vnd.ms-powerpoint"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        else -> "application/octet-stream"
    }
}

/**
 * Writes a table of contents into the document: the bookmarks a reader shows in its side
 * panel. The list replaces whatever was there; an empty list takes the bookmarks out.
 */
class BookmarksOp(private val entries: List<OutlineEntry>) : Op {

    override val tool = ToolId.BOOKMARKS
    override val title = Strings.get(if (entries.isEmpty()) R.string.engine_title_remove_bookmarks else R.string.engine_title_bookmarks)
    override val arity = Op.Arity.ExactlyOne

    override suspend fun run(
        inputs: List<SheafFile>,
        context: OpContext,
        onProgress: (Progress) -> Unit
    ): List<OpOutcome> = withContext(Dispatchers.IO) {
        val input = inputs.firstOrNull()
            ?: return@withContext listOf(OpOutcome.Failed("", Strings.get(R.string.engine_no_document)))
        val clean = entries.map { it.copy(title = it.title.trim()) }.filter { it.title.isNotEmpty() }

        onProgress(Progress(0, 1, Strings.get(R.string.engine_progress_writing_bookmarks)))
        val pdfInput = PdfInput(input.file, context.passwords[input.file.path])
        val output = context.workspace.newOutput(input.baseName, "bookmarked")
        try {
            context.surgeon.writeOutline(pdfInput, clean, output)
        } catch (e: PdfException) {
            output.delete()
            return@withContext listOf(OpOutcome.Failed(input.displayName, e.message ?: Strings.get(R.string.engine_failed)))
        }
        onProgress(Progress(1, 1, Strings.get(R.string.engine_done)))
        listOf(
            OpOutcome.Produced(
                SheafFile(output, "${input.baseName} bookmarked.pdf", SheafFile.Origin.Derived("bookmarked")),
                when {
                    clean.isEmpty() -> Strings.get(R.string.engine_bookmarks_removed)
                    clean.size > 1 && clean.any { it.depth > 0 } ->
                        Strings.get(R.string.engine_bookmarks_nested, Strings.plural(R.plurals.engine_bookmarks_written, clean.size))
                    else -> Strings.plural(R.plurals.engine_bookmarks_written, clean.size)
                }
            )
        )
    }
}
