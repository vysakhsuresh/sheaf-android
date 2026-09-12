package com.layerbit.sheaf.files

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * The only way a user's document enters this app.
 *
 * SHEAF NEVER WRITES TO A FILE THE USER PICKED. Not for a "quick" in-place rotate, not to
 * save a round trip, not ever. One destroyed original is a one-star review that never comes
 * off, and the people most likely to lose something here are the ones handling the documents
 * that matter most.
 *
 * The rule is structural rather than a convention to remember. [import] copies the picked
 * document into Sheaf's cache and hands back a [SheafFile] pointing at the copy; every
 * operation in the app takes a [SheafFile] and can therefore only ever reach the copy. The
 * original Uri survives inside [SheafFile.Origin.Imported] for provenance, and the only
 * component allowed to open a Uri for writing is [Exporter], against a *new* document the
 * user just created in the system picker.
 *
 * If you are ever tempted to add a `contentResolver.openOutputStream(source.uri)` anywhere
 * else in this codebase, that is the bug.
 */
class DocumentStore(private val context: Context) {

    private val importDir: File
        get() = File(context.cacheDir, "imported").apply { mkdirs() }

    /**
     * Copies the document at [uri] into Sheaf's cache.
     *
     * @throws IOException if the document cannot be read, which on SAF usually means the
     *   grant expired or the backing file moved - both worth telling the user plainly.
     */
    suspend fun import(uri: Uri): SheafFile = withContext(Dispatchers.IO) {
        val name = displayName(uri)
        // Content-provider names collide constantly (every scanner app produces "Scan.pdf"),
        // so the cache name is made unique while the display name stays the human one.
        val target = File(importDir, "${System.nanoTime()}-${name.sanitisedForFilesystem()}")

        val copied = context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output) }
        } ?: throw IOException("Could not open $name. The app that owns it may have revoked access.")

        if (copied == 0L) {
            target.delete()
            throw IOException("$name is empty.")
        }

        SheafFile(
            file = target,
            displayName = name,
            origin = SheafFile.Origin.Imported(uri),
            mimeType = mimeTypeOf(uri, name)
        )
    }

    /** Imports several documents, in the order given. Used by the share sheet's SEND_MULTIPLE. */
    suspend fun importAll(uris: List<Uri>): List<SheafFile> = uris.map { import(it) }

    /**
     * The name the owning app reports, falling back to the last path segment.
     *
     * DocumentFile handles the provider query; the fallback matters because a `file://` Uri
     * from an older app that shares one will not answer [OpenableColumns.DISPLAY_NAME] at all.
     *
     * The name is returned as it is. An earlier version appended ".pdf" to anything that did
     * not already end in it, which turned every picked image into "photo.webp.pdf" and then
     * made the rest of the app try to parse it as a document.
     */
    private fun displayName(uri: Uri): String =
        runCatching { DocumentFile.fromSingleUri(context, uri)?.name }.getOrNull()
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "document"

    /**
     * What the picked file actually is.
     *
     * Asking the content provider first, because it knows. The extension is only a fallback,
     * for the providers that answer "application/octet-stream" to everything.
     *
     * Getting this wrong is not cosmetic. Every tool decides what to do with a file from its
     * type, so a photo that claims to be a PDF gets handed to the PDF parser and comes back
     * as "Header doesn't contain versioninfo" - which is exactly what used to happen to every
     * image picked for Images to PDF.
     */
    private fun mimeTypeOf(uri: Uri, name: String): String {
        val reported = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        if (!reported.isNullOrBlank() && reported != GENERIC_BINARY) return reported

        return when (name.substringAfterLast('.', "").lowercase()) {
            "pdf" -> SheafFile.MIME_PDF
            "png" -> SheafFile.MIME_PNG
            "jpg", "jpeg" -> SheafFile.MIME_JPEG
            "webp" -> "image/webp"
            "heic", "heif" -> "image/heif"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "txt" -> SheafFile.MIME_TEXT
            // Unknown rather than assumed. A tool that needs a PDF will say so plainly; a tool
            // that guessed would fail somewhere deeper and less usefully.
            else -> GENERIC_BINARY
        }
    }

    /** Deletes every imported copy. Called when the app has no open work. */
    fun clearImports() {
        importDir.listFiles()?.forEach { it.delete() }
    }
}

private const val GENERIC_BINARY = "application/octet-stream"

/**
 * Strips anything that cannot appear in a filename on the device's filesystem.
 *
 * A display name comes from a content provider and is arbitrary text: it can contain path
 * separators, and a name like "../../databases/sheaf.db" written unchecked would escape the
 * cache directory entirely.
 */
internal fun String.sanitisedForFilesystem(): String {
    val cleaned = buildString {
        for (c in this@sanitisedForFilesystem) {
            append(if (c.isLetterOrDigit() || c == '.' || c == '-' || c == '_' || c == ' ') c else '_')
        }
    }.trim().trim('.')
    return cleaned.take(120).ifEmpty { "document.pdf" }
}
