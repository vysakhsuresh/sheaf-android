package com.layerbit.sheaf.ops

import com.layerbit.sheaf.files.SheafFile
import com.layerbit.sheaf.pdf.BitmapBudget
import com.layerbit.sheaf.pdf.PageSelection
import com.layerbit.sheaf.pdf.PdfException
import com.layerbit.sheaf.pdf.PdfInput
import com.layerbit.sheaf.pdf.TextPlacement
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
class OcrOp(private val dpi: Int = DEFAULT_DPI) : Op {

    override val tool = ToolId.OCR
    override val title = "Make searchable"
    override val arity = Op.Arity.EachIndependently

    override suspend fun run(
        inputs: List<SheafFile>,
        context: OpContext,
        onProgress: (Progress) -> Unit
    ): List<OpOutcome> = withContext(Dispatchers.IO) {
        val ocr = context.ocr
        if (!ocr.available) {
            return@withContext listOf(
                OpOutcome.Failed("", "Text recognition is not available on this device.")
            )
        }

        val outcomes = mutableListOf<OpOutcome>()

        for (input in inputs) {
            context.checkCancelled()
            val pdfInput = PdfInput(input.file, context.passwords[input.file.path])

            val info = try {
                context.surgeon.inspect(pdfInput)
            } catch (e: PdfException) {
                outcomes += OpOutcome.Failed(input.displayName, e.message ?: "Unreadable.")
                continue
            }

            // Total counts each page twice - once to read it, once to write the layer - so the
            // bar reflects the work rather than jumping to the end and then sitting there.
            val total = info.pageCount * 2
            val layers = mutableMapOf<Int, List<TextPlacement>>()
            var recognised = 0

            val engine = if (info.isEncrypted) context.encryptedReader else context.engine
            val failure = try {
                engine.open(input.file, context.passwords[input.file.path]).use { doc ->
                    for (index in 0 until doc.pageCount) {
                        context.checkCancelled()
                        onProgress(Progress(index, total, "Reading page ${index + 1} of ${doc.pageCount}"))

                        val size = doc.pageSize(index)
                        val width = BitmapBudget.widthForDpi(size.widthPoints, dpi)
                        val bitmap = runCatching { doc.renderPage(index, width) }.getOrNull() ?: continue

                        try {
                            val result = ocr.read(bitmap)
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
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
                null
            } catch (e: OpCancelled) {
                throw e
            } catch (e: Exception) {
                e.message ?: "This document could not be read."
            }

            if (failure != null) {
                outcomes += OpOutcome.Failed(input.displayName, failure)
                continue
            }

            if (layers.isEmpty()) {
                outcomes += OpOutcome.Skipped(
                    input.displayName,
                    if (recognised == 0) {
                        "No text was found in this document. If it is already text rather than " +
                            "a scan, it is searchable as it is."
                    } else {
                        "Nothing could be placed on the page."
                    }
                )
                continue
            }

            val output = context.workspace.newOutput(input.baseName, "searchable")
            outcomes += try {
                context.surgeon.addTextLayer(pdfInput, layers, output) { done, count ->
                    context.checkCancelled()
                    onProgress(Progress(info.pageCount + done, total, "Writing page $done of $count"))
                }
                OpOutcome.Produced(
                    SheafFile(
                        output,
                        "${input.baseName} searchable.pdf",
                        SheafFile.Origin.Derived("searchable")
                    ),
                    "Text found on $recognised of ${info.pageCount} pages - " +
                        "${layers.values.sumOf { it.size }} lines are now selectable"
                )
            } catch (e: PdfException) {
                output.delete()
                OpOutcome.Failed(input.displayName, e.message ?: "The text layer could not be written.")
            }
        }

        onProgress(Progress(1, 1, "Done"))
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
    override val title = "Extract text"
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

        onProgress(Progress(0, 1, "Reading ${pages.size} pages"))
        val byPage = try {
            context.surgeon.extractText(pdfInput, pages)
        } catch (e: PdfException) {
            return@withContext listOf(OpOutcome.Failed(input.displayName, e.message ?: "Failed."))
        }

        val body = pages.joinToString("\n\n") { index ->
            val text = byPage[index].orEmpty().trim()
            if (text.isEmpty()) "" else "--- Page ${index + 1} ---\n$text"
        }.trim()

        if (body.isEmpty()) {
            return@withContext listOf(
                OpOutcome.Skipped(
                    input.displayName,
                    "This document has no text in it. It is probably a scan - run Make " +
                        "searchable on it first, then try again."
                )
            )
        }

        val output = context.workspace.newOutput(input.baseName, "text", "txt")
        output.writeText(body)
        onProgress(Progress(1, 1, "Done"))

        val words = body.split(Regex("\\s+")).count { it.isNotBlank() }
        listOf(
            OpOutcome.Produced(
                SheafFile(
                    file = output,
                    displayName = "${input.baseName}.txt",
                    origin = SheafFile.Origin.Derived("text"),
                    mimeType = SheafFile.MIME_TEXT
                ),
                "$words words from ${pages.size} ${if (pages.size == 1) "page" else "pages"}"
            )
        )
    }
}
