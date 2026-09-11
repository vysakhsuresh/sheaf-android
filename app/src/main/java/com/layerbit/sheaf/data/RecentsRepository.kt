package com.layerbit.sheaf.data

import android.net.Uri
import com.layerbit.sheaf.data.db.RecentDao
import com.layerbit.sheaf.data.db.RecentEntity
import kotlinx.coroutines.flow.Flow

class RecentsRepository(private val dao: RecentDao) {

    fun observe(): Flow<List<RecentEntity>> = dao.observeRecents()

    suspend fun record(uri: Uri, displayName: String, pageCount: Int, sizeBytes: Long) {
        dao.upsert(
            RecentEntity(
                uri = uri.toString(),
                displayName = displayName,
                pageCount = pageCount,
                sizeBytes = sizeBytes,
                lastOpenedAt = System.currentTimeMillis()
            )
        )
        dao.trimTo(MAX_RECENTS)
    }

    suspend fun forget(uri: String) = dao.delete(uri)

    suspend fun clear() = dao.clear()

    companion object {
        const val MAX_RECENTS = 40
    }
}
