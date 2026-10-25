package com.layerbit.sheaf.ops

import androidx.annotation.StringRes
import com.layerbit.sheaf.R
import com.layerbit.sheaf.convert.FlowFromPages
import com.layerbit.sheaf.convert.PptxWriter
import com.layerbit.sheaf.convert.SlideSpec
import com.layerbit.sheaf.convert.Readers
import com.layerbit.sheaf.convert.Writers
import com.layerbit.sheaf.files.SheafFile
import com.layerbit.sheaf.pdf.Block
import com.layerbit.sheaf.pdf.ImageFormat
import com.layerbit.sheaf.pdf.PageSelection
import com.layerbit.sheaf.pdf.PageSpec
import com.layerbit.sheaf.pdf.PdfException
import com.layerbit.sheaf.pdf.PdfInput
import com.layerbit.sheaf.text.Strings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** What PDF to Office can make. */
enum class OfficeTarget(@StringRes val labelRes: Int, @StringRes val titleRes: Int, val extension: String, val mimeType: String, val step: String) {
    WORD(R.string.catalog_office_word, R.string.catalog_office_word_title, "docx", Writers.MIME_DOCX, "word"),
    EXCEL(R.string.catalog_office_excel, R.string.catalog_office_excel_title, "xlsx", Writers.MIME_XLSX, "excel"),
    EPUB(R.string.catalog_office_epub, R.string.catalog_office_epub_title, "epub", Writers.MIME_EPUB, "epub"),
    HTML(R.string.catalog_office_html, R.string.catalog_office_html_title, "html", Writers.MIME_HTML, "html"),
    POWERPOINT(R.string.catalog_office_powerpoint, R.string.catalog_office_powerpoint_title, "pptx", PptxWriter.MIME_PPTX, "powerpoint");

    val label: String get() = Strings.get(labelRes)
}

/**
 * A PDF as a Word file, a spreadsheet, an EPUB book or a web page.
 *
 * The pages' lines are read with their size and place and turned back into paragraphs,
 * headings, lists and tables, and the pictures in the pages come along. Honest about what
 * it is: a PDF holds lines, not paragraphs, so this is reconstruction, and a scanned page
 * has no words to reconstruct until Make searchable has read it.
 */
class PdfToOfficeOp(private val target: OfficeTarget, private val withPictures: Boolean = true) : Op {

    override val tool = ToolId.PDF_TO_OFFICE
    override val title = Strings.get(target.titleRes)
    override val arity = Op.Arity.EachIndependently

