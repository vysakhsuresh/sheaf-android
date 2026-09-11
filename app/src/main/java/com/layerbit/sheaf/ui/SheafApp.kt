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
import com.layerbit.sheaf.ui.about.AboutScreen
import com.layerbit.sheaf.ui.home.HomeScreen
import com.layerbit.sheaf.ui.theme.SheafColors
import com.layerbit.sheaf.ui.viewer.ViewerScreen
import com.layerbit.sheaf.ui.viewer.ViewerViewModel

object Routes {
    const val HOME = "home"
    const val VIEWER = "viewer"
    const val ABOUT = "about"
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

    LaunchedEffect(openTicket) {
        if (openTicket > 0) navController.navigate(Routes.VIEWER)
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
                    onOpenRecent = { recent ->
                        onOpenRecentUri(recent.uri)
                        navController.navigate(Routes.VIEWER)
                    },
                    onForgetRecent = { onForgetRecent(it.uri) },
                    onAbout = { navController.navigate(Routes.ABOUT) }
                )
            }

            composable(Routes.VIEWER) {
                ViewerScreen(
                    state = viewerState,
                    renderPage = { index, width -> viewerViewModel.page(index, width) }
                )
            }

            composable(Routes.ABOUT) {
                AboutScreen()
            }
        }
    }
}
