package com.layerbit.sheaf

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.google.android.play.core.review.ReviewManagerFactory
import com.layerbit.sheaf.files.Exporter
import com.layerbit.sheaf.jobs.JobState
import com.layerbit.sheaf.ops.ToolId
import com.layerbit.sheaf.ui.SheafApp
import com.layerbit.sheaf.ui.theme.SheafColors
import com.layerbit.sheaf.ui.theme.SheafTheme
import com.layerbit.sheaf.widgets.refreshRecentsWidget
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val sheaf: SheafApplication get() = application as SheafApplication

    /** Bumped on every open, which is what tells the UI to show the viewer. */
    private var openTicket by mutableIntStateOf(0)

    /** Bumped when photos, Office or text files arrive, which sends the UI to Open a file. */
    private var openAnyTicket by mutableIntStateOf(0)

    /**
     * A tool to go straight to, held until the UI has navigated to it.
     *
     * Set by a launcher shortcut, and by a share of several documents - which the viewer
     * cannot show and Merge can.
     */
    private var jumpToTool by mutableStateOf<ToolId?>(null)

    private val pickDocument = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            // Ask to keep the grant across restarts so the recents list still works tomorrow.
            // Providers are allowed to refuse, and some do, so this is attempted rather than
            // relied upon - a recent entry that will not reopen is handled, not prevented.
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            open(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        sheaf.recents.useResolver(applicationContext.contentResolver)
        if (savedInstanceState == null) {
            // Persisted grants are capped per package and crossing the cap is silent: takes
            // start failing and every document opened after that stops reopening once the
            // process is gone. Reconciling on a cold start keeps the count in step with the
            // list that uses it. The save folder is the one grant with no row behind it, so
            // it is named explicitly or the next silent save would ask for a folder again.
            lifecycleScope.launch {
                sheaf.recents.reconcileGrants(setOfNotNull(sheaf.settings.current.saveFolder))
            }
        }

        setContent {
            val settings by sheaf.settings.state.collectAsState()

            SheafTheme(theme = settings.theme) {
                // The status and navigation bar glyphs, re-decided once the palette is known.
                //
                // The enableEdgeToEdge() in onCreate defaults to SystemBarStyle.auto(), which
                // reads light or dark from the phone's night-mode setting rather than from
                // this app's ThemeChoice. Those agree for SYSTEM, which is the default, and
                // disagree for anyone who picked against their phone - and the failure is not
                // subtle: near-black status icons on a #101A2B bar, which is an unreadable
                // clock. Reapplying here is the only place that knows which palette won.
                //
                // LaunchedEffect rather than SideEffect: this needs to run when the palette
                // flips, not on every recomposition of the whole app. enableEdgeToEdge is
                // idempotent, and it draws its own navigation-bar scrim below API 27, so
                // minSdk 26 degrades without a version check.
                LaunchedEffect(SheafColors.isDark) {
                    val style = if (SheafColors.isDark) {
                        SystemBarStyle.dark(Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    }
                    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                }

                SheafApp(
                    recentsFlow = sheaf.recents.observe(),
                    bookmarkCountFlow = sheaf.bookmarks.observeCount(),
                    settings = settings,
                    onSettingsChange = { transform -> sheaf.settings.update(transform) },
                    openTicket = openTicket,
                    openAnyTicket = openAnyTicket,
                    jumpToTool = jumpToTool,
                    onJumpHandled = { jumpToTool = null },
                    onPickDocument = { pickDocument.launch(arrayOf(Exporter.PDF_MIME)) },
                    onOpenRecentUri = { open(Uri.parse(it)) },
                    onForgetRecent = { uri -> lifecycleScope.launch { sheaf.recents.forget(uri) } }
                )
            }
        }

        handleIntent(intent)
        watchForReviewMoment()

        // The recents widget shows the same list the home screen does, so it is redrawn
        // whenever that list changes. drop(1): the first emission is the list as it already is.
        lifecycleScope.launch {
            sheaf.recents.observe().drop(1).collect { refreshRecentsWidget(applicationContext) }
        }
    }

    /**
     * Asks Play for its review sheet after the third job that fully worked, and never again
     * inside sixty days.
     *
     * After a success because that is the only fair moment to ask; never mid-task, never after
     * a failure. The sheet is drawn by the Play Store app through its own binder, so this needs
     * no INTERNET permission of Sheaf's - and Play applies its own quota on top, so the call is
     * a request that may quietly show nothing, which is fine.
     */
    private fun watchForReviewMoment() {
        lifecycleScope.launch {
            var lastCounted: JobState.Finished? = null
            sheaf.jobRunner.state.collect { state ->
                if (state !is JobState.Finished || !state.allSucceeded || state === lastCounted) return@collect
                lastCounted = state
                val prompts = sheaf.prompts
                prompts.successfulJobs += 1
                val now = System.currentTimeMillis()
                if (!prompts.reviewDue(now)) return@collect
                prompts.lastReviewAsk = now
                runCatching {
                    val manager = ReviewManagerFactory.create(this@MainActivity)
                    manager.requestReviewFlow().addOnSuccessListener { info ->
                        if (!isFinishing) manager.launchReviewFlow(this@MainActivity, info)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /**
     * Documents and shortcuts arriving from outside the app.
     *
     * Being in the share sheet and the "open with" list is how people find Sheaf, so these
     * paths matter as much as the picker does. One document opens in the viewer; several go
     * to Merge with the whole set loaded, because a viewer can only show one of them and
     * merging is the obvious thing to want with five.
     *
     * A launcher shortcut arrives as a VIEW on a sheaf:// Uri rather than as an extra,
     * because a static shortcut cannot reliably carry extras and a scheme it can.
     */
    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW && intent.data?.scheme == SHORTCUT_SCHEME) {
            val name = intent.data?.lastPathSegment
            // The widget's Open a PDF: the same picker the home screen's button opens.
            if (name == PICK_DOCUMENT) {
                pickDocument.launch(arrayOf(Exporter.PDF_MIME))
                return
            }
            jumpToTool = ToolId.entries.firstOrNull { it.name == name }
            return
        }

        // Several documents at once go to Merge with all of them loaded, rather than opening
        // the first and silently dropping the rest - which is what sharing five PDFs to a
        // viewer used to do. Anything that is not all PDFs - a run of photos, most often -
        // goes to Open a file, which already knows which tool suits what it is given.
        if (intent?.action == Intent.ACTION_SEND_MULTIPLE) {
            val uris = intent.parcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            val allPdf = uris.isNotEmpty() && uris.all { isPdf(it, null) }
            when {
                uris.isEmpty() -> Unit
                !allPdf -> openAny(uris)
                uris.size > 1 -> {
                    sheaf.handoff.offer(uris)
                    jumpToTool = ToolId.MERGE
                }
                else -> open(uris.first())
            }
            return
        }

        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> intent.parcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        }
        if (uri != null) {
            if (isPdf(uri, intent?.type)) open(uri) else openAny(listOf(uri))
        }
    }

    /**
     * Whether an incoming Uri is a PDF, by the type the sender declared or the provider reports.
     *
     * The declared type comes first because a sender knows what it meant to share; the
     * provider's answer is the fallback, and a name ending in .pdf the last resort for the
     * file managers that report neither.
     */
    private fun isPdf(uri: Uri, declared: String?): Boolean {
        val type = declared?.takeIf { it != "*/*" } ?: runCatching { contentResolver.getType(uri) }.getOrNull()
        if (type != null) return type == Exporter.PDF_MIME
        return uri.lastPathSegment.orEmpty().endsWith(".pdf", ignoreCase = true)
    }

    /** Hands files that are not a PDF to Open a file, which suggests the right tool for them. */
    private fun openAny(uris: List<Uri>) {
        sheaf.handoff.offerToOpen(uris)
        openAnyTicket += 1
    }

    private fun open(uri: Uri) {
        openTicket += 1
        viewerViewModelOpen(uri)
    }

    /**
     * The viewer's model is created by the Compose tree, so the activity reaches it through
     * the same ViewModelStore rather than holding its own reference.
     */
    private fun viewerViewModelOpen(uri: Uri) {
        val provider = androidx.lifecycle.ViewModelProvider(this)
        provider[com.layerbit.sheaf.ui.viewer.ViewerViewModel::class.java].open(uri)
    }

    companion object {
        private const val SHORTCUT_SCHEME = "sheaf"

        /** The sheaf://tool/ path that opens the document picker rather than a tool. */
        const val PICK_DOCUMENT = "PICK"
    }
}

/** API 33 changed these to typed overloads and deprecated the old ones. */
private inline fun <reified T : android.os.Parcelable> Intent.parcelableExtra(name: String): T? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(name, T::class.java)
    } else {
        @Suppress("DEPRECATION") getParcelableExtra(name) as? T
    }

private inline fun <reified T : android.os.Parcelable> Intent.parcelableArrayListExtra(name: String): ArrayList<T>? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableArrayListExtra(name, T::class.java)
    } else {
        @Suppress("DEPRECATION") getParcelableArrayListExtra(name)
    }
