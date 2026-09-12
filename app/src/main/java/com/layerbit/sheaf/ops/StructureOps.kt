package com.layerbit.sheaf.ops

import com.layerbit.sheaf.files.SheafFile
import com.layerbit.sheaf.pdf.PageSelection
import com.layerbit.sheaf.pdf.PdfException
import com.layerbit.sheaf.pdf.PdfInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The operations that change a document's page tree: merge, split, extract, organise.
 *
 * They share a habit worth stating once. A file that cannot be read is reported as
 * [OpOutcome.Failed] with a sentence saying why, and the run continues. One unreadable scan in
 * a batch of forty must not discard the other thirty-nine, and "something went wrong" on the
 * whole batch is the behavior that makes people stop trusting an app like this.
 */

/** Joins documents end to end, in the order given. */
class MergeOp(private val outputName: String? = null) : Op {

    override val tool = ToolId.MERGE
    override val title = "Merge"
    override val arity = Op.Arity.Together(min = 2)

    override suspend fun run(
        inputs: List<SheafFile>,
        context: OpContext,
        onProgress: (Progress) -> Unit
    ): List<OpOutcome> = withContext(Dispatchers.IO) {
        if (inputs.size < 2) {
            return@withContext listOf(OpOutcome.Failed("", "Pick at least two documents to merge."))
        }

        val name = outputName ?: "${inputs.first().baseName} + ${inputs.size - 1} more"
        val output = context.workspace.newOutput(name, "merged")

        try {
            context.surgeon.merge(
                inputs = inputs.map { PdfInput(it.file, context.passwords[it.file.path]) },
                output = output
            ) { done, total ->
                context.checkCancelled()
                onProgress(Progress(done, total, "Adding ${inputs.getOrNull(done - 1)?.displayName ?: ""}"))
            }
        } catch (e: PdfException) {
            output.delete()
            return@withContext listOf(OpOutcome.Failed(name, e.message ?: "Merge failed."))
        }

        listOf(
            OpOutcome.Produced(
                SheafFile(output, "$name.pdf", SheafFile.Origin.Derived("merged")),
                "${inputs.size} files joined"
            )
        )
    }
}

/**
 * Keeps only the pages named by [spec], in the order the spec gives them.
 *
 * Deleting pages is the same operation with the unwanted ones left out, which is why there is
 * no separate delete. One path through the page tree instead of two, and half as many ways to
 * produce a document that opens everywhere except in Acrobat.
 */
class ExtractPagesOp(private val spec: String) : Op {

    override val tool = ToolId.EXTRACT
    override val title = "Extract pages"
    override val arity = Op.Arity.ExactlyOne

    override suspend fun run(
        inputs: List<SheafFile>,
        context: OpContext,
        onProgress: (Progress) -> Unit
    ): List<OpOutcome> = withContext(Dispatchers.IO) {
        val input = inputs.firstOrNull()
            ?: return@withContext listOf(OpOutcome.Failed("", "No document was selected."))

        val pdfInput = PdfInput(input.file, context.passwords[input.file.path])
        val info = try {
            context.surgeon.inspect(pdfInput)
        } catch (e: PdfException) {
            return@withContext listOf(OpOutcome.Failed(input.displayName, e.message ?: "Unreadable."))
        }

        val pages = PageSelection.parse(spec, info.pageCount)
        if (pages.isEmpty()) {
            return@withContext listOf(
                OpOutcome.Failed(
                    input.displayName,
                    "No pages matched \"$spec\". This document has ${info.pageCount} pages."
                )
            )
        }

        onProgress(Progress(0, 1, "Writing ${pages.size} pages"))
        val output = context.workspace.newOutput(input.baseName, "pages")

        try {
            context.surgeon.extractPages(pdfInput, pages, emptyMap(), output)
        } catch (e: PdfException) {
            output.delete()
            return@withContext listOf(OpOutcome.Failed(input.displayName, e.message ?: "Extract failed."))
        }

        onProgress(Progress(1, 1, "Done"))
        listOf(
            OpOutcome.Produced(
                SheafFile(
                    output,
                    "${input.baseName} p${PageSelection.describe(pages)}.pdf",
                    SheafFile.Origin.Derived("pages")
                ),
                "Kept pages ${PageSelection.describe(pages)} of ${info.pageCount}"
            )
        )
    }
}

/** Breaks one document into several. */
class SplitOp(private val mode: Mode) : Op {

    sealed interface Mode {
        /** Fixed-size chunks. A 10-page document every 3 gives 3, 3, 3, 1. */
        data class EveryNPages(val size: Int) : Mode

