package com.layerbit.sheaf.pdf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import com.layerbit.sheaf.ops.OpCancelled
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.LayerUtility
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDChoice
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDNonTerminalField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDRadioButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import com.tom_roush.pdfbox.util.Matrix
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.rendering.PDFRenderer
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * [PdfSurgeon] on PDFBox (Apache-2.0, via the Android port by Tom Roush).
 *
 * Two habits run through every method here and both are load-bearing:
 *
 * 1. Every document goes through [scratchMemory], inputs being read and outputs being built
 *    alike. PDFBox buffers both in the heap by default, and neither a 90 MB scan being opened
 *    nor thirty photos being assembled fits there. Spilling to a temp file is slower and is
 *    the only version that survives the files people actually have.
 *
 * 2. Every open is inside `use`, and the output is written to a fresh file. Nothing here can
 *    touch an input, which is the same guarantee DocumentStore makes one layer up.
 *
 * Every failure is translated into [PdfException]. Callers never see a PDFBox type, which is
 * what keeps this swappable for QPDF later.
 *
 * @param scratchDir where PDFBox puts the buffers it keeps out of the heap. The app passes the
 *   workspace's own scratch directory, so a spill orphaned by a process that died
 *   mid-operation is pruned along with stale results. Null - the default, for a caller with no
 *   workspace to point at - leaves the spill in the process temp directory, which on Android is
 *   the app's cache root: correct, but cleared on Android's schedule rather than the app's.
 */
class PdfBoxSurgeon(private val scratchDir: File? = null) : PdfSurgeon {

    override fun inspect(input: PdfInput): DocumentInfo = withDocument(input) { doc ->
        DocumentInfo(
            pageCount = doc.numberOfPages,
            pageSizes = (0 until doc.numberOfPages).map { doc.getPage(it).toPageSize() },
            isEncrypted = doc.isEncrypted,
            title = doc.documentInformation?.title,
            author = doc.documentInformation?.author,
            subject = doc.documentInformation?.subject,
            keywords = doc.documentInformation?.keywords,
            producer = doc.documentInformation?.producer
        )
    }

    override fun merge(inputs: List<PdfInput>, output: File, onProgress: (Int, Int) -> Unit) {
        if (inputs.isEmpty()) throw PdfException.Io("Nothing to merge.")
        try {
            val merger = PDFMergerUtility()
            merger.destinationFileName = output.absolutePath
            inputs.forEachIndexed { index, input ->
                // An encrypted input cannot be merged without being decrypted first, and
                // PDFMergerUtility has nowhere to put a password. Say so plainly rather than
                // letting it fail with something about a stream.
                if (input.password != null) {
                    throw PdfException.Unsupported(
                        "remove the password from \"${input.file.name}\" before merging it"
                    )
                }
                merger.addSource(input.file)
                onProgress(index + 1, inputs.size)
            }
            merger.mergeDocuments(scratchMemory())
        } catch (e: PdfException) {
            throw e
        } catch (e: OutOfMemoryError) {
            throw PdfException.OutOfMemory("merging ${inputs.size} documents", e)
        } catch (e: OpCancelled) {
            // A cancel reaches here through the progress callback, which runs inside this try.
            // It is control flow and not a failure, and the arm below would turn it into
            // PdfException.Corrupt - telling someone who tapped cancel that their document
            // could not be read as a PDF.
            throw e
        } catch (e: Exception) {
            throw e.asPdfException("merge")
        }
    }

    override fun extractPages(
        input: PdfInput,
        pages: List<Int>,
        rotations: Map<Int, Int>,
        output: File
    ) = withDocument(input) { source ->
        if (pages.isEmpty()) throw PdfException.Io("No pages were selected.")

        PDDocument(scratchMemory()).use { target ->
            for (index in pages) {
                if (index !in 0 until source.numberOfPages) {
                    throw PdfException.Io("Page ${index + 1} does not exist in this document.")
                }
                val page = source.getPage(index)
                rotations[index]?.let { delta ->
                    page.rotation = normaliseRotation(page.rotation + delta)
                }
                // importPage copies the page's dictionary and re-deflates its content stream
                // into the new document, but the resources that stream names stay indirect
                // references into the source - which is why the source must stay open until
                // after the save below. It is, since this is inside withDocument.
                target.importPage(page)
            }
            target.save(output)
        }
    }

    override fun rotate(input: PdfInput, pages: Collection<Int>, degrees: Int, output: File) =
        withDocument(input) { doc ->
            val targets = pages.toSet()
            for (index in 0 until doc.numberOfPages) {
                if (index !in targets) continue
                val page = doc.getPage(index)
                page.rotation = normaliseRotation(page.rotation + degrees)
            }
            doc.save(output)
        }

    override fun imagesToPdf(
        images: List<File>,
        spec: PageSpec,
        output: File,
        onProgress: (Int, Int) -> Unit
    ) {
        if (images.isEmpty()) throw PdfException.Io("No images were selected.")
        try {
            PDDocument(scratchMemory()).use { doc ->
                images.forEachIndexed { index, imageFile ->
                    val image = try {
                        PDImageXObject.createFromFile(imageFile.absolutePath, doc)
                    } catch (e: OutOfMemoryError) {
                        throw PdfException.OutOfMemory(imageFile.name, e)
                    } catch (e: Exception) {
                        throw PdfException.Corrupt("${imageFile.name} could not be read as an image", e)
                    }

                    val page = PDPage(pageBoxFor(spec, image.width.toFloat(), image.height.toFloat()))
                    doc.addPage(page)

                    PDPageContentStream(doc, page).use { stream ->
                        val box = page.mediaBox
                        val margin = if (spec.size == PageSpec.Size.FIT_IMAGE) 0f else spec.marginPoints
                        val available = box.width - margin * 2 to box.height - margin * 2

                        // Fit, never fill: an image is never cropped to make a page look tidy.
                        val scale = minOf(
                            available.first / image.width,
                            available.second / image.height
                        )
                        val drawWidth = image.width * scale
                        val drawHeight = image.height * scale
                        stream.drawImage(
                            image,
                            (box.width - drawWidth) / 2f,
                            (box.height - drawHeight) / 2f,
                            drawWidth,
                            drawHeight
                        )
                    }
                    onProgress(index + 1, images.size)
                }
                doc.save(output)
            }
        } catch (e: PdfException) {
            throw e
        } catch (e: OutOfMemoryError) {
            throw PdfException.OutOfMemory("building a PDF from ${images.size} images", e)
        } catch (e: OpCancelled) {
            // Thrown by the progress callback above; rethrown for the reason merge gives.
            throw e
        } catch (e: Exception) {
            throw e.asPdfException("images to PDF")
        }
    }

    override fun pdfToImages(
        input: PdfInput,
        pages: List<Int>,
        dpi: Int,
        format: ImageFormat,
        outputDir: File,
        baseName: String,
        onProgress: (Int, Int) -> Unit
    ): List<File> = withDocument(input) { doc ->
        outputDir.mkdirs()
        val renderer = PDFRenderer(doc)
        val written = mutableListOf<File>()

        pages.forEachIndexed { position, index ->
            if (index !in 0 until doc.numberOfPages) {
                throw PdfException.Io("Page ${index + 1} does not exist in this document.")
            }

            // PDFBox renders at a scale relative to 72 DPI, which is PDF's own unit, so the
            // scale is just the ratio. Clamped so a poster at 600 DPI does not try to
            // allocate a bitmap larger than the heap.
            val size = doc.getPage(index).toPageSize()
            val requestedWidth = BitmapBudget.widthForDpi(size.widthPoints, dpi)
            val safeWidth = BitmapBudget.clampWidth(requestedWidth, size.aspectRatio, RENDER_HEAP_BUDGET)
            val scale = if (size.widthPoints <= 0f) 1f else safeWidth / size.widthPoints

            val bitmap = try {
                renderer.renderImage(index, scale)
            } catch (e: OutOfMemoryError) {
                throw PdfException.OutOfMemory("page ${index + 1} at $dpi DPI", e)
            } catch (e: Exception) {
                throw PdfException.Corrupt("page ${index + 1} could not be rendered", e)
            }

            val file = File(outputDir, "$baseName-${(index + 1).toString().padStart(3, '0')}.${format.extension}")
            try {
                FileOutputStream(file).use { out ->
                    val codec = when (format) {
                        ImageFormat.PNG -> Bitmap.CompressFormat.PNG
                        ImageFormat.JPEG -> Bitmap.CompressFormat.JPEG
                    }
                    bitmap.compress(codec, JPEG_EXPORT_QUALITY, out)
                }
            } catch (e: IOException) {
                throw PdfException.Io("could not write ${file.name}", e)
            } finally {
                bitmap.recycle()
            }

            written += file
            onProgress(position + 1, pages.size)
        }
        written
    }

    override fun compress(
        input: PdfInput,
        level: CompressionLevel,
        output: File,
        onProgress: (Int, Int) -> Unit
    ): CompressionResult = withDocument(input) { doc ->
        val originalBytes = input.file.length()
        var recompressed = 0
        var skipped = 0

        for (pageIndex in 0 until doc.numberOfPages) {
            val resources = doc.getPage(pageIndex).resources ?: continue

            // getXObjectNames is lazy over the resource dictionary and replacing an entry
            // while iterating it is undefined, so the names are taken first.
            for (name in resources.xObjectNames.toList()) {
                val existing = try {
                    resources.getXObject(name)
                } catch (_: Exception) {
                    skipped++
                    continue
                }
                if (existing !is PDImageXObject) continue

                // A soft mask carries transparency and JPEG has no alpha channel, so
                // re-encoding one as JPEG would turn transparent areas black. Left alone.
                if (existing.softMask != null) {
                    skipped++
                    continue
                }

                val shrunk = shrink(existing, level)
                if (shrunk == null) {
                    skipped++
                    continue
                }

                try {
                    val replacement = JPEGFactory.createFromImage(doc, shrunk, level.jpegQuality / 100f)
                    resources.put(name, replacement)
                    recompressed++
                } catch (_: Exception) {
                    skipped++
                } finally {
                    shrunk.recycle()
                }
            }
            onProgress(pageIndex + 1, doc.numberOfPages)
        }

        doc.save(output)
        CompressionResult(
            originalBytes = originalBytes,
            compressedBytes = output.length(),
            imagesRecompressed = recompressed,
            imagesSkipped = skipped
        )
    }

    override fun setPassword(
        input: PdfInput,
        userPassword: String,
        output: File,
        permissions: DocumentPermissions,
        restrictOnly: Boolean
    ) =
        withDocument(input) { doc ->
            if (!restrictOnly && userPassword.isBlank()) {
                throw PdfException.Io("The password cannot be empty.")
            }
            if (restrictOnly && permissions.allowPrinting && permissions.allowCopying && permissions.allowChanges) {
                throw PdfException.Io("Everything is allowed, so there is nothing to restrict.")
            }

            // The owner password is set to the same value deliberately. An owner password that
            // the user does not know is a document they can open but never change again, and a
            // random one would be worse - there would be no way to recover it.
            //
            // Restricting only is the one case where a random owner password is the point: the
            // document opens for anyone (empty user password), and the flags are all it carries.
            val ownerPassword = if (restrictOnly) randomOwnerPassword() else userPassword
            val openPassword = if (restrictOnly) "" else userPassword
            val access = AccessPermission().apply {
                setCanPrint(permissions.allowPrinting)
                setCanExtractContent(permissions.allowCopying)
                setCanModify(permissions.allowChanges)
                setCanModifyAnnotations(permissions.allowChanges)
                setCanFillInForm(permissions.allowChanges)
                setCanAssembleDocument(permissions.allowChanges)
                // Never switched off. Turning this off is how a document stops working with a
                // screen reader, and nothing this app is for needs that.
                setCanExtractForAccessibility(true)
            }
            val policy = StandardProtectionPolicy(ownerPassword, openPassword, access).apply {
                // AES-256. The 128-bit default is RC4-era and long past being worth shipping.
                // setPreferAES is called rather than assigned: ProtectionPolicy keeps the
                // field private and only exposes the setter.
                encryptionKeyLength = 256
                setPreferAES(true)
            }
            doc.protect(policy)
            doc.save(output)
        }

    override fun removePassword(input: PdfInput, output: File) = withDocument(input) { doc ->
        if (!doc.isEncrypted) throw PdfException.Io("This document is not password-protected.")
        doc.isAllSecurityToBeRemoved = true
        doc.save(output)
    }

