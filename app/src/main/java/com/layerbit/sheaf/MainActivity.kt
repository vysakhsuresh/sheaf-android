package com.layerbit.sheaf

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.layerbit.sheaf.files.Exporter
import com.layerbit.sheaf.ui.SheafApp
import com.layerbit.sheaf.ui.theme.SheafTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val sheaf: SheafApplication get() = application as SheafApplication

    /** Bumped on every open, which is what tells the UI to show the viewer. */
    private var openTicket by mutableIntStateOf(0)

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

        setContent {
            SheafTheme {
                SheafApp(
                    recentsFlow = sheaf.recents.observe(),
                    openTicket = openTicket,
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
     * Documents arriving from outside the app.
     *
     * Being in the share sheet and the "open with" list is how people find Sheaf, so these
     * paths matter as much as the picker does. SEND_MULTIPLE currently opens the first
     * document; the rest become the input list for a tool once P1 gives it somewhere to go.
     */
    private fun handleIntent(intent: Intent?) {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> intent.parcelableExtra(Intent.EXTRA_STREAM)
            Intent.ACTION_SEND_MULTIPLE ->
                intent.parcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.firstOrNull()
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