    override suspend fun run(
        inputs: List<SheafFile>,
        context: OpContext,
        onProgress: (Progress) -> Unit
    ): List<OpOutcome> = withContext(Dispatchers.IO) {
        val outcomes = mutableListOf<OpOutcome>()
        inputs.forEachIndexed { index, input ->
            context.checkCancelled()
            onProgress(Progress(index, inputs.size, Strings.get(R.string.catalog_progress_reading_file, input.displayName)))
            val pdfInput = PdfInput(input.file, context.passwords[input.file.path])
            val output = context.workspace.newOutput(input.baseName, target.step, target.extension)
            if (target == OfficeTarget.POWERPOINT) {
                outcomes += slides(input, pdfInput, context, output) { label -> onProgress(Progress(index, inputs.size, label)) }
                return@forEachIndexed
            }
            outcomes += try {
                val pages = context.surgeon.pageLines(pdfInput) { done, total ->
                    context.checkCancelled()
                    onProgress(Progress(index, inputs.size, Strings.get(R.string.catalog_progress_reading_page, done, total)))
                }
                val words = pages.sumOf { lines -> lines.sumOf { it.text.length } }
                var flow = FlowFromPages.build(input.baseName, pages)
                var pictures = 0
                if (withPictures && target != OfficeTarget.EXCEL) {
                    // The pictures go at the end of the page they were on; where exactly on
                    // the page they sat is not something a flowing document can say.
                    val dir = File(context.workspace.newOutput(input.baseName, "pictures", "dir").path).also { it.mkdirs() }
                    val files = runCatching { context.surgeon.extractImages(pdfInput, dir, input.baseName) }.getOrDefault(emptyList())
                    val byPage = files.groupBy { pageOf(it.name) }
                    if (byPage.isNotEmpty()) {
                        val blocks = mutableListOf<Block>()
                        var page = 0
                        for (block in flow.blocks) {
                            if (block is Block.PageBreak) {
                                blocks += picturesFor(byPage[page]) .also { pictures += it.size }
                                page += 1
                            }
                            blocks += block
                        }
                        blocks += picturesFor(byPage[page]).also { pictures += it.size }
                        flow = flow.copy(blocks = blocks)
                    }
                    files.forEach { it.delete() }
                    dir.delete()
                }
                if (words == 0 && pictures == 0) {
                    output.delete()
                    OpOutcome.Failed(
                        input.displayName,
                        Strings.get(R.string.catalog_convert_no_words)
                    )
                } else {
                    onProgress(Progress(index, inputs.size, Strings.get(R.string.catalog_progress_writing_ext, target.extension)))
                    when (target) {
                        OfficeTarget.WORD -> Writers.docx(flow, output)
                        OfficeTarget.EXCEL -> Writers.xlsx(flow, output)
                        OfficeTarget.EPUB -> Writers.epub(flow, output)
                        OfficeTarget.HTML -> Writers.html(flow, output)
                        OfficeTarget.POWERPOINT -> Unit
                    }
                    val paragraphs = flow.blocks.count { it is Block.Paragraph }
                    val tables = flow.blocks.count { it is Block.Table }
                    OpOutcome.Produced(
                        SheafFile(output, "${input.baseName}.${target.extension}", SheafFile.Origin.Derived(target.step), target.mimeType),
                        run {
                            val made = listOfNotNull(
                                Strings.plural(R.plurals.catalog_n_paragraphs, paragraphs),
                                if (tables > 0) Strings.plural(R.plurals.catalog_n_tables, tables) else null,
                                if (pictures > 0) Strings.plural(R.plurals.catalog_n_pictures, pictures) else null
                            ).joinToString(", ")
                            val done = Strings.plural(R.plurals.catalog_convert_done, pages.size, made, pages.size)
                            if (words == 0) Strings.get(R.string.catalog_convert_done_no_words, done) else done
                        }
                    )
                }
            } catch (e: PdfException) {
                output.delete()
                OpOutcome.Failed(input.displayName, e.message ?: Strings.get(R.string.catalog_convert_failed))
            } catch (e: Exception) {
                output.delete()
                OpOutcome.Failed(input.displayName, e.message ?: Strings.get(R.string.catalog_write_failed))
            }
        }
        onProgress(Progress(inputs.size, inputs.size, Strings.get(R.string.catalog_done)))
        outcomes
    }

    /**
     * One slide per page: a picture of it, or its lines as text boxes where they sat.
     * The deck takes the first page's shape.
     */
    private fun slides(input: SheafFile, pdfInput: PdfInput, context: OpContext, output: File, step: (String) -> Unit): OpOutcome {
        var dir: File? = null
        return try {
            val info = context.surgeon.inspect(pdfInput)
            if (info.pageCount == 0) throw PdfException.Refused(Strings.get(R.string.catalog_no_pages))
            val sizes = info.pageSizes
            val specs = if (withPictures) {
                step(Strings.get(R.string.catalog_progress_drawing_pages))
                val folder = File(context.workspace.newOutput(input.baseName, "slides", "dir").path).also { it.mkdirs() }
                dir = folder
                val files = context.surgeon.pdfToImages(pdfInput, (0 until info.pageCount).toList(), SLIDE_DPI, ImageFormat.JPEG, folder, "page") { done, total ->
                    context.checkCancelled()
                    step(Strings.get(R.string.catalog_progress_drawing_page, done, total))
                }
                files.mapIndexed { i, file ->
                    val size = sizes.getOrNull(i) ?: sizes.first()
                    SlideSpec(size.widthPoints, size.heightPoints, file, emptyList())
                }
            } else {
                val pages = context.surgeon.pageLines(pdfInput) { done, total ->
                    context.checkCancelled()
                    step(Strings.get(R.string.catalog_progress_reading_page, done, total))
                }
                if (pages.all { lines -> lines.all { it.text.isBlank() } }) {
                    throw PdfException.Refused(Strings.get(R.string.catalog_slides_no_words))
                }
                pages.mapIndexed { i, lines ->
                    val size = sizes.getOrNull(i) ?: sizes.first()
                    SlideSpec(size.widthPoints, size.heightPoints, null, lines)
                }
            }
            step(Strings.get(R.string.catalog_progress_writing_deck))
            PptxWriter.write(input.baseName, specs, output)
            OpOutcome.Produced(
                SheafFile(output, "${input.baseName}.pptx", SheafFile.Origin.Derived("powerpoint"), PptxWriter.MIME_PPTX),
                Strings.plural(if (withPictures) R.plurals.catalog_slides_pictures else R.plurals.catalog_slides_text, specs.size)
            )
        } catch (e: PdfException) {
            output.delete()
            OpOutcome.Failed(input.displayName, e.message ?: Strings.get(R.string.catalog_deck_failed))
        } catch (e: Exception) {
            output.delete()
            OpOutcome.Failed(input.displayName, e.message ?: Strings.get(R.string.catalog_deck_write_failed))
        } finally {
            dir?.listFiles()?.forEach { it.delete() }
            dir?.delete()
        }
    }

