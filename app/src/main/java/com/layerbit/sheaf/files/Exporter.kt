package com.layerbit.sheaf.files

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException

/**
 * How a result leaves the app.
 *
 * This is the ONLY component permitted to open a Uri for writing, and the only Uri it may
 * write to is one the user just created themselves through ACTION_CREATE_DOCUMENT. The system
 * picker is what makes that safe: the user chose the folder and the filename, so Sheaf can
 * never reach a document they did not name. It can still land on one they already had, since
 * the picker offers to replace a name that is taken, so [writeTo] treats the destination as
 * something that may already be worth keeping.
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
     * "wt" truncates, and it has to: plain "w" leaves any bytes past the new content in place,
     * which silently produces a corrupt PDF when the replacement is shorter than what was
     * there. But it truncates at open, and the picker hands back an existing document whenever
     * the user answered a replace prompt, so a copy that dies halfway has already destroyed
     * what they had. That is the one hole in the guarantee [DocumentStore] makes for the rest
     * of the app, which is why a failure here clears up after itself and says plainly what
     * state the destination is in: a half-written file looks saved and will not open.
     *
     * @throws IOException if the destination cannot be written, which usually means the user
     *   picked a location that has since gone away - a removed SD card, an unmounted share -
     *   or that it filled up partway through.
     */
    suspend fun writeTo(destination: Uri, source: SheafFile) = withContext(Dispatchers.IO) {
        val resolver: ContentResolver = context.contentResolver
        val descriptor = resolver.openFileDescriptor(destination, "wt")
            ?: throw IOException("Could not write to the location you chose.")

        try {
            descriptor.use {
                FileOutputStream(it.fileDescriptor).use { output ->
                    source.file.inputStream().use { input -> input.copyTo(output) }
                    output.flush()
                    syncIfSupported(it.fileDescriptor)
                }
            }
        } catch (e: IOException) {
            throw IOException(
                discardPartial(destination, source.displayName, mayHaveReplaced = true, cause = e), e
            )
        } catch (e: SecurityException) {
            // A grant can be revoked while the copy is running, and it arrives as a security
            // failure rather than an IO one. The destination is just as half-written either way.
            throw IOException(
                discardPartial(destination, source.displayName, mayHaveReplaced = true, cause = e), e
            )
        }
    }

    /**
     * Pushes the bytes already written out to storage, where the destination can do that at all.
     *
     * Closing reports success long before the bytes reach the card, so without this an ENOSPC
     * or a card pulled mid-copy is discovered by the user, later, as a file that will not open.
     * But plenty of destinations cannot fsync at all: a provider that writes through
     * ParcelFileDescriptor.createReliablePipe, as MTP and several cloud providers do, fails
     * EINVAL, and a StorageManager proxy fd whose callback does not implement onFsync fails
     * EBADF. By the time this runs the copy has returned and every byte has been handed over,
     * so a refusal here says nothing about whether the save worked - only that getting those
     * bytes onto the medium is the provider's business - and it must never reach the caller,
     * which would answer a good save by deleting the file. That is why the sync is swallowed
     * here rather than left inside the copy's failure scope.
     */
    private fun syncIfSupported(fd: FileDescriptor) {
        runCatching { fd.sync() }
    }

    /**
     * Removes a destination left half-written, and reports which of the two outcomes happened.
     *
     * Deleting is the lesser harm: an absent file cannot be mistaken for a saved one, and the
     * result is still inside Sheaf to save again. A provider is not obliged to allow a delete,
     * and will not when the failure was the grant going away, so the message has to be honest
     * about the file still sitting there.
     *
     * [mayHaveReplaced] is true only for [writeTo], whose destination can be a document the
     * user already had; [writeInto] writes to a name the provider just made unique, so there
     * was never anything of theirs underneath it.
     *
     * [cause] matters because this sentence is the whole report on the folder path: [writeTo]
     * chains the failure into the IOException it throws, but [writeInto] returns a string and
     * nothing else survives. "Could not be written" leaves a person with nowhere to go, where
     * knowing the card is full tells them exactly what to do next.
     */
    private fun discardPartial(
        destination: Uri,
        name: String,
        mayHaveReplaced: Boolean,
        cause: Throwable? = null
    ): String {
        val removed = runCatching {
            DocumentsContract.deleteDocument(context.contentResolver, destination)
        }.getOrDefault(false)

        val outcome = when {
            removed && mayHaveReplaced ->
                "$name could not be written. The incomplete file was removed, so anything it " +
                    "replaced is gone."
            removed -> "$name could not be written. The incomplete file was removed."
            mayHaveReplaced ->
                "$name could not be written. The file you chose is now incomplete and will " +
                    "not open."
            else ->
                "$name could not be written. An incomplete file was left in that folder and " +
                    "will not open."
        }

        return reasonFor(cause)?.let { "$outcome $it" } ?: outcome
    }

    /**
     * The one extra sentence worth adding, or nothing.
     *
     * Only the failures a person can act on are named. The exception's own message is never
     * shown: these arrive as errno text from the kernel by way of the provider, and "EACCES"
     * or "write failed: ENOSPC (No space left on device)" is not addressed to anyone.
     */
    private fun reasonFor(cause: Throwable?): String? = when {
        cause == null -> null
        cause is SecurityException -> "Sheaf's permission for that folder was withdrawn."
        cause.message?.contains("ENOSPC") == true -> "There is no space left on that storage."
        cause.message?.contains("EROFS") == true -> "That storage is read-only."
        else -> null
    }

    /**
     * Asks the user for a folder to put results in, once.
     *
     * The persistable grant is what makes "do not ask me again" possible: without it the
     * permission dies with the process and the next save would have to ask anyway. The
     * caller takes the grant - this only builds the request.
     */
    fun openFolderIntent(): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }

    /**
     * Writes [source] into a folder the user granted earlier.
     *
     * This is what turns saving forty exported pages from forty system pickers into one
     * gesture. It is also the only write path that does not go through a picker, which is why
     * it refuses anything but a tree the user granted and never overwrites: a name already in
     * use is handed to the provider, which makes it unique rather than replacing a file.
     *
     * Nothing the user already had is at risk here, but a copy that dies partway still leaves
     * a file they will find later and try to open, so this syncs and clears up after itself
     * the same way [writeTo] does.
     *
     * @return null on success, or a sentence explaining what went wrong.
     */
    suspend fun writeInto(folder: Uri, source: SheafFile): String? = withContext(Dispatchers.IO) {
        val tree = DocumentFile.fromTreeUri(context, folder)
            ?: return@withContext "That folder is no longer available."
        if (!tree.canWrite()) {
            return@withContext "Sheaf is no longer allowed to write to that folder."
        }

        val target = tree.createFile(source.mimeType, source.displayName)
            ?: return@withContext "${source.displayName} could not be created there."

        try {
            // A descriptor is wanted for the sync, but not at the cost of the destinations that
            // cannot give one: openFileDescriptor refuses a provider whose asset has a declared
            // length, closing it and throwing "Not a whole file", where openOutputStream accepts
            // it. A folder the user granted can belong to such a provider, so the stream is the
            // fallback and the trade is only the sync - the copy and the clean-up are the same.
            val descriptor = runCatching {
                context.contentResolver.openFileDescriptor(target.uri, "wt")
            }.getOrNull()

            if (descriptor != null) {
                descriptor.use {
                    FileOutputStream(it.fileDescriptor).use { output ->
                        source.file.inputStream().use { input -> input.copyTo(output) }
                        output.flush()
                        syncIfSupported(it.fileDescriptor)
                    }
                }
            } else {
                val stream = context.contentResolver.openOutputStream(target.uri, "wt")
                    ?: return@withContext discardPartial(
                        target.uri, source.displayName, mayHaveReplaced = false
                    )
                stream.use { output ->
                    source.file.inputStream().use { input -> input.copyTo(output) }
                    output.flush()
                }
            }
        } catch (e: IOException) {
            return@withContext discardPartial(
                target.uri, source.displayName, mayHaveReplaced = false, cause = e
            )
        } catch (e: SecurityException) {
            // A folder grant can be revoked mid-copy just as a single-document one can.
            return@withContext discardPartial(
                target.uri, source.displayName, mayHaveReplaced = false, cause = e
            )
        }
        null
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
