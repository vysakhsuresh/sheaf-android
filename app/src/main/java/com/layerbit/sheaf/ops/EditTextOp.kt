package com.layerbit.sheaf.ops

import com.layerbit.sheaf.R
import com.layerbit.sheaf.files.SheafFile
import com.layerbit.sheaf.pdf.PdfException
import com.layerbit.sheaf.pdf.PdfInput
import com.layerbit.sheaf.pdf.TextEdit
import com.layerbit.sheaf.text.Strings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Changes words already on the page: each changed line is painted over in the page's own
 * colour and the new words written in its place. See [com.layerbit.sheaf.pdf.TextEditing].
 */
class EditTextOp(private val edits: List<TextEdit>) : Op {

    override val tool = ToolId.EDIT_TEXT
    override val title = Strings.get(R.string.engine_title_edit_text)
    override val arity = Op.Arity.ExactlyOne

    override suspend fun run(
        inputs: List<SheafFile>,
        context: OpContext,
        onProgress: (Progress) -> Unit
    ): List<OpOutcome> = withContext(Dispatchers.IO) {
        val input = inputs.firstOrNull() ?: return@withContext listOf(OpOutcome.Failed("", Strings.get(R.string.engine_pick_one_pdf)))
        if (edits.isEmpty()) return@withContext listOf(OpOutcome.Failed(input.displayName, Strings.get(R.string.engine_edit_text_nothing)))
        onProgress(Progress(0, 1, Strings.get(R.string.engine_progress_writing_changes)))
        val output = context.workspace.newOutput("${input.baseName} (edited)", "edited")
        val made = try {
            context.surgeon.editText(PdfInput(input.file, context.passwords[input.file.path]), edits, output)
        } catch (e: PdfException) {
            output.delete()
            return@withContext listOf(OpOutcome.Failed(input.displayName, e.message ?: Strings.get(R.string.engine_edit_text_failed)))
        }
        onProgress(Progress(1, 1, Strings.get(R.string.engine_done)))
        val pages = edits.map { it.pageIndex }.distinct().size
        listOf(
            OpOutcome.Produced(
                SheafFile(output, "${input.baseName} (edited).pdf", SheafFile.Origin.Derived("edited")),
                if (pages > 1) Strings.plural(R.plurals.engine_edit_text_detail_pages, made, made, pages)
                else Strings.plural(R.plurals.engine_edit_text_detail, made)
            )
        )
    }
}