    override fun addTextLayer(
        input: PdfInput,
        layers: Map<Int, List<TextPlacement>>,
        output: File,
        onProgress: (Int, Int) -> Unit
    ) = withDocument(input) { doc ->
        val total = doc.numberOfPages
        for (index in 0 until total) {
            val placements = layers[index].orEmpty()
            if (placements.isNotEmpty()) {
                val page = doc.getPage(index)
                val box = page.mediaBox ?: PDRectangle.A4

                // APPEND with resetContext, so the layer goes on top of the page that is
                // already there and cannot inherit a graphics state left set by it.
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true)
                    .use { stream ->
                        // Rendering mode 3: the glyphs are laid out and measured exactly as
                        // normal but never painted. That is what makes the text selectable and
                        // searchable while the scan underneath stays the only thing visible.
                        stream.setRenderingMode(RenderingMode.NEITHER)

                        for (placement in placements) {
                            val encodable = placement.text.toWinAnsiSafe()
                            if (encodable.isBlank()) continue

                            // Normalised, origin top-left, to PDF points, origin bottom-left.
                            // This flip is the whole reason TextPlacement is normalised: it is
                            // written here and nowhere else.
                            val x = placement.left * box.width
                            val heightPoints = (placement.height * box.height).coerceAtLeast(1f)
                            val baseline = box.height - (placement.top * box.height) - heightPoints

                            // Helvetica's cap height is 0.717 em, so a line box of h points is
                            // roughly h/0.9 em of font. Close enough that a selection rectangle
                            // lands on the ink rather than above or below it.
                            val fontSize = (heightPoints / 0.9f).coerceIn(1f, 1000f)

                            try {
                                stream.beginText()
                                stream.setFont(PDType1Font.HELVETICA, fontSize)
                                stream.newLineAtOffset(x, baseline)
                                stream.showText(encodable)
                                stream.endText()
                            } catch (_: Exception) {
                                // One unplaceable line must not cost the whole document its
                                // text layer. endText is attempted so the stream is not left
                                // inside a text object, which would corrupt everything after.
                                runCatching { stream.endText() }
                            }
                        }
                    }
            }
            onProgress(index + 1, total)
        }
        doc.save(output)
    }

    override fun extractText(input: PdfInput, pages: List<Int>): Map<Int, String> =
        withDocument(input) { doc ->
            val stripper = PDFTextStripper()
            buildMap {
                for (index in pages) {
                    if (index !in 0 until doc.numberOfPages) continue
                    // One page at a time. Stripping a whole document in one call means holding
                    // all of its text in memory, and a scanned book is a lot of text.
                    stripper.startPage = index + 1
                    stripper.endPage = index + 1
                    val text = runCatching { stripper.getText(doc) }.getOrDefault("")
                    put(index, text)
                }
            }
        }

    override fun search(input: PdfInput, query: String): List<SearchHit> = withDocument(input) { doc ->
        val needle = query.trim()
        if (needle.isEmpty()) return@withDocument emptyList()
        val pattern = Regex(Regex.escape(needle), RegexOption.IGNORE_CASE)

        buildList {
            for (index in 0 until doc.numberOfPages) {
                val finder = finderFor(doc, index, pattern) { true }
                val text = runCatching { finder.getText(doc) }.getOrNull() ?: continue

                val matches = pattern.findAll(text).toList()
                if (matches.isEmpty()) continue

                val first = matches.first().range.first
                val from = (first - SNIPPET_LEAD).coerceAtLeast(0)
                val to = (first + SNIPPET_TRAIL).coerceAtMost(text.length)
                val snippet = text.substring(from, to)
                    .replace(Regex("\\s+"), " ")
                    .trim()

                add(SearchHit(index, snippet, matches.size, finder.areas.toList()))
            }
        }
    }

    override fun findAreas(
        input: PdfInput,
        pattern: Regex,
        accept: (String) -> Boolean
    ): Map<Int, List<PageArea>> = withDocument(input) { doc ->
        buildMap {
            for (index in 0 until doc.numberOfPages) {
                val finder = finderFor(doc, index, pattern, accept)
                runCatching { finder.getText(doc) }.getOrNull() ?: continue
                if (finder.areas.isNotEmpty()) put(index, finder.areas.toList())
            }
        }
    }

    /**
     * A stripper that keeps the glyph positions, not just the words. Page numbers alone told
     * the reader which page to go to and then left them to find the phrase again by eye; these
     * are what let the viewer draw a box round it, and what Remove areas blacks out.
     *
     * A quarter-turned page is stripped in reading order, so the rectangles come back against
     * a page whose width and height have swapped. The viewer renders it turned too, so they
     * have to be normalised against the same swap.
     */
    private fun finderFor(doc: PDDocument, index: Int, pattern: Regex, accept: (String) -> Boolean): MatchFinder {
        val page = doc.getPage(index)
        val box = page.cropBox ?: page.mediaBox ?: PDRectangle.A4
        val quarterTurned = normaliseRotation(page.rotation) % 180 == 90
        val finder = if (quarterTurned) {
            MatchFinder(pattern, accept, box.height, box.width)
        } else {
            MatchFinder(pattern, accept, box.width, box.height)
        }
        finder.startPage = index + 1
        finder.endPage = index + 1
        return finder
    }

    override fun placedWords(input: PdfInput, onProgress: (Int, Int) -> Unit): List<PlacedWord> =
        withDocument(input) { doc ->
            buildList {
                for (index in 0 until doc.numberOfPages) {
                    val page = doc.getPage(index)
                    val box = page.cropBox ?: page.mediaBox ?: PDRectangle.A4
                    val quarterTurned = normaliseRotation(page.rotation) % 180 == 90
                    val stripper = if (quarterTurned) WordFinder(box.height, box.width) else WordFinder(box.width, box.height)
                    stripper.startPage = index + 1
                    stripper.endPage = index + 1
                    runCatching { stripper.getText(doc) }
                    for ((text, area) in stripper.words) add(PlacedWord(text, index, area))
                    onProgress(index + 1, doc.numberOfPages)
                }
            }
        }

    override fun replaceText(
        input: PdfInput,
        pattern: Regex,
        replacement: String,
        output: File,
        onProgress: (Int, Int) -> Unit
    ): Int = withDocument(input) { doc ->
        val font = PDType1Font.HELVETICA
        val text = replacement.toWinAnsiSafe()
        var changed = 0
        for (index in 0 until doc.numberOfPages) {
            val finder = finderFor(doc, index, pattern) { true }
            runCatching { finder.getText(doc) }
            val areas = finder.areas
            if (areas.isEmpty()) {
                onProgress(index + 1, doc.numberOfPages)
                continue
            }
            val page = doc.getPage(index)
            val shown = ShownPage(page)
            PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { stream ->
                stream.saveGraphicsState()
                stream.transform(shown.viewMatrix())
                for (area in areas) {
                    val left = area.left * shown.width
                    val width = area.width * shown.width
                    val height = area.height * shown.height
                    val bottom = shown.height - (area.top + area.height) * shown.height
                    // The patch is a hair bigger than the glyph box: the box is the glyphs'
                    // advance, and a descender or an italic overhang sits just outside it.
                    stream.setNonStrokingColor(1f, 1f, 1f)
                    stream.addRect(left - COVER_PADDING, bottom - height * 0.25f, width + COVER_PADDING * 2f, height * 1.3f)
                    stream.fill()
                    if (text.isNotEmpty()) {
                        // Sized to the old words' height, then shrunk until it fits their width
                        // too, so a longer replacement never runs into the next word.
                        var size = (height / (NOTE_ASCENT + NOTE_DESCENT)).coerceIn(MIN_NOTE_POINTS, MAX_NOTE_POINTS)
                        val needed = font.getStringWidth(text) / 1000f * size
                        if (needed > width && needed > 0f) size = (size * width / needed).coerceAtLeast(MIN_NOTE_POINTS)
                        stream.setNonStrokingColor(0f, 0f, 0f)
                        stream.beginText()
                        stream.setFont(font, size)
                        stream.newLineAtOffset(left, bottom + height * 0.05f)
                        stream.showText(text)
                        stream.endText()
                    }
                    changed += 1
                }
                stream.restoreGraphicsState()
            }
            onProgress(index + 1, doc.numberOfPages)
        }
        if (changed == 0) throw PdfException.Io("Those words were not found. A scanned page has no text to find; run Make searchable first.")
        doc.save(output)
        changed
    }

    override fun writeReceipts(receipts: List<ReceiptSpec>, output: File): Int {
        if (receipts.isEmpty()) throw PdfException.Io("There is nothing to write.")
        val regular = PDType1Font.HELVETICA
        val bold = PDType1Font.HELVETICA_BOLD
        val italic = PDType1Font.HELVETICA_OBLIQUE
        val margin = RECEIPT_MARGIN
        val pageBox = PDRectangle.A4
        val contentWidth = pageBox.width - margin * 2f
        val labelWidth = RECEIPT_LABEL_WIDTH
        val valueWidth = contentWidth - labelWidth
        val body = 10.5f
        val cellPad = 5f

        try {
            PDDocument(scratchMemory()).use { doc ->
                var stream: PDPageContentStream? = null
                var pages = 0
                var cursor = 0f

                fun newPage() {
                    stream?.close()
                    val fresh = PDPage(pageBox)
                    doc.addPage(fresh)
                    stream = PDPageContentStream(doc, fresh, PDPageContentStream.AppendMode.APPEND, true, true)
                    pages += 1
                    cursor = pageBox.height - margin
                }

                fun text(font: PDType1Font, size: Float, x: Float, y: Float, line: String) {
                    val s = stream ?: return
                    s.beginText()
                    s.setFont(font, size)
                    s.newLineAtOffset(x, y)
                    s.showText(line.toWinAnsiSafe())
                    s.endText()
                }

                fun centred(font: PDType1Font, size: Float, y: Float, line: String) {
                    val width = font.getStringWidth(line.toWinAnsiSafe()) / 1000f * size
                    text(font, size, (pageBox.width - width) / 2f, y, line)
                }

                for ((i, receipt) in receipts.withIndex()) {
                    // Two to a page. The second starts at the half-way line, so the two can be
                    // cut apart with one stroke.
                    if (i % 2 == 0) newPage() else cursor = pageBox.height / 2f - 18f
                    val s = stream!!
                    cursor -= 16f
                    centred(bold, 16f, cursor, receipt.title)
                    cursor -= 15f
                    centred(regular, 11f, cursor, receipt.subtitle)
                    cursor -= 14f

                    // The table: label in bold, value wrapped inside its column.
                    val rowsLaidOut = receipt.rows.map { (label, value) ->
                        val lines = wrapForWidth(value.toWinAnsiSafe(), regular, body, valueWidth - cellPad * 2f)
                        Triple(label, lines, lines.size * body * LINE_SPACING + cellPad * 2f)
                    }
                    s.setLineWidth(0.6f)
                    s.setStrokingColor(0.55f, 0.55f, 0.55f)
                    for ((label, lines, rowHeight) in rowsLaidOut) {
                        val top = cursor
                        val bottom = cursor - rowHeight
                        s.addRect(margin, bottom, contentWidth, rowHeight)
                        s.stroke()
                        s.moveTo(margin + labelWidth, top)
                        s.lineTo(margin + labelWidth, bottom)
                        s.stroke()
                        text(bold, body, margin + cellPad, top - cellPad - body * NOTE_ASCENT, label)
                        lines.forEachIndexed { k, line ->
                            text(regular, body, margin + labelWidth + cellPad, top - cellPad - body * NOTE_ASCENT - k * body * LINE_SPACING, line)
                        }
                        cursor = bottom
                    }
                    cursor -= 18f
                    for ((k, line) in receipt.footer.withIndex()) {
                        text(if (k == 0) regular else italic, if (k == 0) body else 9f, margin, cursor, line)
                        cursor -= 14f
                    }
                }
                stream?.close()
                doc.save(output)
                return pages
            }
        } catch (e: IOException) {
            throw e.asPdfException("writing the receipts")
        }
    }

    override fun pageLines(input: PdfInput, onProgress: (Int, Int) -> Unit): List<List<TextLine>> =
        withDocument(input) { doc ->
            List(doc.numberOfPages) { index ->
                val page = doc.getPage(index)
                val box = page.cropBox ?: page.mediaBox ?: PDRectangle.A4
                val quarterTurned = normaliseRotation(page.rotation) % 180 == 90
                val stripper = if (quarterTurned) LineFinder(box.height, box.width) else LineFinder(box.width, box.height)
                stripper.startPage = index + 1
                stripper.endPage = index + 1
                runCatching { stripper.getText(doc) }
                onProgress(index + 1, doc.numberOfPages)
                stripper.lines.toList()
            }
        }

    override fun layoutFlow(document: FlowDocument, output: File, onProgress: (Int, Int) -> Unit): Int {
        try {
            PDDocument(scratchMemory()).use { doc ->
                val pages = FlowLayout(doc, onProgress).layout(document)
                doc.save(output)
                return pages
            }
        } catch (e: IOException) {
            throw e.asPdfException("laying out the document")
        }
    }

    override fun readComments(input: PdfInput): List<Comment> = withDocument(input) { doc ->
        buildList {
            for (index in 0 until doc.numberOfPages) {
                val annotations = runCatching { doc.getPage(index).annotations }.getOrNull() ?: continue
                for (annotation in annotations) {
                    val subtype = annotation.subtype ?: continue
                    // Links and form fields are not comments, and a popup is the window a note
                    // opens in rather than a note of its own.
                    if (subtype == "Link" || subtype == "Widget" || subtype == "Popup") continue
                    val cos = annotation.cosObject
                    // A comment is a remark, or a mark with no remark on it. Marks with
                    // nothing to say are listed too: a highlight is something somebody meant.
                    val text = annotation.contents?.trim().orEmpty()
                    val replyOf = cos.getDictionaryObject(COSName.IRT)
                    add(
                        Comment(
                            pageIndex = index,
                            kind = (if (replyOf != null) "Reply" else commentKind(subtype)),
                            author = cos.getString(COSName.T)?.trim()?.takeIf { it.isNotEmpty() },
                            text = text,
                            date = tidyPdfDate(cos.getString(COSName.M))
                        )
                    )
                }
            }
        }
    }

    private fun commentKind(subtype: String): String = when (subtype) {
        "Text" -> "Note"
        "FreeText" -> "Text box"
        "Highlight" -> "Highlight"
        "Underline" -> "Underline"
        "StrikeOut" -> "Strikethrough"
        "Squiggly" -> "Squiggle"
        "Ink" -> "Drawing"
        "Square" -> "Box"
        "Circle" -> "Circle"
        "Line" -> "Line"
        "Polygon", "PolyLine" -> "Shape"
        "Stamp" -> "Stamp"
        "Caret" -> "Insertion"
        "FileAttachment" -> "Attached file"
        "Sound" -> "Sound"
        "Movie", "Screen", "RichMedia" -> "Media"
        "Redact" -> "Redaction mark"
        else -> subtype
    }

    /** "D:20240305142000+05'30'" becomes "2024-03-05 14:20"; anything else is left alone. */
    private fun tidyPdfDate(raw: String?): String? {
        val s = raw?.trim()?.removePrefix("D:") ?: return null
        if (s.length < 8 || !s.take(8).all { it.isDigit() }) return raw.takeIf { it.isNotBlank() }
        val date = "${s.substring(0, 4)}-${s.substring(4, 6)}-${s.substring(6, 8)}"
        return if (s.length >= 12 && s.substring(8, 12).all { it.isDigit() }) {
            "$date ${s.substring(8, 10)}:${s.substring(10, 12)}"
        } else {
            date
        }
    }

    override fun readOutline(input: PdfInput): List<OutlineEntry> = withDocument(input) { doc ->
        val outline = doc.documentCatalog?.documentOutline ?: return@withDocument emptyList()
        val pageNumbers = doc.pages.withIndex().associate { (index, page) -> page to index }

        buildList {
            fun walk(item: PDOutlineItem?, depth: Int) {
                var current = item
                while (current != null) {
                    // A destination can fail to resolve on a damaged document; an entry that
                    // cannot say where it points is worse than no entry, so it is dropped.
                    val page = runCatching { current?.findDestinationPage(doc) }.getOrNull()
                    val index = page?.let { pageNumbers[it] }
                    val title = current.title?.trim()
                    if (index != null && !title.isNullOrEmpty()) {
                        add(OutlineEntry(title, index, depth))
                    }
                    // Depth is capped rather than trusted: a malformed outline can point at
                    // itself, and an uncapped walk would not return.
                    if (depth < MAX_OUTLINE_DEPTH) walk(current.firstChild, depth + 1)
                    current = current.nextSibling
                }
            }
            walk(outline.firstChild, 0)
        }
    }

    // ---- P3: marking up ----

    override fun watermark(
        input: PdfInput,
        spec: WatermarkSpec,
        output: File,
        onProgress: (Int, Int) -> Unit
    ) = withDocument(input) { doc ->
        val text = spec.text.replace(Regex("[\\r\\n\\t]+"), " ").trim().toWinAnsiSafe()
        if (text.isBlank()) throw PdfException.Io("Type the watermark text, such as DRAFT or COPY.")

        val font = PDType1Font.HELVETICA_BOLD
        val total = doc.numberOfPages
        val chosen = spec.pages.ifEmpty { (0 until total).toList() }.filter { it in 0 until total }
        if (chosen.isEmpty()) throw PdfException.Io("None of the pages typed are in this document.")
        val opacity = spec.opacity.coerceIn(0.02f, 1f)
        val capRatio = (font.fontDescriptor?.capHeight?.takeIf { it > 0f } ?: 718f) / 1000f

        // A tiled watermark is a couple of hundred copies of the words on every page. Drawn
        // one by one that is 17 KB a page; drawn once into a form that every page of the same
        // size shares, each page only says "draw that here".
        val tiles = HashMap<String, PDFormXObject>()

        for ((done, index) in chosen.withIndex()) {
            val page = doc.getPage(index)
            val shown = ShownPage(page)
            val short = minOf(shown.width, shown.height)

            // Sized to the page and to the word, so DRAFT and CONFIDENTIAL both cross the
            // page; anything longer is shrunk to fit inside it.
            val tiled = spec.layout == WatermarkSpec.Layout.TILED
            val straight = spec.layout == WatermarkSpec.Layout.STRAIGHT
            val angle = when (spec.layout) {
                WatermarkSpec.Layout.TILED -> 30.0
                WatermarkSpec.Layout.STRAIGHT -> 0.0
                WatermarkSpec.Layout.ONCE_DIAGONAL -> 45.0
            }
            var size = if (tiled) {
                maxOf(14f, short * 0.045f)
            } else {
                val across = if (straight) shown.width else Math.hypot(shown.width.toDouble(), shown.height.toDouble()).toFloat()
                minOf(short * 0.2f, across * 0.7f / maxOf(4f, text.length * 0.6f))
            }
            if (!tiled) {
                // A small page (a till receipt, a card) gets a margin to match, and words that
                // would still run off its edge, straight or turned, are made smaller to fit.
                val margin = minOf(36f, short * 0.08f)
                size = fitted(shown, angle, margin, size) { s -> (font.getStringWidth(text) / 1000f * s) to (capRatio * s) }
            }
            val width = font.getStringWidth(text) / 1000f * size
            val cap = capRatio * size
            val rad = Math.toRadians(angle)
            val cos = Math.cos(rad).toFloat()
            val sin = Math.sin(rad).toFloat()

            // Every placement is worked out in the space the reader sees (origin bottom-left
            // of the visible page, y up) and the page's own turn is one transform on top.
            val centres = if (tiled) {
                tileCentres(shown.width, shown.height, width, size)
            } else {
                listOf(shown.width / 2f to shown.height / 2f)
            }

            PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true)
                .use { stream ->
                    stream.saveGraphicsState()
                    stream.transform(shown.viewMatrix())
                    if (tiled) {
                        val key = listOf(text, size, shown.width.toInt(), shown.height.toInt()).joinToString("|")
                        val form = tiles.getOrPut(key) {
                            PDFormXObject(doc).also { form ->
                                form.resources = PDResources()
                                form.bBox = PDRectangle(0f, 0f, shown.width, shown.height)
                                PDPageContentStream(doc, form).use { inner ->
                                    inner.setGraphicsStateParameters(alphaState(opacity))
                                    inner.setNonStrokingColor(spec.colour.red(), spec.colour.green(), spec.colour.blue())
                                    inner.beginText()
                                    inner.setFont(font, size)
                                    for ((cx, cy) in centres) {
                                        val vx = cx - cos * width / 2f + sin * cap / 2f
                                        val vy = cy - sin * width / 2f - cos * cap / 2f
                                        inner.setTextMatrix(Matrix.getRotateInstance(rad, vx, vy))
                                        inner.showText(text)
                                    }
                                    inner.endText()
                                }
                            }
                        }
                        stream.drawForm(form)
                    } else {
                        // Transparency lives in a graphics state, not on the text operator, so
                        // a watermark needs one of these - setting a grey fill instead would
                        // go opaque over anything it crossed.
                        stream.setGraphicsStateParameters(alphaState(opacity))
                        stream.setNonStrokingColor(spec.colour.red(), spec.colour.green(), spec.colour.blue())
                        stream.beginText()
                        stream.setFont(font, size)
                        for ((cx, cy) in centres) {
                            // The text's own origin is its bottom-left; put its centre on (cx, cy).
                            val vx = cx - cos * width / 2f + sin * cap / 2f
                            val vy = cy - sin * width / 2f - cos * cap / 2f
                            stream.setTextMatrix(Matrix.getRotateInstance(rad, vx, vy))
                            stream.showText(text)
                        }
                        stream.endText()
                    }
                    stream.restoreGraphicsState()
                }
            onProgress(done + 1, chosen.size)
        }
        doc.save(output)
    }

    override fun addPageNumbers(
        input: PdfInput,
        spec: PageNumberSpec,
        output: File,
        onProgress: (Int, Int) -> Unit
    ) = withDocument(input) { doc ->
        val font = PDType1Font.HELVETICA
        val total = doc.numberOfPages
        val numbered = total - spec.skipFirst

        for (index in 0 until total) {
            if (index < spec.skipFirst) {
                onProgress(index + 1, total)
                continue
            }

            val number = spec.startAt + (index - spec.skipFirst)
            val digits = if (spec.padTo > 0) number.toString().padStart(spec.padTo, '0') else number.toString()
            val label = spec.format
                .replace("{n}", spec.prefix + digits)
                .replace("{total}", numbered.toString())
                .toWinAnsiSafe()
            if (label.isBlank()) {
                onProgress(index + 1, total)
                continue
            }

            val page = doc.getPage(index)
            // Placed on the page as the reader sees it: inside the crop box, and the right
            // way up on a page stored sideways.
            val shown = ShownPage(page)
            val width = font.getStringWidth(label) / 1000f * spec.fontSize
            val margin = minOf(MARGIN_POINTS, minOf(shown.width, shown.height) * 0.08f)

            val x = when (spec.position) {
                PageNumberSpec.Position.TOP_LEFT, PageNumberSpec.Position.BOTTOM_LEFT -> margin
                PageNumberSpec.Position.TOP_CENTRE, PageNumberSpec.Position.BOTTOM_CENTRE -> (shown.width - width) / 2f
                else -> shown.width - width - margin
            }
            val y = when (spec.position) {
                PageNumberSpec.Position.TOP_LEFT, PageNumberSpec.Position.TOP_CENTRE, PageNumberSpec.Position.TOP_RIGHT ->
                    shown.height - margin - spec.fontSize
                else -> margin
            }

            PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true)
                .use { stream ->
                    stream.saveGraphicsState()
                    stream.transform(shown.viewMatrix())
                    stream.setNonStrokingColor(0f, 0f, 0f)
                    stream.beginText()
                    stream.setFont(font, spec.fontSize)
                    stream.newLineAtOffset(x, y)
                    stream.showText(label)
                    stream.endText()
                    stream.restoreGraphicsState()
                }
            onProgress(index + 1, total)
        }
        doc.save(output)
    }

    override fun cropPages(
        input: PdfInput,
        spec: CropSpec,
        output: File,
        onProgress: (Int, Int) -> Unit
    ) = withDocument(input) { doc ->
        val total = doc.numberOfPages
        val chosen = (spec.pages.ifEmpty { (0 until total).toList() }).filter { it in 0 until total }
        if (chosen.isEmpty()) throw PdfException.Io("None of the pages typed are in this document.")

        val renderer = if (spec.auto) PDFRenderer(doc) else null
        var trimmed = 0

        for ((done, index) in chosen.withIndex()) {
            val page = doc.getPage(index)
            val shown = ShownPage(page)

            // The kept part of the page, as fractions of what the reader sees.
            var left = 0f
            var top = 0f
            var right = 1f
            var bottom = 1f

            if (spec.auto) {
                val bitmap = renderer!!.renderImage(index, 1f)
                val ink = try {
                    inkBounds(bitmap)
                } finally {
                    bitmap.recycle()
                }
                if (ink == null) {
                    onProgress(done + 1, chosen.size)
                    continue
                }
                val padX = AUTO_PAD_POINTS / shown.width
                val padY = AUTO_PAD_POINTS / shown.height
                left = (ink[0] - padX).coerceAtLeast(0f)
                top = (ink[1] - padY).coerceAtLeast(0f)
                right = (ink[2] + padX).coerceAtMost(1f)
                bottom = (ink[3] + padY).coerceAtMost(1f)
                // Less than a millimetre to gain all round: leave the page alone.
                if ((left + 1f - right) * shown.width < MM_POINTS && (top + 1f - bottom) * shown.height < MM_POINTS) {
                    onProgress(done + 1, chosen.size)
                    continue
                }
            } else if (spec.trims) {
                left = spec.leftMm * MM_POINTS / shown.width
                top = spec.topMm * MM_POINTS / shown.height
                right = 1f - spec.rightMm * MM_POINTS / shown.width
                bottom = 1f - spec.bottomMm * MM_POINTS / shown.height
            }

            if (spec.auto || spec.trims) {
                if ((right - left) * shown.width < SMALLEST_PAGE_POINTS || (bottom - top) * shown.height < SMALLEST_PAGE_POINTS) {
                    throw PdfException.Io(
                        "That much off the edges would leave almost nothing of page ${index + 1}. Use smaller margins."
                    )
                }
                page.cropBox = shown.storedRect(left, top, right, bottom)
                trimmed += 1
            }
            onProgress(done + 1, chosen.size)
        }

        if (spec.auto && trimmed == 0 && spec.resizeTo == null) {
            throw PdfException.Io(
                "No white margins to remove: what is on the pages already runs to their edges, or they are blank."
            )
        }

        if (spec.resizeTo == null || spec.resizeTo == PageSpec.Size.FIT_IMAGE) {
            doc.save(output)
            return@withDocument
        }

        // Resizing is a different operation from cropping and cannot be done by setting a box.
        // A page has to be redrawn at the new size, so each one is imported as a form and
        // placed, scaled to fit, on a fresh sheet - the same technique N-up uses.
        val chosenSet = chosen.toSet()
        PDDocument(scratchMemory()).use { target ->
            // One utility per target document. It holds the import cache, so building a new
            // one per page would copy shared resources again for every page.
            val layers = LayerUtility(target)

            for (index in 0 until doc.numberOfPages) {
                if (index !in chosenSet) {
                    // A page not chosen keeps its own size, so it is carried across as it is.
                    target.importPage(doc.getPage(index))
                    continue
                }
                val sourceBox = doc.getPage(index).cropBox ?: doc.getPage(index).mediaBox ?: PDRectangle.A4
                val portrait = PDRectangle(spec.resizeTo.widthPoints, spec.resizeTo.heightPoints)

                // A landscape page goes onto a landscape sheet, as the reader sees it. Forcing
                // everything portrait would shrink wide pages to a third of the paper.
                val shown = ShownPage(doc.getPage(index))
                val sheetBox = if (shown.width > shown.height) {
                    PDRectangle(portrait.height, portrait.width)
                } else {
                    portrait
                }

                val sheet = PDPage(sheetBox)
                target.addPage(sheet)

                val form = layers.importPageAsForm(doc, index)
                val bounds = placedBounds(form, sourceBox)
                var scale = minOf(
                    sheetBox.width / bounds.width.coerceAtLeast(1f),
                    sheetBox.height / bounds.height.coerceAtLeast(1f)
                )
                // An ID card stays card-sized on the sheet when asked, for printing.
                if (!spec.enlarge && scale > 1f) scale = 1f
                val drawWidth = bounds.width * scale
                val drawHeight = bounds.height * scale

                PDPageContentStream(target, sheet, PDPageContentStream.AppendMode.APPEND, true, true)
                    .use { stream ->
                        stream.saveGraphicsState()
                        // The origin comes off the translation because the content does not
                        // start at zero after a crop. Centring without it slides the page by
                        // however much was trimmed, which took the first letter off every line.
                        stream.transform(
                            Matrix.getTranslateInstance(
                                (sheetBox.width - drawWidth) / 2f - bounds.lowerLeftX * scale,
                                (sheetBox.height - drawHeight) / 2f - bounds.lowerLeftY * scale
                            )
                        )
                        stream.transform(Matrix.getScaleInstance(scale, scale))
                        stream.drawForm(form)
                        stream.restoreGraphicsState()
                    }
            }
            target.save(output)
        }
    }

    override fun redact(
        input: PdfInput,
        areas: Map<Int, List<PageArea>>,
        dpi: Int,
        output: File,
        onProgress: (Int, Int) -> Unit
    ) {
        // HOW THIS REMOVES THINGS, AND WHAT IT COSTS.
        //
        // A black rectangle drawn over text leaves the text in the file. Anyone can select it,
        // copy it, or read it with a different tool - that is how redaction failures reach the
        // news, and it is what most "redact" buttons actually do.
        //
        // So the marked pages are rendered to an image with the black boxes painted on, and
        // that image replaces the page. The text underneath is not covered, it is gone: it was
        // never written into the new page at all.
        //
        // The cost is real and worth stating. A redacted page becomes a picture, so its text
        // stops being selectable and searchable, and the file gets bigger. Only the marked
        // pages are treated this way; the rest of the document keeps its text untouched.
        val marked = areas.filterValues { it.isNotEmpty() }
        if (marked.isEmpty()) throw PdfException.Io("Nothing was marked for removal.")

        // One page at a time, rendered and replaced before the next one is touched. Rendering
        // every marked page first and replacing them afterwards kept one full-resolution
        // bitmap alive per marked page, and the clamp that sizes them is a per-page budget: a
        // ten-page redaction of A4 at 200 DPI held ten lots of 14.7 MiB against a ceiling that
        // had only ever been asked about one. Sorted, because the marks arrive in the order
        // the pages were visited, and walking a lazily parsed document backwards is the one
        // order it is worst at.
        withDocument(input) { doc ->
            val renderer = PDFRenderer(doc)
            val total = marked.size
            var done = 0

            for ((index, rectangles) in marked.toSortedMap()) {
                if (index !in 0 until doc.numberOfPages) continue
                val page = doc.getPage(index)
                val size = page.toPageSize()
                val requested = BitmapBudget.widthForDpi(size.widthPoints, dpi)
                val width = BitmapBudget.clampWidth(requested, size.aspectRatio, RENDER_HEAP_BUDGET)
                val scale = if (size.widthPoints <= 0f) 1f else width / size.widthPoints

                val bitmap = try {
                    renderer.renderImage(index, scale)
                } catch (e: OutOfMemoryError) {
                    throw PdfException.OutOfMemory("page ${index + 1}", e)
                }

                try {
                    Canvas(bitmap).apply {
                        val paint = Paint().apply { color = Color.BLACK; isAntiAlias = false }
                        for (area in rectangles) {
                            drawRect(
                                area.left * bitmap.width,
                                area.top * bitmap.height,
                                (area.left + area.width) * bitmap.width,
                                (area.top + area.height) * bitmap.height,
                                paint
                            )
                        }
                    }

                    // The existing content is replaced rather than appended to. This is the
                    // line that makes the removal real: the new page has no text operators in
                    // it at all, only the image.
                    page.setContents(PDStream(doc))
                    page.resources = PDResources()

                    val image = JPEGFactory.createFromImage(doc, bitmap, REDACT_JPEG_QUALITY)
                    PDPageContentStream(doc, page, PDPageContentStream.AppendMode.OVERWRITE, true, true)
                        .use { stream ->
                            stream.drawImage(image, 0f, 0f, size.widthPoints, size.heightPoints)
                        }
                    // Rotation is already baked into the rendered image, so leaving it set
                    // would turn the page a second time.
                    page.rotation = 0
                } finally {
                    // The JPEG is in the document by now and this bitmap is nobody else's -
                    // unlike the ones PDImageXObject hands out - so it goes before the next
                    // page is rendered, failure or not.
                    bitmap.recycle()
                }

                done++
                onProgress(done, total)
            }
            doc.save(output)
        }
    }

    override fun stampImage(input: PdfInput, stamp: ImageStamp, output: File) =
        withDocument(input) { doc ->
            if (stamp.pageIndex !in 0 until doc.numberOfPages) {
                throw PdfException.Io("Page ${stamp.pageIndex + 1} does not exist in this document.")
            }
            val page = doc.getPage(stamp.pageIndex)
            // Placed on the page as the reader sees it: inside the crop box, the right way up.
            val shown = ShownPage(page)

            val image = try {
                PDImageXObject.createFromFile(stamp.image.absolutePath, doc)
            } catch (e: Exception) {
                throw PdfException.Corrupt("the image could not be read", e)
            }

            var width = shown.width * stamp.widthFraction.coerceIn(0.05f, 1f)
            var height = width * image.height / image.width.coerceAtLeast(1)
            // A tall picture at a wide setting would run off the page; it is held inside it.
            if (height > shown.height * 0.9f) {
                height = shown.height * 0.9f
                width = height * image.width / image.height.coerceAtLeast(1)
            }
            val margin = shown.width * stamp.marginFraction

            val x = when (stamp.anchor) {
                ImageStamp.Anchor.TOP_LEFT, ImageStamp.Anchor.MIDDLE_LEFT, ImageStamp.Anchor.BOTTOM_LEFT -> margin
                ImageStamp.Anchor.TOP_CENTRE, ImageStamp.Anchor.MIDDLE_CENTRE, ImageStamp.Anchor.BOTTOM_CENTRE ->
                    (shown.width - width) / 2f
                else -> shown.width - width - margin
            }
            val y = when (stamp.anchor) {
                ImageStamp.Anchor.TOP_LEFT, ImageStamp.Anchor.TOP_CENTRE, ImageStamp.Anchor.TOP_RIGHT ->
                    shown.height - height - margin
                ImageStamp.Anchor.MIDDLE_LEFT, ImageStamp.Anchor.MIDDLE_CENTRE, ImageStamp.Anchor.MIDDLE_RIGHT ->
                    (shown.height - height) / 2f
                else -> margin
            }

            // Drawn into the page content rather than added as an annotation, so it is part of
            // the document. An annotation can be moved or deleted by the next reader, which is
            // not what anyone means by signing something.
            PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true)
                .use { stream ->
                    stream.saveGraphicsState()
                    stream.transform(shown.viewMatrix())
                    stream.drawImage(image, x, y, width, height)
                    stream.restoreGraphicsState()
                }

            doc.save(output)
        }

    override fun addText(input: PdfInput, notes: List<TextNote>, output: File) =
        withDocument(input) { doc ->
            for (note in notes) {
                if (note.pageIndex !in 0 until doc.numberOfPages) {
                    throw PdfException.Io("Page ${note.pageIndex + 1} does not exist in this document.")
                }

                val lines = note.text.lines().map { it.toWinAnsiSafe() }
                    .dropLastWhile { it.isBlank() }
                if (lines.isEmpty() || lines.all { it.isBlank() }) continue

                val page = doc.getPage(note.pageIndex)
                // The page as the reader saw it when they tapped, which on a scan filed
                // sideways is not the page as the file stores it.
                val shown = ShownPage(page)

                val size = note.sizePoints.coerceIn(MIN_NOTE_POINTS, MAX_NOTE_POINTS)
                val lineHeight = size * LINE_SPACING
                val across = note.left.coerceIn(0f, 1f) * shown.width
                // The tap marks the top of the line; the baseline sits one line below it.
                val down = note.top.coerceIn(0f, 1f) * shown.height + size
                val (originX, originY) = shown.pointAt(across, down)

                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true)
                    .use { stream ->
                        stream.saveGraphicsState()
                        // One transform carries the position and the page's own quarter turn
                        // together, so everything after it is written as though the first line
                        // began at the origin and ran left to right.
                        stream.transform(Matrix.getRotateInstance(shown.radians, originX, originY))

                        if (note.cover) {
                            val widest = lines.maxOf { widthOfHelvetica(it, size) }
                            val drop = (lines.size - 1) * lineHeight
                            stream.setNonStrokingColor(1f, 1f, 1f)
                            stream.addRect(
                                -COVER_PADDING,
                                -drop - size * NOTE_DESCENT,
                                widest + COVER_PADDING * 2f,
                                drop + size * (NOTE_DESCENT + NOTE_ASCENT)
                            )
                            stream.fill()
                        }

                        stream.setNonStrokingColor(
                            ((note.colour shr 16) and 0xFF) / 255f,
                            ((note.colour shr 8) and 0xFF) / 255f,
                            (note.colour and 0xFF) / 255f
                        )
                        stream.beginText()
                        stream.setFont(PDType1Font.HELVETICA, size)
                        stream.newLineAtOffset(0f, 0f)
                        lines.forEachIndexed { index, line ->
                            // Td is measured from the start of the line before it, so this
                            // both drops a line and returns to the left edge.
                            if (index > 0) stream.newLineAtOffset(0f, -lineHeight)
                            stream.showText(line)
                        }
                        stream.endText()
                        stream.restoreGraphicsState()
                    }
            }

            doc.save(output)
        }

    override fun annotate(
        input: PdfInput,
        marks: Map<Int, List<PageMark>>,
        output: File,
        onProgress: (Int, Int) -> Unit
    ) = withDocument(input) { doc ->
        val marked = marks.filterValues { it.isNotEmpty() }
        if (marked.isEmpty()) throw PdfException.Io("Nothing was marked on any page.")

        val total = marked.size
        var done = 0
        val font = PDType1Font.HELVETICA
        val bold = PDType1Font.HELVETICA_BOLD

        for ((index, list) in marked) {
            if (index !in 0 until doc.numberOfPages) continue
            val page = doc.getPage(index)
            val shown = ShownPage(page)
            // Every mark is worked out in the space the reader sees, with the page's own turn
            // and crop origin applied once, which is what makes a mark land correctly on a
            // page that is stored sideways.
            fun x(fraction: Float) = fraction * shown.width
            fun y(fraction: Float) = shown.height - fraction * shown.height

            PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true)
                .use { stream ->
                    stream.saveGraphicsState()
                    stream.transform(shown.viewMatrix())
                    for (mark in list) {
                        val red = ((mark.colour shr 16) and 0xFF) / 255f
                        val green = ((mark.colour shr 8) and 0xFF) / 255f
                        val blue = (mark.colour and 0xFF) / 255f

                        when (mark) {
                            is PageMark.Highlight -> {
                                stream.saveGraphicsState()
                                // Transparency lives in a graphics state, not on the fill, so
                                // a highlight needs one of these - an opaque block of yellow
                                // would hide the words it is pointing at.
                                stream.setGraphicsStateParameters(alphaState(HIGHLIGHT_ALPHA))
                                stream.setNonStrokingColor(red, green, blue)
                                stream.addRect(
                                    x(mark.area.left),
                                    y(mark.area.top + mark.area.height),
                                    mark.area.width * shown.width,
                                    mark.area.height * shown.height
                                )
                                stream.fill()
                                stream.restoreGraphicsState()
                            }

                            is PageMark.Ink -> {
                                if (mark.points.size < 2) continue
                                stream.saveGraphicsState()
                                stream.setStrokingColor(red, green, blue)
                                stream.setLineWidth(mark.widthPoints.coerceIn(0.3f, 24f))
                                // Round caps and joins: a finger-drawn line with square ends
                                // reads as a series of tiles rather than one stroke.
                                stream.setLineCapStyle(1)
                                stream.setLineJoinStyle(1)
                                stream.moveTo(x(mark.points.first().x), y(mark.points.first().y))
                                for (point in mark.points.drop(1)) stream.lineTo(x(point.x), y(point.y))
                                stream.stroke()
                                stream.restoreGraphicsState()
                            }

                            is PageMark.Shape -> {
                                val left = x(mark.area.left)
                                val right = x(mark.area.left + mark.area.width)
                                val top = y(mark.area.top)
                                val bottom = y(mark.area.top + mark.area.height)
                                stream.saveGraphicsState()
                                stream.setStrokingColor(red, green, blue)
                                stream.setLineWidth(mark.widthPoints.coerceIn(0.3f, 12f))
                                stream.setLineCapStyle(1)
                                stream.setLineJoinStyle(1)
                                when (mark.kind) {
                                    PageMark.Shape.Kind.UNDERLINE -> {
                                        // Just under the dragged area: the drag is across the
                                        // words, and the rule belongs beneath them.
                                        stream.moveTo(left, bottom)
                                        stream.lineTo(right, bottom)
                                        stream.stroke()
                                    }
                                    PageMark.Shape.Kind.STRIKETHROUGH -> {
                                        val middle = (top + bottom) / 2f
                                        stream.moveTo(left, middle)
                                        stream.lineTo(right, middle)
                                        stream.stroke()
                                    }
                                    PageMark.Shape.Kind.BOX -> {
                                        stream.addRect(left, bottom, right - left, top - bottom)
                                        stream.stroke()
                                    }
                                    PageMark.Shape.Kind.CIRCLE -> {
                                        ellipse(stream, left, bottom, right - left, top - bottom)
                                        stream.stroke()
                                    }
                                }
                                stream.restoreGraphicsState()
                            }

                            is PageMark.Line -> {
                                val x1 = x(mark.from.x)
                                val y1 = y(mark.from.y)
                                val x2 = x(mark.to.x)
                                val y2 = y(mark.to.y)
                                val width = mark.widthPoints.coerceIn(0.3f, 12f)
                                stream.saveGraphicsState()
                                stream.setStrokingColor(red, green, blue)
                                stream.setNonStrokingColor(red, green, blue)
                                stream.setLineWidth(width)
                                stream.setLineCapStyle(1)
                                stream.setLineJoinStyle(1)
                                stream.moveTo(x1, y1)
                                stream.lineTo(x2, y2)
                                stream.stroke()
                                if (mark.arrow) {
                                    // A head sized to the line, so a thick arrow has a head
                                    // one can see and a hairline one does not get a barn door.
                                    val head = (width * 5f).coerceIn(7f, 24f)
                                    val angle = Math.atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())
                                    val spread = Math.toRadians(26.0)
                                    val ax = (x2 - head * Math.cos(angle - spread)).toFloat()
                                    val ay = (y2 - head * Math.sin(angle - spread)).toFloat()
                                    val bx = (x2 - head * Math.cos(angle + spread)).toFloat()
                                    val by = (y2 - head * Math.sin(angle + spread)).toFloat()
                                    stream.moveTo(x2, y2)
                                    stream.lineTo(ax, ay)
                                    stream.lineTo(bx, by)
                                    stream.closePath()
                                    stream.fill()
                                }
                                stream.restoreGraphicsState()
                            }

                            is PageMark.Note -> {
                                val text = mark.text.toWinAnsiSafe().trim()
                                if (text.isEmpty()) continue
                                val size = mark.sizePoints.coerceIn(MIN_NOTE_POINTS, MAX_NOTE_POINTS)
                                val lines = wrapForWidth(text, font, size, shown.width * 0.45f)
                                val widest = lines.maxOf { font.getStringWidth(it) / 1000f * size }
                                val pad = size * 0.5f
                                val cardWidth = widest + pad * 2f
                                val cardHeight = lines.size * size * LINE_SPACING + pad * 2f
                                // The card stays on the page: a note tapped near the right
                                // edge is pulled back in rather than cut off.
                                val cardLeft = x(mark.at.x).coerceIn(0f, (shown.width - cardWidth).coerceAtLeast(0f))
                                val cardTop = y(mark.at.y).coerceIn(cardHeight.coerceAtMost(shown.height), shown.height)
                                stream.saveGraphicsState()
                                stream.setNonStrokingColor(1f, 0.97f, 0.78f)
                                stream.setStrokingColor(0.85f, 0.75f, 0.35f)
                                stream.setLineWidth(0.6f)
                                stream.addRect(cardLeft, cardTop - cardHeight, cardWidth, cardHeight)
                                stream.fillAndStroke()
                                stream.setNonStrokingColor(red, green, blue)
                                stream.beginText()
                                stream.setFont(font, size)
                                stream.newLineAtOffset(cardLeft + pad, cardTop - pad - size * NOTE_ASCENT)
                                lines.forEachIndexed { i, line ->
                                    if (i > 0) stream.newLineAtOffset(0f, -size * LINE_SPACING)
                                    stream.showText(line)
                                }
                                stream.endText()
                                stream.restoreGraphicsState()
                            }

                            is PageMark.Stamp -> {
                                val text = mark.text.toWinAnsiSafe().trim().uppercase()
                                if (text.isEmpty()) continue
                                // Sized to the page, like a watermark: a stamp on a receipt
                                // is not the size of one on a poster.
                                val size = (minOf(shown.width, shown.height) * 0.045f).coerceIn(10f, 28f)
                                val textWidth = bold.getStringWidth(text) / 1000f * size
                                val pad = size * 0.45f
                                val boxWidth = textWidth + pad * 2f
                                val boxHeight = size * 1.5f
                                val cx = x(mark.at.x)
                                val cy = y(mark.at.y)
                                stream.saveGraphicsState()
                                stream.setGraphicsStateParameters(alphaState(0.85f))
                                // Turned a few degrees, the way a hand-stamped one lands.
                                stream.transform(Matrix.getRotateInstance(Math.toRadians(-8.0), cx, cy))
                                stream.setStrokingColor(red, green, blue)
                                stream.setNonStrokingColor(red, green, blue)
                                stream.setLineWidth(size * 0.12f)
                                stream.addRect(-boxWidth / 2f, -boxHeight / 2f, boxWidth, boxHeight)
                                stream.stroke()
                                stream.beginText()
                                stream.setFont(bold, size)
                                stream.newLineAtOffset(-textWidth / 2f, -size * 0.36f)
                                stream.showText(text)
                                stream.endText()
                                stream.restoreGraphicsState()
                            }
                        }
                    }
                    stream.restoreGraphicsState()
                }

            done += 1
            onProgress(done, total)
        }

        doc.save(output)
    }

    /** An ellipse inside the box, as four Bezier curves; PDF has no circle operator. */
    private fun ellipse(stream: PDPageContentStream, left: Float, bottom: Float, width: Float, height: Float) {
        val k = 0.5523f
        val cx = left + width / 2f
        val cy = bottom + height / 2f
        val rx = width / 2f
        val ry = height / 2f
        stream.moveTo(cx + rx, cy)
        stream.curveTo(cx + rx, cy + ry * k, cx + rx * k, cy + ry, cx, cy + ry)
        stream.curveTo(cx - rx * k, cy + ry, cx - rx, cy + ry * k, cx - rx, cy)
        stream.curveTo(cx - rx, cy - ry * k, cx - rx * k, cy - ry, cx, cy - ry)
        stream.curveTo(cx + rx * k, cy - ry, cx + rx, cy - ry * k, cx + rx, cy)
        stream.closePath()
    }

    /** Breaks text into lines no wider than [maxWidth], at spaces where it can. */
    private fun wrapForWidth(text: String, font: PDType1Font, size: Float, maxWidth: Float): List<String> {
        val out = mutableListOf<String>()
        for (paragraph in text.split('\n')) {
            var line = StringBuilder()
            for (word in paragraph.split(' ')) {
                val candidate = if (line.isEmpty()) word else "$line $word"
                val width = runCatching { font.getStringWidth(candidate) / 1000f * size }.getOrDefault(candidate.length * size * 0.5f)
                if (width <= maxWidth || line.isEmpty()) {
                    line = StringBuilder(candidate)
                } else {
                    out += line.toString()
                    line = StringBuilder(word)
                }
            }
            out += line.toString()
        }
        return out.ifEmpty { listOf(text) }
    }

    override fun stampHeaderFooter(
        input: PdfInput,
        spec: StampSpec,
        output: File,
        onProgress: (Int, Int) -> Unit
    ) = withDocument(input) { doc ->
        val header = spec.headerText.trim()
        val footer = spec.footerText.trim()
        if (header.isEmpty() && footer.isEmpty()) {
            throw PdfException.Io("There is no header or footer text to add.")
        }

        val font = PDType1Font.HELVETICA
        val size = spec.fontSize.coerceIn(5f, 48f)
        val total = doc.numberOfPages
        val today = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM)
            .format(java.util.Date())
        val name = spec.documentName.substringBeforeLast('.', spec.documentName)

        for (index in 0 until total) {
            if (index < spec.skipFirst) {
                onProgress(index + 1, total)
                continue
            }

            val page = doc.getPage(index)
            val shown = ShownPage(page)
            val margin = spec.marginPoints.coerceIn(8f, shown.height / 3f)

            // Both lines are written in one stream, and both go through the same turn, so a
            // sideways page gets a header along the edge the reader calls the top.
            PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true)
                .use { stream ->
                    stream.setNonStrokingColor(0f, 0f, 0f)

                    for ((text, fromTop) in listOf(
                        header to margin,
                        footer to shown.height - margin
                    )) {
                        val line = text
                            .replace("{page}", (index + 1).toString())
                            .replace("{total}", total.toString())
                            .replace("{date}", today)
                            .replace("{name}", name)
                            .toWinAnsiSafe()
                        if (line.isBlank()) continue

                        val lineWidth = runCatching { font.getStringWidth(line) / 1000f * size }
                            .getOrDefault(line.length * size * 0.5f)
                        val across = when (spec.align) {
                            StampSpec.Align.LEFT -> margin
                            StampSpec.Align.CENTRE -> (shown.width - lineWidth) / 2f
                            StampSpec.Align.RIGHT -> shown.width - lineWidth - margin
                        }.coerceAtLeast(0f)

                        val (x, y) = shown.pointAt(across, fromTop)
                        stream.saveGraphicsState()
                        stream.transform(Matrix.getRotateInstance(shown.radians, x, y))
                        try {
                            stream.beginText()
                            stream.setFont(font, size)
                            stream.newLineAtOffset(0f, 0f)
                            stream.showText(line)
                            stream.endText()
                        } catch (_: Exception) {
                            // A line that will not encode must not leave the stream inside a
                            // text object, which would corrupt every page after this one.
                            runCatching { stream.endText() }
                        }
                        stream.restoreGraphicsState()
                    }
                }
            onProgress(index + 1, total)
        }
        doc.save(output)
    }

    override fun readFormFields(input: PdfInput): List<FormField> = withDocument(input) { doc ->
        val form = doc.documentCatalog?.acroForm ?: return@withDocument emptyList()

        buildList {
            for (field in terminalFields(form.fields)) {
                val name = field.fullyQualifiedName?.takeIf { it.isNotBlank() } ?: continue
                val label = field.alternateFieldName?.takeIf { it.isNotBlank() }
                val value = runCatching { field.valueAsString }.getOrNull().orEmpty()

                when (field) {
                    is PDTextField -> add(
                        FormField(name, label, value, FormField.Kind.TEXT, readOnly = field.isReadOnly)
                    )

                    is PDCheckBox -> add(
                        FormField(
                            name = name,
                            label = label,
                            value = if (runCatching { field.isChecked }.getOrDefault(false)) {
                                TICKED
                            } else {
                                ""
                            },
                            kind = FormField.Kind.TICK,
                            options = listOf(runCatching { field.onValue }.getOrNull().orEmpty()),
                            readOnly = field.isReadOnly
                        )
                    )

                    is PDRadioButton -> add(
                        FormField(
                            name = name,
                            label = label,
                            value = value,
                            kind = FormField.Kind.CHOICE,
                            options = runCatching { field.onValues.toList() }.getOrDefault(emptyList()),
                            readOnly = field.isReadOnly
                        )
                    )

                    is PDChoice -> add(
                        FormField(
                            name = name,
                            label = label,
                            value = value,
                            kind = FormField.Kind.CHOICE,
                            options = runCatching { field.options.orEmpty() }.getOrDefault(emptyList()),
                            readOnly = field.isReadOnly
                        )
                    )

                    // Push buttons do something rather than hold something, and a signature
                    // field needs a certificate this app deliberately does not have. Offering
                    // either as a box to type in would be a lie about what it does.
                    else -> Unit
                }
            }
        }
    }

    override fun fillForm(
        input: PdfInput,
        values: Map<String, String>,
        flatten: Boolean,
        output: File
    ) = withDocument(input) { doc ->
        val form = doc.documentCatalog?.acroForm
            ?: throw PdfException.Unsupported("this document has no form fields in it")

        // Without this a viewer may show the old value until the field is clicked: the flag
        // tells readers the appearances in the file are stale, and PDFBox then builds them.
        form.setNeedAppearances(false)

        for (field in terminalFields(form.fields)) {
            val name = field.fullyQualifiedName ?: continue
            val wanted = values[name] ?: continue
            if (field.isReadOnly) continue

            // One unsettable field must not cost the rest of the form its values. A radio
            // button rejects a value that is not one of its own, and a text field with an
            // unusual format can refuse too.
            runCatching {
                when (field) {
                    is PDTextField -> field.value = wanted
                    is PDCheckBox -> if (wanted.isBlank() || wanted == "false") {
                        field.unCheck()
                    } else {
                        field.check()
                    }
                    is PDButton -> field.value = wanted
                    is PDChoice -> field.setValue(wanted)
                    else -> Unit
                }
            }
        }

        if (flatten) {
            // Draws the values into the pages and takes the fields away. The result behaves
            // like a filled-in piece of paper: nobody can retype it, and no viewer can offer
            // to. That is the point, and it cannot be undone.
            runCatching { form.refreshAppearances() }
            form.flatten()
        }

        doc.save(output)
    }

    override fun insertPages(
        input: PdfInput,
        insert: PdfInput?,
        blankPages: Int,
        atIndex: Int,
        output: File,
        replace: List<Int>
    ) = withDocument(input) { doc ->
        val replacing = replace.filter { it in 0 until doc.numberOfPages }.distinct().sorted()
        if (replacing.size == doc.numberOfPages && insert == null) {
            throw PdfException.Io("That would replace every page with blank ones.")
        }
        // Replacing: the new pages go where the first of the replaced ones was.
        val at = (if (replacing.isNotEmpty()) replacing.first() else atIndex).coerceIn(0, doc.numberOfPages)
        // Null when the pages go at the end, which is where insertBefore cannot help.
        val anchor = if (at < doc.numberOfPages) doc.getPage(at) else null
        // Taken out after the new ones are in, by identity: the anchor itself may be one of
        // them, and removing it first would leave nowhere to insert before.
        val leaving = replacing.map { doc.getPage(it) }

        if (insert != null) {
            // The imported pages share objects with the document they came from, so the
            // source stays open until this one has been written.
            withDocument(insert) { source ->
                if (source.numberOfPages == 0) {
                    throw PdfException.Io("The document to insert has no pages in it.")
                }
                for (page in source.pages) {
                    val imported = doc.importPage(page)
                    if (anchor != null) {
                        // importPage appends; moving it is how it lands at the chosen point,
                        // and doing it per page keeps them in their original order.
                        doc.pages.remove(imported)
                        doc.pages.insertBefore(imported, anchor)
                    }
                }
                for (page in leaving) doc.pages.remove(page)
                doc.save(output)
            }
        } else {
            val count = blankPages.coerceIn(1, MAX_BLANK_PAGES)
            val template = if (doc.numberOfPages > 0) {
                doc.getPage(0).mediaBox ?: PDRectangle.A4
            } else {
                PDRectangle.A4
            }
            repeat(count) {
                val blank = PDPage(PDRectangle(template.width, template.height))
                if (anchor != null) doc.pages.insertBefore(blank, anchor) else doc.addPage(blank)
            }
            for (page in leaving) doc.pages.remove(page)
            doc.save(output)
        }
    }

    // ---- P4: power tools ----

    override fun nUp(
        input: PdfInput,
        perSheet: Int,
        paper: PageSpec.Size,
        output: File,
        onProgress: (Int, Int) -> Unit
    ) = withDocument(input) { source ->
        val per = if (perSheet in GRIDS) perSheet else 2
        val total = source.numberOfPages
        if (total == 0) throw PdfException.Io("This document has no pages.")

        // The grid is chosen for the shape most of the pages have, as the reader sees them.
        val shapes = HashMap<String, Triple<Float, Float, Int>>()
        for (index in 0 until total) {
            val shown = ShownPage(source.getPage(index))
            val key = "${shown.width.toInt()}x${shown.height.toInt()}"
            val seen = shapes[key]
            shapes[key] = Triple(shown.width, shown.height, (seen?.third ?: 0) + 1)
        }
        val usual = shapes.values.maxByOrNull { it.third } ?: Triple(595f, 842f, 1)
        val layout = nUpLayout(per, paper, usual.first, usual.second)

        PDDocument(scratchMemory()).use { target ->
            val layers = LayerUtility(target)
            var index = 0
            while (index < total) {
                val sheet = PDPage(PDRectangle(layout.sheetWidth, layout.sheetHeight))
                target.addPage(sheet)

                PDPageContentStream(target, sheet, PDPageContentStream.AppendMode.APPEND, true, true)
                    .use { stream ->
                        for (slot in 0 until per) {
                            val pageIndex = index + slot
                            if (pageIndex >= total) break
                            val column = slot % layout.columns
                            val row = slot / layout.columns
                            val cellX = layout.margin + column * (layout.cellWidth + layout.gap)
                            // Rows run down the sheet, but PDF's origin is at the bottom, so
                            // row zero is the top and the index is inverted here.
                            val cellY = layout.sheetHeight - layout.margin - (row + 1) * layout.cellHeight - row * layout.gap

                            val form = layers.importPageAsForm(source, pageIndex)
                            val bounds = placedBounds(form, source.getPage(pageIndex).mediaBox ?: PDRectangle.A4)
                            val scale = minOf(
                                layout.cellWidth / bounds.width.coerceAtLeast(1f),
                                layout.cellHeight / bounds.height.coerceAtLeast(1f)
                            )
                            val drawWidth = bounds.width * scale
                            val drawHeight = bounds.height * scale
                            // Both offsets carry the box origin: a page whose content does not
                            // begin at zero is otherwise drawn that far outside its cell.
                            val x = cellX + (layout.cellWidth - drawWidth) / 2f - bounds.lowerLeftX * scale
                            val y = cellY + (layout.cellHeight - drawHeight) / 2f - bounds.lowerLeftY * scale

                            stream.saveGraphicsState()
                            stream.transform(Matrix.getTranslateInstance(x, y))
                            stream.transform(Matrix.getScaleInstance(scale, scale))
                            stream.drawForm(form)
                            stream.restoreGraphicsState()
                        }
                    }

                index += per
                onProgress(minOf(index, total), total)
            }
            target.save(output)
        }
    }

    override fun measureSizeSplit(
        input: PdfInput,
        maxBytes: Long,
        onProgress: (Int, Int) -> Unit
    ): List<List<Int>> = withDocument(input) { doc ->
        val total = doc.numberOfPages
        if (total == 0) return@withDocument emptyList()

        // A trial save of the pages from..to, inclusive, measured and thrown away.
        fun sizeOf(from: Int, to: Int): Long {
            PDDocument(scratchMemory()).use { part ->
                for (index in from..to) part.importPage(doc.getPage(index))
                val trial = File.createTempFile("part", ".pdf", input.file.parentFile)
                try {
                    part.save(trial)
                    return trial.length()
                } finally {
                    trial.delete()
                }
            }
        }

        val groups = mutableListOf<List<Int>>()
        var start = 0
        while (start < total) {
            onProgress(start, total)
            var bestEnd = start
            if (sizeOf(start, start) <= maxBytes) {
                // Doubling out until a part is too big, then halving back into the gap.
                var step = 1
                var tooBig = total
                while (bestEnd < total - 1) {
                    val candidate = minOf(total - 1, bestEnd + step)
                    if (sizeOf(start, candidate) <= maxBytes) {
                        bestEnd = candidate
                        step *= 2
                    } else {
                        tooBig = candidate
                        break
                    }
                }
                var low = bestEnd + 1
                var high = tooBig - 1
                while (low <= high) {
                    val mid = (low + high) / 2
                    if (sizeOf(start, mid) <= maxBytes) {
                        bestEnd = mid
                        low = mid + 1
                    } else {
                        high = mid - 1
                    }
                }
            }
            // A single page over the limit still goes out as a part of its own, flagged by the
            // caller, rather than stopping the whole split.
            groups += (start..bestEnd).toList()
            start = bestEnd + 1
        }
        onProgress(total, total)
        groups
    }

    override fun extractImages(
        input: PdfInput,
        outputDir: File,
        baseName: String,
        onProgress: (Int, Int) -> Unit
    ): List<File> = withDocument(input) { doc ->
        outputDir.mkdirs()
        val written = mutableListOf<File>()
        val total = doc.numberOfPages
        var found = 0

        // One entry per distinct image, not per placement. A letterhead logo is referenced
        // from every page but is a single object in the file, and someone extracting images
        // wants it once rather than nine identical copies.
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())

        for (index in 0 until total) {
            val resources = doc.getPage(index).resources
            if (resources != null) {
                for (name in resources.xObjectNames.toList()) {
                    val xObject = runCatching { resources.getXObject(name) }.getOrNull()
                    if (xObject !is PDImageXObject) continue
                    if (!seen.add(xObject.cosObject)) continue

                    // The embedded original, at whatever resolution it was stored, not a
                    // re-render of the page. That is the difference between this and
                    // PDF to images, and it is the whole reason both exist.
                    //
                    // NEVER RECYCLE THIS BITMAP. PDImageXObject caches it and hands the same
                    // instance back on the next access, so recycling it here meant the second
                    // page that used the same logo failed with "Can't compress a recycled
                    // bitmap". PDFBox frees it when the document closes.
                    val bitmap = runCatching { xObject.image }.getOrNull() ?: continue
                    found++
                    // The page is in the name, so a converter can put each picture back on
                    // the page it came from.
                    val file = File(outputDir, "$baseName-p${index + 1}-${found.toString().padStart(3, '0')}.png")
                    try {
                        FileOutputStream(file).use { out ->
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                        }
                        written += file
                    } catch (e: IOException) {
                        file.delete()
                    } catch (e: IllegalStateException) {
                        // A bitmap another part of the library already released.
                        file.delete()
                    }
                }
            }
            onProgress(index + 1, total)
        }
        written
    }

    override fun writeMetadata(input: PdfInput, metadata: DocumentMetadata, output: File) =
        withDocument(input) { doc ->
            val info = doc.documentInformation
            if (metadata.stripAll) {
                // Every field, including the ones nobody sets by hand. A document produced by
                // a scanner carries its make and model; someone stripping metadata means that
                // too, not just the title.
                info.title = null
                info.author = null
                info.subject = null
                info.keywords = null
                info.creator = null
                info.producer = null
                info.creationDate = null
                info.modificationDate = null
            } else {
                metadata.title?.let { info.title = it.ifBlank { null } }
                metadata.author?.let { info.author = it.ifBlank { null } }
                metadata.subject?.let { info.subject = it.ifBlank { null } }
                metadata.keywords?.let { info.keywords = it.ifBlank { null } }
            }
            doc.save(output)
        }

    override fun cleanDocument(input: PdfInput, spec: CleanSpec, output: File): CleanReport =
        withDocument(input) { doc ->
            val report = doc.cleanUp(spec)
            doc.save(output)
            report
        }

    override fun setPageLabels(input: PdfInput, spec: PageLabelSpec?, output: File) =
        withDocument(input) { doc ->
            doc.applyPageLabels(spec)
            doc.save(output)
        }

    override fun setBackground(
        input: PdfInput,
        colour: Int,
        pages: List<Int>,
        output: File,
        onProgress: (Int, Int) -> Unit
    ) = withDocument(input) { doc ->
        val total = doc.numberOfPages
        val chosen = pages.ifEmpty { (0 until total).toList() }.filter { it in 0 until total }
        if (chosen.isEmpty()) throw PdfException.Io("None of the pages typed are in this document.")
        for ((done, index) in chosen.withIndex()) {
            doc.paintBackground(doc.getPage(index), colour)
            onProgress(done + 1, chosen.size)
        }
        doc.save(output)
    }

    override fun readAttachments(input: PdfInput): List<AttachmentInfo> = withDocument(input) { doc ->
        val all = doc.embeddedFiles().entries.map { (name, spec) -> name to spec } + doc.attachmentNotes()
        all.map { (name, spec) ->
            val size = runCatching { (spec.embeddedFileUnicode ?: spec.embeddedFile)?.size?.toLong() }.getOrNull()
                ?: spec.bytesOrNull()?.size?.toLong()
                ?: 0L
            AttachmentInfo(name, size)
        }
    }

    override fun attachFiles(input: PdfInput, files: List<Pair<String, File>>, output: File) =
        withDocument(input) { doc ->
            if (files.isEmpty()) throw PdfException.Io("Pick at least one file to attach.")
            doc.attach(files)
            doc.save(output)
        }

    override fun extractAttachments(input: PdfInput, outputDir: File): List<File> = withDocument(input) { doc ->
        outputDir.mkdirs()
        val all = doc.embeddedFiles().entries.map { (name, spec) -> name to spec } + doc.attachmentNotes()
        if (all.isEmpty()) throw PdfException.Io("This document has no files attached to it.")
        val written = mutableListOf<File>()
        val taken = mutableSetOf<String>()
        for ((wanted, spec) in all) {
            val bytes = spec.bytesOrNull() ?: continue
            // A file name is the attachment's own; only what cannot be a file name is replaced.
            val safe = wanted.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().ifEmpty { "attachment" }
            var name = safe
            var n = 2
            while (name.lowercase() in taken) {
                val dot = safe.lastIndexOf('.')
                name = if (dot > 0) "${safe.substring(0, dot)} ($n)${safe.substring(dot)}" else "$safe ($n)"
                n += 1
            }
            taken += name.lowercase()
            val file = File(outputDir, name)
            file.writeBytes(bytes)
            written += file
        }
        if (written.isEmpty()) throw PdfException.Io("The attached files could not be read out of this document.")
        written
    }

    override fun writeOutline(input: PdfInput, entries: List<OutlineEntry>, output: File) =
        withDocument(input) { doc ->
            doc.replaceOutline(entries)
            doc.save(output)
        }

    /** One graphics state carrying a fill opacity, for anything see-through. */
    private fun alphaState(opacity: Float): PDExtendedGraphicsState =
        PDExtendedGraphicsState().apply {
            nonStrokingAlphaConstant = opacity
            strokingAlphaConstant = opacity
        }

    /**
     * The size at which words set [size] tall, turned by [angle] degrees, fit inside the
     * page with [margin] all round. Smaller when they would not; never larger.
     */
    private fun fitted(
        shown: ShownPage,
        angle: Double,
        margin: Float,
        size: Float,
        measure: (Float) -> Pair<Float, Float>
    ): Float {
        val rad = Math.toRadians(angle)
        val (w, h) = measure(size)
        val across = (Math.abs(Math.cos(rad)) * w + Math.abs(Math.sin(rad)) * h).toFloat()
        val down = (Math.abs(Math.sin(rad)) * w + Math.abs(Math.cos(rad)) * h).toFloat()
        val roomX = shown.width - 2f * margin
        val roomY = shown.height - 2f * margin
        if (roomX <= 0f || roomY <= 0f || across <= 0f || down <= 0f) return size
        val k = minOf(1f, roomX / across, roomY / down)
        return if (k < 1f) maxOf(size * 0.01f, size * k) else size
    }

    /**
     * Centres for a staggered grid that covers the page, capped so tiny text cannot draw
     * ten thousand copies.
     */
    private fun tileCentres(
        pageWidth: Float,
        pageHeight: Float,
        textWidth: Float,
        size: Float
    ): List<Pair<Float, Float>> {
        val stepX = textWidth + size * 3f
        val stepY = size * 5f
        val out = mutableListOf<Pair<Float, Float>>()
        var row = 0
        var cy = -pageHeight * 0.25f
        while (cy < pageHeight * 1.25f && out.size < MAX_TILES) {
            val offset = if (row % 2 == 0) 0f else stepX / 2f
            var cx = -pageWidth * 0.25f + offset
            while (cx < pageWidth * 1.25f && out.size < MAX_TILES) {
                out += cx to cy
                cx += stepX
            }
            row += 1
            cy += stepY
        }
        return out
    }

    /**
     * An owner password nobody knows, for a file that opens freely but is restricted.
     *
     * The restrictions are only as strong as the owner password, so a guessable one would
     * be no restriction at all. Thrown away after the save: there is nothing to keep.
     */
    private fun randomOwnerPassword(): String {
        val bytes = ByteArray(24)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // ---- internals ----

    /**
     * How PDFBox buffers a document here, and it is not optional either way round.
     *
     * Reading, the default holds the whole file in the heap and the documents that matter are
     * exactly the ones too big for that. Writing, the no-arg `PDDocument()` resolves to
     * setupMainMemoryOnly, so an output buffers entirely in the heap until save is called -
     * thirty gallery photos at 5 MB each is ~150 MB held before a byte is written, and the
     * OutOfMemoryError comes out of PDFBox, which the user is shown as the document being too
     * large to process on this device for a request that is nothing of the kind.
     *
     * The trade is honest: this buys heap with cache I/O, so every save does real disk work it
     * did not do before. A slower save is still a save.
     */
    private fun scratchMemory(): MemoryUsageSetting {
        val setting = MemoryUsageSetting.setupTempFileOnly()
        val dir = scratchDir ?: return setting
        // PDFBox creates its spill file in this directory but will not create the directory,
        // and refuses outright the moment it is missing - which it can be at any point, since
        // the cache belongs to Android and clearing it takes the directory with the files.
        // Falling back to the process temp directory keeps the operation alive; losing the
        // pruning is a smaller loss than failing a merge over where a buffer goes.
        if (!dir.isDirectory && !dir.mkdirs()) return setting
        return setting.setTempDir(dir)
    }

    /**
     * Opens, runs [block], and always closes - translating every failure on the way out.
     */
    private inline fun <T> withDocument(input: PdfInput, block: (PDDocument) -> T): T {
        val doc = try {
            PDDocument.load(
                input.file,
                input.password ?: "",
                scratchMemory()
            )
        } catch (e: InvalidPasswordException) {
            throw PdfException.PasswordRequired(e)
        } catch (e: OutOfMemoryError) {
            throw PdfException.OutOfMemory("opening ${input.file.name}", e)
        } catch (e: Exception) {
            throw e.asPdfException("open")
        }

        return doc.use {
            try {
                block(it)
            } catch (e: PdfException) {
                throw e
            } catch (e: OutOfMemoryError) {
                throw PdfException.OutOfMemory(input.file.name, e)
            } catch (e: OpCancelled) {
                // Every progress callback in this class runs inside block, so a cancel
                // surfaces here. Rethrown as itself: the arm below would report it as a
                // document that could not be read, and redaction now does all of its work
                // inside one of these, so this is the only window a cancel has.
                throw e
            } catch (e: Exception) {
                throw e.asPdfException("process")
            }
        }
    }

    /**
     * Decodes an embedded image and scales it down to the level's ceiling.
     *
     * Returns null when there is nothing to gain - the image is already smaller than the
     * ceiling - so the caller leaves the original in place rather than re-encoding it and
     * losing quality for no size saving.
     */
    private fun shrink(image: PDImageXObject, level: CompressionLevel): Bitmap? {
        val longest = maxOf(image.width, image.height)
        if (longest <= 0) return null

        val decoded = try {
            image.image
        } catch (_: Exception) {
            return null
        } catch (_: OutOfMemoryError) {
            return null
        } ?: return null

        // Already small enough. Re-encoding would only degrade it.
        if (longest <= level.maxDimension) return null

        val scale = level.maxDimension.toFloat() / longest
        val width = (decoded.width * scale).toInt().coerceAtLeast(1)
        val height = (decoded.height * scale).toInt().coerceAtLeast(1)

        // The decoded bitmap belongs to PDImageXObject, which caches it and returns the same
        // instance next time. Recycling it here broke every later page that referenced the
        // same image. Only the scaled copy made below is ours to release, and the caller
        // does that.
        return try {
            val scaled = Bitmap.createScaledBitmap(decoded, width, height, true)
            if (scaled === decoded) null else scaled
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: IllegalStateException) {
            null
        }
    }

    private fun pageBoxFor(spec: PageSpec, imageWidth: Float, imageHeight: Float): PDRectangle =
        when (spec.size) {
            PageSpec.Size.FIT_IMAGE -> PDRectangle(imageWidth, imageHeight)
            else -> {
                val portrait = PDRectangle(spec.size.widthPoints, spec.size.heightPoints)
                if (spec.matchImageOrientation && imageWidth > imageHeight) {
                    PDRectangle(portrait.height, portrait.width)
                } else {
                    portrait
                }
            }
        }

    private fun PDPage.toPageSize(): PageSize {
        val box = mediaBox ?: PDRectangle.A4
        // A page rotated 90 or 270 is displayed with its sides swapped, and every caller here
        // wants the size as it will be seen rather than as it is stored.
        return if (normaliseRotation(rotation) % 180 == 90) {
            PageSize(box.height, box.width)
        } else {
            PageSize(box.width, box.height)
        }
    }

    private companion object {
        /**
         * Budget for an export render. Fixed rather than read from ActivityManager because
         * this class deliberately has no Context - it does no Android work beyond bitmaps.
         * 96 MB is below the heap of any device that can run this app.
         */
        const val RENDER_HEAP_BUDGET = 96L * 1024 * 1024

        /** PNG ignores this; JPEG uses it. Separate from the compression tiers on purpose. */
        const val JPEG_EXPORT_QUALITY = 92

        /** Characters of context either side of a search match. */
        const val SNIPPET_LEAD = 40
        const val SNIPPET_TRAIL = 120

        /** A malformed outline can cycle; this is what stops the walk from not returning. */
        const val MAX_OUTLINE_DEPTH = 8

        /** Half an inch, the margin page numbers sit in. */
        const val MARGIN_POINTS = 36f

        /** A millimetre, in points. */
        const val MM_POINTS = 72f / 25.4f

        /** Two millimetres of white kept around the ink by an automatic crop. */
        const val AUTO_PAD_POINTS = 2f * MM_POINTS

        /** Half an inch each way is the least a page can be cropped down to and still be one. */
        const val SMALLEST_PAGE_POINTS = 36f

        /** A tiled watermark stops here; past it, the copies are smaller than the gaps. */
        const val MAX_TILES = 240

        /** Redacted pages become images; this keeps them readable without doubling the file. */
        const val REDACT_JPEG_QUALITY = 0.82f

        /** Pages per sheet N-up knows how to lay out. */
        val GRIDS: Set<Int> get() = GRID_SHAPES.keys

        /** Smaller than this is unreadable; larger is a poster, not a note. */
        const val MIN_NOTE_POINTS = 4f
        const val MAX_NOTE_POINTS = 200f

        /** Leading, as a multiple of the type size. The usual typesetting ratio. */
        const val LINE_SPACING = 1.25f

        /** Helvetica's ascent and descent, which is how far a covering patch has to reach. */
        const val NOTE_ASCENT = 0.9f
        const val NOTE_DESCENT = 0.25f

        /** A hair either side of a covering patch, so it does not clip the type. */
        const val COVER_PADDING = 2f

        /** A highlighter is see-through or it is a redaction. */
        const val HIGHLIGHT_ALPHA = 0.32f

        /** Enough blank sheets for any real use; past it, somebody has mistyped. */
        const val MAX_BLANK_PAGES = 100

        /** The value a tick box carries when the UI has ticked it. */
        const val TICKED = "true"

        /** A receipt's page margin and the width of its label column, in points. */
        const val RECEIPT_MARGIN = 56f
        const val RECEIPT_LABEL_WIDTH = 150f
    }
}

