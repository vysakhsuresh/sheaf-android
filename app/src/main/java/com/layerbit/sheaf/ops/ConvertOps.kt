package com.layerbit.sheaf.ops

import com.layerbit.sheaf.files.SheafFile
import com.layerbit.sheaf.pdf.ImageFormat
import com.layerbit.sheaf.pdf.PageSelection
import com.layerbit.sheaf.pdf.PageSpec
import com.layerbit.sheaf.pdf.PdfException
import com.layerbit.sheaf.pdf.PdfInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Builds a document from photos and scans, one image per page. */
class ImagesToPdfOp(
    private val spec: PageSpec,
    private val outputName: String? = null
) : Op {

    override val tool = ToolId.IMAGES_TO_PDF
    override val title = "Images to PDF"
    override val arity = Op.Arity.Together(min = 1)

    override suspend fun run(
        inputs: List<SheafFile>,
        context: OpContext,
        onProgress: (Progress) -> Unit
    ): List<OpOutcome> = withContext(Dispatchers.IO) {
        if (inputs.isEmpty()) {
            return@withContext listOf(OpOutcome.Failed("", "No images were selected."))
        }

        val name = outputName ?: inputs.first().baseName
        val output = context.workspace.newOutput(name, "from-images")

        try {
            context.surgeon.imagesToPdf(
                images = inputs.map { it.file },
                spec = spec,
                output = output
            ) { done, total ->
                context.checkCancelled()
                onProgress(Progress(done, total, "Page $done of $total"))
            }
        } catch (e: PdfException) {
            output.delete()
            return@withContext listOf(OpOutcome.Failed(name, e.message ?: "Could not build the PDF."))
        }

        listOf(
            OpOutcome.Produced(
                SheafFile(output, "$name.pdf", SheafFile.Origin.Derived("from-images"))
            )
        )
    }
}

/**
 * Rasterises pages to image files.
 *
 * Each page becomes its own output, so a 40-page export produces 40 results. That is what
 * makes the plural operation type pay for itself: the results screen already knows how to
 * show many files and offer to save them all.
 */
class PdfToImagesOp(
    private val dpi: Int,
    private val format: ImageFormat,
    private val pageSpec: String? = null
) : Op {

    override val tool = ToolId.PDF_TO_IMAGES
    override val title = "PDF to images ($dpi DPI, ${format.name})"
    override val arity = Op.Arity.ExactlyOne

    override suspend fun run(
        inputs: List<SheafFile>,
        context: OpContext,
        onProgress: (Progress) -> Unit
    ): List<OpOutcome> = withContext(Dispatchers.IO) {
        val input = inputs.firstOrNull()
            ?: return@withContext listOf(OpOutcome.Failed("", "No document was selected."))

        val pdfInput = PdfInput(input.file, context.passwords[input.file.path])
        val info = try {
            context.surgeon.inspect(pdfInput)
        } catch (e: PdfException) {
            return@withContext listOf(OpOutcome.Failed(input.displayName, e.message ?: "Unreadable."))
        }

        val pages = if (pageSpec.isNullOrBlank()) {
            PageSelection.all(info.pageCount)
        } else {
            PageSelection.parse(pageSpec, info.pageCount)
        }
        if (pages.isEmpty()) {
            return@withContext listOf(
                OpOutcome.Failed(
                    input.displayName,
                    "No pages matched \"$pageSpec\". This document has ${info.pageCount} pages."
                )
            )
        }

        // Its own directory, so a 300-page export does not scatter files across the workspace
        // and so the whole export can be swept up as one unit.
        val dir = File(context.workspace.newOutput(input.baseName, "images", "dir").path).also {
            it.mkdirs()
        }

        val files = try {
            context.surgeon.pdfToImages(
                input = pdfInput,
                pages = pages,
                dpi = dpi,
                format = format,
                outputDir = dir,
                baseName = input.baseName
            ) { done, total ->
                context.checkCancelled()
                onProgress(Progress(done, total, "Page $done of $total"))
            }
        } catch (e: PdfException) {
            dir.deleteRecursively()
            return@withContext listOf(OpOutcome.Failed(input.displayName, e.message ?: "Export failed."))
        }

        files.map { file ->
            OpOutcome.Produced(
                SheafFile(
                    file = file,
                    displayName = file.name,
                    origin = SheafFile.Origin.Derived("images"),
                    mimeType = format.mimeType
                )
            )
        }
    }
}
