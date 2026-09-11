package com.layerbit.sheaf.ops

import com.layerbit.sheaf.files.SheafFile
import com.layerbit.sheaf.pdf.DocumentMetadata
import com.layerbit.sheaf.pdf.PdfException
import com.layerbit.sheaf.pdf.PdfInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Puts two or four pages on each sheet, for printing. */
class NUpOp(private val perSheet: Int) : Op {

    override val tool = ToolId.N_UP
    override val title = "$perSheet pages per sheet"
    override val arity = Op.Arity.EachIndependently

    override suspend fun run(
        inputs: List<SheafFile>,
        context: OpContext,
        onProgress: (Progress) -> Unit
    ): List<OpOutcome> = withContext(Dispatchers.IO) {
        eachFile(inputs, context, onProgress, "n-up", "Laying out") { input, pdfInput, output ->
            context.surgeon.nUp(pdfInput, perSheet, output) { _, _ -> context.checkCancelled() }
            "${input.baseName} $perSheet-up.pdf"
        }
    }
}

/**
 * Cuts a document into parts that each fit under a size limit.
 *
 * The limit people actually have is an email attachment cap, so the sizes offered are the ones
 * that matter: 5, 10 and 25 MB.
 *
 * The split is planned from the average page size rather than measured part by part. Measuring
 * means writing every candidate to disk, which on a 300-page scan is minutes of work for a
 * number that can only ever be a guide - shared objects mean the parts never add up exactly.
 * So the result screen shows what each part actually came out at.
 */
class SplitBySizeOp(private val maxBytes: Long) : Op {

    override val tool = ToolId.SPLIT_BY_SIZE
    override val title = "Split under ${maxBytes / (1024 * 1024)} MB"
    override val arity = Op.Arity.ExactlyOne

    override suspend fun run(
        inputs: List<SheafFile>,
        context: OpContext,
        onProgress: (Progress) -> Unit
    ): List<OpOutcome> = withContext(Dispatchers.IO) {
        val input = inputs.firstOrNull()
            ?: return@withContext listOf(OpOutcome.Failed("", "No document was selected."))

        val pdfInput = PdfInput(input.file, context.passwords[input.file.path])

        if (input.sizeBytes <= maxBytes) {
            return@withContext listOf(
                OpOutcome.Skipped(
                    input.displayName,
                    "This document is already under that size, so it was left alone."
                )
            )
        }

        val groups = try {
            context.surgeon.planSizeSplit(pdfInput, maxBytes) { _, _ -> context.checkCancelled() }
        } catch (e: PdfException) {
            return@withContext listOf(OpOutcome.Failed(input.displayName, e.message ?: "Failed."))
        }

        if (groups.size <= 1) {
            return@withContext listOf(
                OpOutcome.Failed(
                    input.displayName,
                    "A single page of this document is already larger than that limit."
                )
            )
        }

        val outcomes = mutableListOf<OpOutcome>()
        groups.forEachIndexed { index, pages ->
            context.checkCancelled()
            onProgress(Progress(index, groups.size, "Part ${index + 1} of ${groups.size}"))

            val partName = "${input.baseName} part ${index + 1}"
            val output = context.workspace.newOutput(partName, "size-split")
            outcomes += try {
                context.surgeon.extractPages(pdfInput, pages, emptyMap(), output)
                OpOutcome.Produced(SheafFile(output, "$partName.pdf", SheafFile.Origin.Derived("size-split")))
            } catch (e: PdfException) {
                output.delete()
                OpOutcome.Failed(partName, e.message ?: "This part could not be written.")
            }
        }
        onProgress(Progress(groups.size, groups.size, "Done"))
        outcomes
    }

    companion object {
        const val FIVE_MB = 5L * 1024 * 1024
        const val TEN_MB = 10L * 1024 * 1024
        const val TWENTY_FIVE_MB = 25L * 1024 * 1024
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
            "${input.baseName} $step.pdf"
        }
    }
}