/** A sheet cut into cells, with the pages scaled by [scale] to sit in them. */
private class NUpLayout(
    val sheetWidth: Float,
    val sheetHeight: Float,
    val columns: Int,
    val rows: Int,
    val cellWidth: Float,
    val cellHeight: Float,
    val margin: Float,
    val gap: Float,
    val scale: Float
)

/**
 * The sheet and grid that show the pages biggest: two upright pages side by side on a
 * sideways sheet, two wide slides one above the other on an upright one. Reading order is
 * across, then down.
 */
private fun nUpLayout(per: Int, paper: PageSpec.Size, pageWidth: Float, pageHeight: Float): NUpLayout {
    val margin = NUP_MARGIN_POINTS
    val gap = NUP_GAP_POINTS
    val paperWidth = if (paper.widthPoints > 0f) paper.widthPoints else PDRectangle.A4.width
    val paperHeight = if (paper.heightPoints > 0f) paper.heightPoints else PDRectangle.A4.height
    var best: NUpLayout? = null
    val grids = GRID_SHAPES[per] ?: GRID_SHAPES.getValue(2)
    for ((cols, rows) in grids) {
        for (landscape in listOf(true, false)) {
            val sheetW = if (landscape) maxOf(paperWidth, paperHeight) else minOf(paperWidth, paperHeight)
            val sheetH = if (landscape) minOf(paperWidth, paperHeight) else maxOf(paperWidth, paperHeight)
            val cellW = (sheetW - margin * 2f - gap * (cols - 1)) / cols
            val cellH = (sheetH - margin * 2f - gap * (rows - 1)) / rows
            val scale = minOf(cellW / pageWidth.coerceAtLeast(1f), cellH / pageHeight.coerceAtLeast(1f))
            val current = best
            if (current == null || scale > current.scale + 1e-6f) {
                best = NUpLayout(sheetW, sheetH, cols, rows, cellW, cellH, margin, gap, scale)
            }
        }
    }
    return best!!
}

