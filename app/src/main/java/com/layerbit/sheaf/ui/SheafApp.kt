package com.layerbit.sheaf.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.layerbit.sheaf.SheafApplication
import com.layerbit.sheaf.files.printDocument
import com.layerbit.sheaf.ops.ToolId
import com.layerbit.sheaf.prefs.Settings
import com.layerbit.sheaf.ui.about.AboutScreen
import com.layerbit.sheaf.ui.components.SheafTopBar
import com.layerbit.sheaf.ui.home.HomeScreen
import com.layerbit.sheaf.ui.scan.ScanRoute
import com.layerbit.sheaf.ui.settings.SettingsScreen
import com.layerbit.sheaf.ui.theme.SheafColors
import com.layerbit.sheaf.ui.tools.ToolRoute
import com.layerbit.sheaf.ui.viewer.ViewerScreen
import com.layerbit.sheaf.ui.viewer.ViewerState
import com.layerbit.sheaf.ui.viewer.ViewerViewModel
import kotlinx.coroutines.launch

object Routes {
    const val HOME = "home"
    const val VIEWER = "viewer"
    const val ABOUT = "about"
    const val SETTINGS = "settings"

    /**
     * The tool's enum name is the argument, so a route survives a reordering of ToolId. The
     * optional uri is how the viewer hands the document it has open straight to a tool,
     * which is what stops "open a PDF" being a dead end that only shows you pages.
     */
    const val TOOL = "tool/{tool}?uri={uri}&file={file}"

    /**
     * @param uri a document from outside the app, handed over by the viewer.
     * @param file a file Sheaf already made - a finished result being passed to the next
     *   tool. The two are separate because one has to be imported through the Storage Access
     *   Framework and the other is already in the workspace.
     */
    fun tool(tool: ToolId, uri: String? = null, file: String? = null): String {
        val base = "tool/${tool.name}"
        // Encoded because a content:// Uri and a path are both full of characters the route
        // parser treats as structure - a raw one silently truncates at the first ? or #.
        val arguments = buildList {
            if (uri != null) add("uri=" + Uri.encode(uri))
            if (file != null) add("file=" + Uri.encode(file))
        }
        return if (arguments.isEmpty()) base else base + "?" + arguments.joinToString("&")
    }
}

/**
 * Navigation and the one shared viewer view model.
 *
 * The viewer's model is scoped to the activity rather than to its route.
 *
 * A document opened from a share-sheet intent is loaded before any navigation happens: the
 * intent arrives at MainActivity and the model takes it. The viewer route then finds it
 * already open, rather than reloading from a Uri it would have to carry through the back
 * stack.
 */
