package com.layerbit.sheaf.files

import android.net.Uri
import java.io.File

/**
 * A document Sheaf is allowed to read, and where it came from.
 *
 * The two states are kept apart on purpose, because the difference is the app's central
 * safety rule. An [Imported] file lives in Sheaf's own cache and may be read freely; the
 * [Uri] it came from is never opened for writing. See [DocumentStore].
 */
data class SheafFile(
    /** Sheaf's own copy. Safe to read, safe to delete, never the user's original. */
    val file: File,
    /** What to call it in the UI. Carried forward through an operation chain. */
    val displayName: String,
    /** Where it came from, for provenance and for the recents list. Read-only, always. */
    val origin: Origin,
    /**
     * What this file actually is. Not every result is a PDF - PDF to Images produces PNGs and
     * JPEGs - and the export picker has to offer the right type or the system file picker
     * saves a PNG named .pdf.
     */
    val mimeType: String = MIME_PDF
) {
    val sizeBytes: Long get() = file.length()

    /** Name without the .pdf, for building an output name a person would recognise. */
    val baseName: String
        get() = displayName.substringBeforeLast('.', displayName)

    val isPdf: Boolean get() = mimeType == MIME_PDF

    sealed interface Origin {
        /** Picked by the user through the Storage Access Framework, or received via a share. */
        data class Imported(val uri: Uri) : Origin

        /** Produced by an operation in this app. [step] is what made it, for the result stack. */
        data class Derived(val step: String) : Origin
    }

    companion object {
        const val MIME_PDF = "application/pdf"
        const val MIME_PNG = "image/png"
        const val MIME_JPEG = "image/jpeg"
        const val MIME_TEXT = "text/plain"
    }
}