    /** The page number Extract images writes into a name like "scan-p3-1.jpg", zero-based. */
    private companion object {
        /** Sharp on a projector, and a 40-page deck stays under a few megabytes. */
        const val SLIDE_DPI = 110
    }

    private fun pageOf(name: String): Int =
        Regex("-p(\\d+)-").find(name)?.groupValues?.get(1)?.toIntOrNull()?.minus(1) ?: 0

    private fun picturesFor(files: List<File>?): List<Block.Picture> = files.orEmpty().mapNotNull { file ->
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@mapNotNull null
        val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.path, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return@mapNotNull null
        // Shown at 150 dots to the inch, which keeps a photo under the column width.
        val width = options.outWidth * 72f / 150f
        val height = options.outHeight * 72f / 150f
        Block.Picture(bytes, jpeg = file.name.endsWith(".jpg", true) || file.name.endsWith(".jpeg", true), widthPoints = width, heightPoints = height)
    }
}

/**
 * A Word file, a spreadsheet or a text file as a PDF.
 *
 * Read into the same flow the converters above write from, then laid out on A4 in the
 * phone's own fonts. Paragraphs, headings, lists and tables come through; shapes, text
 * boxes and tracked changes do not, and the fonts are not the original's.
 */
class OfficeToPdfOp : Op {

    override val tool = ToolId.OFFICE_TO_PDF
    override val title = Strings.get(R.string.catalog_to_pdf_title)
    override val arity = Op.Arity.EachIndependently

    override suspend fun run(
        inputs: List<SheafFile>,
        context: OpContext,
        onProgress: (Progress) -> Unit
    ): List<OpOutcome> = withContext(Dispatchers.IO) {
        val outcomes = mutableListOf<OpOutcome>()
        inputs.forEachIndexed { index, input ->
            context.checkCancelled()
            onProgress(Progress(index, inputs.size, Strings.get(R.string.catalog_progress_reading_file, input.displayName)))
            val output = context.workspace.newOutput(input.baseName, "from-office")
            outcomes += try {
                val flow = Readers.forFile(input.file, input.displayName)
                    ?: throw IllegalArgumentException(Strings.get(R.string.catalog_office_unsupported))
                if (flow.blocks.isEmpty()) throw IllegalArgumentException(Strings.get(R.string.catalog_office_empty))
                val pages = context.surgeon.layoutFlow(flow, output) { done, total ->
                    context.checkCancelled()
                    onProgress(Progress(index, inputs.size, Strings.get(R.string.catalog_progress_laying_out, done, total)))
                }
                OpOutcome.Produced(
                    SheafFile(output, "${input.baseName}.pdf", SheafFile.Origin.Derived("from-office")),
                    Strings.plural(R.plurals.catalog_office_done, pages, pages, input.displayName)
                )
            } catch (e: PdfException) {
                output.delete()
                OpOutcome.Failed(input.displayName, e.message ?: Strings.get(R.string.catalog_convert_failed))
            } catch (e: Exception) {
                output.delete()
                OpOutcome.Failed(input.displayName, e.message ?: Strings.get(R.string.catalog_read_failed))
            }
        }
        onProgress(Progress(inputs.size, inputs.size, Strings.get(R.string.catalog_done)))
        outcomes
    }
}

