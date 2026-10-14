package com.layerbit.sheaf.pdf

import android.graphics.Bitmap
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentNameDictionary
import com.tom_roush.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDNameTreeNode
import com.tom_roush.pdfbox.pdmodel.common.PDPageLabelRange
import com.tom_roush.pdfbox.pdmodel.common.PDPageLabels
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification
import com.tom_roush.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationFileAttachment
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitWidthDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import java.io.File
import java.util.Calendar

/**
 * The structural operations behind the Foolscap set, as extensions on an open document.
 *
 * Kept in their own file rather than inside [PdfBoxSurgeon], which is already the longest
 * file in the app, and because each of these is a self-contained piece of PDF knowledge -
 * where page labels live, how an attached file is reachable, what an outline node is - that
 * reads better beside its own comment than in the middle of a thousand lines of rendering.
 * [PdfBoxSurgeon] opens and saves; these change what is between.
 */

// ---- page labels ----

internal fun PDDocument.applyPageLabels(spec: PageLabelSpec?) {
    if (spec == null) {
        documentCatalog.cosObject.removeItem(COSName.PAGE_LABELS)
        return
    }
    val labels = PDPageLabels(this)
    val front = spec.frontPages.coerceIn(0, numberOfPages)
    if (front > 0) {
        labels.setLabelItem(0, PDPageLabelRange().apply { style = spec.frontStyle.pdfStyle })
    }
    if (front < numberOfPages) {
        labels.setLabelItem(
            front,
            PDPageLabelRange().apply {
                style = spec.style.pdfStyle
                if (spec.prefix.isNotBlank()) prefix = spec.prefix
                start = spec.startAt.coerceAtLeast(1)
            }
        )
    }
    documentCatalog.pageLabels = labels
}

private val PageLabelSpec.Style.pdfStyle: String
    get() = when (this) {
        PageLabelSpec.Style.DECIMAL -> PDPageLabelRange.STYLE_DECIMAL
        PageLabelSpec.Style.ROMAN_UPPER -> PDPageLabelRange.STYLE_ROMAN_UPPER
        PageLabelSpec.Style.ROMAN_LOWER -> PDPageLabelRange.STYLE_ROMAN_LOWER
        PageLabelSpec.Style.ALPHA_UPPER -> PDPageLabelRange.STYLE_LETTERS_UPPER
        PageLabelSpec.Style.ALPHA_LOWER -> PDPageLabelRange.STYLE_LETTERS_LOWER
    }

/** What page `n` (counting from 1) is called in a style: 4 is "iv", "IV", "D", "d" or "4". */
fun PageLabelSpec.Style.labelFor(n: Int): String {
    val k = n.coerceAtLeast(1)
    return when (this) {
        PageLabelSpec.Style.DECIMAL -> k.toString()
        PageLabelSpec.Style.ROMAN_LOWER -> roman(k)
        PageLabelSpec.Style.ROMAN_UPPER -> roman(k).uppercase()
        PageLabelSpec.Style.ALPHA_LOWER -> letters(k).lowercase()
        PageLabelSpec.Style.ALPHA_UPPER -> letters(k)
    }
}

private val ROMAN = listOf(
    1000 to "m", 900 to "cm", 500 to "d", 400 to "cd", 100 to "c", 90 to "xc",
    50 to "l", 40 to "xl", 10 to "x", 9 to "ix", 5 to "v", 4 to "iv", 1 to "i"
)

private fun roman(value: Int): String {
    var rest = value
    return buildString {
        for ((n, s) in ROMAN) {
            while (rest >= n) {
                append(s)
                rest -= n
            }
        }
    }
}

/** A to Z, then AA to ZZ, as PDF readers do. */
private fun letters(k: Int): String {
    val letter = ('A' + (k - 1) % 26).toString()
    return letter.repeat((k - 1) / 26 + 1)
}

// ---- background ----

/**
 * Paints a colour under everything already on the page.
 *
 * PREPEND puts the new stream before the existing ones. It is wrapped in its own q/Q rather
 * than asking PDFBox to reset the context: with PREPEND, that option saves the state before
 * the new stream and restores it at the stream's START, which leaves the fill colour set here
 * leaking into the page's own drawing - a page that never sets a colour would then be drawn
 * in the background's.
 */
internal fun PDDocument.paintBackground(page: PDPage, colour: Int) {
    val box = page.mediaBox ?: PDRectangle.A4
    PDPageContentStream(this, page, PDPageContentStream.AppendMode.PREPEND, true, false).use { stream ->
        stream.saveGraphicsState()
        stream.setNonStrokingColor(colour.red(), colour.green(), colour.blue())
        stream.addRect(box.lowerLeftX, box.lowerLeftY, box.width, box.height)
        stream.fill()
        stream.restoreGraphicsState()
    }
}

internal fun Int.red(): Float = ((this shr 16) and 0xFF) / 255f
internal fun Int.green(): Float = ((this shr 8) and 0xFF) / 255f
internal fun Int.blue(): Float = (this and 0xFF) / 255f

// ---- attachments ----

