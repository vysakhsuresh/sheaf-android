package com.layerbit.sheaf.ops

import com.layerbit.sheaf.files.SheafFile
import com.layerbit.sheaf.pdf.NewField
import com.layerbit.sheaf.pdf.PdfException
import com.layerbit.sheaf.pdf.PdfInput
import com.layerbit.sheaf.pdf.SignSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The tools that vouch for a document: an archive copy, a real form, a digital signature. */

/**
 * An archive copy in the PDF/A-2b manner.
 *
 * The standard is what records offices, courts and auditors ask for: a file that will open
 * the same way in twenty years. It needs the colours named, the details in XMP, no scripts,
 * attachments or password, and every font inside the file. The last is the one thing that
 * cannot be fixed without the font, and the result says when it could not be.
 */
class PdfAOp : Op {

    override val tool = ToolId.PDF_A
    override val title = "Archive copy (PDF/A)"
    override val arity = Op.Arity.EachIndependently

    override suspend fun run(
        inputs: List<SheafFile>,
        context: OpContext,
        onProgress: (Progress) -> Unit
    ): List<OpOutcome> = withContext(Dispatchers.IO) {
        eachFile(inputs, context, onProgress, "archive", "Archiving") { input, pdfInput, output ->
            val wasLocked = runCatching { context.surgeon.inspect(pdfInput).isEncrypted }.getOrDefault(false)
            val report = context.surgeon.makeArchival(pdfInput, output)
            val parts = mutableListOf("PDF/A-2b archive copy: colour profile and archive details added")
            val took = buildList {
                if (report.scripts > 0) add(if (report.scripts == 1) "1 script or action" else "${report.scripts} scripts and actions")
                if (report.attachments > 0) add(if (report.attachments == 1) "1 attached file" else "${report.attachments} attached files")
            }
            if (took.isNotEmpty()) parts += took.joinToString(" and ") + " taken out, as the standard asks"
            if (wasLocked) parts += "the password is gone, as archives must open for anyone"
            if (report.fontsMissing > 0) {
                parts += "${report.fontsMissing} ${if (report.fontsMissing == 1) "font the file uses is" else "fonts the file uses are"} " +
                    "not inside it, so a strict checker will flag that; saving it again from the program that made it, with fonts embedded, fixes it"
            }
            "${input.baseName} (PDF-A).pdf" to parts.joinToString("; ")
        }
    }
}

/** Puts fillable fields on a document, so anyone can fill it in, in any PDF reader. */
class CreateFormOp(private val fields: List<NewField>) : Op {

    override val tool = ToolId.CREATE_FORM
    override val title = "Create a form"
    override val arity = Op.Arity.ExactlyOne

    override suspend fun run(
        inputs: List<SheafFile>,
        context: OpContext,
        onProgress: (Progress) -> Unit
    ): List<OpOutcome> = withContext(Dispatchers.IO) {
        val input = inputs.firstOrNull()
            ?: return@withContext listOf(OpOutcome.Failed("", "No document was selected."))
        val wanted = fields.filter { it.name.isNotBlank() }
        if (wanted.isEmpty()) {
            return@withContext listOf(OpOutcome.Failed(input.displayName, "Draw a field on the page and give it a name first."))
        }
        onProgress(Progress(0, 1, "Adding ${wanted.size} ${if (wanted.size == 1) "field" else "fields"}"))
        val pdfInput = PdfInput(input.file, context.passwords[input.file.path])
        val output = context.workspace.newOutput(input.baseName, "form")
        val names = try {
            context.surgeon.addFormFields(pdfInput, wanted, output)
        } catch (e: PdfException) {
            output.delete()
            return@withContext listOf(OpOutcome.Failed(input.displayName, e.message ?: "Failed."))
        }
        onProgress(Progress(1, 1, "Done"))
        val counts = NewField.Kind.entries.mapNotNull { kind ->
            val n = wanted.count { it.kind == kind }
            if (n == 0) null else "$n ${if (n == 1) kind.label.lowercase() else kind.label.lowercase().replace("box", "boxes").replace("list", "lists")}"
        }
        listOf(
            OpOutcome.Produced(
                SheafFile(output, "${input.baseName} (form).pdf", SheafFile.Origin.Derived("form")),
                "${counts.joinToString(", ")} added (${names.size} ${if (names.size == 1) "field" else "fields"}): anyone can fill it in, in any PDF reader"
            )
        )
    }
}

/**
 * A digital signature from a certificate the person already has.
 *
 * The first file is the PDF and the second the .pfx or .p12. The password is held for the
 * run and nowhere else. A locked PDF is signed unlocked, and the result says so, because a
 * lock would have to be redone around the signature, which breaks it.
 */
class DigitalSignOp(private val password: String, private val spec: SignSpec) : Op {

    override val tool = ToolId.DIGITAL_SIGN
    override val title = "Digital signature"
    override val arity = Op.Arity.Together(min = 2)

    override suspend fun run(
        inputs: List<SheafFile>,
        context: OpContext,
        onProgress: (Progress) -> Unit
    ): List<OpOutcome> = withContext(Dispatchers.IO) {
        val document = inputs.firstOrNull { it.isPdf }
            ?: return@withContext listOf(OpOutcome.Failed("", "Pick the PDF first."))
        val certificate = inputs.firstOrNull { !it.isPdf }
            ?: return@withContext listOf(OpOutcome.Failed(document.displayName, "Add your certificate file (.pfx or .p12)."))

        onProgress(Progress(0, 1, "Signing ${document.displayName}"))
        val pdfInput = PdfInput(document.file, context.passwords[document.file.path])
        val wasLocked = runCatching { context.surgeon.inspect(pdfInput).isEncrypted }.getOrDefault(false)
        val output = context.workspace.newOutput(document.baseName, "signed")
        val report = try {
            context.surgeon.signWithCertificate(pdfInput, certificate.file, password, spec, output)
        } catch (e: PdfException) {
            output.delete()
            return@withContext listOf(OpOutcome.Failed(document.displayName, e.message ?: "Failed."))
        }
        onProgress(Progress(1, 1, "Done"))
        val where = if (spec.visible) {
            "shown at the foot of the ${if (spec.onFirstPage) "first" else "last"} page"
        } else {
            "invisible on the page, listed in any reader's signature panel"
        }
        val lock = if (wasLocked) ". The signed copy opens without the password: locking it again would break the signature" else ""
        listOf(
            OpOutcome.Produced(
                SheafFile(output, "${document.baseName} (signed).pdf", SheafFile.Origin.Derived("signed")),
                "Signed by ${report.signer}, certificate from ${report.issuer}; $where$lock"
            )
        )
    }
}