/** Builds a document from photos and scans, one image per page. */
class ImagesToPdfOp(
    private val spec: PageSpec,
    private val outputName: String? = null,
    /** One PDF per image rather than one for the lot. */
    private val separate: Boolean = false
) : Op {

    override val tool = ToolId.IMAGES_TO_PDF
    override val title = Strings.get(R.string.catalog_tool_images_to_pdf_title)
    override val arity = Op.Arity.Together(min = 1)

    override suspend fun run(
        inputs: List<SheafFile>,
        context: OpContext,
        onProgress: (Progress) -> Unit
    ): List<OpOutcome> = withContext(Dispatchers.IO) {
        if (inputs.isEmpty()) {
            return@withContext listOf(OpOutcome.Failed("", Strings.get(R.string.catalog_no_images)))
        }

        if (separate && inputs.size > 1) {
            val outcomes = mutableListOf<OpOutcome>()
            inputs.forEachIndexed { index, image ->
                context.checkCancelled()
                onProgress(Progress(index, inputs.size, image.displayName))
                val output = context.workspace.newOutput(image.baseName, "from-images")
                outcomes += try {
                    context.surgeon.imagesToPdf(listOf(image.file), spec, output)
                    OpOutcome.Produced(
                        SheafFile(output, "${image.baseName}.pdf", SheafFile.Origin.Derived("from-images")),
                        Strings.get(R.string.catalog_one_page_from, image.displayName)
                    )
                } catch (e: PdfException) {
                    output.delete()
                    OpOutcome.Failed(image.displayName, e.message ?: Strings.get(R.string.catalog_build_failed))
                }
            }
            onProgress(Progress(inputs.size, inputs.size, Strings.get(R.string.catalog_done)))
            return@withContext outcomes
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
                onProgress(Progress(done, total, Strings.get(R.string.catalog_progress_page_of, done, total)))
            }
        } catch (e: PdfException) {
            output.delete()
            return@withContext listOf(OpOutcome.Failed(name, e.message ?: Strings.get(R.string.catalog_build_failed)))
        }

        listOf(
            OpOutcome.Produced(
                SheafFile(output, "$name.pdf", SheafFile.Origin.Derived("from-images")),
                Strings.plural(R.plurals.catalog_images_done, inputs.size)
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
    override val title = Strings.get(R.string.catalog_to_images_title, dpi, format.name)
    override val arity = Op.Arity.ExactlyOne

    override suspend fun run(
        inputs: List<SheafFile>,
        context: OpContext,
        onProgress: (Progress) -> Unit
    ): List<OpOutcome> = withContext(Dispatchers.IO) {
        val input = inputs.firstOrNull()
            ?: return@withContext listOf(OpOutcome.Failed("", Strings.get(R.string.catalog_no_document)))

        val pdfInput = PdfInput(input.file, context.passwords[input.file.path])
        val info = try {
            context.surgeon.inspect(pdfInput)
        } catch (e: PdfException) {
            return@withContext listOf(OpOutcome.Failed(input.displayName, e.message ?: Strings.get(R.string.catalog_unreadable)))
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
                    Strings.plural(R.plurals.catalog_no_pages_matched, info.pageCount, pageSpec.orEmpty(), info.pageCount)
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
                onProgress(Progress(done, total, Strings.get(R.string.catalog_progress_page_of, done, total)))
            }
        } catch (e: PdfException) {
            dir.deleteRecursively()
            return@withContext listOf(OpOutcome.Failed(input.displayName, e.message ?: Strings.get(R.string.catalog_export_failed)))
        }

        files.mapIndexed { position, file ->
            OpOutcome.Produced(
                SheafFile(
                    file = file,
                    displayName = file.name,
                    origin = SheafFile.Origin.Derived("images"),
                    mimeType = format.mimeType
                ),
                Strings.get(R.string.catalog_image_page, pages.getOrNull(position)?.plus(1) ?: position + 1, dpi)
            )
        }
    }
}
