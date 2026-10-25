package com.layerbit.sheaf.ops

import com.layerbit.sheaf.R
import com.layerbit.sheaf.files.SheafFile
import com.layerbit.sheaf.pdf.CleanReport
import com.layerbit.sheaf.pdf.CleanSpec
import com.layerbit.sheaf.pdf.DocumentMetadata
import com.layerbit.sheaf.pdf.PageSpec
import com.layerbit.sheaf.pdf.PdfException
import com.layerbit.sheaf.pdf.PdfInput
import com.layerbit.sheaf.text.Strings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Puts two, four, six, eight, nine or sixteen pages on each sheet, for printing. */
class NUpOp(private val perSheet: Int, private val paper: PageSpec.Size = PageSpec.Size.A4) : Op {

    override val tool = ToolId.N_UP
    override val title = Strings.plural(R.plurals.engine_title_nup, perSheet)
    override val arity = Op.Arity.EachIndependently

    override suspend fun run(
        inputs: List<SheafFile>,
        context: OpContext,
        onProgress: (Progress) -> Unit
    ): List<OpOutcome> = withContext(Dispatchers.IO) {
        eachFile(inputs, context, onProgress, "n-up", R.string.engine_progress_laying_out) { input, pdfInput, output ->
            var pages = 0
            context.surgeon.nUp(pdfInput, perSheet, paper, output) { _, total ->
                context.checkCancelled(); pages = total
            }
            val sheets = (pages + perSheet - 1) / perSheet
            "${input.baseName} $perSheet-up.pdf" to
                Strings.get(
                    R.string.engine_nup_detail,
                    Strings.plural(R.plurals.engine_count_pages, pages),
                    Strings.plural(R.plurals.engine_count_sheets, sheets),
                    paper.label
                )
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
    override val title = Strings.get(R.string.engine_title_split_size, describeLimit(maxBytes))
    override val arity = Op.Arity.ExactlyOne

    override suspend fun run(
        inputs: List<SheafFile>,
        context: OpContext,
        onProgress: (Progress) -> Unit
    ): List<OpOutcome> = withContext(Dispatchers.IO) {
        val input = inputs.firstOrNull()
            ?: return@withContext listOf(OpOutcome.Failed("", Strings.get(R.string.engine_no_document)))
        if (maxBytes < SMALLEST_LIMIT) {
            return@withContext listOf(OpOutcome.Failed(input.displayName, Strings.get(R.string.engine_split_limit_small)))
        }

        val pdfInput = PdfInput(input.file, context.passwords[input.file.path])

        if (input.sizeBytes <= maxBytes) {
            return@withContext listOf(
                OpOutcome.Skipped(
                    input.displayName,
                    Strings.get(R.string.engine_split_already_under, describeLimit(maxBytes))
                )
            )
        }

        onProgress(Progress(0, 1, Strings.get(R.string.engine_progress_measuring)))
        val groups = try {
            context.surgeon.measureSizeSplit(pdfInput, maxBytes) { done, total ->
                context.checkCancelled()
                onProgress(Progress(done, total, Strings.get(R.string.engine_progress_measuring_pages, done, total)))
            }
        } catch (e: PdfException) {
            return@withContext listOf(OpOutcome.Failed(input.displayName, e.message ?: Strings.get(R.string.engine_failed)))
        }

        if (groups.isEmpty()) {
            return@withContext listOf(OpOutcome.Failed(input.displayName, Strings.get(R.string.engine_no_pages)))
        }

        val outcomes = mutableListOf<OpOutcome>()
        groups.forEachIndexed { index, pages ->
            context.checkCancelled()
            onProgress(Progress(index, groups.size, Strings.get(R.string.engine_progress_writing_part, index + 1, groups.size)))

            val partName = "${input.baseName} part ${index + 1}"
            val output = context.workspace.newOutput(partName, "size-split")
            outcomes += try {
                context.surgeon.extractPages(pdfInput, pages, emptyMap(), output)
                val over = output.length() > maxBytes
                OpOutcome.Produced(
                    SheafFile(output, "$partName.pdf", SheafFile.Origin.Derived("size-split")),
                    (
                        if (pages.size == 1) Strings.get(R.string.engine_split_part_page, pages.first() + 1)
                        else Strings.get(R.string.engine_split_part_pages, pages.first() + 1, pages.last() + 1)
                    ).let {
                        // A single page bigger than the limit on its own cannot be cut any
                        // further; it is written as its own part and said so, not hidden.
                        if (over) Strings.get(R.string.engine_split_part_over, it, describeLimit(maxBytes)) else it
                    }
                )
            } catch (e: PdfException) {
                output.delete()
                OpOutcome.Failed(partName, e.message ?: Strings.get(R.string.engine_split_part_failed))
            }
        }
        onProgress(Progress(groups.size, groups.size, Strings.get(R.string.engine_done)))
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
    override val title = Strings.get(R.string.engine_title_extract_images)
    override val arity = Op.Arity.ExactlyOne

    override suspend fun run(
        inputs: List<SheafFile>,
        context: OpContext,
        onProgress: (Progress) -> Unit
    ): List<OpOutcome> = withContext(Dispatchers.IO) {
        val input = inputs.firstOrNull()
            ?: return@withContext listOf(OpOutcome.Failed("", Strings.get(R.string.engine_no_document)))

        val dir = File(context.workspace.newOutput(input.baseName, "images", "dir").path).also { it.mkdirs() }
        val pdfInput = PdfInput(input.file, context.passwords[input.file.path])

        val files = try {
            context.surgeon.extractImages(pdfInput, dir, input.baseName) { done, total ->
                context.checkCancelled()
                onProgress(Progress(done, total, Strings.get(R.string.engine_progress_page_of, done, total)))
            }
        } catch (e: PdfException) {
            dir.deleteRecursively()
            return@withContext listOf(OpOutcome.Failed(input.displayName, e.message ?: Strings.get(R.string.engine_failed)))
        }

        if (files.isEmpty()) {
            dir.deleteRecursively()
            return@withContext listOf(
                OpOutcome.Skipped(
                    input.displayName,
                    Strings.get(R.string.engine_extract_images_none)
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
    override val title = Strings.get(if (metadata.stripAll) R.string.engine_title_strip_metadata else R.string.engine_title_edit_details)
    override val arity = Op.Arity.EachIndependently

    override suspend fun run(
        inputs: List<SheafFile>,
        context: OpContext,
        onProgress: (Progress) -> Unit
    ): List<OpOutcome> = withContext(Dispatchers.IO) {
        val step = if (metadata.stripAll) "stripped" else "details"
        eachFile(inputs, context, onProgress, step, R.string.engine_progress_writing) { input, pdfInput, output ->
            context.surgeon.writeMetadata(pdfInput, metadata, output)
            "${input.baseName} $step.pdf" to
                Strings.get(if (metadata.stripAll) R.string.engine_metadata_stripped else R.string.engine_metadata_updated)
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
    override val title = Strings.get(R.string.engine_title_clean)
    override val arity = Op.Arity.EachIndependently

    override suspend fun run(
        inputs: List<SheafFile>,
        context: OpContext,
        onProgress: (Progress) -> Unit
    ): List<OpOutcome> = withContext(Dispatchers.IO) {
        eachFile(inputs, context, onProgress, "cleaned", R.string.engine_progress_cleaning) { input, pdfInput, output ->
            val report = context.surgeon.cleanDocument(pdfInput, spec, output)
            "${input.baseName} cleaned.pdf" to describe(report)
        }
    }

    private fun describe(report: CleanReport): String {
        if (report.total == 0) return Strings.get(R.string.engine_clean_nothing)
        val parts = buildList {
            if (report.details > 0) add(Strings.get(R.string.engine_clean_details))
            if (report.xmp > 0) add(Strings.get(R.string.engine_clean_xmp))
            if (report.scripts > 0) add(Strings.plural(R.plurals.engine_clean_scripts, report.scripts))
            if (report.attachments > 0) add(Strings.plural(R.plurals.engine_clean_attachments, report.attachments))
            if (report.comments > 0) add(Strings.plural(R.plurals.engine_clean_comments, report.comments))
            if (report.thumbnails > 0) add(Strings.plural(R.plurals.engine_clean_thumbnails, report.thumbnails))
            if (report.privateData > 0) add(Strings.get(R.string.engine_clean_private))
        }
        return Strings.get(R.string.engine_clean_removed, parts.joinToString(Strings.get(R.string.engine_list_separator)))
    }
}
