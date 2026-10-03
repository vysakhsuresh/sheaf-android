package com.layerbit.sheaf

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.layerbit.sheaf.files.Exporter
import com.layerbit.sheaf.ops.ToolId
import com.layerbit.sheaf.ui.SheafApp
import com.layerbit.sheaf.ui.theme.SheafTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val sheaf: SheafApplication get() = application as SheafApplication

    /** Bumped on every open, which is what tells the UI to show the viewer. */
    private var openTicket by mutableIntStateOf(0)

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
                SheafApp(
                    recentsFlow = sheaf.recents.observe(),
                    bookmarkCountFlow = sheaf.bookmarks.observeCount(),
                    settings = settings,
                    onSettingsChange = { transform -> sheaf.settings.update(transform) },
                    openTicket = openTicket,
                    jumpToTool = jumpToTool,
                    onJumpHandled = { jumpToTool = null },
                    onPickDocument = { pickDocument.launch(arrayOf(Exporter.PDF_MIME)) },
                    onOpenRecentUri = { open(Uri.parse(it)) },
                    onForgetRecent = { uri -> lifecycleScope.launch { sheaf.recents.forget(uri) } }
                )
            }
        }

        handleIntent(intent)
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
            jumpToTool = ToolId.entries.firstOrNull { it.name == name }
            return
        }

        // Several documents at once go to Merge with all of them loaded, rather than opening
        // the first and silently dropping the rest - which is what sharing five PDFs to a
        // viewer used to do.
        if (intent?.action == Intent.ACTION_SEND_MULTIPLE) {
            val uris = intent.parcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            when {
                uris.size > 1 -> {
                    sheaf.handoff.offer(uris)
                    jumpToTool = ToolId.MERGE
                }
                uris.size == 1 -> open(uris.first())
            }
            return
        }

        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> intent.parcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        }
        if (uri != null) open(uri)
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

    private companion object {
        const val SHORTCUT_SCHEME = "sheaf"
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
