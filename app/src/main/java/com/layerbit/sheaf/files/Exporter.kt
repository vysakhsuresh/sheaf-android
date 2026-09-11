package com.layerbit.sheaf.files

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import java.io.IOException

/**
 * How a result leaves the app.
 *
 * This is the ONLY component permitted to open a Uri for writing, and the only Uri it may
 * write to is one the user just created themselves through ACTION_CREATE_DOCUMENT. The system
 * picker is what makes that safe: the user chose the folder and the filename, so there is no
 * path at which Sheaf could overwrite something they did not intend.
 *
 * Sharing goes out through FileProvider rather than by handing over a cache path, because a
 * raw file:// Uri has been an immediate FileUriExposedException since Android 7.
 */
class Exporter(private val context: Context) {

    /**
     * An intent that asks the user where to save [suggestedName].
     *
     * Launch it with ActivityResultContracts.StartActivityForResult and pass the returned Uri
     * to [writeTo]. The two halves are separate because the picker is an async round trip
     * through another process and the write should not be tangled up in that.
     */
    fun createDocumentIntent(suggestedName: String, mimeType: String = PDF_MIME): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeType
            putExtra(Intent.EXTRA_TITLE, suggestedName)
        }

    /**
     * Copies [source] to the document the user picked.
     *
     * @throws IOException if the destination cannot be written, which usually means the user
     *   picked a location that has since gone away - a removed SD card, an unmounted share.
     */
    suspend fun writeTo(destination: Uri, source: SheafFile) = withContext(Dispatchers.IO) {
        val resolver: ContentResolver = context.contentResolver
        // "wt" truncates. Plain "w" leaves any bytes past the new content in place, which
        // silently produces a corrupt PDF when the replacement is shorter than what was there.
        resolver.openFileDescriptor(destination, "wt")?.use { descriptor ->
            FileOutputStream(descriptor.fileDescriptor).use { output ->
                source.file.inputStream().use { input -> input.copyTo(output) }
            }
        } ?: throw IOException("Could not write to the location you chose.")
    }

    /**
     * A share-sheet intent for [source].
     *
     * Requires the FileProvider declared in the manifest with `cache-path` coverage of the
     * work directory; a file outside those paths makes getUriForFile throw.
     */
    fun shareIntent(source: SheafFile): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", source.file)
        return Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = source.mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            null
        )
    }

    /**
     * Share several results at once.
     *
     * PDF to images produces one file per page, and saving forty pages through forty separate
     * system pickers is not something anyone would do twice. ACTION_SEND_MULTIPLE hands the
     * whole set to one app in one gesture.
     */
    fun shareIntent(sources: List<SheafFile>): Intent {
        if (sources.size == 1) return shareIntent(sources.first())

        val uris = ArrayList(
            sources.map { FileProvider.getUriForFile(context, "${context.packageName}.files", it.file) }
        )
        // A mixed set has no single type; the generic one still reaches every app that can
        // take a stream, which is what matters.
        val type = sources.map { it.mimeType }.distinct().singleOrNull() ?: "*/*"

        return Intent.createChooser(
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                this.type = type
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            null
        )
    }

    companion object {
        const val PDF_MIME = "application/pdf"
    }
}
