package com.layerbit.sheaf.ops

import android.graphics.Bitmap
import com.layerbit.sheaf.R
import com.layerbit.sheaf.files.SheafFile
import com.layerbit.sheaf.pdf.BitmapBudget
import com.layerbit.sheaf.pdf.PageSelection
import com.layerbit.sheaf.pdf.OcrScript
import com.layerbit.sheaf.pdf.PdfException
import com.layerbit.sheaf.pdf.PdfInput
import com.layerbit.sheaf.pdf.TextPlacement
import com.layerbit.sheaf.text.Strings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Turns a scan into a document you can search.
 *
 * Each page is rendered, read by the recogniser, and the words are written back as an
 * invisible layer sitting exactly over the ink. The scan still looks identical - nothing is
 * redrawn, nothing is re-encoded - but the text is now selectable, copyable and findable.
 *
 * This is the slowest thing in the app by a wide margin: a render and a recognition pass per
 * page, and forty pages is minutes rather than seconds. It is also the clearest reason every
 * operation runs as a foreground-service Worker, because this is the one people will start and
 * then switch away from.
 */
class OcrOp(private val dpi: Int = DEFAULT_DPI, private val script: OcrScript = OcrScript.LATIN) : Op {

    override val tool = ToolId.OCR
    override val title = if (script == OcrScript.LATIN) {
        Strings.get(R.string.engine_title_ocr)
    } else {
        Strings.get(R.string.engine_title_ocr_script, script.label)
    }
    override val arity = Op.Arity.EachIndependently

    override suspend fun run(
        inputs: List<SheafFile>,
        context: OpContext,
        onProgress: (Progress) -> Unit
    ): List<OpOutcome> = withContext(Dispatchers.IO) {
        val ocr = context.ocr
        if (!ocr.available) {
            return@withContext listOf(
                OpOutcome.Failed("", Strings.get(R.string.engine_ocr_unavailable))
            )
        }

        val outcomes = mutableListOf<OpOutcome>()

        for (input in inputs) {
            context.checkCancelled()
            val pdfInput = PdfInput(input.file, context.passwords[input.file.path])

            val info = try {
                context.surgeon.inspect(pdfInput)
            } catch (e: PdfException) {
                outcomes += OpOutcome.Failed(input.displayName, e.message ?: Strings.get(R.string.engine_unreadable))
                continue
            }

            // Total counts each page twice - once to read it, once to write the layer - so the
            // bar reflects the work rather than jumping to the end and then sitting there.
            val total = info.pageCount * 2
            val layers = mutableMapOf<Int, List<TextPlacement>>()
            var recognised = 0

            val engine = if (info.isEncrypted) context.encryptedReader else context.engine

            // The sheet every page of this document is rendered onto, offered back to the
            // engine on each call.
            //
            // A scan is three hundred pages of one size, and this pass used to allocate and
            // throw away a full-page bitmap for every one of them - at 200 DPI an A4 page is
            // 13 MB, so the better part of four gigabytes is churned through the same heap the
            // recogniser is working in. Held across pages it is a single allocation.
            //
            // Safe to hand over only because nothing else ever sees it. The recogniser has
            // finished with the bitmap by the time `read` returns - which is what made the
            // immediate recycle below correct too - and no bitmap from here reaches the UI.
            var sheet: Bitmap? = null
            val failure = try {
                engine.open(input.file, context.passwords[input.file.path]).use { doc ->
                    for (index in 0 until doc.pageCount) {
                        context.checkCancelled()
                        onProgress(Progress(index, total, Strings.get(R.string.engine_progress_reading_page, index + 1, doc.pageCount)))

                        val size = doc.pageSize(index)
                        val width = BitmapBudget.widthForDpi(size.widthPoints, dpi)
                        val bitmap = runCatching { doc.renderPage(index, width, sheet) }
                            .getOrNull() ?: continue

                        // The offer is an offer and never a promise: an engine with no reuse
                        // path, or a page of a size the sheet does not match, renders fresh,
                        // and that fresh bitmap becomes the sheet. Comparing identity is the
                        // only way to find out which of the two happened. A mixed-size
                        // document therefore reallocates where the size changes and reuses
                        // everywhere it does not, which is the best this can do without
                        // keeping one sheet per size and the memory that implies.
                        if (bitmap !== sheet) {
                            sheet?.recycle()
                            sheet = bitmap
                        }

                        val result = ocr.read(bitmap, script)
                        if (!result.isEmpty) {
                            recognised++
                            layers[index] = result.lines.map { line ->
                                // Normalised against the bitmap that was actually produced,
                                // not the one that was asked for - BitmapBudget may have
                                // clamped it, and normalising against the request would put
                                // every word in the wrong place on a large page.
                                TextPlacement(
                                    text = line.text,
                                    left = line.left / bitmap.width,
                                    top = line.top / bitmap.height,
                                    width = line.width / bitmap.width,
                                    height = line.height / bitmap.height
                                )
                            }
                        }
                    }
                }
                null
            } catch (e: OpCancelled) {
                throw e
            } catch (e: Exception) {
                e.message ?: Strings.get(R.string.engine_unreadable_document)
            } finally {
                // Per document, not per batch: the next file's pages are a different size as
                // often as not, and a sheet carried across would be refused anyway.
                sheet?.recycle()
                sheet = null
            }

            if (failure != null) {
                outcomes += OpOutcome.Failed(input.displayName, failure)
                continue
            }

            if (layers.isEmpty()) {
                outcomes += OpOutcome.Skipped(
                    input.displayName,
                    if (recognised == 0) {
                        Strings.get(R.string.engine_ocr_no_text)
                    } else {
                        Strings.get(R.string.engine_ocr_nothing_placed)
                    }
                )
                continue
            }

            val output = context.workspace.newOutput(input.baseName, "searchable")
            outcomes += try {
                context.surgeon.addTextLayer(pdfInput, layers, output) { done, count ->
                    context.checkCancelled()
                    onProgress(Progress(info.pageCount + done, total, Strings.get(R.string.engine_progress_writing_page, done, count)))
                }
                OpOutcome.Produced(
                    SheafFile(
                        output,
                        "${input.baseName} searchable.pdf",
                        SheafFile.Origin.Derived("searchable")
                    ),
                    layers.values.sumOf { it.size }.let { lines ->
                        Strings.plural(R.plurals.engine_ocr_detail, lines, recognised, info.pageCount, lines)
                    }
                )
            } catch (e: PdfException) {
                output.delete()
                OpOutcome.Failed(input.displayName, e.message ?: Strings.get(R.string.engine_ocr_write_failed))
            }
        }

        onProgress(Progress(1, 1, Strings.get(R.string.engine_done)))
        outcomes
    }

