package com.layerbit.sheaf.ops

import com.layerbit.sheaf.R
import com.layerbit.sheaf.files.SheafFile
import com.layerbit.sheaf.pdf.PdfException
import com.layerbit.sheaf.pdf.PdfInput
import com.layerbit.sheaf.pdf.PictureEdit
import com.layerbit.sheaf.text.Strings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Moves, resizes, takes out or swaps the pictures already on a page.
 *
 * Nothing else on the page changes: the text stays text, and only the one drawing instruction
 * for each picture is rewritten. See [com.layerbit.sheaf.pdf.PictureSurgery].
 */
class EditPicturesOp(private val edits: Map<Int, List<PictureEdit>>) : Op {

    override val tool = ToolId.EDIT_IMAGES
    override val title = Strings.get(R.string.engine_title_edit_pictures)
    override val arity = Op.Arity.ExactlyOne

    override suspend fun run(
        inputs: List<SheafFile>,
        context: OpContext,
        onProgress: (Progress) -> Unit
    ): List<OpOutcome> = withContext(Dispatchers.IO) {
        val input = inputs.firstOrNull() ?: return@withContext listOf(OpOutcome.Failed("", Strings.get(R.string.engine_pick_one_pdf)))
        if (edits.values.all { it.isEmpty() }) {
            return@withContext listOf(OpOutcome.Failed(input.displayName, Strings.get(R.string.engine_edit_pictures_nothing)))
        }
        onProgress(Progress(0, 1, Strings.get(R.string.engine_progress_changing_pictures)))
        val output = context.workspace.newOutput("${input.baseName} (pictures edited)", "pictures")
        val report = try {
            context.surgeon.editPictures(PdfInput(input.file, context.passwords[input.file.path]), edits, output)
        } catch (e: PdfException) {
            output.delete()
            return@withContext listOf(OpOutcome.Failed(input.displayName, e.message ?: Strings.get(R.string.engine_edit_pictures_failed)))
        }
        onProgress(Progress(1, 1, Strings.get(R.string.engine_done)))
        fun count(n: Int, what: Int) = if (n == 0) null else Strings.plural(what, n)
        val parts = listOfNotNull(
            count(report.removed, R.plurals.engine_pictures_removed),
            count(report.moved, R.plurals.engine_pictures_moved),
            count(report.replaced, R.plurals.engine_pictures_replaced)
        )
        val missed = if (report.missed > 0) Strings.plural(R.plurals.engine_pictures_missed, report.missed) else ""
        listOf(
            OpOutcome.Produced(
                SheafFile(output, "${input.baseName} (pictures edited).pdf", SheafFile.Origin.Derived("pictures")),
                parts.joinToString(Strings.get(R.string.engine_list_separator)) + missed
            )
        )
    }
}
