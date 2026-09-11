package com.layerbit.sheaf.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A document the user opened, so it can be reopened from the home screen.
 *
 * What is stored is the SAF Uri and a name - never the document's contents and never anything
 * read out of it. A recents list that cached page text would be a copy of the user's papers
 * sitting in a database, which is exactly what this app exists not to do.
 *
 * The Uri may stop working: a persistable permission can be revoked, and the file behind it
 * can be moved or deleted by the app that owns it. That is expected, and the home screen
 * handles a dead entry by offering to remove it rather than by showing an error.
 */
@Entity(tableName = "recents")
data class RecentEntity(
    /** The SAF Uri as a string. Natural key - reopening the same document updates the row. */
    @PrimaryKey val uri: String,
    val displayName: String,
    val pageCount: Int,
    val sizeBytes: Long,
    val lastOpenedAt: Long
)
