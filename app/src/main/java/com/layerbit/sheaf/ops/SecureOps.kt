package com.layerbit.sheaf.ops

import com.layerbit.sheaf.files.SheafFile
import com.layerbit.sheaf.pdf.CompressionLevel
import com.layerbit.sheaf.pdf.DocumentPermissions
import com.layerbit.sheaf.pdf.PdfException
import com.layerbit.sheaf.pdf.PdfInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Makes a PDF smaller by re-encoding the images inside it.
 *
 * That is where essentially all the bytes in a large PDF are - a scanned page is a photograph
 * with a PDF wrapper. Text and vector content is left completely alone, so text stays
 * selectable and stays sharp at any zoom, which is the opposite trade from the tools that
 * flatten a whole page to a JPEG and call it compression.
 *
 * The honest consequence is that a text-only document will barely shrink. The result says so
 * in words rather than presenting a 2% saving as a success.
 */
class CompressOp(
    private val level: CompressionLevel,
    /**
     * A size to get under, in bytes. The levels are tried lightest first and the first that
     * fits is kept; when none does, the smallest is kept and the result says so.
     */
    private val targetBytes: Long? = null
) : Op {

    override val tool = ToolId.COMPRESS
    override val title = if (targetBytes != null) "Compress to under ${describeBytes(targetBytes)}" else "Compress (${level.label})"
    override val arity = Op.Arity.EachIndependently

    override suspend fun run(
        inputs: List<SheafFile>,
        context: OpContext,
        onProgress: (Progress) -> Unit
    ): List<OpOutcome> = withContext(Dispatchers.IO) {
        val outcomes = mutableListOf<OpOutcome>()

        inputs.forEachIndexed { index, input ->
            context.checkCancelled()
            onProgress(Progress(index, inputs.size, input.displayName))

            if (targetBytes != null) {
                outcomes += toSize(input, context, targetBytes) { label ->
                    onProgress(Progress(index, inputs.size, "${input.displayName}: $label"))
                }
                return@forEachIndexed
            }

            val output = context.workspace.newOutput(input.baseName, "compressed")
            try {
                val result = context.surgeon.compress(
                    input = PdfInput(input.file, context.passwords[input.file.path]),
                    level = level,
                    output = output
                ) { _, _ -> context.checkCancelled() }

                when {
                    // Re-encoding can make a file BIGGER - an already-optimised PDF, or one
                    // whose images were stored more efficiently than JPEG manages. Handing
                    // back something larger than the original is never the right answer.
                    result.compressedBytes >= result.originalBytes -> {
                        output.delete()
                        outcomes += OpOutcome.Skipped(
                            input.displayName,
                            "Already as small as it goes - compressing made it larger, so the original was kept."
                        )
                    }

                    result.negligible -> {
                        output.delete()
                        outcomes += OpOutcome.Skipped(
                            input.displayName,
                            if (result.imagesRecompressed == 0) {
                                "Nothing to compress - this document is text, not scanned images."
                            } else {
                                "Only ${(result.savedFraction * 100).toInt()}% smaller, so the original was kept."
                            }
                        )
                    }

                    else -> outcomes += OpOutcome.Produced(
                        SheafFile(
                            output,
                            "${input.baseName} compressed.pdf",
                            SheafFile.Origin.Derived("compressed")
                        ),
                        "${(result.savedFraction * 100).toInt()}% smaller - " +
                            "${result.imagesRecompressed} images re-encoded"
                    )
                }
            } catch (e: PdfException) {
                output.delete()
                outcomes += OpOutcome.Failed(input.displayName, e.message ?: "Compression failed.")
            }
        }

        onProgress(Progress(inputs.size, inputs.size, "Done"))
        outcomes
    }

    /** Lightest level first, stopping at the first that fits under [target]. */
    private fun toSize(input: SheafFile, context: OpContext, target: Long, step: (String) -> Unit): OpOutcome {
        if (input.sizeBytes <= target) {
            return OpOutcome.Skipped(input.displayName, "Already under ${describeBytes(target)}, so it was left alone.")
        }
        val pdfInput = PdfInput(input.file, context.passwords[input.file.path])
        var smallest: Pair<File, CompressionLevel>? = null
        try {
            for (candidate in CompressionLevel.entries) {
                context.checkCancelled()
                step("trying ${candidate.label.lowercase()}")
                val output = context.workspace.newOutput(input.baseName, "compressed")
                val result = context.surgeon.compress(pdfInput, candidate, output) { _, _ -> context.checkCancelled() }
                val size = result.compressedBytes
                if (size <= target) {
                    smallest?.first?.delete()
                    return OpOutcome.Produced(
                        SheafFile(output, "${input.baseName} compressed.pdf", SheafFile.Origin.Derived("compressed")),
                        "Under ${describeBytes(target)} at ${describeBytes(size)}, with ${candidate.label.lowercase()} compression"
                    )
                }
                val best = smallest
                if (best == null || size < best.first.length()) {
                    best?.first?.delete()
                    smallest = output to candidate
                } else {
                    output.delete()
                }
            }
        } catch (e: PdfException) {
            smallest?.first?.delete()
            return OpOutcome.Failed(input.displayName, e.message ?: "Compression failed.")
        }
        val (file, level) = smallest ?: return OpOutcome.Failed(input.displayName, "Compression failed.")
        if (file.length() >= input.sizeBytes) {
            file.delete()
            return OpOutcome.Skipped(
                input.displayName,
                "Cannot get under ${describeBytes(target)}: the pictures are already as small as they go, and " +
                    "re-encoding them made the file larger. Split by size can cut it into parts that fit."
            )
        }
        return OpOutcome.Produced(
            SheafFile(file, "${input.baseName} compressed.pdf", SheafFile.Origin.Derived("compressed")),
            "Still over ${describeBytes(target)}: the smallest it goes is ${describeBytes(file.length())}, " +
                "with ${level.label.lowercase()} compression. Split by size can cut it into parts that fit."
        )
    }

    companion object {
        fun describeBytes(bytes: Long): String {
            val mb = bytes / (1024f * 1024f)
            return if (mb == mb.toInt().toFloat()) "${mb.toInt()} MB" else "%.1f MB".format(mb)
        }
    }
}

