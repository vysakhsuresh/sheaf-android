package com.layerbit.sheaf.data

import com.layerbit.sheaf.data.db.OutputDao
import com.layerbit.sheaf.data.db.OutputEntity
import com.layerbit.sheaf.files.SheafFile
import com.layerbit.sheaf.ops.ToolId
import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * Everything Sheaf has made: the Library, the bin, and the rule for what a trim may take.
 *
 * The workspace knows about bytes on a disk and nothing else. This knows which of those bytes
 * someone is waiting on, which they have already copied into their own Files, and which they
 * have thrown away - which is the difference between a cleanup and losing a user's work.
 *
 * Deliberately the only thing that deletes from the workspace. [com.layerbit.sheaf.files.Workspace.trimTo]
 * still exists for the files that were never outputs - PDFBox spill, an orphan from a killed
 * process - but a produced file now goes through [trim] here, which has the record to make a
 * decision with.
 */
class OutputStore(private val dao: OutputDao) {

    /** What the Library screen shows. */
    fun observeLibrary(): Flow<List<OutputEntity>> = dao.observeLibrary()

    /** What the Recently deleted screen shows. */
    fun observeDeleted(): Flow<List<OutputEntity>> = dao.observeDeleted()

    /**
     * Writes down a file an operation has just produced.
     *
     * Called the moment the job reports it, not when the user does something with it: the
     * window this closes is precisely the one between a result existing and the user getting
     * round to saving it, which is where the old trim could take it unannounced.
     */
    suspend fun record(file: SheafFile, tool: ToolId, now: Long) {
        dao.record(
            OutputEntity(
                path = file.file.absolutePath,
                displayName = file.displayName,
                toolId = tool.name,
                sizeBytes = file.file.length(),
                createdAt = now
            )
        )
    }

    /** The user exported it to their own Files, so a copy now exists that Sheaf cannot lose. */
    suspend fun markSaved(file: File, now: Long) = dao.markSaved(file.absolutePath, now)

    /**
     * Throws one away, keeping the bytes so it can come back.
     *
     * A bin that cannot restore is a list of regrets, so this moves the row and not the file.
     * The bytes are only given up when [trim] needs the space, and binned rows are the first
     * thing it takes.
     */
    suspend fun delete(path: String, now: Long) = dao.markDeleted(path, now)

    /**
     * Puts one back, if there is anything to put back.
     *
     * @return false when the bytes are gone - a trim took them after it was binned, or
     *   Android cleared the cache - so the screen can say that instead of silently doing
     *   nothing and leaving the row in the bin with no explanation.
     */
    suspend fun restore(path: String): Boolean {
        if (!File(path).isFile) return false
        dao.restore(path)
        return true
    }

    /** Gone for good: bytes and row together. */
    suspend fun forget(path: String) {
        File(path).delete()
        dao.forget(path)
    }

    /**
     * Brings the table back in line with the disk.
     *
     * Android can clear the cache directory whenever it likes and a reinstall certainly does,
     * so rows can outlive their files with nobody told. Run at startup: a row whose file has
     * gone is moved to the bin rather than removed, because "this existed and is gone" is
     * more use to someone looking for it than silence, and the row still names the tool that
     * would make it again.
     */
    suspend fun reconcile(now: Long) {
        dao.all().forEach { row ->
            if (row.deletedAt == null && !File(row.path).isFile) dao.markDeleted(row.path, now)
        }
    }

    /**
     * Frees space, taking the least valuable thing each time.
     *
     * The order comes from the DAO and is the point of this whole class: already binned,
     * then already saved elsewhere, then - only if there is still nothing else - unsaved
     * work. The previous behaviour was oldest-first across everything, which meant the result
     * somebody had been waiting ten minutes for could be deleted because it happened to be
     * the oldest thing in a directory.
     *
     * @param protect files the current session is still using; never taken whatever band
     *   they fall in.
     */
    suspend fun trim(limitBytes: Long, protect: Set<File> = emptySet(), now: Long) {
        val rows = dao.inTrimOrder()
        var total = rows.sumOf { File(it.path).length() }
        if (total <= limitBytes) return

        val protectedPaths = protect.map { it.absolutePath }.toSet()
        for (row in rows) {
            if (total <= limitBytes) break
            if (row.path in protectedPaths) continue

            val file = File(row.path)
            val size = file.length()
            if (!file.delete()) continue
            total -= size

            // The row stays either way, so the bin can account for what went. One that was
            // already in the bin is now gone for good and has nothing left to offer.
            if (row.deletedAt != null) dao.forget(row.path) else dao.markDeleted(row.path, now)
        }
    }
}
