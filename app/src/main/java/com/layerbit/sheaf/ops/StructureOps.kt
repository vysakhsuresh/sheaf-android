package com.layerbit.sheaf.ops

import com.layerbit.sheaf.R
import com.layerbit.sheaf.files.SheafFile
import com.layerbit.sheaf.pdf.PageSelection
import com.layerbit.sheaf.pdf.PdfException
import com.layerbit.sheaf.pdf.PdfInput
import kotlinx.coroutines.Dispatchers
import com.layerbit.sheaf.text.Strings
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
    override val title = Strings.get(R.string.catalog_tool_merge_title)
    override val arity = Op.Arity.Together(min = 2)

    override suspend fun run(
        inputs: List<SheafFile>,
        context: OpContext,
        onProgress: (Progress) -> Unit
    ): List<OpOutcome> = withContext(Dispatchers.IO) {
        if (inputs.size < 2) {
            return@withContext listOf(OpOutcome.Failed("", Strings.get(R.string.catalog_merge_need_two)))
        }

        val name = outputName ?: "${inputs.first().baseName} + ${inputs.size - 1} more"
        val output = context.workspace.newOutput(name, "merged")

        try {
            context.surgeon.merge(
                inputs = inputs.map { PdfInput(it.file, context.passwords[it.file.path]) },
                output = output
            ) { done, total ->
                context.checkCancelled()
                onProgress(Progress(done, total, Strings.get(R.string.catalog_progress_adding, inputs.getOrNull(done - 1)?.displayName ?: "")))
            }
        } catch (e: PdfException) {
            output.delete()
            return@withContext listOf(OpOutcome.Failed(name, e.message ?: Strings.get(R.string.catalog_merge_failed)))
        }

        listOf(
            OpOutcome.Produced(
                SheafFile(output, "$name.pdf", SheafFile.Origin.Derived("merged")),
                Strings.plural(R.plurals.catalog_merge_joined, inputs.size)
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
class ExtractPagesOp(private val spec: String, private val remove: Boolean = false) : Op {

    override val tool = ToolId.EXTRACT
    override val title = Strings.get(if (remove) R.string.catalog_remove_title else R.string.catalog_extract_title)
    override val arity = Op.Arity.ExactlyOne

    override suspend fun run(
        inputs: List<SheafFile>,
        context: OpContext,
        onProgress: (Progress) -> Unit
    ): List<OpOutcome> = withContext(Dispatchers.IO) {
        val input = inputs.firstOrNull()
            ?: return@withContext listOf(OpOutcome.Failed("", Strings.get(R.string.catalog_no_document)))

        val pdfInput = PdfInput(input.file, context.passwords[input.file.path])
        val info = try {
            context.surgeon.inspect(pdfInput)
        } catch (e: PdfException) {
            return@withContext listOf(OpOutcome.Failed(input.displayName, e.message ?: Strings.get(R.string.catalog_unreadable)))
        }

        val named = PageSelection.parse(spec, info.pageCount)
        if (named.isEmpty()) {
            return@withContext listOf(
                OpOutcome.Failed(
                    input.displayName,
                    Strings.plural(R.plurals.catalog_no_pages_matched, info.pageCount, spec, info.pageCount)
                )
            )
        }
        // Removing is keeping the others, in their own order.
        val pages = if (remove) PageSelection.invert(named, info.pageCount) else named
        if (pages.isEmpty()) {
            return@withContext listOf(
                OpOutcome.Failed(input.displayName, Strings.get(R.string.catalog_remove_every_page))
            )
        }

        onProgress(Progress(0, 1, Strings.plural(R.plurals.catalog_progress_writing_pages, pages.size)))
        val output = context.workspace.newOutput(input.baseName, "pages")

        try {
            context.surgeon.extractPages(pdfInput, pages, emptyMap(), output)
        } catch (e: PdfException) {
            output.delete()
            return@withContext listOf(OpOutcome.Failed(input.displayName, e.message ?: Strings.get(R.string.catalog_extract_failed)))
        }

        onProgress(Progress(1, 1, Strings.get(R.string.catalog_done)))
        listOf(
            OpOutcome.Produced(
                SheafFile(
                    output,
                    if (remove) "${input.baseName} without p${PageSelection.describe(named)}.pdf"
                    else "${input.baseName} p${PageSelection.describe(pages)}.pdf",
                    SheafFile.Origin.Derived("pages")
                ),
                if (remove) {
                    Strings.plural(R.plurals.catalog_removed_pages, named.size, PageSelection.describe(named), pages.size, info.pageCount)
                } else {
                    Strings.get(R.string.catalog_kept_pages, PageSelection.describe(pages), info.pageCount)
                }
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

        /**
         * One output per top-level entry in the document's own table of contents.
         *
         * The natural way to break up a scanned book or a bundle of statements, because the
         * author already said where the seams are. Only the top level is used: splitting at
         * every sub-heading of a contract would produce a file per clause.
         */
        data object AtBookmarks : Mode
    }

    override val tool = ToolId.SPLIT
    override val title = when (mode) {
        is Mode.EveryNPages -> Strings.plural(R.plurals.catalog_split_every_title, mode.size)
        Mode.EachPage -> Strings.get(R.string.catalog_split_single_title)
        is Mode.Ranges -> Strings.get(R.string.catalog_split_ranges_title)
        Mode.AtBookmarks -> Strings.get(R.string.catalog_split_bookmarks_title)
    }
    override val arity = Op.Arity.ExactlyOne

    override suspend fun run(
        inputs: List<SheafFile>,
        context: OpContext,
        onProgress: (Progress) -> Unit
    ): List<OpOutcome> = withContext(Dispatchers.IO) {
        val input = inputs.firstOrNull()
            ?: return@withContext listOf(OpOutcome.Failed("", Strings.get(R.string.catalog_no_document)))

        val pdfInput = PdfInput(input.file, context.passwords[input.file.path])
        val info = try {
            context.surgeon.inspect(pdfInput)
        } catch (e: PdfException) {
            return@withContext listOf(OpOutcome.Failed(input.displayName, e.message ?: Strings.get(R.string.catalog_unreadable)))
        }

        val groups: List<List<Int>> = when (mode) {
            is Mode.EveryNPages -> PageSelection.chunks(info.pageCount, mode.size)
            Mode.EachPage -> PageSelection.chunks(info.pageCount, 1)
            is Mode.Ranges -> mode.spec.split(',')
                .map { PageSelection.parse(it, info.pageCount) }
                .filter { it.isNotEmpty() }

            Mode.AtBookmarks -> {
                val starts = runCatching { context.surgeon.readOutline(pdfInput) }
                    .getOrDefault(emptyList())
                    .filter { it.depth == 0 }
                    .map { it.pageIndex }
                    .distinct()
                    .sorted()
                if (starts.isEmpty()) {
                    return@withContext listOf(
                        OpOutcome.Failed(
                            input.displayName,
                            Strings.get(R.string.catalog_split_no_toc)
                        )
                    )
                }
                // Each part runs from its own heading up to the next one. A first heading
                // that is not on page one leaves a front section, which is kept rather than
                // dropped - it is usually the cover.
                val boundaries = (if (starts.first() > 0) listOf(0) else emptyList()) + starts
                boundaries.mapIndexed { index, start ->
                    val end = boundaries.getOrNull(index + 1) ?: info.pageCount
                    (start until end).toList()
                }.filter { it.isNotEmpty() }
            }
        }

        if (groups.isEmpty()) {
            return@withContext listOf(
                OpOutcome.Failed(input.displayName, Strings.get(R.string.catalog_split_no_pages))
            )
        }
        // One group is only a problem when it is the WHOLE document. Splitting a 3-page file
        // by the range "1-2" is one file of two pages, which is a perfectly good answer - and
        // refusing it was the bug: the old check looked at the number of groups alone.
        if (groups.size == 1 && groups.first().size == info.pageCount) {
            return@withContext listOf(
                OpOutcome.Failed(
                    input.displayName,
                    Strings.get(R.string.catalog_split_whole)
                )
            )
        }

        val outcomes = mutableListOf<OpOutcome>()
        groups.forEachIndexed { index, pages ->
            context.checkCancelled()
            onProgress(Progress(index, groups.size, Strings.get(R.string.catalog_progress_part, index + 1, groups.size)))

            val partName = "${input.baseName} part ${index + 1}"
            val output = context.workspace.newOutput(partName, "split")
            outcomes += try {
                context.surgeon.extractPages(pdfInput, pages, emptyMap(), output)
                OpOutcome.Produced(
                    SheafFile(output, "$partName.pdf", SheafFile.Origin.Derived("split")),
                    Strings.get(R.string.catalog_split_part_pages, PageSelection.describe(pages))
                )
            } catch (e: PdfException) {
                output.delete()
                OpOutcome.Failed(partName, e.message ?: Strings.get(R.string.catalog_split_part_failed))
            }
        }
        onProgress(Progress(groups.size, groups.size, Strings.get(R.string.catalog_done)))
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
/**
 * Puts pages into a document at a chosen point - another document's, or blank ones.
 *
 * Merge already joins things end to end, so this exists for the case merge cannot do: the
 * signed page that has to go in at twelve, the blank left side a double-sided print needs.
 * Both halves are one tool because they answer the same question, and two tools would mean
 * choosing between them before knowing which you needed.
 *
 * The first file chosen is the document being added to; the second, if there is one, is what
 * gets inserted. Stated in those words on the screen, because with two files in a list there
 * is otherwise no way to tell which way round it goes.
 */
class InsertPagesOp(
    private val atPage: Int,
    private val blankPages: Int,
    /** Pages of the first document to take out, the new ones going in where they were. */
    private val replaceSpec: String = ""
) : Op {

    override val tool = ToolId.INSERT
    override val title = Strings.get(R.string.catalog_insert_title)

    // Together rather than ExactlyOne: the second file is not another job, it is part of this
    // one, and the two are read in the same pass.
    override val arity = Op.Arity.Together(min = 1)

    override suspend fun run(
        inputs: List<SheafFile>,
        context: OpContext,
        onProgress: (Progress) -> Unit
    ): List<OpOutcome> = withContext(Dispatchers.IO) {
        val base = inputs.firstOrNull()
            ?: return@withContext listOf(OpOutcome.Failed("", Strings.get(R.string.catalog_no_document)))
        val insert = inputs.getOrNull(1)

        if (insert == null && blankPages <= 0) {
            return@withContext listOf(
                OpOutcome.Failed(
                    base.displayName,
                    Strings.get(R.string.catalog_insert_need_second)
                )
            )
        }

        onProgress(Progress(0, 1, Strings.get(R.string.catalog_progress_inserting, base.displayName)))
        val replacing = replaceSpec.isNotBlank()
        val step = if (replacing) "replaced" else "inserted"
        val output = context.workspace.newOutput(base.baseName, step)
        val baseInput = PdfInput(base.file, context.passwords[base.file.path])

        val replace = if (replacing) {
            val total = try {
                context.surgeon.inspect(baseInput).pageCount
            } catch (e: PdfException) {
                return@withContext listOf(OpOutcome.Failed(base.displayName, e.message ?: Strings.get(R.string.catalog_unreadable)))
            }
            val pages = PageSelection.parse(replaceSpec, total)
            if (pages.isEmpty()) {
                return@withContext listOf(
                    OpOutcome.Failed(base.displayName, Strings.plural(R.plurals.catalog_replace_none_typed, total))
                )
            }
            if (pages.size >= total && insert == null && blankPages <= 0) {
                return@withContext listOf(OpOutcome.Failed(base.displayName, Strings.get(R.string.catalog_replace_every)))
            }
            pages
        } else {
            emptyList()
        }

        try {
            context.surgeon.insertPages(
                input = baseInput,
                insert = insert?.let { PdfInput(it.file, context.passwords[it.file.path]) },
                blankPages = blankPages,
                atIndex = if (replacing) replace.first() else (atPage - 1).coerceAtLeast(0),
                output = output,
                replace = replace
            )
        } catch (e: PdfException) {
            output.delete()
            return@withContext listOf(OpOutcome.Failed(base.displayName, e.message ?: Strings.get(R.string.catalog_failed)))
        }

        onProgress(Progress(1, 1, Strings.get(R.string.catalog_done)))
        val what = if (insert != null) insert.displayName else {
            Strings.plural(R.plurals.catalog_blank_pages, blankPages)
        }
        listOf(
            OpOutcome.Produced(
                SheafFile(
                    output,
                    "${base.baseName} $step.pdf",
                    SheafFile.Origin.Derived(step)
                ),
                if (replacing) {
                    Strings.plural(R.plurals.catalog_replaced_pages, replace.size, PageSelection.describe(replace), what)
                } else {
                    Strings.get(R.string.catalog_put_in_at, what, atPage)
                }
            )
        )
    }
}

class OrganiseOp(
    private val order: List<Int>,
    private val rotations: Map<Int, Int> = emptyMap()
) : Op {

    override val tool = ToolId.ORGANISE
    override val title = Strings.get(R.string.catalog_tool_organise_title)
    override val arity = Op.Arity.ExactlyOne

    override suspend fun run(
        inputs: List<SheafFile>,
        context: OpContext,
        onProgress: (Progress) -> Unit
    ): List<OpOutcome> = withContext(Dispatchers.IO) {
        val input = inputs.firstOrNull()
            ?: return@withContext listOf(OpOutcome.Failed("", Strings.get(R.string.catalog_no_document)))

        if (order.isEmpty()) {
            return@withContext listOf(
                OpOutcome.Failed(input.displayName, Strings.get(R.string.catalog_keep_one_page))
            )
        }

        onProgress(Progress(0, 1, Strings.plural(R.plurals.catalog_progress_rebuilding, order.size)))
        val pdfInput = PdfInput(input.file, context.passwords[input.file.path])
        val output = context.workspace.newOutput(input.baseName, "organised")

        try {
            context.surgeon.extractPages(pdfInput, order, rotations, output)
        } catch (e: PdfException) {
            output.delete()
            return@withContext listOf(OpOutcome.Failed(input.displayName, e.message ?: Strings.get(R.string.catalog_failed)))
        }

        onProgress(Progress(1, 1, Strings.get(R.string.catalog_done)))
        listOf(
            OpOutcome.Produced(
                SheafFile(output, "${input.baseName} organised.pdf", SheafFile.Origin.Derived("organised")),
                if (rotations.isEmpty()) Strings.plural(R.plurals.catalog_n_pages, order.size)
                else Strings.plural(R.plurals.catalog_pages_rotated, order.size, order.size, rotations.size)
            )
        )
    }
}
