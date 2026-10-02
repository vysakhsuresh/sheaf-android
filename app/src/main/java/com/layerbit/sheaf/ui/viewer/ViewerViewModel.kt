package com.layerbit.sheaf.ui.viewer

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.layerbit.sheaf.SheafApplication
import com.layerbit.sheaf.data.db.BookmarkEntity
import com.layerbit.sheaf.files.SheafFile
import com.layerbit.sheaf.pdf.PageArea
import com.layerbit.sheaf.pdf.PageSize
import com.layerbit.sheaf.pdf.PdfDocument
import com.layerbit.sheaf.pdf.OutlineEntry
import com.layerbit.sheaf.pdf.PdfException
import com.layerbit.sheaf.pdf.PdfInput
import com.layerbit.sheaf.pdf.SearchHit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Holds one open document for the viewer.
 *
 * The whole design here is about not holding pages. A five-hundred-page document rendered
 * eagerly is gigabytes of bitmap; rendered lazily with a bounded cache it is a few megabytes
 * no matter how long the document is.
 *
 * Page count and page sizes are read once and kept. Those are a few bytes each, and the list
 * needs them to lay out before anything is rendered - which is what lets the scrollbar be
 * honest on a document the reader has only seen the first screen of.
 */
class ViewerViewModel(app: Application) : AndroidViewModel(app) {

    private val sheaf = app as SheafApplication

    private val _state = MutableStateFlow<ViewerState>(ViewerState.Loading)
    val state: StateFlow<ViewerState> = _state.asStateFlow()

    private var document: PdfDocument? = null
    private var source: SheafFile? = null

    /**
     * The document's Uri, which is its identity for everything stored about it.
     *
     * Null for a result handed over by a tool: it has no Uri of its own yet, so there is
     * nothing to key a reading position or a bookmark against until the user saves it.
     */
    private var sourceKey: String? = null

    /**
     * PdfRenderer permits one open page at a time per document and is not thread-safe, so
     * every render is serialised through this. Contention is not a problem in practice - the
     * viewer renders what is on screen, which is a handful of pages.
     */
    private val renderLock = Mutex()

    /** Incremented per document, so cached state from the previous one cannot be reused. */
    private var generation = 0

    private val _reading = MutableStateFlow(ReadingState())
    val reading: StateFlow<ReadingState> = _reading.asStateFlow()

    private var searchJob: kotlinx.coroutines.Job? = null
    private var bookmarkJob: kotlinx.coroutines.Job? = null
    private var positionJob: kotlinx.coroutines.Job? = null

    /** Rises each time anything asks for a jump, so the same page twice still scrolls. */
    private var jumpToken = 0

    /**
     * Bitmaps for pages near the viewport.
     *
     * Sized in kilobytes rather than in entries. Page bitmaps differ by an order of magnitude
     * between a text page and a full-bleed scan, so a count-based cache would either waste
     * memory or thrash, depending on which document was open.
     *
     * KEYED BY PAGE INDEX AND RENDER WIDTH, AND EMPTIED BY [closeCurrent] BEFORE ANY NEW
     * DOCUMENT IS OPENED. The index alone served the previous document's page 0 as the new
     * document's cover; the width is in the key because zooming renders the same page again at
     * a larger size, and a cache that ignored it would hand back the blurry one for ever.
     */
    private val pageCache = object : LruCache<PageKey, Bitmap>(PAGE_CACHE_KB) {
        override fun sizeOf(key: PageKey, value: Bitmap): Int = value.byteCount / 1024
    }

