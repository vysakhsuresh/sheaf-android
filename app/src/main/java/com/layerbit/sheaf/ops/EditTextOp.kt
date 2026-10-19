package com.layerbit.sheaf.ops

import com.layerbit.sheaf.files.SheafFile
import com.layerbit.sheaf.pdf.PdfException
import com.layerbit.sheaf.pdf.PdfInput
import com.layerbit.sheaf.pdf.TextEdit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Changes words already on the page: each changed line is painted over in the page's own
 * colour and the new words written in its place. See [com.layerbit.sheaf.pdf.TextEditing].
 */
class EditTextOp(private val edits: List<TextEdit>) : Op {

    override val tool = ToolId.EDIT_TEXT
    override val title = "Edit text"
    override val arity = Op.Arity.ExactlyOne

    override suspend fun run(
        inputs: List<SheafFile>,
        context: OpContext,
        onProgress: (Progress) -> Unit
    ): List<OpOutcome> = withContext(Dispatchers.IO) {
        val input = inputs.firstOrNull() ?: return@withContext listOf(OpOutcome.Failed("", "Pick one PDF."))
        if (edits.isEmpty()) return@withContext listOf(OpOutcome.Failed(input.displayName, "Tap a line of text and change it first."))
        onProgress(Progress(0, 1, "Writing the changes"))
        val output = context.workspace.newOutput("${input.baseName} (edited)", "edited")
        val made = try {
            context.surgeon.editText(PdfInput(input.file, context.passwords[input.file.path]), edits, output)
        } catch (e: PdfException) {
            output.delete()
            return@withContext listOf(OpOutcome.Failed(input.displayName, e.message ?: "The changes could not be written."))
        }
        onProgress(Progress(1, 1, "Done"))
        val pages = edits.map { it.pageIndex }.distinct().size
        listOf(
            OpOutcome.Produced(
                SheafFile(output, "${input.baseName} (edited).pdf", SheafFile.Origin.Derived("edited")),
                "$made ${if (made == 1) "change" else "changes"} made" + (if (pages > 1) " across $pages pages" else "") +
                    ". The old words are painted over, not taken out: to remove words for good, use Remove areas"
            )
        )
    }
}
