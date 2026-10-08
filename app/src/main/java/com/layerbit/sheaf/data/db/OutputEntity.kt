package com.layerbit.sheaf.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A file Sheaf made: the Library, and the bin it goes to before it is gone.
 *
 * THIS TABLE EXISTS BECAUSE THE APP COULD LOSE YOUR WORK. A finished result lives in the
 * scratch workspace, and [com.layerbit.sheaf.files.Workspace.trimTo] deletes oldest-first to
 * keep that under a size ceiling. Nothing recorded what had been produced, so a result the
 * user had not yet exported to Files could be deleted without ever being listed, mentioned or
 * recoverable. On a tool that makes forty page images, the ceiling is reached in one run.
 *
 * So every output is written down the moment it exists, the trim takes already-saved and
 * already-binned files before it touches unsaved work, and deleting sets [deletedAt] rather
 * than removing the row - which is what turns an invisible cleanup into a Recently deleted
 * screen somebody can undo from.
 *
 * ONE TABLE FOR BOTH SCREENS, not two. The Library is the rows with no [deletedAt] and the
 * bin is the rows with one; they are the same file at two points in its life, and splitting
 * them would mean moving a row between tables on every delete and every restore, which is
 * two places for the truth to disagree.
 *
 * What is stored is a path, a name and some counts - never the document's contents, which is
 * the same promise [RecentEntity] makes and for the same reason.
 */
@Entity(tableName = "outputs")
data class OutputEntity(
    /**
     * Absolute path in the workspace. Natural key: the workspace never reuses a name, because
     * [com.layerbit.sheaf.files.Workspace.newOutput] disambiguates, so a path identifies one
     * file for as long as it exists.
     */
    @PrimaryKey val path: String,
    val displayName: String,
    /** [com.layerbit.sheaf.ops.ToolId] name, so the Library can say what made this. */
    val toolId: String,
    val sizeBytes: Long,
    val createdAt: Long,
    /**
     * When the user exported it to Files, or null if they never did.
     *
     * Kept so the Library can mark what is already safe elsewhere, and so a trim can prefer
     * to take those first: a result that exists in the user's own Documents folder is a copy,
     * and a result that exists nowhere else is the only one.
     */
    val savedAt: Long? = null,
    /**
     * When it was deleted, or null while it is in the Library.
     *
     * THE FILE IS STILL THERE while this is set, which is the whole point: a bin you cannot
     * restore from is a list of regrets. Deleting moves the row, not the bytes.
     *
     * That has to be paid for, and the trim is where. When the workspace is over its ceiling
     * it takes, in this order: rows already in the bin, oldest first - those have been thrown
     * away once already; then Library rows with a [savedAt], because a copy exists in the
     * user's own Files and this one is a convenience; and only then unsaved Library work,
     * which is the case worth avoiding and now the last resort rather than, as before, the
     * first thing to go because it happened to be oldest.
     *
     * Anything the trim takes keeps its row with [deletedAt] set and loses its bytes, so the
     * bin can still say what was lost and which tool would make it again. Restore checks the
     * file is really there and says so plainly when it is not.
     */
    val deletedAt: Long? = null
)