    fun open(uri: Uri) {
        viewModelScope.launch {
            // Before anything else. The previous document's pages, file descriptor and cached
            // copy all have to go, and every one of them is a bug if it survives into the next.
            closeCurrent()
            _state.value = ViewerState.Loading
            try {
                val imported = sheaf.documentStore.import(uri)
                source = imported
                sourceKey = uri.toString()

                val opened = withContext(Dispatchers.IO) { sheaf.pdfEngine.open(imported.file) }
                document = opened

                val sizes = withContext(Dispatchers.IO) {
                    // One pass, up front. Each entry is two floats; even a four-thousand-page
                    // document is measured in kilobytes, and having them all means the list can
                    // reserve correct space for pages it has not drawn.
                    renderLock.withLock { List(opened.pageCount) { opened.pageSize(it) } }
                }

                // Where the reader got to last time. Read before the state is published so the
                // list can be positioned on its first layout rather than scrolling visibly.
                val resumeAt = if (sheaf.settings.current.resumeReading) {
                    sheaf.recents.positionFor(uri.toString())
                        .coerceIn(0, (opened.pageCount - 1).coerceAtLeast(0))
                } else {
                    0
                }

                generation += 1
                _state.value = ViewerState.Ready(
                    generation = generation,
                    sourceUri = uri.toString(),
                    displayName = imported.displayName,
                    pageCount = opened.pageCount,
                    pageSizes = sizes,
                    sizeBytes = imported.sizeBytes,
                    initialPage = resumeAt
                )

                sheaf.recents.record(uri, imported.displayName, opened.pageCount, imported.sizeBytes)
                watchBookmarks(uri.toString())

                // The outline is small and quick to read, so it is fetched with the document
                // rather than when the sheet opens. Doing it later would leave the reader
                // looking at a spinner in a panel that is usually empty anyway.
                val outline = withContext(Dispatchers.IO) {
                    runCatching { sheaf.surgeon.readOutline(PdfInput(imported.file)) }
                        .getOrDefault(emptyList())
                }
                _reading.value = _reading.value.copy(outline = outline)
            } catch (_: PdfException.PasswordRequired) {
                _state.value = ViewerState.NeedsPassword
            } catch (e: PdfException) {
                _state.value = ViewerState.Failed(e.message ?: "This document could not be opened.")
            } catch (e: Exception) {
                _state.value = ViewerState.Failed(e.message ?: "This document could not be opened.")
            }
        }
    }

    /**
     * Renders one page, or returns it from cache.
     *
     * Returns null rather than throwing if the page cannot be drawn: one unreadable page in an
     * otherwise fine document should leave a gap the user can scroll past, not empty the screen.
     */
    suspend fun page(index: Int, widthPx: Int): Bitmap? {
        val key = PageKey(index, widthPx)
        pageCache.get(key)?.let { return it }
        val doc = document ?: return null

        return try {
            withContext(Dispatchers.IO) {
                renderLock.withLock {
                    // Re-checked inside the lock: several composables can ask for the same page
                    // as it scrolls into view, and without this they each render it.
                    pageCache.get(key)
                        ?: doc.renderPage(index, widthPx).also { pageCache.put(key, it) }
                }
            }
        } catch (_: PdfException) {
            null
        }
    }

    /**
     * Opens a file Sheaf already owns - a finished result - without importing it again.
     *
     * The point is being able to check what a tool produced before deciding to save it. The
     * file is not copied and, crucially, not deleted when the viewer moves on: it belongs to
     * the result list, not to the viewer.
     */
    fun openLocal(file: SheafFile) {
        viewModelScope.launch {
            closeCurrent()
            _state.value = ViewerState.Loading
            try {
                source = file
                sourceKey = null
                val opened = withContext(Dispatchers.IO) { sheaf.pdfEngine.open(file.file) }
                document = opened

                val sizes = withContext(Dispatchers.IO) {
                    renderLock.withLock { List(opened.pageCount) { opened.pageSize(it) } }
                }

                generation += 1
                _state.value = ViewerState.Ready(
                    generation = generation,
                    sourceUri = "",
                    displayName = file.displayName,
                    pageCount = opened.pageCount,
                    pageSizes = sizes,
                    sizeBytes = file.sizeBytes,
                    initialPage = 0
                )

                val outline = withContext(Dispatchers.IO) {
                    runCatching { sheaf.surgeon.readOutline(PdfInput(file.file)) }
                        .getOrDefault(emptyList())
                }
                _reading.value = _reading.value.copy(outline = outline)
            } catch (e: Exception) {
                _state.value = ViewerState.Failed(e.message ?: "This document could not be opened.")
            }
        }
    }

