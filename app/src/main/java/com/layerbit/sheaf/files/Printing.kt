package com.layerbit.sheaf.files

import android.content.Context
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import java.io.File
import java.io.FileOutputStream

/**
 * Printing, which on an offline app is the one export that needs no file at all.
 *
 * Android's print framework takes a PrintDocumentAdapter and asks it for PDF bytes, which is
 * exactly what Sheaf already has on disk - so this adapter hands over the file unchanged
 * rather than re-rendering anything. The print service on the other side does the rasterising
 * and the page selection, and it is also the one that talks to the printer: nothing here
 * opens a socket, which is what keeps printing compatible with having no INTERNET permission.
 *
 * This is also the answer to "save as PDF": every Android print dialog offers it, so a
 * document can be written to Files through the system's own picker from here.
 */
fun printDocument(context: Context, file: File, jobName: String, pageCount: Int) {
    val manager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return
    manager.print(
        jobName.take(MAX_JOB_NAME),
        FilePrintAdapter(file, jobName, pageCount),
        PrintAttributes.Builder().build()
    )
}

/**
 * Streams an existing PDF to the print framework.
 *
 * The page range the user picks in the dialog is deliberately ignored and the whole document
 * is written back with [PageRange.ALL_PAGES]: the framework then applies the selection itself.
 * Honouring it here would mean splitting the document to answer a question that is already
 * answered downstream.
 */
private class FilePrintAdapter(
    private val file: File,
    private val jobName: String,
    private val pageCount: Int
) : PrintDocumentAdapter() {

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes?,
        cancellationSignal: CancellationSignal?,
        callback: LayoutResultCallback?,
        extras: Bundle?
    ) {
        if (cancellationSignal?.isCanceled == true) {
            callback?.onLayoutCancelled()
            return
        }

        val info = PrintDocumentInfo.Builder(jobName.take(MAX_JOB_NAME))
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(if (pageCount > 0) pageCount else PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
            .build()

        // The second argument says whether the layout changed since the last call. The
        // document is a file on disk and page setup cannot alter it, so it never has.
        callback?.onLayoutFinished(info, false)
    }

    override fun onWrite(
        pages: Array<out PageRange>?,
        destination: ParcelFileDescriptor?,
        cancellationSignal: CancellationSignal?,
        callback: WriteResultCallback?
    ) {
        if (destination == null) {
            callback?.onWriteFailed("There was nowhere to write the document.")
            return
        }
        try {
            file.inputStream().use { input ->
                FileOutputStream(destination.fileDescriptor).use { output ->
                    input.copyTo(output)
                }
            }
            if (cancellationSignal?.isCanceled == true) {
                callback?.onWriteCancelled()
            } else {
                callback?.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            }
        } catch (e: Exception) {
            callback?.onWriteFailed(e.message ?: "The document could not be sent to the printer.")
        }
    }
}

/** The framework truncates a long job name anyway; doing it here keeps the dialog readable. */
private const val MAX_JOB_NAME = 60