/** Breathing room on an N-up sheet: outside the grid, and between its cells. */
private const val NUP_MARGIN_POINTS = 18f
private const val NUP_GAP_POINTS = 12f

/** The ways a sheet can be cut into equal cells: columns x rows, by pages per sheet. */
private val GRID_SHAPES: Map<Int, List<Pair<Int, Int>>> = mapOf(
    2 to listOf(2 to 1, 1 to 2),
    4 to listOf(2 to 2),
    6 to listOf(3 to 2, 2 to 3),
    8 to listOf(4 to 2, 2 to 4),
    9 to listOf(3 to 3),
    16 to listOf(4 to 4)
)

/**
 * Flattens a form's field tree down to the fields that actually hold a value.
 *
 * A PDF form is a tree: a government form typically groups its fields into sections which
 * group into pages, and only the leaves have values. Walking it rather than reading
 * `form.fields` is the difference between seeing forty fields and seeing three.
 *
 * The depth is capped for the same reason the outline walk is: a malformed file can point at
 * itself, and an uncapped recursion would not return.
 */
private fun terminalFields(fields: List<PDField>?): List<PDField> = buildList {
    fun walk(list: List<PDField>?, depth: Int) {
        if (list == null || depth > MAX_FIELD_DEPTH) return
        for (field in list) {
            if (field is PDNonTerminalField) walk(field.children, depth + 1) else add(field)
        }
    }
    walk(fields, 0)
}