        /** One output file per page. */
        data object EachPage : Mode

        /** One output per comma-separated group, e.g. "1-3, 4-8, 9-". */
        data class Ranges(val spec: String) : Mode
    }

    override val tool = ToolId.SPLIT
    override val title = when (mode) {
        is Mode.EveryNPages -> "Split every ${mode.size} pages"
        Mode.EachPage -> "Split into single pages"
        is Mode.Ranges -> "Split by ranges"
    }
    override val arity = Op.Arity.ExactlyOne

    override suspend fun run(
        inputs: List<SheafFile>,
        context: OpContext,
        onProgress: (Progress) -> Unit
    ): List<OpOutcome> = withContext(Dispatchers.IO) {
        val input = inputs.firstOrNull()
            ?: return@withContext listOf(OpOutcome.Failed("", "No document was selected."))

        val pdfInput = PdfInput(input.file, context.passwords[input.file.path])
        val info = try {
            context.surgeon.inspect(pdfInput)
        } catch (e: PdfException) {
            return@withContext listOf(OpOutcome.Failed(input.displayName, e.message ?: "Unreadable."))
        }

        val groups: List<List<Int>> = when (mode) {
            is Mode.EveryNPages -> PageSelection.chunks(info.pageCount, mode.size)
            Mode.EachPage -> PageSelection.chunks(info.pageCount, 1)
            is Mode.Ranges -> mode.spec.split(',')
                .map { PageSelection.parse(it, info.pageCount) }
                .filter { it.isNotEmpty() }
        }

        if (groups.isEmpty()) {
            return@withContext listOf(
                OpOutcome.Failed(input.displayName, "That split produced no pages.")
            )
        }
        if (groups.size == 1) {
            return@withContext listOf(
                OpOutcome.Failed(
                    input.displayName,
                    "That would produce a single file identical to the original."
                )
            )
        }

        val outcomes = mutableListOf<OpOutcome>()
        groups.forEachIndexed { index, pages ->
            context.checkCancelled()
            onProgress(Progress(index, groups.size, "Part ${index + 1} of ${groups.size}"))

            val partName = "${input.baseName} part ${index + 1}"
            val output = context.workspace.newOutput(partName, "split")
            outcomes += try {
                context.surgeon.extractPages(pdfInput, pages, emptyMap(), output)
                OpOutcome.Produced(
                    SheafFile(output, "$partName.pdf", SheafFile.Origin.Derived("split")),
                    "Pages ${PageSelection.describe(pages)}"
                )
            } catch (e: PdfException) {
                output.delete()
                OpOutcome.Failed(partName, e.message ?: "This part could not be written.")
            }
        }
        onProgress(Progress(groups.size, groups.size, "Done"))
        outcomes
    }
}

/**
 * Reorder, rotate and delete in a single pass.
 *
 * [order] is the final page order as zero-based indices into the original; anything missing
 * from it is deleted. [rotations] is a delta in degrees applied to the original index, not the
 * new position, because that is what the user pointed at on screen.
 */
class OrganiseOp(
    private val order: List<Int>,
    private val rotations: Map<Int, Int> = emptyMap()
) : Op {

    override val tool = ToolId.ORGANISE
    override val title = "Organise pages"
    override val arity = Op.Arity.ExactlyOne

    override suspend fun run(
        inputs: List<SheafFile>,
        context: OpContext,
        onProgress: (Progress) -> Unit
    ): List<OpOutcome> = withContext(Dispatchers.IO) {
        val input = inputs.firstOrNull()
            ?: return@withContext listOf(OpOutcome.Failed("", "No document was selected."))

        if (order.isEmpty()) {
            return@withContext listOf(
                OpOutcome.Failed(input.displayName, "A document must keep at least one page.")
            )
        }

        onProgress(Progress(0, 1, "Rebuilding ${order.size} pages"))
        val pdfInput = PdfInput(input.file, context.passwords[input.file.path])
        val output = context.workspace.newOutput(input.baseName, "organised")

        try {
            context.surgeon.extractPages(pdfInput, order, rotations, output)
        } catch (e: PdfException) {
            output.delete()
            return@withContext listOf(OpOutcome.Failed(input.displayName, e.message ?: "Failed."))
        }

        onProgress(Progress(1, 1, "Done"))
        listOf(
            OpOutcome.Produced(
                SheafFile(output, "${input.baseName} organised.pdf", SheafFile.Origin.Derived("organised")),
                "${order.size} pages" +
                    if (rotations.isNotEmpty()) ", ${rotations.size} rotated" else ""
            )
        )
    }
}
