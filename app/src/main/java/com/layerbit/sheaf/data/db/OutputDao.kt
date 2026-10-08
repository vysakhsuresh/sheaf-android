package com.layerbit.sheaf.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** The Library, the bin, and the order a trim is allowed to take things in. */
@Dao
interface OutputDao {

    /** Everything Sheaf has made and the user has not thrown away, newest first. */
    @Query("SELECT * FROM outputs WHERE deletedAt IS NULL ORDER BY createdAt DESC")
    fun observeLibrary(): Flow<List<OutputEntity>>

    /** The bin, most recently thrown away first. */
    @Query("SELECT * FROM outputs WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    fun observeDeleted(): Flow<List<OutputEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun record(output: OutputEntity)

    @Query("UPDATE outputs SET deletedAt = :at WHERE path = :path")
    suspend fun markDeleted(path: String, at: Long)

    @Query("UPDATE outputs SET deletedAt = NULL WHERE path = :path")
    suspend fun restore(path: String)

    @Query("UPDATE outputs SET savedAt = :at WHERE path = :path")
    suspend fun markSaved(path: String, at: Long)

    /** Gone for good: the row as well as the bytes. */
    @Query("DELETE FROM outputs WHERE path = :path")
    suspend fun forget(path: String)

    @Query("SELECT * FROM outputs WHERE path = :path")
    suspend fun find(path: String): OutputEntity?

    /**
     * What a trim may take, in the order it may take it.
     *
     * The ordering is the policy, written here rather than left to whoever calls it. Binned
     * rows first, because they have been thrown away once already; then rows with a savedAt,
     * because the user's own copy in Files is the real one and this is a convenience; and
     * unsaved Library work last, because that is the only copy in existence and losing it is
     * the failure this whole table was added to prevent.
     *
     * Oldest first within each band, which is the one part of the previous behaviour worth
     * keeping.
     */
    @Query(
        """
        SELECT * FROM outputs
        ORDER BY
            CASE WHEN deletedAt IS NOT NULL THEN 0
                 WHEN savedAt IS NOT NULL THEN 1
                 ELSE 2 END,
            createdAt ASC
        """
    )
    suspend fun inTrimOrder(): List<OutputEntity>

    /**
     * Rows whose file has gone without the app being told - a cache wipe by Android, or a
     * reinstall. Reconciled at startup so the Library cannot promise a file that is not there.
     */
    @Query("SELECT * FROM outputs")
    suspend fun all(): List<OutputEntity>
}
