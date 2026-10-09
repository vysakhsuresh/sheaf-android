package com.layerbit.sheaf.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.navigation.compose.currentBackStackEntryAsState
import com.layerbit.sheaf.R
import com.layerbit.sheaf.data.db.OutputEntity
import com.layerbit.sheaf.files.SheafFile
import com.layerbit.sheaf.ui.components.BottomDestination
import com.layerbit.sheaf.ui.components.SheafBottomBar
import com.layerbit.sheaf.ui.home.RecentScreen
import com.layerbit.sheaf.ui.library.LibraryScreen
import com.layerbit.sheaf.ui.library.RecentlyDeletedScreen
import com.layerbit.sheaf.ui.library.SessionsScreen
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.runtime.mutableStateOf
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

    /** Work finished, and work abandoned part-way. Both are bottom-bar destinations. */
    const val LIBRARY = "library"
    const val SESSIONS = "sessions"

    /** The bin. Reached from the Library, so it is not a fourth tab that is usually empty. */
    const val DELETED = "deleted"

    /**
     * Documents that have been opened here. Behind the board rather than on it, and separate
     * from the Library, which holds what Sheaf produced rather than what it was handed.
     */
    const val RECENT = "recent"

    /**
     * The screens the bottom bar appears on, and therefore the ones that may not assume they
     * own the bottom of the window. Everything else - the viewer, a tool, settings, the bin -
     * is somewhere you went on purpose and leave with the back arrow.
     */
    val TOP_LEVEL = setOf(HOME, SESSIONS, LIBRARY)

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

    // The Library and the bin. Observed, because a job finishing writes to this table from a
    // worker and the screen has to notice without being told.
    val library by sheaf.outputs.observeLibrary().collectAsState(initial = emptyList())
    val deleted by sheaf.outputs.observeDeleted().collectAsState(initial = emptyList())

    // One line of feedback for the Library's actions. The tool screens have their own; this
    // is the first thing at this level that needed to say anything back.
    val snackbar = remember { SnackbarHostState() }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            message = null
        }
    }

    // Which Library file the system save picker is currently collecting a destination for.
    // Held across the launcher round trip, because the result arrives with only a Uri.
    var saving by remember { mutableStateOf<SheafFile?>(null) }
    val saveDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val destination = result.data?.data
        val source = saving
        saving = null
        if (destination != null && source != null) {
            scope.launch {
                // writeTo throws rather than returning a reason, and it has already cleaned
                // up a partial file by the time it does.
                message = try {
                    sheaf.exporter.writeTo(destination, source)
                    // Recorded, so the Library can say this one is safe elsewhere and a trim
                    // knows it may take this before it takes anything unsaved.
                    sheaf.outputs.markSaved(source.file, System.currentTimeMillis())
                    "Saved."
                } catch (e: Exception) {
                    e.message ?: "That could not be saved."
                }
            }
        }
    }

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

    // Which screen is showing, so the bar can mark its tab and know whether to appear at all.
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val onTopLevel = currentRoute in Routes.TOP_LEVEL

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SheafColors.Background)
            .windowInsetsPadding(WindowInsets.systemBars)
    ) {
    Box(modifier = Modifier.weight(1f)) {
        // The four transitions, chosen rather than inherited.
        //
        // navigation-compose defaults all four to fadeIn/fadeOut over 700ms, and because
        // popEnter and popExit default to the same lambdas as enter and exit, going forward
        // and coming back looked identical - a long, directionless dissolve that said nothing
        // about where you were in the app. Going in slides from the right; coming back slides
        // out to the right, and the screen behind simply fades up.
        //
        // An eighth of the width, not the whole of it: every route draws its own SheafTopBar
        // at the same place, and a full-width slide makes the bar look as though it travels
        // across the screen rather than the content moving underneath it.
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            enterTransition = { slideInHorizontally(tween(260)) { it / 8 } + fadeIn(tween(200)) },
            exitTransition = { fadeOut(tween(160)) },
            popEnterTransition = { fadeIn(tween(200)) },
            popExitTransition = {
                slideOutHorizontally(tween(260)) { it / 8 } + fadeOut(tween(160))
            }
        ) {
            composable(Routes.HOME) {
                Column {
                    SheafTopBar(
                        title = "Sheaf",
                        actions = {
                            // Documents live behind this now rather than on the board. The
                            // board is a board of tools; a strip of files across the top and
                            // a list of them at the bottom made it two screens wearing one
                            // name, and the files were the half that kept growing.
                            if (recents.isNotEmpty()) {
                                TextButton(onClick = { navController.navigate(Routes.RECENT) }) {
                                    Text("Recent", color = SheafColors.Muted)
                                }
                            }
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
                    favourites = settings.favouriteTools,
                    renderPage = { index, width -> viewerViewModel.page(index, width) },
                    renderTile = { index, fullWidth, tile, outWidth, outHeight ->
                        viewerViewModel.tile(index, fullWidth, tile, outWidth, outHeight)
                    },
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
                        ScanRoute(
                            onFinished = { navController.popBackStack() },
                            onOpenResult = { file ->
                                viewerViewModel.openLocal(file)
                                navController.navigate(Routes.VIEWER) { launchSingleTop = true }
                            },
                            // The same route a tool result takes, so a scan chains onward on
                            // the same terms: a new destination rather than a reset of this
                            // one, and back walks the chain in reverse.
                            onChainTool = { next, file ->
                                navController.navigate(Routes.tool(next, file = file.file.path))
                            }
                        )
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
                            favourites = settings.favouriteTools,
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
                        recentCount = recents.size,
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

            composable(Routes.LIBRARY) {
                LibraryScreen(
                    outputs = library,
                    onOpen = { output ->
                        val file = output.asSheafFile()
                        if (file == null) {
                            message = "That file is no longer on this phone."
                        } else {
                            viewerViewModel.openLocal(file)
                            navController.navigate(Routes.VIEWER) { launchSingleTop = true }
                        }
                    },
                    onSave = { output ->
                        val file = output.asSheafFile()
                        if (file == null) {
                            message = "That file is no longer on this phone."
                        } else {
                            saving = file
                            saveDocument.launch(
                                sheaf.exporter.createDocumentIntent(file.displayName, file.mimeType)
                            )
                        }
                    },
                    onDelete = { output ->
                        scope.launch {
                            sheaf.outputs.delete(output.path, System.currentTimeMillis())
                            message = "Moved to Recently deleted."
                        }
                    },
                    onDeleted = { navController.navigate(Routes.DELETED) }
                )
            }

            composable(Routes.DELETED) {
                RecentlyDeletedScreen(
                    outputs = deleted,
                    onBack = { navController.popBackStack() },
                    onRestore = { output ->
                        scope.launch {
                            // Says so rather than failing quietly: the row outlives the file
                            // when a trim has had to give the bytes up, and a Put back that
                            // appears to work and does nothing is worse than a refusal.
                            message = if (sheaf.outputs.restore(output.path)) {
                                "Put back in the Library."
                            } else {
                                "That one is gone - Sheaf needed the space. " +
                                    "${toolTitleFor(output.toolId)} would make it again."
                            }
                        }
                    },
                    onForget = { output ->
                        scope.launch { sheaf.outputs.forget(output.path) }
                    }
                )
            }

            composable(Routes.RECENT) {
                RecentScreen(
                    recents = recents,
                    onBack = { navController.popBackStack() },
                    onOpen = { onOpenRecentUri(it.uri) },
                    onForget = { onForgetRecent(it.uri) }
                )
            }

            composable(Routes.SESSIONS) {
                SessionsScreen(onPickTool = { navController.navigate(Routes.HOME) })
            }
        }
    }

        SnackbarHost(
            snackbar,
            snackbar = { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = SheafColors.Surface,
                    contentColor = SheafColors.Text,
                    actionColor = SheafColors.BandBright
                )
            }
        )

        if (onTopLevel) {
            SheafBottomBar(
                destinations = BOTTOM_DESTINATIONS,
                currentRoute = currentRoute,
                onSelect = { destination ->
                    if (destination.route != currentRoute) {
                        navController.navigate(destination.route) {
                            // One entry per destination rather than a back stack that grows
                            // every time somebody taps between tabs. Back from any of them
                            // goes to the board, and from the board it leaves the app, which
                            // is what the system back button is expected to do.
                            popUpTo(Routes.HOME) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                }
            )
        }
    }
}

/** The three places things live. Order is left-to-right in the bar. */
private val BOTTOM_DESTINATIONS = listOf(
    BottomDestination(Routes.HOME, "Tools", R.drawable.ic_nav_tools),
    BottomDestination(Routes.SESSIONS, "In progress", R.drawable.ic_nav_sessions),
    BottomDestination(Routes.LIBRARY, "Library", R.drawable.ic_nav_library)
)

private fun toolTitleFor(toolId: String): String =
    ToolId.entries.firstOrNull { it.name == toolId }?.title ?: "The tool that made it"

/**
 * The Library's record as something the rest of the app can take.
 *
 * Null when the bytes have gone - a trim took them, or Android cleared the cache since the
 * screen was drawn. Every caller has to answer that case, which is the point of returning it
 * rather than handing back a SheafFile pointing at nothing.
 */
private fun OutputEntity.asSheafFile(): SheafFile? {
    val file = java.io.File(path)
    if (!file.isFile) return null
    return SheafFile(
        file = file,
        displayName = displayName,
        origin = SheafFile.Origin.Derived(toolId),
        mimeType = if (displayName.endsWith(".pdf", ignoreCase = true)) {
            SheafFile.MIME_PDF
        } else {
            "application/octet-stream"
        }
    )
}
