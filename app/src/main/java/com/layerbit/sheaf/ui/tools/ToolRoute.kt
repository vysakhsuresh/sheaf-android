package com.layerbit.sheaf.ui.tools

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.layerbit.sheaf.files.SheafFile
import com.layerbit.sheaf.ops.ToolId

/**
 * Owns the system pickers a tool needs, and joins the view model to the screen.
 *
 * The launchers live here rather than in MainActivity because they are per-tool: the file
 * picker's MIME filter comes from the tool, and the save picker needs to know which result it
 * is collecting a destination for. Keeping them beside the screen that triggers them is what
 * stops that becoming a pile of activity-level state.
 */
@Composable
fun ToolRoute(
    tool: ToolId,
    modifier: Modifier = Modifier,
    /** Opens a finished PDF in the viewer, so a result can be checked before it is saved. */
    onOpenResult: (SheafFile) -> Unit = {},
    /** A document handed over from the viewer, so the user does not pick the same file twice. */
    preloadUri: String? = null,
    viewModel: ToolViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val jobState by viewModel.jobState.collectAsState()
    val context = LocalContext.current

    val snackbar = remember { SnackbarHostState() }
    var message by remember { mutableStateOf<String?>(null) }

    /** Which result the save picker is currently collecting a destination for. */
    var pendingSave by remember { mutableStateOf<SheafFile?>(null) }

    LaunchedEffect(tool, preloadUri) {
        viewModel.start(tool)
        if (preloadUri != null) viewModel.addDocuments(listOf(Uri.parse(preloadUri)))
    }

    val pickFiles = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        // Used even for single-input tools; the view model keeps only the last one. One
        // contract instead of two, and someone who multi-selects out of habit on a
        // single-file tool gets a sensible result rather than nothing at all.
        viewModel.addDocuments(uris)
    }

    val saveFile = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val destination = result.data?.data
        val file = pendingSave
        pendingSave = null
        if (result.resultCode == Activity.RESULT_OK && destination != null && file != null) {
            viewModel.writeTo(destination, file) { error ->
                // A save that silently does nothing is worse than one that says why, and the
                // reasons here are real: a removed card, a revoked grant, a full disk.
                message = error ?: "Saved ${file.displayName}"
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        ToolScreen(
            tool = tool,
            state = state,
            jobState = jobState,
            onAddFiles = { pickFiles.launch(tool.mimeFilter) },
            onRemoveFile = viewModel::removeDocument,
            onMoveFile = viewModel::moveDocument,
            onSetPassword = viewModel::setPasswordFor,
            onVerifyPassword = viewModel::verifyPassword,
            onConfigChange = viewModel::updateConfig,
            onLoadPages = viewModel::loadPages,
            onTogglePage = viewModel::togglePageSelected,
            onRotateSelected = viewModel::rotateSelected,
            onDeleteSelected = viewModel::deleteSelectedPages,
            onMovePage = viewModel::movePage,
            onSigned = viewModel::setSignature,
            makeSignatureFile = viewModel::newSignatureFile,
            onRedactPage = viewModel::setRedactPage,
            onAddRedaction = viewModel::addRedaction,
            onClearRedactions = viewModel::clearRedactions,
            onRun = viewModel::run,
            onCancel = viewModel::cancel,
            onSave = { file ->
                pendingSave = file
                saveFile.launch(viewModel.exportIntent(file))
            },
            onShare = { files -> context.startActivity(viewModel.shareIntent(files)) },
            onOpenResult = onOpenResult,
            onLoadPreview = viewModel::loadPreview,
            onCopy = { text ->
                viewModel.copyToClipboard(text)
                message = "Copied"
            },
            onDone = viewModel::acknowledgeResult
        )

        SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter))
    }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            message = null
        }
    }
}