/** Deep enough for any real form; past it, the tree is pointing at itself. */
private const val MAX_FIELD_DEPTH = 8

/**
 * A page as the reader sees it, which is not how the file stores it.
 *
 * Two things differ. The visible area is the crop box, whose origin is not necessarily zero;
 * and a page with a /Rotate entry is shown turned, so its width and height have swapped and
 * "along the top" is a different edge of the stored page. Every operation that places
 * something at a spot a person pointed at needs both corrections, so they are written here
 * once instead of in each of them.
 */
private class ShownPage(page: PDPage) {

    val box: PDRectangle = page.cropBox ?: page.mediaBox ?: PDRectangle.A4
    val rotation: Int = normaliseRotation(page.rotation)
    private val quarterTurned = rotation % 180 == 90

    /** What the reader sees, in points. Swapped from the stored box on a turned page. */
    val width: Float = if (quarterTurned) box.height else box.width
    val height: Float = if (quarterTurned) box.width else box.height

    /** How far the text has to be turned, in radians, to read upright on this page. */
    val radians: Double = Math.toRadians(rotation.toDouble())

    /**
     * Turns a point measured from the top-left of what the reader sees into page space.
     *
     * [across] runs left to right and [down] runs top to bottom, both in points, both in the
     * space the reader is looking at.
     */
    fun pointAt(across: Float, down: Float): Pair<Float, Float> = when (rotation) {
        90 -> (box.lowerLeftX + down) to (box.lowerLeftY + across)
        180 -> (box.upperRightX - across) to (box.lowerLeftY + down)
        270 -> (box.upperRightX - down) to (box.upperRightY - across)
        else -> (box.lowerLeftX + across) to (box.upperRightY - down)
    }

