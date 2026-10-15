package com.layerbit.sheaf.ops

import com.layerbit.sheaf.files.SheafFile
import com.layerbit.sheaf.pdf.CleanReport
import com.layerbit.sheaf.pdf.CleanSpec
import com.layerbit.sheaf.pdf.DocumentMetadata
import com.layerbit.sheaf.pdf.PageSpec
import com.layerbit.sheaf.pdf.PdfException
import com.layerbit.sheaf.pdf.PdfInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Puts two, four, six, eight, nine or sixteen pages on each sheet, for printing. */
class NUpOp(private val perSheet: Int, private val paper: PageSpec.Size = PageSpec.Size.A4) : Op {

    override val tool = ToolId.N_UP
    override val title = "$perSheet pages per sheet"
    override val arity = Op.Arity.EachIndependently

    override suspend fun run(
        inputs: List<SheafFile>,
        context: OpContext,
        onProgress: (Progress) -> Unit
    ): List<OpOutcome> = withContext(Dispatchers.IO) {
        eachFile(inputs, context, onProgress, "n-up", "Laying out") { input, pdfInput, output ->
            var pages = 0
            context.surgeon.nUp(pdfInput, perSheet, paper, output) { _, total ->
                context.checkCancelled(); pages = total
            }
            val sheets = (pages + perSheet - 1) / perSheet
            "${input.baseName} $perSheet-up.pdf" to
                "$pages ${if (pages == 1) "page" else "pages"} on $sheets ${if (sheets == 1) "sheet" else "sheets"} of ${paper.label}"
        }
    }
}

/**
 * Cuts a document into parts that each fit under a size limit.
 *
 * The limit people actually have is an email attachment cap, so the sizes offered are the
 * ones that matter - 2, 5, 10 and 25 MB - and any other number can be typed.
 *
 * Each part is measured by saving it, not estimated from the average page: shared fonts and
 * images are copied into every part, so pages never add up, and an estimate that runs a part
 * one page over the limit is a part that bounces. The result screen shows the real sizes.
 */
class SplitBySizeOp(private val maxBytes: Long) : Op {

    override val tool = ToolId.SPLIT_BY_SIZE
    override val title = "Split under ${describeLimit(maxBytes)}"
    override val arity = Op.Arity.ExactlyOne

    override suspend fun run(
        inputs: List<SheafFile>,
        context: OpContext,
        onProgress: (Progress) -> Unit
    ): List<OpOutcome> = withContext(Dispatchers.IO) {
        val input = inputs.firstOrNull()
            ?: return@withContext listOf(OpOutcome.Failed("", "No document was selected."))
        if (maxBytes < SMALLEST_LIMIT) {
            return@withContext listOf(OpOutcome.Failed(input.displayName, "The limit has to be at least 1 MB."))
        }

        val pdfInput = PdfInput(input.file, context.passwords[input.file.path])

        if (input.sizeBytes <= maxBytes) {
            return@withContext listOf(
                OpOutcome.Skipped(
                    input.displayName,
                    "This document is already under ${describeLimit(maxBytes)}, so it was left alone."
                )
            )
        }

        onProgress(Progress(0, 1, "Measuring where to cut"))
        val groups = try {
            context.surgeon.measureSizeSplit(pdfInput, maxBytes) { done, total ->
                context.checkCancelled()
                onProgress(Progress(done, total, "Measuring: $done of $total pages placed"))
            }
        } catch (e: PdfException) {
            return@withContext listOf(OpOutcome.Failed(input.displayName, e.message ?: "Failed."))
        }

        if (groups.isEmpty()) {
            return@withContext listOf(OpOutcome.Failed(input.displayName, "This document has no pages."))
        }

        val outcomes = mutableListOf<OpOutcome>()
        groups.forEachIndexed { index, pages ->
            context.checkCancelled()
            onProgress(Progress(index, groups.size, "Writing part ${index + 1} of ${groups.size}"))

            val partName = "${input.baseName} part ${index + 1}"
            val output = context.workspace.newOutput(partName, "size-split")
            outcomes += try {
                context.surgeon.extractPages(pdfInput, pages, emptyMap(), output)
                val over = output.length() > maxBytes
                OpOutcome.Produced(
                    SheafFile(output, "$partName.pdf", SheafFile.Origin.Derived("size-split")),
                    buildString {
                        append(if (pages.size == 1) "Page ${pages.first() + 1}" else "Pages ${pages.first() + 1}-${pages.last() + 1}")
                        // A single page bigger than the limit on its own cannot be cut any
                        // further; it is written as its own part and said so, not hidden.
                        if (over) append(" - this page is bigger than ${describeLimit(maxBytes)} on its own")
                    }
                )
            } catch (e: PdfException) {
                output.delete()
                OpOutcome.Failed(partName, e.message ?: "This part could not be written.")
            }
        }
        onProgress(Progress(groups.size, groups.size, "Done"))
        outcomes
    }

