package com.layerbit.sheaf.ops

import com.layerbit.sheaf.R
import com.layerbit.sheaf.files.SheafFile
import com.layerbit.sheaf.pdf.CompressionLevel
import com.layerbit.sheaf.pdf.DocumentPermissions
import com.layerbit.sheaf.pdf.PdfException
import com.layerbit.sheaf.pdf.PdfInput
import com.layerbit.sheaf.text.Strings
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
    private val targetBytes: Long? = null,
    /**
     * When no level gets under [targetBytes], let each page become one picture of itself,
     * its words kept invisibly underneath. Asked for, never assumed: text stops being sharp.
     */
    private val allowPictures: Boolean = false
) : Op {

    override val tool = ToolId.COMPRESS
    override val title = if (targetBytes != null) {
        Strings.get(R.string.engine_title_compress_to, describeBytes(targetBytes))
    } else {
        Strings.get(R.string.engine_title_compress, level.label)
    }
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
                            Strings.get(R.string.engine_compress_larger)
                        )
                    }

                    result.negligible -> {
                        output.delete()
                        outcomes += OpOutcome.Skipped(
                            input.displayName,
                            if (result.imagesRecompressed == 0) {
                                Strings.get(R.string.engine_compress_text_only)
                            } else {
                                Strings.get(R.string.engine_compress_negligible, (result.savedFraction * 100).toInt())
                            }
                        )
                    }

                    else -> outcomes += OpOutcome.Produced(
                        SheafFile(
                            output,
                            "${input.baseName} compressed.pdf",
                            SheafFile.Origin.Derived("compressed")
                        ),
                        Strings.plural(
                            R.plurals.engine_compress_detail, result.imagesRecompressed,
                            (result.savedFraction * 100).toInt(), result.imagesRecompressed
                        )
                    )
                }
            } catch (e: PdfException) {
                output.delete()
                outcomes += OpOutcome.Failed(input.displayName, e.message ?: Strings.get(R.string.engine_compress_failed))
            }
        }

        onProgress(Progress(inputs.size, inputs.size, Strings.get(R.string.engine_done)))
        outcomes
    }

    /** Lightest level first, stopping at the first that fits under [target]. */
    private fun toSize(input: SheafFile, context: OpContext, target: Long, step: (String) -> Unit): OpOutcome {
        if (input.sizeBytes <= target) {
            return OpOutcome.Skipped(input.displayName, Strings.get(R.string.engine_compress_already_under, describeBytes(target)))
        }
        val pdfInput = PdfInput(input.file, context.passwords[input.file.path])
        var smallest: Pair<File, CompressionLevel>? = null
        try {
            for (candidate in CompressionLevel.entries) {
                context.checkCancelled()
                step(Strings.get(R.string.engine_compress_trying, candidate.label.lowercase()))
                val output = context.workspace.newOutput(input.baseName, "compressed")
                val result = context.surgeon.compress(pdfInput, candidate, output) { _, _ -> context.checkCancelled() }
                val size = result.compressedBytes
                if (size <= target) {
                    smallest?.first?.delete()
                    return OpOutcome.Produced(
                        SheafFile(output, "${input.baseName} compressed.pdf", SheafFile.Origin.Derived("compressed")),
                        Strings.get(R.string.engine_compress_under_at, describeBytes(target), describeBytes(size), candidate.label.lowercase())
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
            return OpOutcome.Failed(input.displayName, e.message ?: Strings.get(R.string.engine_compress_failed))
        }
        val (file, level) = smallest ?: return OpOutcome.Failed(input.displayName, Strings.get(R.string.engine_compress_failed))
        if (allowPictures) {
            picturesUnder(input, context, target, step)?.let { pictures ->
                file.delete()
                return pictures
            }
        }
        if (file.length() >= input.sizeBytes) {
            file.delete()
            return OpOutcome.Skipped(
                input.displayName,
                Strings.get(R.string.engine_compress_cannot_get_under, describeBytes(target))
            )
        }
        return OpOutcome.Produced(
            SheafFile(file, "${input.baseName} compressed.pdf", SheafFile.Origin.Derived("compressed")),
            Strings.get(R.string.engine_compress_still_over, describeBytes(target), describeBytes(file.length()), level.label.lowercase())
        )
    }


    /**
     * Each page as one JPEG of itself, down a ladder of sharpness until the file fits. The
     * page's words go underneath, invisible, so it can still be searched and copied. Null when
     * not even the last rung fits, so the caller reports its own smallest instead.
     */
    private fun picturesUnder(input: SheafFile, context: OpContext, target: Long, step: (String) -> Unit): OpOutcome? {
        val password = context.passwords[input.file.path]
        val pdfInput = PdfInput(input.file, password)
        val words = runCatching { context.surgeon.placedWords(pdfInput) { _, _ -> context.checkCancelled() } }.getOrDefault(emptyList())
        val layers = words.groupBy { it.pageIndex }.mapValues { (_, list) ->
            list.map { com.layerbit.sheaf.pdf.TextPlacement(it.text, it.area.left, it.area.top, it.area.width, it.area.height) }
        }
        val encrypted = runCatching { context.surgeon.inspect(pdfInput).isEncrypted }.getOrDefault(false)
        val engine = if (encrypted) context.encryptedReader else context.engine
        for ((dpi, quality) in PICTURE_LADDER) {
            context.checkCancelled()
            step(Strings.get(R.string.engine_compress_trying_pictures, dpi))
            val jpegs = mutableListOf<Pair<File, com.layerbit.sheaf.pdf.PageSize>>()
            val output = context.workspace.newOutput(input.baseName, "compressed")
            try {
                engine.open(input.file, password).use { doc ->
                    for (i in 0 until doc.pageCount) {
                        context.checkCancelled()
                        val size = doc.pageSize(i)
                        val bitmap = doc.renderPage(i, (size.widthPoints / 72f * dpi).toInt().coerceAtLeast(16))
                        val jpeg = context.workspace.newOutput("${input.baseName}-$i", "page", "jpg")
                        jpeg.outputStream().buffered().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, it) }
                        bitmap.recycle()
                        jpegs += jpeg to size
                    }
                }
                context.surgeon.picturePages(jpegs, layers, output)
            } catch (e: OpCancelled) {
                output.delete()
                throw e
            } catch (e: Exception) {
                output.delete()
                return null
            } finally {
                jpegs.forEach { it.first.delete() }
            }
            if (output.length() <= target) {
                return OpOutcome.Produced(
                    SheafFile(output, "${input.baseName} compressed.pdf", SheafFile.Origin.Derived("compressed")),
                    Strings.get(
                        R.string.engine_compress_as_pictures,
                        describeBytes(input.sizeBytes), describeBytes(output.length()), describeBytes(target), dpi
                    )
                )
            }
            output.delete()
        }
        return null
    }
    companion object {
        /** Sharpness, then JPEG quality, for pages made pictures: Foolscap's ladder. */
        private val PICTURE_LADDER = listOf(150 to 70, 120 to 60, 100 to 50, 85 to 45, 72 to 40, 60 to 35)

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
    override val title = Strings.get(if (restrictOnly) R.string.engine_title_restrict else R.string.engine_title_add_password)
    override val arity = Op.Arity.EachIndependently

    override suspend fun run(
        inputs: List<SheafFile>,
        context: OpContext,
        onProgress: (Progress) -> Unit
    ): List<OpOutcome> = withContext(Dispatchers.IO) {
        val withheld = buildList {
            if (!permissions.allowPrinting) add(Strings.get(R.string.engine_withheld_printing))
            if (!permissions.allowCopying) add(Strings.get(R.string.engine_withheld_copying))
            if (!permissions.allowChanges) add(Strings.get(R.string.engine_withheld_changes))
        }
        if (!restrictOnly && password.isBlank()) {
            return@withContext listOf(OpOutcome.Failed("", Strings.get(R.string.engine_password_empty)))
        }
        if (restrictOnly && withheld.isEmpty()) {
            return@withContext listOf(OpOutcome.Failed("", Strings.get(R.string.engine_restrict_nothing)))
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
                        restrictOnly -> Strings.get(
                            R.string.engine_restricted_detail,
                            withheld.joinToString(Strings.get(R.string.engine_list_or))
                        )
                        withheld.isEmpty() -> Strings.get(R.string.engine_encrypted)
                        else -> Strings.get(
                            R.string.engine_encrypted_restricted,
                            withheld.joinToString(Strings.get(R.string.engine_list_or))
                        )
                    }
                )
            } catch (e: PdfException) {
                output.delete()
                OpOutcome.Failed(input.displayName, e.message ?: Strings.get(R.string.engine_password_add_failed))
            }
        }
        onProgress(Progress(inputs.size, inputs.size, Strings.get(R.string.engine_done)))
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
    override val title = Strings.get(R.string.engine_title_remove_password)
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
                    Strings.get(R.string.engine_password_needed)
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
                    Strings.get(R.string.engine_unlocked)
                )
            } catch (_: PdfException.PasswordRequired) {
                output.delete()
                OpOutcome.Failed(input.displayName, Strings.get(R.string.engine_password_wrong))
            } catch (e: PdfException) {
                output.delete()
                OpOutcome.Failed(input.displayName, e.message ?: Strings.get(R.string.engine_password_remove_failed))
            }
        }
        onProgress(Progress(inputs.size, inputs.size, Strings.get(R.string.engine_done)))
        outcomes
    }
}
