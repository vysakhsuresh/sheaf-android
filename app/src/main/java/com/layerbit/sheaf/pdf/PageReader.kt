package com.layerbit.sheaf.pdf

import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import android.util.Log
import java.io.Closeable

/**
 * What the viewer needs to know about a page beyond its pixels: where each word sits, and
 * where the links are.
 *
 * Kept open for as long as the document is on screen, unlike every [PdfSurgeon] call, which
 * loads and closes the file each time. Those calls run once per job; this one answers a
 * question every time a page scrolls into view, and parsing the cross-reference table of a
 * long document for each of them would make the links arrive a second after the page.
 *
 * Asked one page at a time, never for the whole document. A long-press on page 212 needs the
 * words of page 212, and reading all of them first would be the wait the reader notices.
 *
 * Not thread-safe, like every PDFBox document: the caller serialises access.
 */
interface PageReader : Closeable {

    /** The words on a page in reading order, or empty for a page with no text layer. */
    fun words(pageIndex: Int): List<PageWord>

    /** The links on a page that lead somewhere Sheaf can take the reader. */
    fun links(pageIndex: Int): List<PageLink>

    companion object {
        /**
         * Opens [input] for reading words and links.
         *
         * @throws PdfException.PasswordRequired when the document is locked and [input] does
         *   not carry the right password; anything else unreadable is [PdfException.Corrupt].
         */
        @Throws(PdfException::class)
        fun open(input: PdfInput): PageReader {
            val doc = try {
                PDDocument.load(input.file, input.password ?: "", MemoryUsageSetting.setupTempFileOnly())
            } catch (e: InvalidPasswordException) {
                throw PdfException.PasswordRequired(e)
            } catch (e: OutOfMemoryError) {
                throw PdfException.OutOfMemory("opening ${input.file.name}", e)
            } catch (e: Exception) {
                throw PdfException.Corrupt(e.message ?: "could not be opened", e)
            }
            return PdfBoxPageReader(doc)
        }
    }
}

/** A word and its box, as fractions of the page the reader sees, origin top-left. */
data class PageWord(val text: String, val area: PageArea)

/** A tappable area of a page and where it leads. */
data class PageLink(val area: PageArea, val target: LinkTarget)

sealed interface LinkTarget {
    /** Another page of the same document, zero-based. */
    data class Page(val index: Int) : LinkTarget

    /** Somewhere outside the document, handed to whichever app the phone opens it with. */
    data class Web(val uri: String) : LinkTarget
}

/**
 * Turning a link's rectangle in PDF space into a fraction of the page as it is displayed.
 *
 * Plain arithmetic with no PDFBox types, so the unit tests can hold it to the corners. PDF
 * measures up from the bottom-left of the crop box; the viewer measures down from the top-left
 * of the page after its /Rotate has been applied, so the conversion is a flip and then one of
 * four quarter turns.
 */
object LinkGeometry {

    /**
     * @param rect the annotation's /Rect as left, bottom, right, top in user space.
     * @param box the crop box as left, bottom, right, top.
     * @param rotation the page's /Rotate, any multiple of 90.
     */
    fun normalise(rect: FloatArray, box: FloatArray, rotation: Int): PageArea? {
        val boxWidth = box[2] - box[0]
        val boxHeight = box[3] - box[1]
        if (boxWidth <= 0f || boxHeight <= 0f) return null

        // Unrotated, origin top-left. Rects are allowed to arrive with their corners either
        // way round, so the edges are sorted rather than trusted.
        val u0 = ((minOf(rect[0], rect[2]) - box[0]) / boxWidth).coerceIn(0f, 1f)
        val u1 = ((maxOf(rect[0], rect[2]) - box[0]) / boxWidth).coerceIn(0f, 1f)
        val v0 = ((box[3] - maxOf(rect[1], rect[3])) / boxHeight).coerceIn(0f, 1f)
        val v1 = ((box[3] - minOf(rect[1], rect[3])) / boxHeight).coerceIn(0f, 1f)
        if (u1 - u0 <= 0f || v1 - v0 <= 0f) return null

        // /Rotate turns the page clockwise for display. A point (u, v) on the unturned page
        // lands at (1 - v, u) after a quarter turn, (1 - u, 1 - v) after a half and (v, 1 - u)
        // after three quarters; a rectangle takes the same turn, corners re-sorted.
        return when (((rotation % 360) + 360) % 360) {
            90 -> PageArea(1f - v1, u0, v1 - v0, u1 - u0)
            180 -> PageArea(1f - u1, 1f - v1, u1 - u0, v1 - v0)
            270 -> PageArea(v0, 1f - u1, v1 - v0, u1 - u0)
            else -> PageArea(u0, v0, u1 - u0, v1 - v0)
        }
    }

