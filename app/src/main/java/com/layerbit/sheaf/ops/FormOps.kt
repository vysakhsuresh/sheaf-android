package com.layerbit.sheaf.ops

import com.layerbit.sheaf.R
import com.layerbit.sheaf.files.SheafFile
import com.layerbit.sheaf.pdf.PdfException
import com.layerbit.sheaf.pdf.PdfInput
import com.layerbit.sheaf.text.Strings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fills in a PDF that was built as a form, and optionally seals the values into the page.
 *
 * This is the one tool here that needs the document to have been made a particular way. A
 * government form exported from a form designer has real fields and this types into them; a
 * scan of the same form printed out has none, and no amount of work on this side can invent
 * them. The screen says which of the two it is holding rather than offering an empty list,
 * and points at Add text for the other case.
 *
 * Flattening is offered because an unflattened filled form is still a form: the next person
 * to open it can retype every answer, and some readers will offer to. Flattened, it behaves
 * like a filled-in piece of paper. It cannot be undone, which is why it is a choice and not
 * the default.
 */
class FillFormOp(
    private val values: Map<String, String>,
    private val flatten: Boolean
) : Op {

    override val tool = ToolId.FORMS
    override val title = Strings.get(if (flatten) R.string.engine_title_fill_seal else R.string.engine_title_fill)
    override val arity = Op.Arity.ExactlyOne

    override suspend fun run(
        inputs: List<SheafFile>,
        context: OpContext,
        onProgress: (Progress) -> Unit
    ): List<OpOutcome> = withContext(Dispatchers.IO) {
        val input = inputs.firstOrNull()
            ?: return@withContext listOf(OpOutcome.Failed("", Strings.get(R.string.engine_no_document)))
        if (values.isEmpty()) {
            return@withContext listOf(
                OpOutcome.Failed(input.displayName, Strings.get(R.string.engine_fill_nothing))
            )
        }

        onProgress(Progress(0, 1, Strings.get(R.string.engine_progress_filling, input.displayName)))
        val pdfInput = PdfInput(input.file, context.passwords[input.file.path])
        val output = context.workspace.newOutput(input.baseName, "filled")

        try {
            context.surgeon.fillForm(pdfInput, values, flatten, output)
        } catch (e: PdfException) {
            output.delete()
            return@withContext listOf(OpOutcome.Failed(input.displayName, e.message ?: Strings.get(R.string.engine_failed)))
        }

        onProgress(Progress(1, 1, Strings.get(R.string.engine_done)))
        val filled = values.count { it.value.isNotBlank() }
        listOf(
            OpOutcome.Produced(
                SheafFile(output, "${input.baseName} filled.pdf", SheafFile.Origin.Derived("filled")),
                Strings.plural(R.plurals.engine_fields_filled, filled).let {
                    if (flatten) Strings.get(R.string.engine_fields_sealed, it) else it
                }
            )
        )
    }
}
