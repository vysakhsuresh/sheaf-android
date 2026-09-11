package com.layerbit.sheaf

import android.app.Application
import com.layerbit.sheaf.data.RecentsRepository
import com.layerbit.sheaf.data.db.SheafDatabase
import com.layerbit.sheaf.files.DocumentStore
import com.layerbit.sheaf.files.Exporter
import com.layerbit.sheaf.files.Workspace
import com.layerbit.sheaf.jobs.JobRunner
import com.layerbit.sheaf.pdf.PdfEngine
import com.layerbit.sheaf.pdf.PlatformPdfEngine

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

    val pdfEngine: PdfEngine by lazy { PlatformPdfEngine(this) }
    val documentStore: DocumentStore by lazy { DocumentStore(this) }
    val workspace: Workspace by lazy { Workspace(this) }
    val exporter: Exporter by lazy { Exporter(this) }
    val jobRunner: JobRunner by lazy { JobRunner(this) }
    val recents: RecentsRepository by lazy {
        RecentsRepository(SheafDatabase.get(this).recentDao())
    }

    override fun onCreate() {
        super.onCreate()
        // Results from a previous session are of no use now and the user has already exported
        // anything they wanted. Trimming at startup rather than on a timer keeps the cache
        // bounded without a background job to maintain.
        workspace.trimTo(Workspace.DEFAULT_LIMIT_BYTES)
    }
}