/**
 * Encrypts a copy with AES-256.
 *
 * The password is held in memory for the length of the job and nowhere else. It is never
 * written to the database, never logged, and not recoverable.
 *
 * That is worth saying out loud in the UI. Someone who encrypts their only copy of something
 * and forgets the password has lost it permanently, and no amount of support can undo it.
 *
 * The permission flags are a request rather than a lock, and the screen says so. The PDF
 * specification asks a conforming reader to honour them; the bits sit inside a file the
 * recipient's password opens, so a reader that ignores them can. Encryption is the part that
 * actually holds.
 */
class SetPasswordOp(
    private val password: String,
    private val permissions: DocumentPermissions = DocumentPermissions(),
    /**
     * Opens without a password but asks readers to withhold what [permissions] withholds.
     * The owner password is random and thrown away, so the restrictions cannot be lifted
     * by typing it - and [password] is ignored.
     */
    private val restrictOnly: Boolean = false
) : Op {

    override val tool = ToolId.SET_PASSWORD
    override val title = if (restrictOnly) "Restrict a PDF" else "Add a password"
    override val arity = Op.Arity.EachIndependently

    override suspend fun run(
        inputs: List<SheafFile>,
        context: OpContext,
        onProgress: (Progress) -> Unit
    ): List<OpOutcome> = withContext(Dispatchers.IO) {
        val withheld = buildList {
            if (!permissions.allowPrinting) add("printing")
            if (!permissions.allowCopying) add("copying text")
            if (!permissions.allowChanges) add("changes")
        }
        if (!restrictOnly && password.isBlank()) {
            return@withContext listOf(OpOutcome.Failed("", "Enter a password first."))
        }
        if (restrictOnly && withheld.isEmpty()) {
            return@withContext listOf(OpOutcome.Failed("", "Choose at least one thing to restrict."))
        }

        val step = if (restrictOnly) "restricted" else "protected"
        val outcomes = mutableListOf<OpOutcome>()
        inputs.forEachIndexed { index, input ->
            context.checkCancelled()
            onProgress(Progress(index, inputs.size, input.displayName))

            val output = context.workspace.newOutput(input.baseName, step)
            outcomes += try {
                context.surgeon.setPassword(
                    input = PdfInput(input.file, context.passwords[input.file.path]),
                    userPassword = if (restrictOnly) "" else password,
                    output = output,
                    permissions = permissions,
                    restrictOnly = restrictOnly
                )
                OpOutcome.Produced(
                    SheafFile(
                        output,
                        "${input.baseName} $step.pdf",
                        SheafFile.Origin.Derived(step)
                    ),
                    when {
                        restrictOnly ->
                            "Opens without a password; asks readers not to allow " + withheld.joinToString(" or ")
                        withheld.isEmpty() -> "Encrypted with AES-256"
                        else ->
                            "Encrypted with AES-256, asking readers not to allow " + withheld.joinToString(" or ")
                    }
                )
            } catch (e: PdfException) {
                output.delete()
                OpOutcome.Failed(input.displayName, e.message ?: "Could not add a password.")
            }
        }
        onProgress(Progress(inputs.size, inputs.size, "Done"))
        outcomes
    }
}

/**
 * Writes an unlocked copy of a document whose password the user knows.
 *
 * This is not a password cracker and will never become one. It needs the correct password,
 * uses it to open the document exactly as any reader would, and saves the result without
 * encryption - the same thing "print to PDF" does, done properly and without leaving the
 * device.
 */
class RemovePasswordOp : Op {

    override val tool = ToolId.REMOVE_PASSWORD
    override val title = "Remove a password"
    override val arity = Op.Arity.EachIndependently

    override suspend fun run(
        inputs: List<SheafFile>,
        context: OpContext,
        onProgress: (Progress) -> Unit
    ): List<OpOutcome> = withContext(Dispatchers.IO) {
        val outcomes = mutableListOf<OpOutcome>()

        inputs.forEachIndexed { index, input ->
            context.checkCancelled()
            onProgress(Progress(index, inputs.size, input.displayName))

            val password = context.passwords[input.file.path]
            if (password.isNullOrEmpty()) {
                outcomes += OpOutcome.Failed(
                    input.displayName,
                    "Sheaf needs this document's password to unlock it."
                )
                return@forEachIndexed
            }

            val output = context.workspace.newOutput(input.baseName, "unlocked")
            outcomes += try {
                context.surgeon.removePassword(PdfInput(input.file, password), output)
                OpOutcome.Produced(
                    SheafFile(
                        output,
                        "${input.baseName} unlocked.pdf",
                        SheafFile.Origin.Derived("unlocked")
                    ),
                    "Opens without a password"
                )
            } catch (_: PdfException.PasswordRequired) {
                output.delete()
                OpOutcome.Failed(input.displayName, "That password did not open this document.")
            } catch (e: PdfException) {
                output.delete()
                OpOutcome.Failed(input.displayName, e.message ?: "Could not remove the password.")
            }
        }
        onProgress(Progress(inputs.size, inputs.size, "Done"))
        outcomes
    }
}
