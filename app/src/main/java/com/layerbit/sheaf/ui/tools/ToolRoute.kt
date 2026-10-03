package com.layerbit.sheaf.ui.tools

import android.app.Activity
import android.content.Intent
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
import com.layerbit.sheaf.jobs.JobState
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
    /** Sends a finished result on to the next tool in a chain. */
    onChainTool: (ToolId, SheafFile) -> Unit = { _, _ -> },
    /** A document handed over from the viewer, so the user does not pick the same file twice. */
    preloadUri: String? = null,
    /** A finished result handed on by another tool: already Sheaf's own file, not a Uri. */
    preloadFile: String? = null,
    /** Settings: show the finished document without waiting to be asked. */
    openResultWhenDone: Boolean = false,
    viewModel: ToolViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val jobState by viewModel.jobState.collectAsState()
    val context = LocalContext.current

    val snackbar = remember { SnackbarHostState() }
    var message by remember { mutableStateOf<String?>(null) }

    /** Which result the save picker is currently collecting a destination for. */
    var pendingSave by remember { mutableStateOf<SheafFile?>(null) }

    /** Results waiting for a folder, when one has not been granted yet. */
    var pendingBatch by remember { mutableStateOf<List<SheafFile>>(emptyList()) }

    LaunchedEffect(tool, preloadUri, preloadFile) {
        viewModel.start(tool)
        if (preloadUri != null) viewModel.addDocuments(listOf(Uri.parse(preloadUri)))
        if (preloadFile != null) viewModel.addLocalDocument(preloadFile)
    }

    /** Guards against reopening the same finished job every time this screen recomposes. */
    var openedResultOf by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(jobState, openResultWhenDone) {
        val finished = jobState as? JobState.Finished ?: return@LaunchedEffect
        if (!openResultWhenDone) return@LaunchedEffect
        // One result only. A batch that produced forty files has no single document to show,
        // and opening the first of forty would be a guess at which one was meant.
        val single = finished.produced.singleOrNull()?.file?.takeIf { it.isPdf } ?: return@LaunchedEffect
        if (openedResultOf == single.file.path) return@LaunchedEffect
        openedResultOf = single.file.path
        onOpenResult(single)
    }

    val pickFiles = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        // Used even for single-input tools; the view model keeps only the last one. One
        // contract instead of two, and someone who multi-selects out of habit on a
        // single-file tool gets a sensible result rather than nothing at all.
        viewModel.addDocuments(uris)
    }

    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val folder = result.data?.data
        val waiting = pendingBatch
        pendingBatch = emptyList()
        if (result.resultCode == Activity.RESULT_OK && folder != null) {
            // Held across restarts, which is the only reason "do not ask again" can work.
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    folder,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            viewModel.rememberFolder(folder)
            if (waiting.isNotEmpty()) {
                viewModel.writeInto(folder, waiting) { outcome -> message = outcome }
            }
        }
    }

    /**
     * Saves straight into the chosen folder, or asks for one first.
     *
     * The ask happens once per folder rather than once per file, which is the difference
     * between saving a forty-page export and giving up on it.
     */
    fun saveInto(files: List<SheafFile>) {
        val folder = viewModel.savedFolder()
        if (folder == null) {
            pendingBatch = files
            pickFolder.launch(viewModel.folderIntent())
        } else {
            viewModel.writeInto(folder, files) { outcome -> message = outcome }
        }
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
        } else if (result.resultCode == Activity.RESULT_OK && destination != null) {
            // A destination came back and the result it was asked for did not: pendingSave is
            // remembered, not saved, so a trip through the picker that outlived this screen
            // returns with nowhere to read from. ACTION_CREATE_DOCUMENT created the file the
            // moment the user named it, and that empty file stays whatever is said here - so
            // what the message buys is the user knowing it is not their result, and saving
            // again over it instead of keeping a document that will not open.
            message = "That save was interrupted. Tap Save again."
        }
    }

    /** The document list this composition drew, which is the one the rows below count against. */
    val documents = state.documents

    Box(modifier = modifier.fillMaxSize()) {
        ToolScreen(
            tool = tool,
            state = state,
            jobState = jobState,
            onAddFiles = { pickFiles.launch(tool.mimeFilter) },
            // The screen hands back the row's index, and it means the row of the list this
            // composition drew. Resolving it here against that same list, rather than letting
            // the view model look it up in whatever the state holds by then, is what keeps a
            // second tap arriving before the next frame from removing the following document.
            onRemoveFile = { index -> documents.getOrNull(index)?.let(viewModel::removeDocument) },
            onMoveFile = viewModel::moveDocument,
            onSetPassword = viewModel::setPasswordFor,
            onVerifyPassword = viewModel::verifyPassword,
            onConfigChange = viewModel::updateConfig,
            onLoadPages = viewModel::loadPages,
            onTogglePage = viewModel::togglePageSelected,
            onSelectAllPages = viewModel::selectAllPages,
            onClearPageSelection = viewModel::clearPageSelection,
            onRotateSelected = viewModel::rotateSelected,
            onDeleteSelected = viewModel::deleteSelectedPages,
            onMovePage = viewModel::movePage,
            onSigned = viewModel::setSignature,
            makeSignatureFile = viewModel::newSignatureFile,
            onLoadSignatures = viewModel::loadSavedSignatures,
            onKeepSignature = viewModel::keepSignature,
            onUseSignature = viewModel::useSavedSignature,
            onForgetSignature = viewModel::forgetSignature,
            onUndoPageEdit = viewModel::undoPageEdit,
            onRedactPage = viewModel::setRedactPage,
            onMarkupPage = viewModel::setMarkupPage,
            onHighlight = viewModel::addHighlight,
            onInk = viewModel::addInk,
            onUndoMark = viewModel::undoMark,
            onClearMarks = viewModel::clearMarks,
            onAddRedaction = viewModel::addRedaction,
            onClearRedactions = viewModel::clearRedactions,
            onRun = viewModel::run,
            onCancel = viewModel::cancel,
            onSave = { file ->
                if (viewModel.askWhereToSave) {
                    pendingSave = file
                    saveFile.launch(viewModel.exportIntent(file))
                } else {
                    saveInto(listOf(file))
                }
            },
            onSaveAll = { files -> saveInto(files) },
            onShare = { files -> context.startActivity(viewModel.shareIntent(files)) },
            onOpenResult = onOpenResult,
            onChainTool = onChainTool,
            onLoadPreview = viewModel::loadPreview,
            onLoadPreviewPage = viewModel::loadPreviewPage,
            onLoadForm = viewModel::loadFormFields,
            onFormValue = viewModel::setFormValue,
            onCheckSize = viewModel::checkCompressedSize,
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