    /**
     * Only links that open in a browser, a mail app or the dialler are passed on.
     *
     * A PDF can carry any URI at all - file:, intent:, javascript:, content: - and handing one
     * of those to the system on a tap is a way for a document to make the phone do something
     * the reader did not ask for. The four here are what a document link almost always is.
     */
    fun isSafeWebUri(uri: String): Boolean {
        val scheme = uri.substringBefore(':', "").trim().lowercase()
        return scheme in SAFE_SCHEMES
    }

    private val SAFE_SCHEMES = setOf("http", "https", "mailto", "tel")
}

private class PdfBoxPageReader(private val doc: PDDocument) : PageReader {

    override fun words(pageIndex: Int): List<PageWord> {
        if (pageIndex !in 0 until doc.numberOfPages) return emptyList()
        return try {
            val page = doc.getPage(pageIndex)
            val box = page.cropBox ?: page.mediaBox ?: PDRectangle.A4
            val quarterTurned = normaliseRotation(page.rotation) % 180 == 90
            // The same stripper and the same width and height swap as search and Compare, so a
            // word the reader selects sits exactly where a search for it would draw its box.
            val finder = if (quarterTurned) WordFinder(box.height, box.width) else WordFinder(box.width, box.height)
            finder.startPage = pageIndex + 1
            finder.endPage = pageIndex + 1
            finder.getText(doc)
            finder.words.map { (text, area) -> PageWord(text, area) }
        } catch (e: Exception) {
            Log.w(TAG, "words on page ${pageIndex + 1} could not be read", e)
            emptyList()
        } catch (e: OutOfMemoryError) {
            emptyList()
        }
    }

    override fun links(pageIndex: Int): List<PageLink> {
        if (pageIndex !in 0 until doc.numberOfPages) return emptyList()
        return try {
            val page = doc.getPage(pageIndex)
            val box = page.cropBox ?: page.mediaBox ?: PDRectangle.A4
            val boxEdges = floatArrayOf(box.lowerLeftX, box.lowerLeftY, box.upperRightX, box.upperRightY)
            val rotation = normaliseRotation(page.rotation)
            val annotations = runCatching { page.annotations }.getOrNull().orEmpty()
            annotations.mapNotNull { annotation ->
                if (annotation !is PDAnnotationLink) return@mapNotNull null
                val rect = annotation.rectangle ?: return@mapNotNull null
                val area = LinkGeometry.normalise(
                    floatArrayOf(rect.lowerLeftX, rect.lowerLeftY, rect.upperRightX, rect.upperRightY),
                    boxEdges,
                    rotation
                ) ?: return@mapNotNull null
                targetOf(annotation)?.let { PageLink(area, it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "links on page ${pageIndex + 1} could not be read", e)
            emptyList()
        }
    }

    /**
     * Where a link goes. A link may carry its destination directly (/Dest) or through an
     * action (/A); the action may be a GoTo with a destination or a URI. Anything else -
     * launching a file, running a script, going to another document - is ignored on purpose.
     */
    private fun targetOf(link: PDAnnotationLink): LinkTarget? {
        val action = runCatching { link.action }.getOrNull()
        when (action) {
            is PDActionURI -> {
                val uri = action.uri?.trim().orEmpty()
                return if (uri.isNotEmpty() && LinkGeometry.isSafeWebUri(uri)) LinkTarget.Web(uri) else null
            }
            is PDActionGoTo -> return pageOf(runCatching { action.destination }.getOrNull())
        }
        return pageOf(runCatching { link.destination }.getOrNull())
    }

    private fun pageOf(destination: PDDestination?): LinkTarget? {
        val pageDestination = when (destination) {
            is PDPageDestination -> destination
            // A named destination is looked up in the catalogue, which is where most documents
            // made by a word processor keep their cross-references.
            is PDNamedDestination -> runCatching { doc.documentCatalog.findNamedDestinationPage(destination) }.getOrNull()
            else -> null
        } ?: return null
        val index = runCatching { pageDestination.retrievePageNumber() }.getOrDefault(-1)
        return if (index in 0 until doc.numberOfPages) LinkTarget.Page(index) else null
    }

    override fun close() {
        try {
            doc.close()
        } catch (e: Exception) {
            Log.w(TAG, "page reader would not close cleanly", e)
        }
    }

    private companion object {
        const val TAG = "PageReader"
    }
}
