package com.layerbit.sheaf.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {

    @Query("SELECT * FROM bookmarks WHERE uri = :uri ORDER BY pageIndex ASC")
    fun observeFor(uri: String): Flow<List<BookmarkEntity>>

    @Query("SELECT COUNT(*) FROM bookmarks")
    fun observeCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE uri = :uri AND pageIndex = :pageIndex")
    suspend fun remove(uri: String, pageIndex: Int)

    @Query("DELETE FROM bookmarks WHERE uri = :uri")
    suspend fun clearFor(uri: String)

    @Query("DELETE FROM bookmarks")
    suspend fun clear()
}
