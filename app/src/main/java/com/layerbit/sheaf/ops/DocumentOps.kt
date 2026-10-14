package com.layerbit.sheaf.ops

import com.layerbit.sheaf.files.SheafFile
import com.layerbit.sheaf.pdf.OutlineEntry
import com.layerbit.sheaf.pdf.PageLabelSpec
import com.layerbit.sheaf.pdf.PageSelection
import com.layerbit.sheaf.pdf.PdfException
import com.layerbit.sheaf.pdf.PdfInput
import com.layerbit.sheaf.pdf.labelFor
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
    override val title = if (spec == null) "Remove page labels" else "Page labels"
    override val arity = Op.Arity.EachIndependently

    override suspend fun run(
        inputs: List<SheafFile>,
        context: OpContext,
        onProgress: (Progress) -> Unit
    ): List<OpOutcome> = withContext(Dispatchers.IO) {
        eachFile(inputs, context, onProgress, "labelled", "Labelling") { input, pdfInput, output ->
            val total = context.surgeon.inspect(pdfInput).pageCount
            context.surgeon.setPageLabels(pdfInput, spec, output)
            "${input.baseName} labelled.pdf" to describe(spec, total)
        }
    }

    private fun describe(spec: PageLabelSpec?, total: Int): String {
        if (spec == null) return "Page labels removed; readers count from the first page again"
        val front = spec.frontPages.coerceIn(0, total)
        val body = (total - front).coerceAtLeast(0)
        return buildString {
            if (front > 0) {
                append("${spec.frontStyle.labelFor(1)} to ${spec.frontStyle.labelFor(front)} for the first ")
                append(if (front == 1) "page" else "$front pages")
                if (body > 0) append(", then ")
            }
            if (body > 0) {
                val first = spec.prefix + spec.style.labelFor(spec.startAt)
                val last = spec.prefix + spec.style.labelFor(spec.startAt + body - 1)
                append(if (body == 1) first else "$first to $last")
            }
        }
    }
}

/** Gives pages a background colour, behind everything already on them. */
class BackgroundOp(private val colour: Int, private val pageSpec: String = "") : Op {

    override val tool = ToolId.BACKGROUND
    override val title = "Page background"
    override val arity = Op.Arity.EachIndependently

    override suspend fun run(
        inputs: List<SheafFile>,
        context: OpContext,
        onProgress: (Progress) -> Unit
    ): List<OpOutcome> = withContext(Dispatchers.IO) {
        eachFile(inputs, context, onProgress, "background", "Colouring") { input, pdfInput, output ->
            val total = context.surgeon.inspect(pdfInput).pageCount
            val pages = if (pageSpec.isBlank()) emptyList() else PageSelection.parse(pageSpec, total)
            if (pageSpec.isNotBlank() && pages.isEmpty()) {
                throw PdfException.Io("This file has $total pages; none of the pages typed are in it.")
            }
            context.surgeon.setBackground(pdfInput, colour, pages, output) { _, _ -> context.checkCancelled() }
            val which = if (pages.isEmpty() || pages.size == total) {
                "all $total ${if (total == 1) "page" else "pages"}"
            } else {
                "${if (pages.size == 1) "page" else "pages"} ${PageSelection.describe(pages)}"
            }
            "${input.baseName} background.pdf" to "Background behind $which"
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
    override val title = if (extract) "Extract attachments" else "Attach files"
    override val arity = Op.Arity.Together(min = 1)

    override suspend fun run(
        inputs: List<SheafFile>,
        context: OpContext,
        onProgress: (Progress) -> Unit
    ): List<OpOutcome> = withContext(Dispatchers.IO) {
        val base = inputs.firstOrNull()
            ?: return@withContext listOf(OpOutcome.Failed("", "No document was selected."))
        if (!base.isPdf) {
            return@withContext listOf(OpOutcome.Failed(base.displayName, "The first file has to be the PDF."))
        }
        val pdfInput = PdfInput(base.file, context.passwords[base.file.path])

        if (extract) {
            onProgress(Progress(0, 1, "Reading the attachments"))
            val dir = context.workspace.newOutput(base.baseName, "attachments", "dir")
            val files = try {
                context.surgeon.extractAttachments(pdfInput, dir)
            } catch (e: PdfException) {
                return@withContext listOf(OpOutcome.Failed(base.displayName, e.message ?: "Failed."))
            }
            onProgress(Progress(1, 1, "Done"))
            return@withContext files.map { file ->
                OpOutcome.Produced(
                    SheafFile(
                        file = file,
                        displayName = file.name,
                        origin = SheafFile.Origin.Derived("attachments"),
                        mimeType = mimeFor(file.name)
                    ),
                    "Attached to ${base.displayName}"
                )
            }
        }

        val attachments = inputs.drop(1)
        if (attachments.isEmpty()) {
            return@withContext listOf(
                OpOutcome.Failed(base.displayName, "Add the files to attach after the PDF.")
            )
        }
        onProgress(Progress(0, 1, "Attaching ${attachments.size} ${if (attachments.size == 1) "file" else "files"}"))
        val output = context.workspace.newOutput(base.baseName, "attached")
        try {
            context.surgeon.attachFiles(pdfInput, attachments.map { it.displayName to it.file }, output)
        } catch (e: PdfException) {
            output.delete()
            return@withContext listOf(OpOutcome.Failed(base.displayName, e.message ?: "Failed."))
        }
        onProgress(Progress(1, 1, "Done"))
        listOf(
            OpOutcome.Produced(
                SheafFile(output, "${base.baseName} with attachments.pdf", SheafFile.Origin.Derived("attached")),
                if (attachments.size == 1) "${attachments.first().displayName} attached"
                else "${attachments.size} files attached: " + attachments.joinToString(", ") { it.displayName }
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
    override val title = if (entries.isEmpty()) "Remove bookmarks" else "Bookmarks"
    override val arity = Op.Arity.ExactlyOne

    override suspend fun run(
        inputs: List<SheafFile>,
        context: OpContext,
        onProgress: (Progress) -> Unit
    ): List<OpOutcome> = withContext(Dispatchers.IO) {
        val input = inputs.firstOrNull()
            ?: return@withContext listOf(OpOutcome.Failed("", "No document was selected."))
        val clean = entries.map { it.copy(title = it.title.trim()) }.filter { it.title.isNotEmpty() }

        onProgress(Progress(0, 1, "Writing the bookmarks"))
        val pdfInput = PdfInput(input.file, context.passwords[input.file.path])
        val output = context.workspace.newOutput(input.baseName, "bookmarked")
        try {
            context.surgeon.writeOutline(pdfInput, clean, output)
        } catch (e: PdfException) {
            output.delete()
            return@withContext listOf(OpOutcome.Failed(input.displayName, e.message ?: "Failed."))
        }
        onProgress(Progress(1, 1, "Done"))
        listOf(
            OpOutcome.Produced(
                SheafFile(output, "${input.baseName} bookmarked.pdf", SheafFile.Origin.Derived("bookmarked")),
                when (clean.size) {
                    0 -> "Bookmarks removed"
                    1 -> "1 bookmark written"
                    else -> "${clean.size} bookmarks written" +
                        if (clean.any { it.depth > 0 }) ", nested" else ""
                }
            )
        )
    }
}
