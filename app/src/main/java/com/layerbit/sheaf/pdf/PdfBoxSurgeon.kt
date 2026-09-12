package com.layerbit.sheaf.pdf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
import com.tom_roush.pdfbox.util.Matrix
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
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
 * 1. Documents are opened with [MemoryUsageSetting.setupTempFileOnly]. PDFBox will happily
 *    read a whole document into the heap otherwise, and a 90 MB scan does not fit. Spilling to
 *    a temp file is slower and is the only version that survives the files people actually
 *    have.
 *
 * 2. Every open is inside `use`, and the output is written to a fresh file. Nothing here can
 *    touch an input, which is the same guarantee DocumentStore makes one layer up.
 *
 * Every failure is translated into [PdfException]. Callers never see a PDFBox type, which is
 * what keeps this swappable for QPDF later.
 */
class PdfBoxSurgeon : PdfSurgeon {

    override fun inspect(input: PdfInput): DocumentInfo = withDocument(input) { doc ->
        DocumentInfo(
            pageCount = doc.numberOfPages,
            pageSizes = (0 until doc.numberOfPages).map { doc.getPage(it).toPageSize() },
            isEncrypted = doc.isEncrypted,
            title = doc.documentInformation?.title,
            author = doc.documentInformation?.author
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
            merger.mergeDocuments(MemoryUsageSetting.setupTempFileOnly())
        } catch (e: PdfException) {
            throw e
        } catch (e: OutOfMemoryError) {
            throw PdfException.OutOfMemory("merging ${inputs.size} documents", e)
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

        PDDocument().use { target ->
            for (index in pages) {
                if (index !in 0 until source.numberOfPages) {
                    throw PdfException.Io("Page ${index + 1} does not exist in this document.")
                }
                val page = source.getPage(index)
                rotations[index]?.let { delta ->
                    page.rotation = normaliseRotation(page.rotation + delta)
                }
                // importPage copies the page's dictionary into the new document but shares the
                // underlying content streams with the source, which is why the source must stay
                // open until after the save below - it is, since this is inside withDocument.
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
            PDDocument().use { doc ->
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

    override fun setPassword(input: PdfInput, userPassword: String, output: File) =
        withDocument(input) { doc ->
            if (userPassword.isBlank()) throw PdfException.Io("The password cannot be empty.")

            // The owner password is set to the same value deliberately. An owner password that
            // the user does not know is a document they can open but never change again, and a
            // random one would be worse - there would be no way to recover it.
            val permissions = AccessPermission()
            val policy = StandardProtectionPolicy(userPassword, userPassword, permissions).apply {
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

        val stripper = PDFTextStripper()
        buildList {
            for (index in 0 until doc.numberOfPages) {
                stripper.startPage = index + 1
                stripper.endPage = index + 1
                val text = runCatching { stripper.getText(doc) }.getOrNull() ?: continue

                val matches = Regex(Regex.escape(needle), RegexOption.IGNORE_CASE).findAll(text).toList()
                if (matches.isEmpty()) continue

                val first = matches.first().range.first
                val from = (first - SNIPPET_LEAD).coerceAtLeast(0)
                val to = (first + SNIPPET_TRAIL).coerceAtMost(text.length)
                val snippet = text.substring(from, to)
                    .replace(Regex("\\s+"), " ")
                    .trim()

                add(SearchHit(index, snippet, matches.size))
            }
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
        val text = spec.text.toWinAnsiSafe()
        if (text.isBlank()) throw PdfException.Io("The watermark has no text in it.")

        val font = PDType1Font.HELVETICA_BOLD
        val total = doc.numberOfPages

        for (index in 0 until total) {
            val page = doc.getPage(index)
            val box = page.mediaBox ?: PDRectangle.A4

            PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true)
                .use { stream ->
                    // Transparency lives in a graphics state, not on the text operator, so a
                    // watermark needs one of these - setting a grey fill instead would go
                    // opaque over anything it crossed.
                    stream.setGraphicsStateParameters(
                        PDExtendedGraphicsState().apply {
                            nonStrokingAlphaConstant = spec.opacity.coerceIn(0.02f, 1f)
                        }
                    )
                    stream.setNonStrokingColor(0.35f, 0.35f, 0.35f)
                    stream.setFont(font, spec.fontSize)

                    val widthPoints = font.getStringWidth(text) / 1000f * spec.fontSize
                    val positions = if (spec.tiled) {
                        tilePositions(box.width, box.height, widthPoints, spec.fontSize)
                    } else {
                        listOf((box.width - widthPoints) / 2f to box.height / 2f)
                    }

                    for ((x, y) in positions) {
                        stream.beginText()
                        // A rotation has to go through a text matrix; newLineAtOffset can only
                        // translate, so an angled watermark cannot be done with it alone.
                        stream.setTextMatrix(Matrix.getRotateInstance(Math.toRadians(spec.degrees.toDouble()), x, y))
                        stream.showText(text)
                        stream.endText()
                    }
                }
            onProgress(index + 1, total)
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
            val shown = if (spec.padTo > 0) number.toString().padStart(spec.padTo, '0') else number.toString()
            val label = spec.format
                .replace("{n}", shown)
                .replace("{total}", numbered.toString())
                .toWinAnsiSafe()
            if (label.isBlank()) {
                onProgress(index + 1, total)
                continue
            }

            val page = doc.getPage(index)
            val box = page.mediaBox ?: PDRectangle.A4
            val width = font.getStringWidth(label) / 1000f * spec.fontSize
            val margin = MARGIN_POINTS

            val x = when (spec.position) {
                PageNumberSpec.Position.TOP_LEFT, PageNumberSpec.Position.BOTTOM_LEFT -> margin
                PageNumberSpec.Position.TOP_CENTRE, PageNumberSpec.Position.BOTTOM_CENTRE -> (box.width - width) / 2f
                else -> box.width - width - margin
            }
            val y = when (spec.position) {
                PageNumberSpec.Position.TOP_LEFT, PageNumberSpec.Position.TOP_CENTRE, PageNumberSpec.Position.TOP_RIGHT ->
                    box.height - margin - spec.fontSize
                else -> margin
            }

            PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true)
                .use { stream ->
                    stream.setNonStrokingColor(0f, 0f, 0f)
                    stream.beginText()
                    stream.setFont(font, spec.fontSize)
                    stream.newLineAtOffset(x, y)
                    stream.showText(label)
                    stream.endText()
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

        for (index in 0 until total) {
            val page = doc.getPage(index)
            val box = page.cropBox ?: page.mediaBox ?: PDRectangle.A4

            val left = box.lowerLeftX + box.width * spec.left.coerceIn(0f, MAX_TRIM)
            val bottom = box.lowerLeftY + box.height * spec.bottom.coerceIn(0f, MAX_TRIM)
            val right = box.upperRightX - box.width * spec.right.coerceIn(0f, MAX_TRIM)
            val top = box.upperRightY - box.height * spec.top.coerceIn(0f, MAX_TRIM)

            if (right > left && top > bottom) {
                page.cropBox = PDRectangle(left, bottom, right - left, top - bottom)
            }
            onProgress(index + 1, total)
        }

        if (spec.resizeTo == null || spec.resizeTo == PageSpec.Size.FIT_IMAGE) {
            doc.save(output)
            return@withDocument
        }

        // Resizing is a different operation from cropping and cannot be done by setting a box.
        // A page has to be redrawn at the new size, so each one is imported as a form and
        // placed, scaled to fit, on a fresh sheet - the same technique N-up uses.
        PDDocument().use { target ->
            // One utility per target document. It holds the import cache, so building a new
            // one per page would copy shared resources again for every page.
            val layers = LayerUtility(target)

            for (index in 0 until doc.numberOfPages) {
                val sourceBox = doc.getPage(index).cropBox ?: doc.getPage(index).mediaBox ?: PDRectangle.A4
                val portrait = PDRectangle(spec.resizeTo.widthPoints, spec.resizeTo.heightPoints)

                // A landscape page goes onto a landscape sheet. Forcing everything portrait
                // would shrink wide pages to a third of the paper for no reason.
                val sheetBox = if (sourceBox.width > sourceBox.height) {
                    PDRectangle(portrait.height, portrait.width)
                } else {
                    portrait
                }

                val sheet = PDPage(sheetBox)
                target.addPage(sheet)

                val form = layers.importPageAsForm(doc, index)
                val bounds = form.bBox ?: sourceBox
                val scale = minOf(sheetBox.width / bounds.width, sheetBox.height / bounds.height)
                val drawWidth = bounds.width * scale
                val drawHeight = bounds.height * scale

                PDPageContentStream(target, sheet, PDPageContentStream.AppendMode.APPEND, true, true)
                    .use { stream ->
                        stream.saveGraphicsState()
                        stream.transform(
                            Matrix.getTranslateInstance(
                                (sheetBox.width - drawWidth) / 2f,
                                (sheetBox.height - drawHeight) / 2f
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

        val flattened = withDocument(input) { source ->
            val renderer = PDFRenderer(source)
            val total = marked.size
            var done = 0

            val images = mutableMapOf<Int, Pair<Bitmap, PageSize>>()
            for ((index, rectangles) in marked) {
                if (index !in 0 until source.numberOfPages) continue
                val size = source.getPage(index).toPageSize()
                val requested = BitmapBudget.widthForDpi(size.widthPoints, dpi)
                val width = BitmapBudget.clampWidth(requested, size.aspectRatio, RENDER_HEAP_BUDGET)
                val scale = if (size.widthPoints <= 0f) 1f else width / size.widthPoints

                val bitmap = try {
                    renderer.renderImage(index, scale)
                } catch (e: OutOfMemoryError) {
                    throw PdfException.OutOfMemory("page ${index + 1}", e)
                }

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
                images[index] = bitmap to size
                done++
                onProgress(done, total)
            }
            images
        }

        try {
            withDocument(input) { doc ->
                for ((index, pair) in flattened) {
                    val (bitmap, size) = pair
                    val page = doc.getPage(index)

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
                }
                doc.save(output)
            }
        } finally {
            flattened.values.forEach { (bitmap, _) -> bitmap.recycle() }
        }
    }

    override fun stampImage(input: PdfInput, stamp: ImageStamp, output: File) =
        withDocument(input) { doc ->
            if (stamp.pageIndex !in 0 until doc.numberOfPages) {
                throw PdfException.Io("Page ${stamp.pageIndex + 1} does not exist in this document.")
            }
            val page = doc.getPage(stamp.pageIndex)
            val box = page.mediaBox ?: PDRectangle.A4

            val image = try {
                PDImageXObject.createFromFile(stamp.image.absolutePath, doc)
            } catch (e: Exception) {
                throw PdfException.Corrupt("the signature image could not be read", e)
            }

            val width = box.width * stamp.widthFraction.coerceIn(0.05f, 1f)
            val height = width * image.height / image.width.coerceAtLeast(1)
            val margin = box.width * stamp.marginFraction

            val x = when (stamp.anchor) {
                ImageStamp.Anchor.TOP_LEFT, ImageStamp.Anchor.MIDDLE_LEFT, ImageStamp.Anchor.BOTTOM_LEFT -> margin
                ImageStamp.Anchor.TOP_CENTRE, ImageStamp.Anchor.MIDDLE_CENTRE, ImageStamp.Anchor.BOTTOM_CENTRE ->
                    (box.width - width) / 2f
                else -> box.width - width - margin
            }
            val y = when (stamp.anchor) {
                ImageStamp.Anchor.TOP_LEFT, ImageStamp.Anchor.TOP_CENTRE, ImageStamp.Anchor.TOP_RIGHT ->
                    box.height - height - margin
                ImageStamp.Anchor.MIDDLE_LEFT, ImageStamp.Anchor.MIDDLE_CENTRE, ImageStamp.Anchor.MIDDLE_RIGHT ->
                    (box.height - height) / 2f
                else -> margin
            }

            // Drawn into the page content rather than added as an annotation, so it is part of
            // the document. An annotation can be moved or deleted by the next reader, which is
            // not what anyone means by signing something.
            PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true)
                .use { stream -> stream.drawImage(image, x, y, width, height) }

            doc.save(output)
        }

    // ---- P4: power tools ----

    override fun nUp(input: PdfInput, perSheet: Int, output: File, onProgress: (Int, Int) -> Unit) =
        withDocument(input) { source ->
            val columns = if (perSheet >= 4) 2 else 1
            val rows = if (perSheet >= 4) 2 else 2
            val slots = columns * rows

            PDDocument().use { target ->
                val layers = LayerUtility(target)
                val total = source.numberOfPages
                var index = 0

                while (index < total) {
                    // The sheet takes the first page's proportions, turned on its side for
                    // 2-up so two portrait pages sit side by side the way a booklet does.
                    val first = source.getPage(index).mediaBox ?: PDRectangle.A4
                    val sheetBox = if (slots == 2) {
                        PDRectangle(first.height, first.width)
                    } else {
                        PDRectangle(first.width, first.height)
                    }
                    val sheet = PDPage(sheetBox)
                    target.addPage(sheet)

                    PDPageContentStream(target, sheet, PDPageContentStream.AppendMode.APPEND, true, true)
                        .use { stream ->
                            for (slot in 0 until slots) {
                                val pageIndex = index + slot
                                if (pageIndex >= total) break

                                val form = layers.importPageAsForm(source, pageIndex)
                                val cellWidth = sheetBox.width / columns
                                val cellHeight = sheetBox.height / rows
                                val column = slot % columns
                                val row = slot / columns

                                val bounds = form.bBox ?: first
                                val scale = minOf(
                                    (cellWidth - CELL_GAP * 2) / bounds.width,
                                    (cellHeight - CELL_GAP * 2) / bounds.height
                                )
                                val drawWidth = bounds.width * scale
                                val drawHeight = bounds.height * scale
                                val x = column * cellWidth + (cellWidth - drawWidth) / 2f
                                // Rows run down the sheet, but PDF's origin is at the bottom,
                                // so row zero is the top and the index is inverted here.
                                val y = (rows - 1 - row) * cellHeight + (cellHeight - drawHeight) / 2f

                                stream.saveGraphicsState()
                                stream.transform(Matrix.getTranslateInstance(x, y))
                                stream.transform(Matrix.getScaleInstance(scale, scale))
                                stream.drawForm(form)
                                stream.restoreGraphicsState()
                            }
                        }

                    index += slots
                    onProgress(minOf(index, total), total)
                }
                target.save(output)
            }
        }

    override fun planSizeSplit(
        input: PdfInput,
        maxBytes: Long,
        onProgress: (Int, Int) -> Unit
    ): List<List<Int>> = withDocument(input) { doc ->
        val total = doc.numberOfPages
        if (total == 0) return@withDocument emptyList()

        // Estimated from the whole file rather than measured per page. Measuring means writing
        // every candidate to disk, which on a 300-page scan is minutes of work for a number
        // that is only ever a guide - shared objects mean the parts never add up exactly.
        val bytesPerPage = (input.file.length().toDouble() / total).coerceAtLeast(1.0)
        val perPart = kotlin.math.floor(maxBytes / bytesPerPage).toInt().coerceAtLeast(1)

        val groups = (0 until total).chunked(perPart)
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

        for (index in 0 until total) {
            val resources = doc.getPage(index).resources
            if (resources != null) {
                for (name in resources.xObjectNames.toList()) {
                    val xObject = runCatching { resources.getXObject(name) }.getOrNull()
                    if (xObject !is PDImageXObject) continue

                    // The embedded original, at whatever resolution it was stored, not a
                    // re-render of the page. That is the difference between this and
                    // PDF to images, and it is the whole reason both exist.
                    val bitmap = runCatching { xObject.image }.getOrNull() ?: continue
                    found++
                    val file = File(outputDir, "$baseName-${found.toString().padStart(3, '0')}.png")
                    try {
                        FileOutputStream(file).use { out ->
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                        }
                        written += file
                    } catch (e: IOException) {
                        file.delete()
                    } finally {
                        bitmap.recycle()
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

    /** Evenly spaced anchor points for a tiled watermark. */
    private fun tilePositions(
        pageWidth: Float,
        pageHeight: Float,
        textWidth: Float,
        fontSize: Float
    ): List<Pair<Float, Float>> {
        val stepX = (textWidth + fontSize * 2f).coerceAtLeast(fontSize * 4f)
        val stepY = fontSize * 4f
        val positions = mutableListOf<Pair<Float, Float>>()
        var y = -pageHeight * 0.2f
        while (y < pageHeight * 1.2f) {
            var x = -pageWidth * 0.2f
            while (x < pageWidth * 1.2f) {
                positions += x to y
                x += stepX
            }
            y += stepY
        }
        return positions
    }

    // ---- internals ----

    /**
     * Opens, runs [block], and always closes - translating every failure on the way out.
     *
     * setupTempFileOnly is not optional. PDFBox's default buffers the whole document in the
     * heap, and the documents that matter here are exactly the ones too big for that.
     */
    private inline fun <T> withDocument(input: PdfInput, block: (PDDocument) -> T): T {
        val doc = try {
            PDDocument.load(
                input.file,
                input.password ?: "",
                MemoryUsageSetting.setupTempFileOnly()
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

        if (longest <= level.maxDimension) {
            // Already small enough. Re-encoding would only degrade it.
            decoded.recycle()
            return null
        }

        val scale = level.maxDimension.toFloat() / longest
        val width = (decoded.width * scale).toInt().coerceAtLeast(1)
        val height = (decoded.height * scale).toInt().coerceAtLeast(1)

        return try {
            Bitmap.createScaledBitmap(decoded, width, height, true).also {
                if (it !== decoded) decoded.recycle()
            }
        } catch (_: OutOfMemoryError) {
            decoded.recycle()
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

        /** Cropping past this leaves almost nothing of the page. */
        const val MAX_TRIM = 0.45f

        /** Redacted pages become images; this keeps them readable without doubling the file. */
        const val REDACT_JPEG_QUALITY = 0.82f

        /** Breathing room around each page on an N-up sheet. */
        const val CELL_GAP = 8f
    }
}

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
        runCatching { doc.close() }
    }

    private companion object {
        const val VIEWER_HEAP_BUDGET = 96L * 1024 * 1024
    }
}
