package com.layerbit.sheaf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import android.net.Uri
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.layerbit.sheaf.ops.ToolId
import com.layerbit.sheaf.ui.about.AboutScreen
import com.layerbit.sheaf.ui.scan.ScanRoute
import com.layerbit.sheaf.ui.tools.ToolRoute
import com.layerbit.sheaf.ui.home.HomeScreen
import com.layerbit.sheaf.ui.theme.SheafColors
import com.layerbit.sheaf.ui.viewer.ViewerScreen
import com.layerbit.sheaf.ui.viewer.ViewerViewModel

object Routes {
    const val HOME = "home"
    const val VIEWER = "viewer"
    const val ABOUT = "about"

    /**
     * The tool's enum name is the argument, so a route survives a reordering of ToolId. The
     * optional uri is how the viewer hands the document it has open straight to a tool,
     * which is what stops "open a PDF" being a dead end that only shows you pages.
     */
    const val TOOL = "tool/{tool}?uri={uri}"

    fun tool(tool: ToolId, uri: String? = null): String {
        val base = "tool/${tool.name}"
        // Encoded because a content:// Uri is full of characters the route parser treats as
        // structure - a raw one silently truncates at the first ? or #.
        return if (uri == null) base else "$base?uri=" + Uri.encode(uri)
    }
}

/**
 * Navigation and the one shared viewer view model.
 *
 * The viewer's model is scoped to the activity rather than to its route, because a document
 * opened from a share-sheet intent is loaded before any navigation happens - the intent
 * arrives at MainActivity, the model takes it, and the viewer route then finds it already
 * open rather than reloading from a Uri it would have to carry through the back stack.
 */
@Composable
fun SheafApp(
    viewerViewModel: ViewerViewModel = viewModel(),
    recentsFlow: kotlinx.coroutines.flow.Flow<List<com.layerbit.sheaf.data.db.RecentEntity>>,
    /**
     * Increments each time MainActivity starts opening a document - from the picker, or
     * from a VIEW/SEND intent that arrived while the app was already running. A counter
     * rather than a Uri, so opening the same document twice still navigates.
     */
    openTicket: Int,
    onPickDocument: () -> Unit,
    onOpenRecentUri: (String) -> Unit,
    onForgetRecent: (String) -> Unit
) {
    val navController = rememberNavController()
    val recents by recentsFlow.collectAsState(initial = emptyList())
    val viewerState by viewerViewModel.state.collectAsState()
    val reading by viewerViewModel.reading.collectAsState()

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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SheafColors.Background)
            .windowInsetsPadding(WindowInsets.systemBars)
    ) {
        NavHost(navController = navController, startDestination = Routes.HOME) {
            composable(Routes.HOME) {
                HomeScreen(
                    recents = recents,
                    onOpenDocument = onPickDocument,
                    onOpenRecent = { recent -> onOpenRecentUri(recent.uri) },
                    onForgetRecent = { onForgetRecent(it.uri) },
                    onTool = { tool -> navController.navigate(Routes.tool(tool)) },
                    onAbout = { navController.navigate(Routes.ABOUT) }
                )
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
                    onClearSearch = viewerViewModel::clearSearch
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
                    }
                )
            ) { entry ->
                val name = entry.arguments?.getString("tool")
                val tool = ToolId.entries.firstOrNull { it.name == name }
                if (tool == ToolId.SCAN) {
                    ScanRoute(onFinished = { navController.popBackStack() })
                } else if (tool != null) {
                    ToolRoute(tool = tool, preloadUri = entry.arguments?.getString("uri"))
                } else {
                    // Only reachable from a stale deep link. Going back beats an error screen.
                    LaunchedEffect(Unit) { navController.popBackStack() }
                }
            }

            composable(Routes.ABOUT) {
                AboutScreen()
            }
        }
    }
}