    companion object {
        /**
         * 200 DPI. Recognition accuracy climbs steeply to about 200 and then flattens, while
         * render cost and memory keep climbing, so this is where the curve stops paying.
         */
        const val DEFAULT_DPI = 200
    }
}

/**
 * Pulls the words out of a PDF as plain text.
 *
 * Only works on a document that already has text - a PDF made by a word processor, or a scan
 * that has been through Make searchable. A raw scan is a photograph, and this will honestly
 * report that it found nothing rather than returning an empty file and letting the user
 * wonder.
 *
 * The output is .txt, deliberately. A .docx of the same content would carry none of the
 * original layout and would imply a fidelity that is not there.
 */
class ExtractTextOp(private val pageSpec: String? = null) : Op {

    override val tool = ToolId.EXTRACT_TEXT
    override val title = Strings.get(R.string.engine_title_extract_text)
    override val arity = Op.Arity.ExactlyOne

    override suspend fun run(
        inputs: List<SheafFile>,
        context: OpContext,
        onProgress: (Progress) -> Unit
    ): List<OpOutcome> = withContext(Dispatchers.IO) {
        val input = inputs.firstOrNull()
            ?: return@withContext listOf(OpOutcome.Failed("", Strings.get(R.string.engine_no_document)))

        val pdfInput = PdfInput(input.file, context.passwords[input.file.path])
        val info = try {
            context.surgeon.inspect(pdfInput)
        } catch (e: PdfException) {
            return@withContext listOf(OpOutcome.Failed(input.displayName, e.message ?: Strings.get(R.string.engine_unreadable)))
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
                    Strings.plural(R.plurals.engine_extract_text_no_match, info.pageCount, pageSpec.orEmpty(), info.pageCount)
                )
            )
        }

        onProgress(Progress(0, 1, Strings.plural(R.plurals.engine_progress_reading_pages, pages.size)))
        val byPage = try {
            context.surgeon.extractText(pdfInput, pages)
        } catch (e: PdfException) {
            return@withContext listOf(OpOutcome.Failed(input.displayName, e.message ?: Strings.get(R.string.engine_failed)))
        }

        val body = pages.joinToString("\n\n") { index ->
            val text = byPage[index].orEmpty().trim()
            if (text.isEmpty()) "" else "--- Page ${index + 1} ---\n$text"
        }.trim()

        if (body.isEmpty()) {
            return@withContext listOf(
                OpOutcome.Skipped(
                    input.displayName,
                    Strings.get(R.string.engine_extract_text_none)
                )
            )
        }

        val output = context.workspace.newOutput(input.baseName, "text", "txt")
        output.writeText(body)
        onProgress(Progress(1, 1, Strings.get(R.string.engine_done)))

        val words = body.split(Regex("\\s+")).count { it.isNotBlank() }
        listOf(
            OpOutcome.Produced(
                SheafFile(
                    file = output,
                    displayName = "${input.baseName}.txt",
                    origin = SheafFile.Origin.Derived("text"),
                    mimeType = SheafFile.MIME_TEXT
                ),
                Strings.get(
                    R.string.engine_words_from_pages,
                    Strings.plural(R.plurals.engine_words, words),
                    Strings.plural(R.plurals.engine_count_pages, pages.size)
                )
            )
        )
    }
}
