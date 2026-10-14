package com.layerbit.sheaf.ui.viewer

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.layerbit.sheaf.pdf.Comment
import java.util.Locale
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicReference

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

    /**
     * The document on screen, or null between one being closed and the next being published.
     *
     * Volatile because it is written on the main dispatcher and read from [Dispatchers.IO] by
     * [page], whose in-lock guard compares it against the handle that render started with.
     * Where a close coroutine is involved [renderLock] supplies the happens-before edge, but on
     * the path where no close has run there is nothing else to make the write visible.
     */
    @Volatile
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

    /**
     * Incremented per document, so cached state from the previous one cannot be reused.
     *
     * Volatile for the same reason as [document], and read alongside it: a render that saw the
     * new document under a stale generation would file its bitmap under the wrong key.
     */
    @Volatile
    private var generation = 0

    /**
     * Where a closed document's teardown runs.
     *
     * Closing has to happen under [renderLock], which means waiting for whatever render is in
     * flight, and nothing that opens a document can afford to wait for that. Not
     * [viewModelScope] either: the last document is handed over from [onCleared], by which
     * point that scope is already cancelled and the close would never run.
     */
    private val closeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Guards the two fields below, which the close coroutines touch as well as the main thread. */
    private val closeGuard = Any()

    /**
     * How many teardowns handed to [closeScope] have not finished, and whether [onCleared] has
     * run and is waiting on them.
     *
     * Two teardowns can be outstanding at once - one still waiting on [renderLock] when the
     * next document is opened and hands over another - so remembering only the most recent is
     * not enough: shutting the scope down behind that one cancels the earlier close and leaks
     * exactly the descriptor this is all here to release.
     */
    private var outstandingCloses = 0
    private var shutdownWanted = false

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
     * KEYED BY DOCUMENT GENERATION, PAGE INDEX AND RENDER WIDTH, AND EMPTIED BY
     * [closeCurrent] BEFORE ANY NEW DOCUMENT IS OPENED. The index alone served the previous
     * document's page 0 as the new document's cover, and emptying the cache is not enough on
     * its own: a render already in flight when the next document opens finishes afterwards
     * and puts its bitmap in behind the eviction, so the generation it was asked for is part
     * of the key and that entry is simply unreachable. The width is in the key because zooming
     * renders the same page again at a larger size, and a cache that ignored it would hand
     * back the blurry one for ever.
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

                val opened = openGuarded(imported.file)
                // The generation rises before the document is published, not after: in between
                // the two, a render that began would read the new document under the previous
                // generation and file its bitmap under a key belonging to a document it did not
                // come from, where nothing can reach it again.
                generation += 1
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
                loadComments()
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
     * Opens a document, closing it again if this coroutine is cancelled while the open is in
     * flight.
     *
     * [withContext] rethrows a cancellation that arrived while its block was running and
     * discards what the block returned, so a handle the engine had just finished opening would
     * never reach [document] and nothing would ever close it - a descriptor held for the life of
     * the process. The handle is therefore stashed where the cancellation path can still find
     * it, in an atomic because that path resumes on another thread. Closing it is
     * [NonCancellable] rather than handed to [closeScope]: a cancelled coroutine cannot suspend
     * on [renderLock], and the only thing that cancels an open is the viewer going away, by
     * which point that scope may already be shutting down.
     */
    private suspend fun openGuarded(file: File): PdfDocument {
        val handle = AtomicReference<PdfDocument?>(null)
        try {
            return withContext(Dispatchers.IO) {
                sheaf.pdfEngine.open(file).also { handle.set(it) }
            }
        } catch (cancelled: CancellationException) {
            handle.getAndSet(null)?.let { orphan ->
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { renderLock.withLock { orphan.close() } }
                }
            }
            throw cancelled
        }
    }

    /**
     * Renders one page, or returns it from cache.
     *
     * Returns null rather than throwing if the page cannot be drawn: one unreadable page in an
     * otherwise fine document should leave a gap the user can scroll past, not empty the screen.
     */
    suspend fun page(index: Int, widthPx: Int): Bitmap? {
        val doc = document ?: return null
        // Taken before the render starts, and carried in the key: by the time the bitmap
        // exists this may no longer be the document on screen, and the answer to a question
        // about one document must never be filed as the answer for another.
        val gen = generation
        val key = PageKey(gen, index, widthPx)
        pageCache.get(key)?.let { return it }

        return try {
            withContext(Dispatchers.IO) {
                renderLock.withLock {
                    // Re-checked inside the lock: several composables can ask for the same page
                    // as it scrolls into view, and without this they each render it.
                    val cached = pageCache.get(key)
                    when {
                        cached != null -> cached
                        // The document went while this waited for the lock. Rendering from it
                        // would throw at best, and nothing will ask for this page again.
                        gen != generation || document !== doc -> null
                        else -> doc.renderPage(index, widthPx).also { pageCache.put(key, it) }
                    }
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
                val opened = openGuarded(file.file)
                // Raised before the document is published, for the reason given in [open].
                generation += 1
                document = opened

                val sizes = withContext(Dispatchers.IO) {
                    renderLock.withLock { List(opened.pageCount) { opened.pageSize(it) } }
                }

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
                loadComments()
            } catch (e: Exception) {
                _state.value = ViewerState.Failed(e.message ?: "This document could not be opened.")
            }
        }
    }

    /** The file currently open, for printing and sharing. Null when nothing is open. */
    fun currentFile(): SheafFile? = source

    /**
     * The comments on the pages, read once per document. Most documents have none, and the
     * menu only offers the list when there is something in it.
     */
    private fun loadComments() {
        val file = source ?: return
        val forGeneration = generation
        viewModelScope.launch {
            val comments = withContext(Dispatchers.IO) {
                runCatching { sheaf.surgeon.readComments(PdfInput(file.file)) }.getOrDefault(emptyList())
            }
            if (generation == forGeneration) _reading.value = _reading.value.copy(comments = comments)
        }
    }

    // ---- read aloud ----

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var readJob: Job? = null
    /** The page asked for while the engine was still starting. */
    private var pendingReadPage = -1

    /**
     * Reads the document out from [fromPage], page after page, with the phone's own
     * text-to-speech engine.
     *
     * Only a voice that works without the network is used: Sheaf has no INTERNET permission,
     * so an online voice would fail anyway, and the promise that nothing leaves the phone
     * covers what is read out as much as what is written. A scanned page has no text to read;
     * Make searchable gives it some.
     */
    fun readAloud(fromPage: Int) {
        val engine = tts
        if (engine == null) {
            pendingReadPage = fromPage
            _reading.value = _reading.value.copy(readAloud = ReadAloud(speaking = true, page = fromPage, starting = true))
            tts = TextToSpeech(getApplication<Application>()) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) {
                    chooseLocalVoice()
                    tts?.setOnUtteranceProgressListener(progress)
                    val page = pendingReadPage
                    pendingReadPage = -1
                    if (page >= 0) speakPage(page)
                } else {
                    _reading.value = _reading.value.copy(
                        readAloud = ReadAloud(available = false, note = "This phone has no text-to-speech engine.")
                    )
                }
            }
            return
        }
        if (!ttsReady) {
            pendingReadPage = fromPage
            return
        }
        speakPage(fromPage)
    }

    fun stopReading() {
        readJob?.cancel()
        pendingReadPage = -1
        runCatching { tts?.stop() }
        _reading.value = _reading.value.copy(readAloud = _reading.value.readAloud.copy(speaking = false, starting = false, page = -1))
    }

    private fun chooseLocalVoice() {
        val engine = tts ?: return
        runCatching {
            val wanted = Locale.getDefault()
            engine.setLanguage(wanted)
            val local = engine.voices.orEmpty().filter {
                !it.isNetworkConnectionRequired && !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
            }
            val voice = local.firstOrNull { it.locale == wanted }
                ?: local.firstOrNull { it.locale.language == wanted.language }
                ?: local.firstOrNull { it.locale.language == "en" }
                ?: local.firstOrNull()
            if (voice != null) engine.voice = voice
        }
    }

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit
        override fun onError(utteranceId: String?) {
            _reading.value = _reading.value.copy(
                readAloud = ReadAloud(available = true, speaking = false, note = "The voice stopped. Try again.")
            )
        }
        override fun onDone(utteranceId: String?) {
            // The last piece of a page is the signal to move to the next one.
            val id = utteranceId ?: return
            if (!id.endsWith("-last")) return
            val page = id.removeSuffix("-last").removePrefix("page-").toIntOrNull() ?: return
            val total = (_state.value as? ViewerState.Ready)?.pageCount ?: return
            if (_reading.value.readAloud.speaking && page + 1 < total) {
                speakPage(page + 1)
            } else {
                _reading.value = _reading.value.copy(readAloud = ReadAloud(speaking = false, note = "Reached the end."))
            }
        }
    }

    private fun speakPage(page: Int) {
        val file = source ?: return
        val engine = tts ?: return
        val total = (_state.value as? ViewerState.Ready)?.pageCount ?: return
        if (page !in 0 until total) return
        readJob?.cancel()
        readJob = viewModelScope.launch {
            _reading.value = _reading.value.copy(readAloud = ReadAloud(speaking = true, page = page))
            val text = withContext(Dispatchers.IO) {
                runCatching { sheaf.surgeon.extractText(PdfInput(file.file), listOf(page))[page] }.getOrNull()
            }.orEmpty().replace(Regex("\\s+"), " ").trim()
            if (text.isEmpty()) {
                // Nothing to say on this page: go on to the next, up to the end.
                if (page + 1 < total) {
                    jumpTo(page + 1)
                    speakPage(page + 1)
                } else {
                    _reading.value = _reading.value.copy(
                        readAloud = ReadAloud(speaking = false, note = "No text to read. A scanned page needs Make searchable first.")
                    )
                }
                return@launch
            }
            jumpTo(page)
            val limit = runCatching { TextToSpeech.getMaxSpeechInputLength() }.getOrDefault(3900).coerceAtMost(3900)
            val pieces = splitForSpeech(text, limit)
            pieces.forEachIndexed { index, piece ->
                val id = if (index == pieces.lastIndex) "page-$page-last" else "page-$page-$index"
                engine.speak(piece, if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id)
            }
        }
    }

    /** Sentences, grouped under the engine's input limit, so no sentence is cut mid-word. */
    private fun splitForSpeech(text: String, limit: Int): List<String> {
        val sentences = text.split(Regex("(?<=[.!?।])\\s+"))
        val out = mutableListOf<String>()
        val current = StringBuilder()
        for (sentence in sentences) {
            if (sentence.length > limit) {
                if (current.isNotEmpty()) { out += current.toString(); current.clear() }
                sentence.chunked(limit).forEach { out += it }
                continue
            }
            if (current.length + sentence.length + 1 > limit) {
                out += current.toString()
                current.clear()
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(sentence)
        }
        if (current.isNotEmpty()) out += current.toString()
        return out.ifEmpty { listOf(text.take(limit)) }
    }

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
     *
     * Closing the document itself is the one step that cannot be done here, so it is handed
     * to [closeScope]. It has to run under [renderLock] - a PdfRenderer closed while a native
     * render is in flight throws, and the descriptor it is holding is then pulled out from
     * under native code - and this function stays non-suspending so that opening the next
     * document can show Loading at once rather than after the page on screen finishes drawing.
     */
    private fun closeCurrent() {
        stopReading()
        searchJob?.cancel()
        bookmarkJob?.cancel()
        positionJob?.cancel()
        _reading.value = ReadingState()
        pageCache.evictAll()

        // The field is cleared first so no new render can reach this document, and the handle
        // travels to the closer rather than being dropped: clearing it alone left the last
        // reference to an open renderer with the garbage collector.
        val closing = document
        document = null
        if (closing != null) {
            // Counted before the launch, so the completion handler can never run against a
            // count that does not include this close yet.
            synchronized(closeGuard) { outstandingCloses += 1 }
            closeScope.launch {
                // The engine reports a close it could not complete; this only keeps a teardown
                // that runs after the viewer is gone from taking the process with it.
                runCatching { renderLock.withLock { closing.close() } }
            }.invokeOnCompletion { closeFinished() }
        }

        // Only a copy the viewer made itself. A result handed over by a tool is still in that
        // tool's list and deleting it here would take it out from under the reader. The closer
        // may still hold a descriptor on this file, which is fine: unlinking only takes the
        // name, and the bytes go with the last descriptor.
        if (source?.origin is SheafFile.Origin.Imported) source?.file?.delete()
        source = null
        sourceKey = null
    }

    /**
     * One handed-over teardown has finished. Whichever is the last one out shuts [closeScope]
     * down, if [onCleared] has already asked for that.
     */
    private fun closeFinished() {
        val last = synchronized(closeGuard) {
            outstandingCloses -= 1
            shutdownWanted && outstandingCloses == 0
        }
        if (last) closeScope.cancel()
    }

    override fun onCleared() {
        super.onCleared()
        closeCurrent()
        runCatching { tts?.shutdown() }
        tts = null
        // Nothing more will be handed over, so the scope goes - but not until every teardown
        // still outstanding has run, because cancelling it now would cancel a close that is
        // waiting on [renderLock] and leave its descriptor open for as long as the process
        // lives. The last close out does it instead.
        val idle = synchronized(closeGuard) {
            shutdownWanted = true
            outstandingCloses == 0
        }
        if (idle) closeScope.cancel()
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

/**
 * A rendered page is identified by the document it was drawn from, which page it is and how
 * wide it was drawn. The generation is the document's: without it a bitmap that finished
 * rendering after its document closed is indistinguishable from the next document's page.
 */
private data class PageKey(val generation: Int, val index: Int, val width: Int)

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
    val jump: Jump? = null,
    /** The comments on the pages, for the list in the menu. */
    val comments: List<Comment> = emptyList(),
    val readAloud: ReadAloud = ReadAloud()
)

/** Whether the document is being read out, and from where. */
data class ReadAloud(
    val available: Boolean = true,
    val speaking: Boolean = false,
    /** True while the engine is starting up, before the first word. */
    val starting: Boolean = false,
    val page: Int = -1,
    /** Something to tell the reader: why it stopped, or that there was nothing to read. */
    val note: String? = null
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