    companion object {
        const val TWO_MB = 2L * 1024 * 1024
        const val FIVE_MB = 5L * 1024 * 1024
        const val TEN_MB = 10L * 1024 * 1024
        const val TWENTY_FIVE_MB = 25L * 1024 * 1024
        const val SMALLEST_LIMIT = 1L * 1024 * 1024

        fun describeLimit(bytes: Long): String {
            val mb = bytes / (1024f * 1024f)
            return if (mb == mb.toInt().toFloat()) "${mb.toInt()} MB" else "%.1f MB".format(mb)
        }
    }
}

/**
 * Pulls out the pictures that are already inside a document.
 *
 * Not the same as PDF to images, and the difference matters. That one photographs each page as
 * it appears. This one takes the embedded originals at whatever resolution they were stored -
 * so a scanned photograph comes out as the photograph, not as a picture of a page with a
 * photograph on it.
 */
class ExtractImagesOp : Op {

    override val tool = ToolId.EXTRACT_IMAGES
    override val title = "Extract images"
    override val arity = Op.Arity.ExactlyOne

    override suspend fun run(
        inputs: List<SheafFile>,
        context: OpContext,
        onProgress: (Progress) -> Unit
    ): List<OpOutcome> = withContext(Dispatchers.IO) {
        val input = inputs.firstOrNull()
            ?: return@withContext listOf(OpOutcome.Failed("", "No document was selected."))

        val dir = File(context.workspace.newOutput(input.baseName, "images", "dir").path).also { it.mkdirs() }
        val pdfInput = PdfInput(input.file, context.passwords[input.file.path])

        val files = try {
            context.surgeon.extractImages(pdfInput, dir, input.baseName) { done, total ->
                context.checkCancelled()
                onProgress(Progress(done, total, "Page $done of $total"))
            }
        } catch (e: PdfException) {
            dir.deleteRecursively()
            return@withContext listOf(OpOutcome.Failed(input.displayName, e.message ?: "Failed."))
        }

        if (files.isEmpty()) {
            dir.deleteRecursively()
            return@withContext listOf(
                OpOutcome.Skipped(
                    input.displayName,
                    "There are no embedded images in this document. If you wanted pictures of " +
                        "the pages themselves, use PDF to images."
                )
            )
        }

        files.map { file ->
            OpOutcome.Produced(
                SheafFile(
                    file = file,
                    displayName = file.name,
                    origin = SheafFile.Origin.Derived("images"),
                    mimeType = SheafFile.MIME_PNG
                )
            )
        }
    }
}

/**
 * Edits or clears the information a document carries about itself.
 *
 * Worth having for the same reason the rest of this app is: a PDF quietly records who made it
 * and with what. A scanner writes its make and model, a word processor writes the account name
 * it was licensed to. Someone sending a document to a stranger may not want to send that too.
 */
class MetadataOp(private val metadata: DocumentMetadata) : Op {

    override val tool = ToolId.METADATA
    override val title = if (metadata.stripAll) "Strip metadata" else "Edit details"
    override val arity = Op.Arity.EachIndependently

    override suspend fun run(
        inputs: List<SheafFile>,
        context: OpContext,
        onProgress: (Progress) -> Unit
    ): List<OpOutcome> = withContext(Dispatchers.IO) {
        val step = if (metadata.stripAll) "stripped" else "details"
        eachFile(inputs, context, onProgress, step, "Writing") { input, pdfInput, output ->
            context.surgeon.writeMetadata(pdfInput, metadata, output)
            "${input.baseName} $step.pdf" to
                if (metadata.stripAll) "Title, author, dates and producer cleared" else "Details updated"
        }
    }
}

/**
 * Takes out what a file gives away before it is sent: the details and their hidden XMP copy,
 * scripts and open actions, attached files, comments, page thumbnails and the private data
 * that editing programs leave behind.
 *
 * Each kind is counted, so the result says what was found - "2 scripts, 1 attachment" -
 * rather than "cleaned", which on a file that had nothing in it reads as nothing happening.
 */
class CleanOp(private val spec: CleanSpec) : Op {

    override val tool = ToolId.METADATA
    override val title = "Clean hidden data"
    override val arity = Op.Arity.EachIndependently

    override suspend fun run(
        inputs: List<SheafFile>,
        context: OpContext,
        onProgress: (Progress) -> Unit
    ): List<OpOutcome> = withContext(Dispatchers.IO) {
        eachFile(inputs, context, onProgress, "cleaned", "Cleaning") { input, pdfInput, output ->
            val report = context.surgeon.cleanDocument(pdfInput, spec, output)
            "${input.baseName} cleaned.pdf" to describe(report)
        }
    }

    private fun describe(report: CleanReport): String {
        if (report.total == 0) return "Nothing hidden was found; the copy is the same document"
        val parts = buildList {
            if (report.details > 0) add("the details")
            if (report.xmp > 0) add("a hidden XMP copy of them")
            if (report.scripts > 0) add(count(report.scripts, "script"))
            if (report.attachments > 0) add(count(report.attachments, "attached file"))
            if (report.comments > 0) add(count(report.comments, "comment"))
            if (report.thumbnails > 0) add(count(report.thumbnails, "page thumbnail"))
            if (report.privateData > 0) add("private editing data")
        }
        return "Removed " + parts.joinToString(", ")
    }

    private fun count(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"
}
