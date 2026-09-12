package com.layerbit.sheaf.ops

import com.layerbit.sheaf.files.SheafFile
import com.layerbit.sheaf.pdf.CropSpec
import com.layerbit.sheaf.pdf.ImageStamp
import com.layerbit.sheaf.pdf.PageArea
import com.layerbit.sheaf.pdf.PageNumberSpec
import com.layerbit.sheaf.pdf.PdfException
import com.layerbit.sheaf.pdf.PdfInput
import com.layerbit.sheaf.pdf.WatermarkSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Lays repeating text across every page - DRAFT, CONFIDENTIAL, a name. */
class WatermarkOp(private val spec: WatermarkSpec) : Op {

    override val tool = ToolId.WATERMARK
    override val title = "Watermark"
    override val arity = Op.Arity.EachIndependently

    override suspend fun run(
        inputs: List<SheafFile>,
        context: OpContext,
        onProgress: (Progress) -> Unit
    ): List<OpOutcome> = withContext(Dispatchers.IO) {
        if (spec.text.isBlank()) {
            return@withContext listOf(OpOutcome.Failed("", "Type the watermark text first."))
        }
        eachFile(inputs, context, onProgress, "watermarked", "Watermark") { input, pdfInput, output ->
            var pages = 0
            context.surgeon.watermark(pdfInput, spec, output) { _, total ->
                context.checkCancelled(); pages = total
            }
            "${input.baseName} watermarked.pdf" to "\"${spec.text}\" on $pages pages"
        }
    }
}

/** Numbers the pages, including Bates-style zero padding for legal bundles. */
class PageNumberOp(private val spec: PageNumberSpec) : Op {

    override val tool = ToolId.PAGE_NUMBERS
    override val title = "Page numbers"
    override val arity = Op.Arity.EachIndependently

    override suspend fun run(
        inputs: List<SheafFile>,
        context: OpContext,
        onProgress: (Progress) -> Unit
    ): List<OpOutcome> = withContext(Dispatchers.IO) {
        eachFile(inputs, context, onProgress, "numbered", "Numbering") { input, pdfInput, output ->
            var pages = 0
            context.surgeon.addPageNumbers(pdfInput, spec, output) { _, total ->
                context.checkCancelled(); pages = total
            }
            val from = spec.startAt
            "${input.baseName} numbered.pdf" to
                "Numbered from $from, ${pages - spec.skipFirst} pages"
        }
    }
}

/**
 * Trims the margins, and optionally puts every page on one standard size.
 *
 * Cropping hides; it does not remove. What falls outside the new edge is still in the file and
 * comes back if someone crops it the other way. That is how PDF cropping works everywhere, and
 * it is why this is not a way to take something out of a document - [RedactOp] is.
 */
class CropOp(private val spec: CropSpec) : Op {

    override val tool = ToolId.CROP
    override val title = "Crop pages"
    override val arity = Op.Arity.EachIndependently

    override suspend fun run(
        inputs: List<SheafFile>,
        context: OpContext,
        onProgress: (Progress) -> Unit
    ): List<OpOutcome> = withContext(Dispatchers.IO) {
        val nothingToDo = spec.left == 0f && spec.top == 0f && spec.right == 0f &&
            spec.bottom == 0f && spec.resizeTo == null
        if (nothingToDo) {
            return@withContext listOf(OpOutcome.Failed("", "Choose how much to trim first."))
        }
        eachFile(inputs, context, onProgress, "cropped", "Cropping") { input, pdfInput, output ->
            var pages = 0
            context.surgeon.cropPages(pdfInput, spec, output) { _, total ->
                context.checkCancelled(); pages = total
            }
            val trimmed = (spec.left * 100).toInt()
            val size = spec.resizeTo?.name?.lowercase()?.replaceFirstChar { it.uppercase() }
            "${input.baseName} cropped.pdf" to buildString {
                if (trimmed > 0) append("Trimmed $trimmed% from each edge")
                if (size != null) {
                    if (isNotEmpty()) append(", ")
                    append("resized to $size")
                }
                if (isEmpty()) append("$pages pages")
            }
        }
    }
}

/**
 * Takes marked areas out of a document permanently.
 *
 * The marked pages are rendered to an image with the boxes painted on, and that image replaces
 * the page. The text underneath is not covered, it is gone - it was never written into the new
 * page. A rectangle drawn on top would leave it selectable in any other reader, which is how
 * redaction failures reach the news.
 *
 * The cost is stated plainly in the UI: a redacted page becomes a picture, so its text stops
 * being selectable and searchable. Only marked pages are affected.
 */