@Composable
fun SheafApp(
    viewerViewModel: ViewerViewModel = viewModel(),
    recentsFlow: kotlinx.coroutines.flow.Flow<List<com.layerbit.sheaf.data.db.RecentEntity>>,
    bookmarkCountFlow: kotlinx.coroutines.flow.Flow<Int>,
    settings: Settings,
    onSettingsChange: ((Settings) -> Settings) -> Unit,
    /**
     * Increments each time MainActivity starts opening a document - from the picker, or
     * from a VIEW/SEND intent that arrived while the app was already running. A counter
     * rather than a Uri, so opening the same document twice still navigates.
     */
    openTicket: Int,
    /**
     * A tool to go straight to - from a launcher shortcut, or from a share of several files
     * that only a tool can take. Consumed once and then forgotten.
     */
    jumpToTool: ToolId? = null,
    onJumpHandled: () -> Unit = {},
    onPickDocument: () -> Unit,
    onOpenRecentUri: (String) -> Unit,
    onForgetRecent: (String) -> Unit
) {
    val navController = rememberNavController()
    val recents by recentsFlow.collectAsState(initial = emptyList())
    val bookmarkCount by bookmarkCountFlow.collectAsState(initial = 0)
    val viewerState by viewerViewModel.state.collectAsState()
    val reading by viewerViewModel.reading.collectAsState()

    val context = LocalContext.current
    val sheaf = context.applicationContext as SheafApplication
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // Recomputed on demand rather than observed: the number only moves when a job finishes or
    // the user clears it, and a file-system walk per frame would be an odd thing to pay for.
    var workspaceBytes by remember { mutableLongStateOf(0L) }

    // The ONLY place the viewer is navigated to. Opening a recent used to navigate here and
    // from its own callback as well, which pushed the viewer onto the back stack twice - the
    // first back press popped one copy and appeared to do nothing. launchSingleTop keeps that
    // from coming back if another caller ever navigates here too, and makes a document opened
    // while the viewer is already showing replace it rather than stack on it.
    LaunchedEffect(openTicket) {
        if (openTicket > 0) {
            navController.navigate(Routes.VIEWER) { launchSingleTop = true }
        }
    }

    // A launcher shortcut, or a share of several documents, lands on a tool rather than on
    // the home screen.
    LaunchedEffect(jumpToTool) {
        jumpToTool?.let { tool ->
            navController.navigate(Routes.tool(tool)) { launchSingleTop = true }
            onJumpHandled()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SheafColors.Background)
            .windowInsetsPadding(WindowInsets.systemBars)
    ) {
        NavHost(navController = navController, startDestination = Routes.HOME) {
            composable(Routes.HOME) {
                Column {
                    SheafTopBar(
                        title = "Sheaf",
                        actions = {
                            TextButton(onClick = { navController.navigate(Routes.SETTINGS) }) {
                                Text("Settings", color = SheafColors.Muted)
                            }
                            TextButton(onClick = { navController.navigate(Routes.ABOUT) }) {
                                Text("About", color = SheafColors.Muted)
                            }
                        }
                    )
                    HomeScreen(
                        recents = recents,
                        favourites = settings.favouriteTools,
                        onOpenDocument = onPickDocument,
                        onOpenRecent = { recent -> onOpenRecentUri(recent.uri) },
                        onForgetRecent = { onForgetRecent(it.uri) },
                        onTool = { tool -> navController.navigate(Routes.tool(tool)) },
                        onToggleFavourite = { tool -> sheaf.settings.toggleFavourite(tool.name) },
                        onAbout = { navController.navigate(Routes.ABOUT) }
                    )
                }
            }

            composable(Routes.VIEWER) {
                ViewerScreen(
                    state = viewerState,
                    reading = reading,
                    renderPage = { index, width -> viewerViewModel.page(index, width) },
                    onUseTool = { tool, uri -> navController.navigate(Routes.tool(tool, uri)) },
                    onBack = { navController.popBackStack() },
                    onToggleNight = viewerViewModel::toggleNightMode,
                    onSearch = viewerViewModel::search,
                    onClearSearch = viewerViewModel::clearSearch,
                    onStepHit = viewerViewModel::stepHit,
                    onJump = viewerViewModel::jumpTo,
                    onToggleBookmark = viewerViewModel::toggleBookmark,
                    onRemoveBookmark = viewerViewModel::removeBookmark,
                    onPageChanged = viewerViewModel::rememberPosition,
                    onPrint = {
                        // The print dialog is also every Android device's "save as PDF", which
                        // is why this is offered on a document that is already a PDF.
                        viewerViewModel.currentFile()?.let { file ->
                            printDocument(
                                context = context,
                                file = file.file,
                                jobName = file.displayName,
                                pageCount = (viewerState as? ViewerState.Ready)?.pageCount ?: 0
                            )
                        }
                    },
                    onShare = {
                        viewerViewModel.currentFile()?.let { file ->
                            runCatching { context.startActivity(sheaf.exporter.shareIntent(file)) }
                        }
                    },
                    keepScreenOn = settings.keepScreenOn,
                    showPageBadge = settings.showPageBadge
                )
            }

            composable(
                route = Routes.TOOL,
                arguments = listOf(
                    navArgument("tool") { type = NavType.StringType },
                    navArgument("uri") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                    navArgument("file") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                )
            ) { entry ->
                val name = entry.arguments?.getString("tool")
                val tool = ToolId.entries.firstOrNull { it.name == name }
                if (tool == ToolId.SCAN) {
                    Column {
                        SheafTopBar(
                            title = ToolId.SCAN.title,
                            subtitle = ToolId.SCAN.summary,
                            onBack = { navController.popBackStack() }
                        )
                        ScanRoute(onFinished = { navController.popBackStack() })
                    }
                } else if (tool != null) {
                    Column {
                        SheafTopBar(
                            title = tool.title,
                            subtitle = tool.summary,
                            onBack = { navController.popBackStack() }
                        )
                        ToolRoute(
                            tool = tool,
                            preloadUri = entry.arguments?.getString("uri"),
                            preloadFile = entry.arguments?.getString("file"),
                            openResultWhenDone = settings.openResultWhenDone,
                            onOpenResult = { file ->
                                // Opened straight from the result list so a finished document
                                // can be checked before anyone decides to save it.
                                viewerViewModel.openLocal(file)
                                navController.navigate(Routes.VIEWER) { launchSingleTop = true }
                            },
                            onChainTool = { next, file ->
                                // A new route rather than a reset of this one, so back goes
                                // to the result the user came from and the chain is walkable
                                // in both directions.
                                navController.navigate(Routes.tool(next, file = file.file.path))
                            }
                        )
                    }
                } else {
                    // Only reachable from a stale deep link. Going back beats an error screen.
                    LaunchedEffect(Unit) { navController.popBackStack() }
                }
            }

            composable(Routes.SETTINGS) {
                LaunchedEffect(Unit) { workspaceBytes = sheaf.workspace.sizeBytes() }
                Column {
                    SheafTopBar(title = "Settings", onBack = { navController.popBackStack() })
                    SettingsScreen(
                        settings = settings,
                        workspaceBytes = workspaceBytes,
                        bookmarkCount = bookmarkCount,
                        onChange = onSettingsChange,
                        onClearWorkspace = {
                            sheaf.workspace.clear()
                            workspaceBytes = sheaf.workspace.sizeBytes()
                        },
                        onClearRecents = { scope.launch { sheaf.recents.clear() } },
                        onClearBookmarks = { scope.launch { sheaf.bookmarks.clear() } }
                    )
                }
            }

            composable(Routes.ABOUT) {
                Column {
                    SheafTopBar(title = "About Sheaf", onBack = { navController.popBackStack() })
                    AboutScreen()
                }
            }
        }
    }
}
