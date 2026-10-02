package com.layerbit.sheaf.ops

import com.layerbit.sheaf.files.SheafFile
import com.layerbit.sheaf.pdf.PdfException
import com.layerbit.sheaf.pdf.PdfInput
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
    override val title = if (flatten) "Fill in a form and seal it" else "Fill in a form"
    override val arity = Op.Arity.ExactlyOne

    override suspend fun run(
        inputs: List<SheafFile>,
        context: OpContext,
        onProgress: (Progress) -> Unit
    ): List<OpOutcome> = withContext(Dispatchers.IO) {
        val input = inputs.firstOrNull()
            ?: return@withContext listOf(OpOutcome.Failed("", "No document was selected."))
        if (values.isEmpty()) {
            return@withContext listOf(
                OpOutcome.Failed(input.displayName, "Fill in at least one field first.")
            )
        }

        onProgress(Progress(0, 1, "Filling in ${input.displayName}"))
        val pdfInput = PdfInput(input.file, context.passwords[input.file.path])
        val output = context.workspace.newOutput(input.baseName, "filled")

        try {
            context.surgeon.fillForm(pdfInput, values, flatten, output)
        } catch (e: PdfException) {
            output.delete()
            return@withContext listOf(OpOutcome.Failed(input.displayName, e.message ?: "Failed."))
        }

        onProgress(Progress(1, 1, "Done"))
        val filled = values.count { it.value.isNotBlank() }
        listOf(
            OpOutcome.Produced(
                SheafFile(output, "${input.baseName} filled.pdf", SheafFile.Origin.Derived("filled")),
                buildString {
                    append(if (filled == 1) "1 field filled" else "$filled fields filled")
                    if (flatten) append(", and sealed so they cannot be retyped")
                }
            )
        )
    }
}