    /** The file currently open, for printing and sharing. Null when nothing is open. */
    fun currentFile(): SheafFile? = source

    /** Inverts the page rendering, for reading in the dark without a white rectangle. */
    fun toggleNightMode() {
        _reading.value = _reading.value.copy(nightMode = !_reading.value.nightMode)
    }

    /**
     * Finds [query] across the document.
     *
     * Each hit carries the pages it is on, a snippet, and where on the page every match sits.
     * The rectangles are what let the page itself show the match: a result list that only says
     * "page 4" leaves the reader to find the phrase again by eye once they get there.
     *
     * They are collected into a map here rather than in the UI so that the page composable,
     * which runs for every page on screen, does not walk the whole hit list to find its own.
     */
    fun search(query: String) {
        searchJob?.cancel()
        val source = source
        _reading.value = _reading.value.copy(query = query, hitIndex = -1)

        if (query.isBlank() || source == null) {
            _reading.value = _reading.value.copy(
                hits = emptyList(),
                highlights = emptyMap(),
                searching = false
            )
            return
        }

        searchJob = viewModelScope.launch {
            // A pause before searching, so typing a six-letter word is one pass over the
            // document rather than six. On a long scan each pass is seconds of work.
            delay(SEARCH_DEBOUNCE_MS)
            _reading.value = _reading.value.copy(searching = true)
            val hits = withContext(Dispatchers.IO) {
                runCatching { sheaf.surgeon.search(PdfInput(source.file), query) }
                    .getOrDefault(emptyList())
            }
            _reading.value = _reading.value.copy(
                hits = hits,
                highlights = hits.filter { it.areas.isNotEmpty() }
                    .associate { it.pageIndex to it.areas },
                searching = false
            )
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        _reading.value = _reading.value.copy(
            query = "",
            hits = emptyList(),
            highlights = emptyMap(),
            hitIndex = -1,
            searching = false
        )
    }

    /**
     * Walks the matches one at a time, wrapping round at either end.
     *
     * Page-level results are enough to navigate a report and useless in a contract where the
     * word appears on every page, which is why this exists alongside the list.
     */
    fun stepHit(forward: Boolean) {
        val hits = _reading.value.hits
        if (hits.isEmpty()) return
        val current = _reading.value.hitIndex
        val next = when {
            current < 0 -> if (forward) 0 else hits.lastIndex
            forward -> (current + 1) % hits.size
            else -> (current - 1 + hits.size) % hits.size
        }
        _reading.value = _reading.value.copy(hitIndex = next)
        jumpTo(hits[next].pageIndex)
    }

    /**
     * Asks the list to scroll to a page.
     *
     * Routed through the model rather than done in the screen so that everything which can
     * move the reader - the outline, a bookmark, a search result, the page field - arrives by
     * one path. The token is what makes jumping to the page you are already on still work.
     */
    fun jumpTo(pageIndex: Int) {
        jumpToken += 1
        _reading.value = _reading.value.copy(jump = Jump(pageIndex, jumpToken))
    }

    /**
     * Records where the reader is, so the document reopens here.
     *
     * Debounced, and deliberately a plain UPDATE: scrolling must not reorder the recents list
     * under the reader's thumb, and it must not write a row per page turned.
     */
    fun rememberPosition(pageIndex: Int) {
        val key = sourceKey ?: return
        if (!sheaf.settings.current.resumeReading) return
        positionJob?.cancel()
        positionJob = viewModelScope.launch {
            delay(POSITION_SAVE_DELAY_MS)
            runCatching { sheaf.recents.rememberPosition(key, pageIndex) }
        }
    }

    /** Marks or unmarks the page, with the document's own outline supplying a label. */
    fun toggleBookmark(pageIndex: Int) {
        val key = sourceKey ?: return
        val marked = _reading.value.bookmarks.any { it.pageIndex == pageIndex }
        viewModelScope.launch {
            if (marked) {
                sheaf.bookmarks.remove(key, pageIndex)
            } else {
                // The nearest heading above this page names the mark, which is far more use
                // later than a list of page numbers. Documents without an outline get the
                // page number, which is all there is to say about them.
                val heading = _reading.value.outline
                    .filter { it.pageIndex <= pageIndex }
                    .maxByOrNull { it.pageIndex }
                    ?.title
                    ?.takeIf { it.isNotBlank() }
                sheaf.bookmarks.add(
                    uri = key,
                    pageIndex = pageIndex,
                    label = heading ?: "Page ${pageIndex + 1}"
                )
            }
        }
    }

    fun removeBookmark(pageIndex: Int) {
        val key = sourceKey ?: return
        viewModelScope.launch { sheaf.bookmarks.remove(key, pageIndex) }
    }

    private fun watchBookmarks(key: String) {
        bookmarkJob?.cancel()
        bookmarkJob = viewModelScope.launch {
            sheaf.bookmarks.observeFor(key).collect { marks ->
                _reading.value = _reading.value.copy(bookmarks = marks)
            }
        }
    }

    /**
     * Releases everything tied to the document currently open.
     *
     * Called before opening another and from [onCleared]. Each step matters on its own.
     *
     * The cache would otherwise serve the wrong pages. The descriptor would leak one per
     * document opened. The imported copy would sit in the cache directory until Android
     * decided to clear it, which on a phone full of scans is a lot of wasted storage.
     */
    private fun closeCurrent() {
        searchJob?.cancel()
        bookmarkJob?.cancel()
        positionJob?.cancel()
        _reading.value = ReadingState()
        pageCache.evictAll()
        runCatching { document?.close() }
        document = null
        // Only a copy the viewer made itself. A result handed over by a tool is still in that
        // tool's list and deleting it here would take it out from under the reader.
        if (source?.origin is SheafFile.Origin.Imported) source?.file?.delete()
        source = null
        sourceKey = null
    }

    override fun onCleared() {
        super.onCleared()
        closeCurrent()
    }

    companion object {
        /** Roughly a dozen screen-width pages. Enough to scroll smoothly, small enough to be safe. */
        private const val PAGE_CACHE_KB = 48 * 1024

        /** One keystroke's grace before a search walks the whole document. */
        private const val SEARCH_DEBOUNCE_MS = 250L

        /** Long enough that scrolling through a book is one write, not four hundred. */
        private const val POSITION_SAVE_DELAY_MS = 600L
    }
}

/** A rendered page is identified by which page it is and how wide it was drawn. */
private data class PageKey(val index: Int, val width: Int)

/** A request to move the reader, and the token that makes a repeat of it count. */
data class Jump(val pageIndex: Int, val token: Int)

/** Everything about how the document is being read, as opposed to what it contains. */
data class ReadingState(
    val nightMode: Boolean = false,
    val outline: List<OutlineEntry> = emptyList(),
    val bookmarks: List<BookmarkEntity> = emptyList(),
    val query: String = "",
    val hits: List<SearchHit> = emptyList(),
    /** Match rectangles by page index, so a page can draw its own without scanning [hits]. */
    val highlights: Map<Int, List<PageArea>> = emptyMap(),
    /** Which match the reader has stepped to, or -1 before they have stepped anywhere. */
    val hitIndex: Int = -1,
    val searching: Boolean = false,
    val jump: Jump? = null
)

sealed interface ViewerState {
    data object Loading : ViewerState
    data object NeedsPassword : ViewerState
    data class Failed(val reason: String) : ViewerState
    data class Ready(
        /**
         * Identifies which document these pages belong to. The UI keys its per-page state on
         * it, so a page composable cannot carry a bitmap over from the document before.
         */
        val generation: Int,
        /**
         * Where this document came from, so the viewer can hand it to a tool.
         *
         * The tool imports its own copy from the same Uri rather than sharing this one. The
         * viewer deletes its cached copy the moment another document is opened, and a tool
         * holding a reference to a deleted file is a bug waiting to happen.
         */
        val sourceUri: String,
        val displayName: String,
        val pageCount: Int,
        val pageSizes: List<PageSize>,
        val sizeBytes: Long,
        /** The page to open at: where the reader left off, or the first page. */
        val initialPage: Int = 0
    ) : ViewerState
}