/**
 * Every file the document carries, flattened out of the name tree.
 *
 * A name tree can be one node with names, or a node with kids that each have names, to any
 * depth; a document made by a program that attaches hundreds of files has the kids. Walked
 * rather than read from the root, which a smaller implementation would get wrong on exactly
 * the files with the most attachments.
 */
internal fun PDDocument.embeddedFiles(): LinkedHashMap<String, PDComplexFileSpecification> {
    val out = LinkedHashMap<String, PDComplexFileSpecification>()
    val root = documentCatalog.names?.embeddedFiles ?: return out
    fun walk(node: PDNameTreeNode<PDComplexFileSpecification>) {
        runCatching { node.names }.getOrNull()?.forEach { (name, spec) -> out[name] = spec }
        runCatching { node.kids }.getOrNull()?.forEach { kid -> walk(kid) }
    }
    walk(root)
    return out
}

/** Files attached as notes on a page, which a reader lists beside the document's own. */
internal fun PDDocument.attachmentNotes(): List<Pair<String, PDComplexFileSpecification>> = buildList {
    for (page in pages) {
        val annotations = runCatching { page.annotations }.getOrNull() ?: continue
        for (annotation in annotations) {
            if (annotation !is PDAnnotationFileAttachment) continue
            val spec = runCatching { annotation.file }.getOrNull() as? PDComplexFileSpecification ?: continue
            val name = spec.filename ?: spec.file ?: "attachment"
            add(name to spec)
        }
    }
}

internal fun PDComplexFileSpecification.bytesOrNull(): ByteArray? =
    runCatching { (embeddedFileUnicode ?: embeddedFile)?.toByteArray() }.getOrNull()

internal fun PDDocument.attach(files: List<Pair<String, File>>) {
    val catalog = documentCatalog
    val all = embeddedFiles()
    val taken = all.keys.mapTo(mutableSetOf()) { it.lowercase() }

    for ((wanted, file) in files) {
        // Two files of one name would be one entry in a reader's list, and the other could
        // not be found: the second becomes "name (2).ext".
        var name = wanted
        var n = 2
        while (name.lowercase() in taken) {
            val dot = wanted.lastIndexOf('.')
            name = if (dot > 0) "${wanted.substring(0, dot)} ($n)${wanted.substring(dot)}" else "$wanted ($n)"
            n += 1
        }
        taken += name.lowercase()

        val embedded = file.inputStream().use { input ->
            PDEmbeddedFile(this, input, COSName.FLATE_DECODE).apply {
                size = file.length().toInt()
                subtype = mimeTypeFor(name)
                modDate = Calendar.getInstance()
            }
        }
        all[name] = PDComplexFileSpecification().apply {
            this.file = name
            fileUnicode = name
            embeddedFile = embedded
            embeddedFileUnicode = embedded
        }
    }

    val tree = PDEmbeddedFilesNameTreeNode().apply { names = all }
    val names = catalog.names ?: PDDocumentNameDictionary(catalog)
    names.embeddedFiles = tree
    catalog.names = names
}

/** What a reader is told the file is, from its extension. Only a hint; the bytes are untouched. */
private fun mimeTypeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "pdf" -> "application/pdf"
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "txt" -> "text/plain"
    "csv" -> "text/csv"
    "json" -> "application/json"
    "xml" -> "application/xml"
    "zip" -> "application/zip"
    "doc" -> "application/msword"
    "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    "xls" -> "application/vnd.ms-excel"
    "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    "ppt" -> "application/vnd.ms-powerpoint"
    "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    else -> "application/octet-stream"
}

// ---- outline ----

/**
 * Replaces the document's bookmarks.
 *
 * Each entry opens its page fitted to the width, which is what a reader does for a chapter
 * heading. Nesting follows the depth: an entry deeper than the one before it goes inside
 * it, and an entry shallower closes everything deeper than itself. A depth that jumps by
 * more than one is treated as one deeper, since there is nothing in between to nest under.
 */
internal fun PDDocument.replaceOutline(entries: List<OutlineEntry>) {
    if (entries.isEmpty()) {
        documentCatalog.cosObject.removeItem(COSName.OUTLINES)
        return
    }
    val outline = PDDocumentOutline()
    val open = ArrayDeque<Pair<Int, PDOutlineItem>>()
    for (entry in entries) {
        val index = entry.pageIndex.coerceIn(0, (numberOfPages - 1).coerceAtLeast(0))
        val item = PDOutlineItem().apply {
            title = entry.title
            destination = PDPageFitWidthDestination().apply { page = getPage(index) }
        }
        val depth = entry.depth.coerceAtLeast(0).coerceAtMost((open.lastOrNull()?.first ?: -1) + 1)
        while (open.isNotEmpty() && open.last().first >= depth) open.removeLast()
        if (open.isEmpty()) outline.addLast(item) else open.last().second.addLast(item)
        open.addLast(depth to item)
    }
    documentCatalog.documentOutline = outline
    outline.openNode()
}

// ---- cleaning ----

/**
 * Takes out what a file gives away, and counts what it found.
 *
 * The details go by deleting the Info dictionary from the trailer rather than blanking its
 * fields: an unlinked dictionary is not written, while a blanked one still says that a
 * title was there. The XMP packet is the hidden second copy of the same details, which is
 * why stripping the Info alone has never been enough.
 */
