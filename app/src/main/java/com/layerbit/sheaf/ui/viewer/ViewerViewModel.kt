package com.layerbit.sheaf.ui.viewer

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.layerbit.sheaf.SheafApplication
import com.layerbit.sheaf.files.SheafFile
import com.layerbit.sheaf.pdf.PageSize
import com.layerbit.sheaf.pdf.PdfDocument
import com.layerbit.sheaf.pdf.PdfException
import kotlinx.coroutines.Dispatchers
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
 * no matter how long the document is. Page count and page sizes are read once and kept,
 * because those are a few bytes each and the list needs them to lay out before anything is
 * rendered - that is what lets the scrollbar be honest on a document the user has only seen
 * the first screen of.
 */
class ViewerViewModel(app: Application) : AndroidViewModel(app) {

    private val sheaf = app as SheafApplication

    private val _state = MutableStateFlow<ViewerState>(ViewerState.Loading)
    val state: StateFlow<ViewerState> = _state.asStateFlow()

    private var document: PdfDocument? = null
    private var source: SheafFile? = null

    /**
     * PdfRenderer permits one open page at a time per document and is not thread-safe, so
     * every render is serialised through this. Contention is not a problem in practice - the
     * viewer renders what is on screen, which is a handful of pages.
     */
    private val renderLock = Mutex()

    /**
     * Bitmaps for pages near the viewport.
     *
     * Sized in kilobytes rather than in entries, because page bitmaps differ by an order of
     * magnitude between a text page and a full-bleed scan and a count-based cache would either
     * waste memory or thrash depending on which document was open.
     */
    private val pageCache = object : LruCache<Int, Bitmap>(PAGE_CACHE_KB) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount / 1024
    }

    fun open(uri: Uri) {
        viewModelScope.launch {
            _state.value = ViewerState.Loading
            try {
                val imported = sheaf.documentStore.import(uri)
                source = imported

                val opened = withContext(Dispatchers.IO) { sheaf.pdfEngine.open(imported.file) }
                document = opened

                val sizes = withContext(Dispatchers.IO) {
                    // One pass, up front. Each entry is two floats; even a four-thousand-page
                    // document is measured in kilobytes, and having them all means the list can
                    // reserve correct space for pages it has not drawn.
                    renderLock.withLock { List(opened.pageCount) { opened.pageSize(it) } }
                }

                _state.value = ViewerState.Ready(
                    displayName = imported.displayName,
                    pageCount = opened.pageCount,
                    pageSizes = sizes,
                    sizeBytes = imported.sizeBytes
                )

                sheaf.recents.record(uri, imported.displayName, opened.pageCount, imported.sizeBytes)
            } catch (e: PdfException.PasswordRequired) {
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
        pageCache.get(index)?.let { return it }
        val doc = document ?: return null

        return try {
            withContext(Dispatchers.IO) {
                renderLock.withLock {
                    // Re-checked inside the lock: several composables can ask for the same page
                    // as it scrolls into view, and without this they each render it.
                    pageCache.get(index) ?: doc.renderPage(index, widthPx).also { pageCache.put(index, it) }
                }
            }
        } catch (e: PdfException) {
            null
        }
    }

    override fun onCleared() {
        super.onCleared()
        pageCache.evictAll()
        runCatching { document?.close() }
        document = null
    }

    companion object {
        /** Roughly a dozen screen-width pages. Enough to scroll smoothly, small enough to be safe. */
        private const val PAGE_CACHE_KB = 48 * 1024
    }
}

sealed interface ViewerState {
    data object Loading : ViewerState
    data object NeedsPassword : ViewerState
    data class Failed(val reason: String) : ViewerState
    data class Ready(
        val displayName: String,
        val pageCount: Int,
        val pageSizes: List<PageSize>,
        val sizeBytes: Long
    ) : ViewerState
}
