package com.layerbit.sheaf.files

import android.content.Context
import java.io.File

/**
 * Scratch space for operation output.
 *
 * Every operation writes a new file here rather than modifying its input, which is what makes
 * a chain of operations undoable: each step leaves its predecessor intact, so stepping back is
 * just pointing at the previous file again. It is also why a failed operation cannot damage
 * anything - the worst case is a half-written temp file that gets swept up.
 *
 * Nothing here survives a reinstall and nothing here is backed up, which is correct: these are
 * intermediate results, and the user's real output went out through [Exporter].
 */
class Workspace(private val context: Context) {

    private val root: File
        get() = File(context.cacheDir, "work").apply { mkdirs() }

    /**
     * A fresh, empty file for an operation to write into.
     *
     * @param baseName the human name the result should carry, without extension.
     * @param step short tag for what produced it, e.g. "merged", "pages-3-9". It ends up in
     *   the filename so a half-finished chain is readable in a file listing while debugging.
     */
    fun newOutput(baseName: String, step: String): File {
        val safe = baseName.sanitisedForFilesystem().let { it.substringBeforeLast('.', it) }
        return File(root, "${System.nanoTime()}-$step-$safe.pdf")
    }

    /** Total bytes currently held in scratch. Shown in Settings, and drives [trimTo]. */
    fun sizeBytes(): Long = root.listFiles()?.sumOf { it.length() } ?: 0L

    /**
     * Deletes oldest-first until scratch is under [limitBytes].
     *
     * Sheaf works on files that are routinely tens of megabytes and a user who runs ten
     * operations has left ten results behind. Without a ceiling the cache grows until Android
     * clears it at the least convenient moment - mid-operation - so the app manages it instead.
     *
     * @param protect files the current session still needs; never deleted regardless of age.
     */
    fun trimTo(limitBytes: Long, protect: Set<File> = emptySet()) {
        val files = root.listFiles()?.toMutableList() ?: return
        var total = files.sumOf { it.length() }
        if (total <= limitBytes) return

        val protectedPaths = protect.map { it.absolutePath }.toSet()
        files.sortBy { it.lastModified() }

        for (file in files) {
            if (total <= limitBytes) break
            if (file.absolutePath in protectedPaths) continue
            val size = file.length()
            if (file.delete()) total -= size
        }
    }

    /** Drops everything. Called from Settings, and when the app has no work in flight. */
    fun clear() {
        root.listFiles()?.forEach { it.delete() }
    }

    companion object {
        /** Roughly a large document plus a few results. Past this, oldest results go. */
        const val DEFAULT_LIMIT_BYTES = 512L * 1024L * 1024L
    }
}
