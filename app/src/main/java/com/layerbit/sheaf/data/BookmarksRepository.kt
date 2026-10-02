package com.layerbit.sheaf.data

import com.layerbit.sheaf.data.db.BookmarkDao
import com.layerbit.sheaf.data.db.BookmarkEntity
import kotlinx.coroutines.flow.Flow

/**
 * The reader's own marked pages, kept beside the document rather than inside it.
 *
 * Writing a bookmark into the PDF would mean rewriting a file nobody asked to have changed,
 * and on a read-only document it would not be possible at all. Keyed by the document's Uri,
 * which means a mark survives being closed and reopened and disappears with the recent entry
 * when the user forgets the document.
 */
class BookmarksRepository(private val dao: BookmarkDao) {

    fun observeFor(uri: String): Flow<List<BookmarkEntity>> = dao.observeFor(uri)

    fun observeCount(): Flow<Int> = dao.observeCount()

    suspend fun add(uri: String, pageIndex: Int, label: String) = dao.add(
        BookmarkEntity(
            uri = uri,
            pageIndex = pageIndex,
            label = label.ifBlank { "Page ${pageIndex + 1}" },
            createdAt = System.currentTimeMillis()
        )
    )

    suspend fun remove(uri: String, pageIndex: Int) = dao.remove(uri, pageIndex)

    suspend fun clearFor(uri: String) = dao.clearFor(uri)

    suspend fun clear() = dao.clear()
}
