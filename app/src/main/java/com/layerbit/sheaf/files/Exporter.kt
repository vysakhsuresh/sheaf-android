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
    fun createDocumentIntent(suggestedName: String): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = PDF_MIME
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
                type = PDF_MIME
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            null
        )
    }

    companion object {
        const val PDF_MIME = "application/pdf"
    }
}
