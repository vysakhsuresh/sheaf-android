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

    /** Every Uri the list holds, for checking which persisted grants still have a row behind them. */
    @Query("SELECT uri FROM recents")
    suspend fun allUris(): List<String>

    /** Where the reader got to, or zero for a document this list has never seen. */
    @Query("SELECT lastPage FROM recents WHERE uri = :uri")
    suspend fun positionFor(uri: String): Int?

    /**
     * Writes the reading position without touching the rest of the row.
     *
     * An UPDATE rather than an upsert on purpose: scrolling must not promote a document up
     * the recents list, or the order would change under the reader's thumb every time they
     * turned a page.
     */
    @Query("UPDATE recents SET lastPage = :pageIndex WHERE uri = :uri")
    suspend fun rememberPosition(uri: String, pageIndex: Int)

    /**
     * Keeps the table from growing without bound. Called after each insert rather than on a
     * schedule, because a list nobody is looking at does not need a background job.
     */
    @Query("DELETE FROM recents WHERE uri NOT IN (SELECT uri FROM recents ORDER BY lastOpenedAt DESC LIMIT :keep)")
    suspend fun trimTo(keep: Int)

    /**
     * The Uris [trimTo] is about to drop, read before it runs.
     *
     * Each of those rows is holding a persisted Uri grant that has to be handed back with
     * it, and once the delete has happened there is nothing left to say which Uris they were.
     */
    @Query("SELECT uri FROM recents WHERE uri NOT IN (SELECT uri FROM recents ORDER BY lastOpenedAt DESC LIMIT :keep)")
    suspend fun urisBeyond(keep: Int): List<String>
}