    /**
     * The stored rectangle that shows as the part of the page between these fractions of
     * what the reader sees: [left] and [right] across, [top] and [bottom] down, 0 to 1.
     */
    fun storedRect(left: Float, top: Float, right: Float, bottom: Float): PDRectangle {
        val (x1, y1) = pointAt(left * width, top * height)
        val (x2, y2) = pointAt(right * width, bottom * height)
        return PDRectangle(minOf(x1, x2), minOf(y1, y2), Math.abs(x2 - x1), Math.abs(y2 - y1))
    }

    /**
     * The transform that takes a point in the reader's space (origin at the bottom-left of
     * the visible page, y upwards, the page the right way up) into the stored page.
     *
     * Concatenated onto a content stream, everything drawn after it can be worked out as
     * if the page were unrotated and its visible corner were at zero, which is how every
     * placement here is worked out.
     */
    fun viewMatrix(): Matrix {
        val (originX, originY) = pointAt(0f, height)
        return Matrix.getRotateInstance(radians, originX, originY)
    }
}

/**
 * Where a form's content actually lands once the form's own matrix is applied.
 *
 * A form is drawn at Matrix(BBox) rather than at BBox. The importer sets that matrix to carry
 * a page whose box does not begin at the origin, so the rectangle to scale and centre is the
 * transformed one. Placing from the raw box instead slides the content by the box's own
 * origin, which is what took the left edge off a cropped page resized to a fixed sheet.
 *
 * The four corners are transformed rather than the two, because a rotated page's matrix turns
 * the box as well as moving it.
 */
