package com.layerbit.sheaf.ops

import com.layerbit.sheaf.files.SheafFile
import com.layerbit.sheaf.pdf.PdfException
import com.layerbit.sheaf.pdf.PdfInput
import com.layerbit.sheaf.pdf.PictureEdit
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
    override val title = "Edit pictures"
    override val arity = Op.Arity.ExactlyOne

    override suspend fun run(
        inputs: List<SheafFile>,
        context: OpContext,
        onProgress: (Progress) -> Unit
    ): List<OpOutcome> = withContext(Dispatchers.IO) {
        val input = inputs.firstOrNull() ?: return@withContext listOf(OpOutcome.Failed("", "Pick one PDF."))
        if (edits.values.all { it.isEmpty() }) {
            return@withContext listOf(OpOutcome.Failed(input.displayName, "Move, take out or replace a picture first."))
        }
        onProgress(Progress(0, 1, "Changing the pictures"))
        val output = context.workspace.newOutput("${input.baseName} (pictures edited)", "pictures")
        val report = try {
            context.surgeon.editPictures(PdfInput(input.file, context.passwords[input.file.path]), edits, output)
        } catch (e: PdfException) {
            output.delete()
            return@withContext listOf(OpOutcome.Failed(input.displayName, e.message ?: "The pictures could not be changed."))
        }
        onProgress(Progress(1, 1, "Done"))
        fun count(n: Int, what: String) = if (n == 0) null else "$n ${if (n == 1) "picture" else "pictures"} $what"
        val parts = listOfNotNull(count(report.removed, "taken out"), count(report.moved, "moved"), count(report.replaced, "replaced"))
        val missed = if (report.missed > 0) "; ${report.missed} ${if (report.missed == 1) "change" else "changes"} could not be made" else ""
        listOf(
            OpOutcome.Produced(
                SheafFile(output, "${input.baseName} (pictures edited).pdf", SheafFile.Origin.Derived("pictures")),
                parts.joinToString(", ") + missed
            )
        )
    }
}
