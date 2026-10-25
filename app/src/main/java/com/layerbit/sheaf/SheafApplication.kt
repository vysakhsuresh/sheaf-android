package com.layerbit.sheaf

import android.app.Application
import com.layerbit.sheaf.data.BookmarksRepository
import com.layerbit.sheaf.data.OutputStore
import com.layerbit.sheaf.data.RecentsRepository
import com.layerbit.sheaf.data.db.SheafDatabase
import com.layerbit.sheaf.files.CrashLog
import com.layerbit.sheaf.files.DocumentStore
import com.layerbit.sheaf.files.Handoff
import com.layerbit.sheaf.files.SignatureStore
import com.layerbit.sheaf.files.Exporter
import com.layerbit.sheaf.files.Workspace
import com.layerbit.sheaf.jobs.JobRunner
import com.layerbit.sheaf.prefs.Prompts
import com.layerbit.sheaf.prefs.SheafSettings
import com.layerbit.sheaf.pdf.MlKitOcrEngine
import com.layerbit.sheaf.pdf.OcrEngine
import com.layerbit.sheaf.pdf.PdfBoxEngine
import com.layerbit.sheaf.pdf.PdfBoxSurgeon
import com.layerbit.sheaf.pdf.PdfEngine
import com.layerbit.sheaf.pdf.PdfSurgeon
import com.layerbit.sheaf.pdf.PlatformPdfEngine
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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

    /**
     * Restructuring and writing. Everything in ops/ goes through this.
     *
     * It is handed the workspace's scratch directory, so the buffers PDFBox keeps out of the
     * heap land in the one place this app already prunes rather than wherever the process
     * temp directory happens to point.
     */
    val surgeon: PdfSurgeon by lazy { PdfBoxSurgeon(workspace.scratchDir) }

    /**
     * Reading words out of a picture of a page, on the bundled ML Kit model.
     *
     * Lazy matters here: the recogniser loads several megabytes of model on first use, and
     * someone who only ever merges two PDFs should never pay for it.
     */
    val ocr: OcrEngine by lazy { MlKitOcrEngine() }
    val documentStore: DocumentStore by lazy { DocumentStore(this) }

    /** Documents shared in from another app, waiting for the tool that can take them. */
    val handoff: Handoff by lazy { Handoff() }

    /** Signatures the user kept, so a signature is drawn once rather than once per document. */
    val signatures: SignatureStore by lazy { SignatureStore(this) }
    val workspace: Workspace by lazy { Workspace(this) }
    val exporter: Exporter by lazy { Exporter(this) }
    val jobRunner: JobRunner by lazy { JobRunner(this) }
    val recents: RecentsRepository by lazy {
        RecentsRepository(SheafDatabase.get(this).recentDao())
    }

    /** The reader's own marked pages, per document. */
    val bookmarks: BookmarksRepository by lazy {
        BookmarksRepository(SheafDatabase.get(this).bookmarkDao())
    }

    /**
     * Everything Sheaf has produced: the Library, the bin, and the rule a trim obeys.
     *
     * It exists because the workspace alone could not tell a result somebody was waiting on
     * from a stale intermediate, and so deleted whichever was oldest.
     */
    val outputs: OutputStore by lazy {
        OutputStore(SheafDatabase.get(this).outputDao())
    }

    /**
     * Preferences, in memory. The theme is read before the first frame composes and on every
     * recomposition after it, which is why these live in SharedPreferences and not in Room.
     */
    val settings: SheafSettings by lazy { SheafSettings(this) }

    /** What the app has already asked or shown once: the notification ask, the intro, a review. */
    val prompts: Prompts by lazy { Prompts(this) }

    override fun onCreate() {
        super.onCreate()
        // First, so a crash anywhere after this - including in the setup below - is written
        // down where Settings can offer it.
        CrashLog.install(this)
        // PDFBox loads its font metrics and glyph lists from assets, and without this every
        // call that touches a font throws. Cheap, and it must happen before any operation.
        PDFBoxResourceLoader.init(this)

        // Keeping the cache bounded, at startup rather than on a timer, so there is no
        // background job to maintain.
        //
        // Through the OutputStore rather than the Workspace, which is the whole point of
        // that class: it knows which files are results somebody is waiting on and which are
        // stale intermediates, so it can take the already-saved and the already-binned
        // before it touches unsaved work. The bare Workspace.trimTo this replaces knew only
        // modification times, and would take the result of the last thing the user ran if it
        // happened to be the oldest file in the directory.
        //
        // Reconcile first: Android may have cleared the cache since, and a Library promising
        // files that are not there is worse than one that admits they went.
        applicationScope.launch {
            val now = System.currentTimeMillis()
            outputs.reconcile(now)
            outputs.trim(Workspace.DEFAULT_LIMIT_BYTES, now = now)
        }
    }

    /**
     * For the handful of jobs that outlive any screen - the startup trim, recording a result.
     *
     * Not a scope that can be cancelled: this work is short and finishing it matters more
     * than tidiness at shutdown, and there is nothing to cancel it from anyway.
     */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