internal fun PDDocument.cleanUp(spec: CleanSpec): CleanReport {
    val catalog = documentCatalog
    val root = catalog.cosObject
    var details = 0
    var xmp = 0
    var scripts = 0
    var attachments = 0
    var comments = 0
    var thumbnails = 0
    var privateData = 0

    if (spec.metadata) {
        val info = documentInformation
        details = listOf(info.title, info.author, info.subject, info.keywords, info.creator, info.producer)
            .count { !it.isNullOrBlank() }
        if (info.creationDate != null || info.modificationDate != null) details += 1
        document.trailer.removeItem(COSName.INFO)

        if (root.containsKey(COSName.METADATA)) {
            root.removeItem(COSName.METADATA)
            xmp += 1
        }
        if (root.containsKey(COSName.PIECE_INFO)) {
            root.removeItem(COSName.PIECE_INFO)
            privateData += 1
        }
        for (page in pages) {
            val cos = page.cosObject
            if (cos.containsKey(COSName.METADATA)) {
                cos.removeItem(COSName.METADATA)
                xmp += 1
            }
            if (cos.containsKey(THUMB)) {
                cos.removeItem(THUMB)
                thumbnails += 1
            }
            if (cos.containsKey(COSName.PIECE_INFO)) {
                cos.removeItem(COSName.PIECE_INFO)
                privateData += 1
            }
        }
    }

    if (spec.scripts) {
        if (root.containsKey(COSName.OPEN_ACTION)) {
            root.removeItem(COSName.OPEN_ACTION)
            scripts += 1
        }
        if (root.containsKey(COSName.AA)) {
            root.removeItem(COSName.AA)
            scripts += 1
        }
        val names = root.getDictionaryObject(COSName.NAMES) as? COSDictionary
        if (names != null && names.containsKey(COSName.JAVA_SCRIPT)) {
            names.removeItem(COSName.JAVA_SCRIPT)
            scripts += 1
        }
        for (page in pages) {
            if (page.cosObject.containsKey(COSName.AA)) {
                page.cosObject.removeItem(COSName.AA)
                scripts += 1
            }
            val annotations = runCatching { page.annotations }.getOrNull() ?: continue
            for (annotation in annotations) {
                val cos = annotation.cosObject
                if (cos.containsKey(COSName.AA)) {
                    cos.removeItem(COSName.AA)
                    scripts += 1
                }
                val action = cos.getDictionaryObject(COSName.A) as? COSDictionary
                if (action != null && action.getNameAsString(COSName.S) == "JavaScript") {
                    cos.removeItem(COSName.A)
                    scripts += 1
                }
            }
        }
    }

    if (spec.attachments) {
        attachments += embeddedFiles().size
        val names = root.getDictionaryObject(COSName.NAMES) as? COSDictionary
        names?.removeItem(COSName.EMBEDDED_FILES)
        for (page in pages) {
            val annotations = runCatching { page.annotations }.getOrNull() ?: continue
            val kept = annotations.filterNot { it is PDAnnotationFileAttachment }
            if (kept.size != annotations.size) {
                attachments += annotations.size - kept.size
                page.annotations = kept
            }
        }
    }

    if (spec.comments) {
        for (page in pages) {
            val annotations = runCatching { page.annotations }.getOrNull() ?: continue
            // Links and form fields are not comments: a reader does not list them as such,
            // and taking them out would break what the document does.
            val kept = annotations.filter { it.subtype == "Link" || it.subtype == "Widget" }
            if (kept.size != annotations.size) {
                comments += annotations.size - kept.size
                page.annotations = kept
            }
        }
    }

    return CleanReport(
        details = details,
        xmp = xmp,
        scripts = scripts,
        attachments = attachments,
        comments = comments,
        thumbnails = thumbnails,
        privateData = privateData
    )
}

private val THUMB: COSName = COSName.getPDFName("Thumb")

// ---- the ink on a rendered page ----

/**
 * Where the ink is on a rendered page, as fractions from the top-left, or null for a blank
 * page. Near-white or transparent is paper; anything else is ink.
 */
internal fun inkBounds(bitmap: Bitmap): FloatArray? {
    val width = bitmap.width
    val height = bitmap.height
    if (width <= 0 || height <= 0) return null
    val row = IntArray(width)
    var x0 = width
    var y0 = height
    var x1 = -1
    var y1 = -1
    for (y in 0 until height) {
        bitmap.getPixels(row, 0, width, 0, y, width, 1)
        for (x in 0 until width) {
            val pixel = row[x]
            val alpha = (pixel ushr 24) and 0xFF
            if (alpha < 16) continue
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            if (r > 242 && g > 242 && b > 242) continue
            if (x < x0) x0 = x
            if (x > x1) x1 = x
            if (y < y0) y0 = y
            if (y > y1) y1 = y
        }
    }
    if (x1 < 0) return null
    return floatArrayOf(
        x0.toFloat() / width,
        y0.toFloat() / height,
        (x1 + 1).toFloat() / width,
        (y1 + 1).toFloat() / height
    )
}
