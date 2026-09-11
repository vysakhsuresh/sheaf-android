package com.layerbit.sheaf.ops

import com.layerbit.sheaf.files.SheafFile
import com.layerbit.sheaf.pdf.CompressionLevel
import com.layerbit.sheaf.pdf.PdfException
import com.layerbit.sheaf.pdf.PdfInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
class CompressOp(private val level: CompressionLevel) : Op {

    override val tool = ToolId.COMPRESS
    override val title = "Compress (${level.label})"
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
                        )
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
}

/**
 * Encrypts a copy with AES-256.
 *
 * The password is held in memory for the length of the job and nowhere else. It is never
 * written to the database, never logged, and not recoverable - which is worth saying out loud
 * in the UI, because a person who encrypts their only copy of something and forgets the
 * password has lost it permanently, and no amount of support can undo that.
 */
class SetPasswordOp(private val password: String) : Op {

    override val tool = ToolId.SET_PASSWORD
    override val title = "Add a password"
    override val arity = Op.Arity.EachIndependently

    override suspend fun run(
        inputs: List<SheafFile>,
        context: OpContext,
        onProgress: (Progress) -> Unit
    ): List<OpOutcome> = withContext(Dispatchers.IO) {
        if (password.isBlank()) {
            return@withContext listOf(OpOutcome.Failed("", "Enter a password first."))
        }

        val outcomes = mutableListOf<OpOutcome>()
        inputs.forEachIndexed { index, input ->
            context.checkCancelled()
            onProgress(Progress(index, inputs.size, input.displayName))

            val output = context.workspace.newOutput(input.baseName, "protected")
            outcomes += try {
                context.surgeon.setPassword(
                    input = PdfInput(input.file, context.passwords[input.file.path]),
                    userPassword = password,
                    output = output
                )
                OpOutcome.Produced(
                    SheafFile(
                        output,
                        "${input.baseName} protected.pdf",
                        SheafFile.Origin.Derived("protected")
                    )
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
                    )
                )
            } catch (e: PdfException.PasswordRequired) {
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