class RedactOp(
    private val areas: Map<Int, List<PageArea>>,
    private val dpi: Int = DEFAULT_DPI
) : Op {

    override val tool = ToolId.REDACT
    override val title = "Remove areas"
    override val arity = Op.Arity.ExactlyOne

    override suspend fun run(
        inputs: List<SheafFile>,
        context: OpContext,
        onProgress: (Progress) -> Unit
    ): List<OpOutcome> = withContext(Dispatchers.IO) {
        val input = inputs.firstOrNull()
            ?: return@withContext listOf(OpOutcome.Failed("", "No document was selected."))
        if (areas.values.all { it.isEmpty() }) {
            return@withContext listOf(
                OpOutcome.Failed(input.displayName, "Draw a box over what should be removed first.")
            )
        }

        val pdfInput = PdfInput(input.file, context.passwords[input.file.path])
        val output = context.workspace.newOutput(input.baseName, "redacted")

        try {
            context.surgeon.redact(pdfInput, areas, dpi, output) { done, total ->
                context.checkCancelled()
                onProgress(Progress(done, total, "Removing from page $done of $total"))
            }
        } catch (e: PdfException) {
            output.delete()
            return@withContext listOf(OpOutcome.Failed(input.displayName, e.message ?: "Failed."))
        }

        val boxes = areas.values.sumOf { it.size }
        val pages = areas.count { it.value.isNotEmpty() }
        listOf(
            OpOutcome.Produced(
                SheafFile(output, "${input.baseName} redacted.pdf", SheafFile.Origin.Derived("redacted")),
                "$boxes ${if (boxes == 1) "area" else "areas"} removed from " +
                    "$pages ${if (pages == 1) "page" else "pages"}, which are now images"
            )
        )
    }

    companion object {
        /** 200 DPI keeps a redacted page readable without doubling the size of the file. */
        const val DEFAULT_DPI = 200
    }
}

/**
 * Draws a signature onto a page and flattens it into the content.
 *
 * Flattened rather than added as an annotation, deliberately. An annotation can be moved or
 * deleted by whoever opens the file next, which is not what anyone means by signing something.
 *
 * This is not a digital signature and the UI says so. It is the same thing as printing a
 * document, signing it and scanning it back - done without the printer, and without the file
 * leaving the phone.
 */
class SignOp(private val stamp: ImageStamp) : Op {

    override val tool = ToolId.SIGN
    override val title = "Sign"
    override val arity = Op.Arity.ExactlyOne

    override suspend fun run(
        inputs: List<SheafFile>,
        context: OpContext,
        onProgress: (Progress) -> Unit
    ): List<OpOutcome> = withContext(Dispatchers.IO) {
        val input = inputs.firstOrNull()
            ?: return@withContext listOf(OpOutcome.Failed("", "No document was selected."))
        if (!stamp.image.isFile) {
            return@withContext listOf(OpOutcome.Failed(input.displayName, "Draw your signature first."))
        }

        onProgress(Progress(0, 1, "Placing the signature"))
        val pdfInput = PdfInput(input.file, context.passwords[input.file.path])
        val output = context.workspace.newOutput(input.baseName, "signed")

        try {
            context.surgeon.stampImage(pdfInput, stamp, output)
        } catch (e: PdfException) {
            output.delete()
            return@withContext listOf(OpOutcome.Failed(input.displayName, e.message ?: "Failed."))
        }

        onProgress(Progress(1, 1, "Done"))
        listOf(
            OpOutcome.Produced(
                SheafFile(output, "${input.baseName} signed.pdf", SheafFile.Origin.Derived("signed")),
                "Signed on page ${stamp.pageIndex + 1}"
            )
        )
    }
}

/**
 * The shape every one-in-one-out operation here shares.
 *
 * Each file is handled on its own and a failure is reported against that file rather than
 * thrown, so one unreadable document in a batch never discards the rest.
 */
internal suspend inline fun eachFile(
    inputs: List<SheafFile>,
    context: OpContext,
    noinline onProgress: (Progress) -> Unit,
    step: String,
    verb: String,
    crossinline work: (SheafFile, PdfInput, File) -> Pair<String, String?>
): List<OpOutcome> {
    val outcomes = mutableListOf<OpOutcome>()

    inputs.forEachIndexed { index, input ->
        context.checkCancelled()
        onProgress(Progress(index, inputs.size, "$verb ${input.displayName}"))

        val output = context.workspace.newOutput(input.baseName, step)
        outcomes += try {
            val (name, detail) = work(input, PdfInput(input.file, context.passwords[input.file.path]), output)
            OpOutcome.Produced(SheafFile(output, name, SheafFile.Origin.Derived(step)), detail)
        } catch (e: PdfException) {
            output.delete()
            OpOutcome.Failed(input.displayName, e.message ?: "$verb failed.")
        }
    }

    onProgress(Progress(inputs.size, inputs.size, "Done"))
    return outcomes
}
