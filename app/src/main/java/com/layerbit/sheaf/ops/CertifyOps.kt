package com.layerbit.sheaf.ops

import com.layerbit.sheaf.R
import com.layerbit.sheaf.files.SheafFile
import com.layerbit.sheaf.pdf.NewField
import com.layerbit.sheaf.pdf.PdfException
import com.layerbit.sheaf.pdf.PdfInput
import com.layerbit.sheaf.pdf.SignSpec
import com.layerbit.sheaf.text.Strings
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
    override val title = Strings.get(R.string.engine_title_pdfa)
    override val arity = Op.Arity.EachIndependently

    override suspend fun run(
        inputs: List<SheafFile>,
        context: OpContext,
        onProgress: (Progress) -> Unit
    ): List<OpOutcome> = withContext(Dispatchers.IO) {
        eachFile(inputs, context, onProgress, "archive", R.string.engine_progress_archiving) { input, pdfInput, output ->
            val wasLocked = runCatching { context.surgeon.inspect(pdfInput).isEncrypted }.getOrDefault(false)
            val report = context.surgeon.makeArchival(pdfInput, output)
            val parts = mutableListOf(Strings.get(R.string.engine_pdfa_done))
            val took = buildList {
                if (report.scripts > 0) add(Strings.plural(R.plurals.engine_pdfa_scripts, report.scripts))
                if (report.attachments > 0) add(Strings.plural(R.plurals.engine_clean_attachments, report.attachments))
            }
            if (took.isNotEmpty()) parts += Strings.get(R.string.engine_pdfa_taken_out, took.joinToString(Strings.get(R.string.engine_list_and)))
            if (wasLocked) parts += Strings.get(R.string.engine_pdfa_unlocked)
            if (report.fontsMissing > 0) parts += Strings.plural(R.plurals.engine_pdfa_fonts_missing, report.fontsMissing)
            "${input.baseName} (PDF-A).pdf" to parts.joinToString(Strings.get(R.string.engine_clause_separator))
        }
    }
}

/** Puts fillable fields on a document, so anyone can fill it in, in any PDF reader. */
class CreateFormOp(private val fields: List<NewField>) : Op {

    override val tool = ToolId.CREATE_FORM
    override val title = Strings.get(R.string.engine_title_create_form)
    override val arity = Op.Arity.ExactlyOne

    override suspend fun run(
        inputs: List<SheafFile>,
        context: OpContext,
        onProgress: (Progress) -> Unit
    ): List<OpOutcome> = withContext(Dispatchers.IO) {
        val input = inputs.firstOrNull()
            ?: return@withContext listOf(OpOutcome.Failed("", Strings.get(R.string.engine_no_document)))
        val wanted = fields.filter { it.name.isNotBlank() }
        if (wanted.isEmpty()) {
            return@withContext listOf(OpOutcome.Failed(input.displayName, Strings.get(R.string.engine_form_nothing)))
        }
        onProgress(Progress(0, 1, Strings.plural(R.plurals.engine_progress_adding_fields, wanted.size)))
        val pdfInput = PdfInput(input.file, context.passwords[input.file.path])
        val output = context.workspace.newOutput(input.baseName, "form")
        val names = try {
            context.surgeon.addFormFields(pdfInput, wanted, output)
        } catch (e: PdfException) {
            output.delete()
            return@withContext listOf(OpOutcome.Failed(input.displayName, e.message ?: Strings.get(R.string.engine_failed)))
        }
        onProgress(Progress(1, 1, Strings.get(R.string.engine_done)))
        val counts = NewField.Kind.entries.mapNotNull { kind ->
            val n = wanted.count { it.kind == kind }
            if (n == 0) null else Strings.plural(kind.countRes, n)
        }
        listOf(
            OpOutcome.Produced(
                SheafFile(output, "${input.baseName} (form).pdf", SheafFile.Origin.Derived("form")),
                Strings.plural(R.plurals.engine_form_detail, names.size, counts.joinToString(Strings.get(R.string.engine_list_separator)), names.size)
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
    override val title = Strings.get(R.string.engine_title_digital_sign)
    override val arity = Op.Arity.Together(min = 2)

    override suspend fun run(
        inputs: List<SheafFile>,
        context: OpContext,
        onProgress: (Progress) -> Unit
    ): List<OpOutcome> = withContext(Dispatchers.IO) {
        val document = inputs.firstOrNull { it.isPdf }
            ?: return@withContext listOf(OpOutcome.Failed("", Strings.get(R.string.engine_pick_pdf_first)))
        val certificate = inputs.firstOrNull { !it.isPdf }
            ?: return@withContext listOf(OpOutcome.Failed(document.displayName, Strings.get(R.string.engine_certificate_missing)))

        onProgress(Progress(0, 1, Strings.get(R.string.engine_progress_signing, document.displayName)))
        val pdfInput = PdfInput(document.file, context.passwords[document.file.path])
        val wasLocked = runCatching { context.surgeon.inspect(pdfInput).isEncrypted }.getOrDefault(false)
        val output = context.workspace.newOutput(document.baseName, "signed")
        val report = try {
            context.surgeon.signWithCertificate(pdfInput, certificate.file, password, spec, output)
        } catch (e: PdfException) {
            output.delete()
            return@withContext listOf(OpOutcome.Failed(document.displayName, e.message ?: Strings.get(R.string.engine_failed)))
        }
        onProgress(Progress(1, 1, Strings.get(R.string.engine_done)))
        val where = Strings.get(
            when {
                !spec.visible -> R.string.engine_signature_invisible
                spec.onFirstPage -> R.string.engine_signature_first_page
                else -> R.string.engine_signature_last_page
            }
        )
        val signed = if (wasLocked) R.string.engine_signed_by_unlocked else R.string.engine_signed_by
        listOf(
            OpOutcome.Produced(
                SheafFile(output, "${document.baseName} (signed).pdf", SheafFile.Origin.Derived("signed")),
                Strings.get(signed, report.signer, report.issuer, where)
            )
        )
    }
}
