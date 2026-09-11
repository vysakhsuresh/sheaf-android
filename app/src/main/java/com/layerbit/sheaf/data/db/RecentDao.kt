package com.layerbit.sheaf.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RecentDao {

    @Query("SELECT * FROM recents ORDER BY lastOpenedAt DESC LIMIT :limit")
    fun observeRecents(limit: Int = 20): Flow<List<RecentEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(recent: RecentEntity)

    @Query("DELETE FROM recents WHERE uri = :uri")
    suspend fun delete(uri: String)

    @Query("DELETE FROM recents")
    suspend fun clear()

    /**
     * Keeps the table from growing without bound. Called after each insert rather than on a
     * schedule, because a list nobody is looking at does not need a background job.
     */
    @Query("DELETE FROM recents WHERE uri NOT IN (SELECT uri FROM recents ORDER BY lastOpenedAt DESC LIMIT :keep)")
    suspend fun trimTo(keep: Int)
}
