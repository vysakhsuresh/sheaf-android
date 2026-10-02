package com.layerbit.sheaf.files

import android.content.Context
import java.io.File

/**
 * Signatures the user chose to keep.
 *
 * Drawing a signature with a fingertip is the slowest thing in the app and the one most likely
 * to be done badly on the third attempt. Someone who signs a document a week should draw it
 * once, which is what this is for.
 *
 * In filesDir rather than the cache, because the whole point is that it survives - the
 * workspace is swept on a schedule. Backup is off for the whole app, so a signature does not
 * leave the device even through Android's own backup, which for this of all files is correct.
 */
class SignatureStore(context: Context) {

    private val root: File = File(context.filesDir, "signatures").apply { mkdirs() }

    /** Newest first, which is the order someone would look for one in. */
    fun list(): List<File> = root.listFiles()
        ?.filter { it.isFile && it.length() > 0 }
        ?.sortedByDescending { it.lastModified() }
        .orEmpty()

    /**
     * Copies a drawn signature in and returns the kept file.
     *
     * Copied rather than moved: the drawn one belongs to the job that is about to use it and
     * is swept with the rest of the workspace.
     */
    fun keep(drawn: File): File? = runCatching {
        if (!drawn.isFile) return null
        val kept = File(root, "signature-${System.currentTimeMillis()}.png")
        drawn.copyTo(kept, overwrite = true)
        trimTo(MAX_KEPT)
        kept
    }.getOrNull()

    fun forget(file: File) {
        // Only ever inside this folder. A path from somewhere else is a bug, and deleting
        // what it points at would be a worse one.
        if (file.parentFile?.absolutePath == root.absolutePath) file.delete()
    }

    fun clear() {
        root.listFiles()?.forEach { it.delete() }
    }

    /** Oldest go first. Nobody keeps a dozen signatures, and a list that long is a mistake. */
    private fun trimTo(keep: Int) {
        val files = list()
        if (files.size <= keep) return
        files.drop(keep).forEach { it.delete() }
    }

    private companion object {
        const val MAX_KEPT = 6
    }
}