private fun placedBounds(form: PDFormXObject, fallback: PDRectangle): PDRectangle {
    val box = form.bBox ?: return fallback
    val matrix = form.matrix ?: return box

    val a = matrix.getValue(0, 0)
    val b = matrix.getValue(0, 1)
    val c = matrix.getValue(1, 0)
    val d = matrix.getValue(1, 1)
    val e = matrix.getValue(2, 0)
    val f = matrix.getValue(2, 1)

    val corners = listOf(
        box.lowerLeftX to box.lowerLeftY,
        box.upperRightX to box.lowerLeftY,
        box.upperRightX to box.upperRightY,
        box.lowerLeftX to box.upperRightY
    )
    val xs = corners.map { (x, y) -> a * x + c * y + e }
    val ys = corners.map { (x, y) -> b * x + d * y + f }

    val left = xs.min()
    val bottom = ys.min()
    return PDRectangle(left, bottom, xs.max() - left, ys.max() - bottom)
}

/**
 * A text stripper that records where each match sits on the page.
 *
 * PDFBox hands writeString the run of text and the glyph position behind every character of
 * it, so a match found in the string can be mapped straight back to the rectangle those
 * glyphs occupy. That rectangle, normalised to the page, is what the viewer highlights.
 *
 * The positions are approximate at the edges - a ligature is one glyph for two characters, so
 * a match spanning one can be a character out. It is the difference between a box that is
 * exactly right and one that is a few points wide either way, which nobody reading notices.
 */
