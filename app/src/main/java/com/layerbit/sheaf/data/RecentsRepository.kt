package com.layerbit.sheaf.data

import android.net.Uri
import com.layerbit.sheaf.data.db.RecentDao
import com.layerbit.sheaf.data.db.RecentEntity
import kotlinx.coroutines.flow.Flow

class RecentsRepository(private val dao: RecentDao) {

    fun observe(): Flow<List<RecentEntity>> = dao.observeRecents()

    suspend fun record(uri: Uri, displayName: String, pageCount: Int, sizeBytes: Long) {
        // The existing reading position is carried over rather than reset: REPLACE writes a
        // whole row, so reopening a document would otherwise send the reader back to page one.
        val existing = dao.positionFor(uri.toString()) ?: 0
        dao.upsert(
            RecentEntity(
                uri = uri.toString(),
                displayName = displayName,
                pageCount = pageCount,
                sizeBytes = sizeBytes,
                lastOpenedAt = System.currentTimeMillis(),
                lastPage = existing.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
            )
        )
        dao.trimTo(MAX_RECENTS)
    }

    /** Where the reader got to in this document, or zero. */
    suspend fun positionFor(uri: String): Int = dao.positionFor(uri) ?: 0

    suspend fun rememberPosition(uri: String, pageIndex: Int) =
        dao.rememberPosition(uri, pageIndex)

    suspend fun forget(uri: String) = dao.delete(uri)

    suspend fun clear() = dao.clear()

    companion object {
        const val MAX_RECENTS = 40
    }
}
