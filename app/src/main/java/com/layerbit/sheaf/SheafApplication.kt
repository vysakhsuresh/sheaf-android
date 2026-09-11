package com.layerbit.sheaf

import android.app.Application
import com.layerbit.sheaf.data.RecentsRepository
import com.layerbit.sheaf.data.db.SheafDatabase
import com.layerbit.sheaf.files.DocumentStore
import com.layerbit.sheaf.files.Exporter
import com.layerbit.sheaf.files.Workspace
import com.layerbit.sheaf.jobs.JobRunner
import com.layerbit.sheaf.pdf.MlKitOcrEngine
import com.layerbit.sheaf.pdf.OcrEngine
import com.layerbit.sheaf.pdf.PdfBoxEngine
import com.layerbit.sheaf.pdf.PdfBoxSurgeon
import com.layerbit.sheaf.pdf.PdfEngine
import com.layerbit.sheaf.pdf.PdfSurgeon
import com.layerbit.sheaf.pdf.PlatformPdfEngine
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

/**
 * The app's object graph, assembled by hand.
 *
 * Deja and Abhyas do the same, and at this size it stays the right call: there are seven
 * singletons and none of them has a complicated lifetime. A dependency-injection framework
 * would add a build step and an annotation processor to solve a problem that has not appeared.
 *
 * [pdfEngine] is the one worth watching. It is typed as [PdfEngine], not as the platform
 * implementation, so the day PDFBox arrives the change lands here and nowhere else.
 */
class SheafApplication : Application() {

    /** Reading and rasterising for the viewer: the platform renderer, which allocates least. */
    val pdfEngine: PdfEngine by lazy { PlatformPdfEngine(this) }

    /**
     * Reading a document the platform renderer refuses - which in practice means an encrypted
     * one, since android.graphics.pdf has no way to accept a password.
     */
    val encryptedReader: PdfEngine by lazy { PdfBoxEngine() }

    /** Restructuring and writing. Everything in ops/ goes through this. */
    val surgeon: PdfSurgeon by lazy { PdfBoxSurgeon() }

    /**
     * Reading words out of a picture of a page, on the bundled ML Kit model.
     *
     * Lazy matters here: the recogniser loads several megabytes of model on first use, and
     * someone who only ever merges two PDFs should never pay for it.
     */
    val ocr: OcrEngine by lazy { MlKitOcrEngine() }
    val documentStore: DocumentStore by lazy { DocumentStore(this) }
    val workspace: Workspace by lazy { Workspace(this) }
    val exporter: Exporter by lazy { Exporter(this) }
    val jobRunner: JobRunner by lazy { JobRunner(this) }
    val recents: RecentsRepository by lazy {
        RecentsRepository(SheafDatabase.get(this).recentDao())
    }

    override fun onCreate() {
        super.onCreate()
        // PDFBox loads its font metrics and glyph lists from assets, and without this every
        // call that touches a font throws. Cheap, and it must happen before any operation.
        PDFBoxResourceLoader.init(this)

        // Results from a previous session are of no use now and the user has already exported
        // anything they wanted. Trimming at startup rather than on a timer keeps the cache
        // bounded without a background job to maintain.
        workspace.trimTo(Workspace.DEFAULT_LIMIT_BYTES)
    }
}