private class MatchFinder(
    private val pattern: Regex,
    private val accept: (String) -> Boolean,
    private val pageWidth: Float,
    private val pageHeight: Float
) : PDFTextStripper() {

    val areas = mutableListOf<PageArea>()

    override fun writeString(text: String, textPositions: List<TextPosition>) {
        for (match in pattern.findAll(text)) {
            if (!accept(match.value)) continue
            val start = match.range.first
            val end = (match.range.last + 1).coerceAtMost(textPositions.size)
            if (start < textPositions.size && end > start) {
                areaFor(textPositions.subList(start, end))?.let { areas += it }
            }
        }
        super.writeString(text, textPositions)
    }

    private fun areaFor(glyphs: List<TextPosition>): PageArea? = glyphArea(glyphs, pageWidth, pageHeight)
}

/**
 * Every word on a page with its box, in the order the stripper reads them. A word is a run
 * of glyphs with no space in it; punctuation stays attached, as it does on the page.
 */
private class WordFinder(
    private val pageWidth: Float,
    private val pageHeight: Float
) : PDFTextStripper() {

    val words = mutableListOf<Pair<String, PageArea>>()

    override fun writeString(text: String, textPositions: List<TextPosition>) {
        var start = -1
        val count = minOf(text.length, textPositions.size)
        for (i in 0..count) {
            val space = i == count || text[i].isWhitespace()
            if (space) {
                if (start >= 0) {
                    glyphArea(textPositions.subList(start, i), pageWidth, pageHeight)?.let { area ->
                        words += text.substring(start, i) to area
                    }
                    start = -1
                }
            } else if (start < 0) {
                start = i
            }
        }
        super.writeString(text, textPositions)
    }
}

/**
 * Every line on a page with its size, weight and box, cut into cells at its wide gaps.
 *
 * The stripper hands over text a run at a time and says where lines end; this keeps the
 * runs of the current line with their glyphs, and at the line's end works out the box, the
 * commonest size and whether the run of a table's columns shows in the gaps.
 */
private class LineFinder(
    private val pageWidth: Float,
    private val pageHeight: Float
) : PDFTextStripper() {

    val lines = mutableListOf<TextLine>()
    private val runs = mutableListOf<Pair<String, List<TextPosition>>>()

    override fun writeString(text: String, textPositions: List<TextPosition>) {
        if (text.isNotEmpty() && textPositions.isNotEmpty()) runs += text to textPositions
    }

    override fun writeWordSeparator() = Unit

    override fun writeLineSeparator() = endLine()

    override fun writeParagraphSeparator() = endLine()

    override fun endPage(page: PDPage) {
        endLine()
        super.endPage(page)
    }

    private fun endLine() {
        if (runs.isEmpty()) return
        val glyphs = runs.flatMap { it.second }
        val area = glyphArea(glyphs, pageWidth, pageHeight)
        if (area == null) { runs.clear(); return }
        val size = glyphs.groupingBy { Math.round(it.fontSizeInPt * 2f) / 2f }.eachCount().maxByOrNull { it.value }?.key ?: 11f
        val names = glyphs.mapNotNull { runCatching { it.font?.name }.getOrNull() }
        val bold = names.isNotEmpty() && names.count { it.contains("Bold", true) || it.contains("Black", true) || it.contains("Heavy", true) } * 2 >= names.size
        val italic = names.isNotEmpty() && names.count { it.contains("Italic", true) || it.contains("Oblique", true) } * 2 >= names.size

        // Cells: a gap wider than a couple of spaces splits the line, which is how a table's
        // columns show. Runs come in reading order, so only the gap to the one before counts.
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var lastEnd = Float.NaN
        for ((text, positions) in runs) {
            val first = positions.first()
            val start = first.xDirAdj
            val end = positions.last().let { it.xDirAdj + it.widthDirAdj }
            if (!lastEnd.isNaN()) {
                val gap = start - lastEnd
                val wide = gap > maxOf(first.fontSizeInPt * 1.6f, first.widthOfSpace * 3f)
                if (wide) {
                    if (current.isNotBlank()) cells += current.toString().trim()
                    current.clear()
                } else if (gap > first.widthOfSpace * 0.3f && current.isNotEmpty() && !current.endsWith(' ')) {
                    current.append(' ')
                }
            }
            current.append(text)
            lastEnd = end
        }
        if (current.isNotBlank()) cells += current.toString().trim()
        runs.clear()
        if (cells.isEmpty()) return
        lines += TextLine(
            cells = cells,
            sizePoints = size,
            bold = bold,
            italic = italic,
            left = area.left,
            top = area.top,
            width = area.width,
            height = area.height
        )
    }
}

/** The box round a run of glyphs, as fractions of the page, origin top-left. */
private fun glyphArea(glyphs: List<TextPosition>, pageWidth: Float, pageHeight: Float): PageArea? {
    if (glyphs.isEmpty() || pageWidth <= 0f || pageHeight <= 0f) return null

    val left = glyphs.minOf { it.xDirAdj }
    val right = glyphs.maxOf { it.xDirAdj + it.widthDirAdj }
    // yDirAdj is measured from the top of the page down to the baseline, which is the one
    // place in this file where the vertical axis already points the way the screen does.
    val baseline = glyphs.maxOf { it.yDirAdj }
    val height = glyphs.maxOf { it.heightDir }.coerceAtLeast(1f)

    return PageArea(
        left = (left / pageWidth).coerceIn(0f, 1f),
        top = ((baseline - height) / pageHeight).coerceIn(0f, 1f),
        width = ((right - left) / pageWidth).coerceIn(0f, 1f),
        height = (height / pageHeight).coerceIn(0f, 1f)
    )
}

/**
 * How wide a line of Helvetica is, in points.
 *
 * Only a covering patch needs this, and a patch that is a little wide is better than one that
 * is short, so a font that will not measure falls back to half the type size per character -
 * Helvetica's average is a shade under that.
 */
private fun widthOfHelvetica(line: String, size: Float): Float =
    runCatching { PDType1Font.HELVETICA.getStringWidth(line) / 1000f * size }
        .getOrDefault(line.length * size * 0.5f)

/**
 * Drops characters Helvetica's WinAnsi encoding cannot represent.
 *
 * The invisible OCR layer is written in a standard-14 font, which covers Latin text and
 * nothing else. showText throws on anything outside that, so the alternative to filtering is
 * a line that fails to place at all. Filtering loses the odd symbol; not filtering loses the
 * line. Embedding a Unicode font would fix it properly and costs megabytes per script, which
 * is a trade worth making when someone actually needs it.
 */
private fun String.toWinAnsiSafe(): String =
    filter { it == ' ' || (it.code in 32..126) || (it.code in 160..255) }.trim()

/** PDF stores rotation as a multiple of 90 and tolerates negatives; every caller wants 0-270. */
internal fun normaliseRotation(degrees: Int): Int = ((degrees % 360) + 360) % 360

private fun Exception.asPdfException(what: String): PdfException = when {
    this is InvalidPasswordException -> PdfException.PasswordRequired(this)
    this is IOException && message?.contains("password", ignoreCase = true) == true ->
        PdfException.PasswordRequired(this)
    this is IOException -> PdfException.Corrupt(message ?: "could not $what this document", this)
    else -> PdfException.Corrupt(message ?: "could not $what this document", this)
}

/**
 * Reading a document through the [PdfEngine] seam, backed by PDFBox.
 *
 * The viewer uses the platform renderer because it is faster and allocates less. This exists
 * for the one thing the platform renderer cannot do at all: open an encrypted document given
 * its password. Same interface, so the viewer picks it up without knowing anything changed.
 */
class PdfBoxEngine : PdfEngine {

    override val capabilities = EngineCapabilities(
        render = true,
        restructure = true,
        encryptedInput = true,
        write = true,
        textExtraction = true
    )

    override fun open(file: File, password: String?): PdfDocument {
        val doc = try {
            PDDocument.load(file, password ?: "", MemoryUsageSetting.setupTempFileOnly())
        } catch (e: InvalidPasswordException) {
            throw PdfException.PasswordRequired(e)
        } catch (e: Exception) {
            throw PdfException.Corrupt(e.message ?: "could not be opened", e)
        }
        return PdfBoxDocument(doc)
    }
}

private class PdfBoxDocument(private val doc: PDDocument) : PdfDocument {

    private val renderer = PDFRenderer(doc)

    override val pageCount: Int = doc.numberOfPages

    override fun pageSize(index: Int): PageSize {
        checkIndex(index)
        val box = doc.getPage(index).mediaBox ?: PDRectangle.A4
        val rotation = normaliseRotation(doc.getPage(index).rotation)
        return if (rotation % 180 == 90) PageSize(box.height, box.width) else PageSize(box.width, box.height)
    }

    override fun renderPage(index: Int, targetWidthPx: Int): Bitmap {
        checkIndex(index)
        val size = pageSize(index)
        val width = BitmapBudget.clampWidth(targetWidthPx, size.aspectRatio, VIEWER_HEAP_BUDGET)
        val scale = if (size.widthPoints <= 0f) 1f else width / size.widthPoints
        return try {
            renderer.renderImage(index, scale)
        } catch (e: OutOfMemoryError) {
            throw PdfException.OutOfMemory("page ${index + 1}", e)
        } catch (e: Exception) {
            throw PdfException.Corrupt("page ${index + 1} could not be rendered", e)
        }
    }

    private fun checkIndex(index: Int) {
        if (index !in 0 until pageCount) {
            throw PdfException.Io("page ${index + 1} does not exist in a $pageCount-page document")
        }
    }

    override fun close() {
        try {
            doc.close()
        } catch (e: Exception) {
            // PDDocument closes the file, the COS document and every font it owns before it
            // rethrows the first failure, so unlike PlatformPdfDocument there is no descriptor
            // left here to release by hand. What the interface still asks for is that the
            // refusal is not dropped, and by the time a document is closing the reader has
            // moved on and there is nobody to show it to - so it is logged.
            Log.w(TAG, "document would not close cleanly", e)
        }
    }

    private companion object {
        const val VIEWER_HEAP_BUDGET = 96L * 1024 * 1024
        const val TAG = "PdfBoxEngine"
    }
}
