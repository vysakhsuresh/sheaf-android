package com.layerbit.sheaf.data.db

import androidx.room.Entity
import androidx.room.Index

/**
 * A page the reader marked, in a document they opened.
 *
 * Deliberately separate from the PDF's own outline. An outline is the author's structure and
 * is read out of the file; this is the reader's, and writing it into the document would mean
 * rewriting a file the user did not ask to have changed. The page number and a label is all
 * it takes, and - like everything else in this database - no content from the document is
 * copied here.
 *
 * The key is the document and the page together, so marking the same page twice replaces the
 * mark rather than stacking a second one on it.
 */
@Entity(
    tableName = "bookmarks",
    primaryKeys = ["uri", "pageIndex"],
    indices = [Index("uri")]
)
data class BookmarkEntity(
    val uri: String,
    val pageIndex: Int,
    val label: String,
    val createdAt: Long
)
