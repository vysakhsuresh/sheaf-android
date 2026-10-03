package com.layerbit.sheaf.ui.scan

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.ui.draw.clip
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.layerbit.sheaf.files.SheafFile
import com.layerbit.sheaf.jobs.JobState
import com.layerbit.sheaf.ops.ToolId
import com.layerbit.sheaf.ui.components.Panel
import com.layerbit.sheaf.ui.components.ProblemRow
import com.layerbit.sheaf.ui.theme.SheafColors
import com.layerbit.sheaf.ui.tools.ToolPickerSheet
import java.util.concurrent.Executors

/**
 * The scanner, end to end: viewfinder, corner adjustment, page strip, make a PDF.
 *
 * CameraX rather than an ACTION_IMAGE_CAPTURE hand-off, for the same reason Abhyas made that
 * call. Photographing a page is the entry point to the whole feature, so it gets a viewfinder
 * built for it - not whatever camera app happens to be installed, returning whatever it
 * decides to return.
 *
 * @param onOpenResult hands the finished PDF to the viewer.
 * @param onChainTool hands the finished PDF to another tool. A scan produces a document and
 *   the usual next thought is to do something to it - make it searchable, most often - so the
 *   result screen offers the same chain the tool screens do rather than ending the journey.
 */
@Composable
fun ScanRoute(
    modifier: Modifier = Modifier,
    viewModel: ScanViewModel = viewModel(),
    onFinished: () -> Unit,
    onOpenResult: (SheafFile) -> Unit = {},
    onChainTool: (ToolId, SheafFile) -> Unit = { _, _ -> }
) {
    val state by viewModel.state.collectAsState()
    val jobState by viewModel.jobState.collectAsState()
    val context = LocalContext.current

    var hasCamera by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val requestCamera = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCamera = granted }

    // Asked at the moment the scanner opens rather than at first launch. A permission prompt
    // before someone knows what it is for is how apps earn a denial they never come back from.
    LaunchedEffect(Unit) {
        if (!hasCamera) requestCamera.launch(Manifest.permission.CAMERA)
    }

    val finished = jobState as? JobState.Finished
    val editing = state.editing

    Column(modifier = modifier.fillMaxSize().background(SheafColors.Background)) {
        when {
            finished != null -> ScanResult(
                finished = finished,
                onShare = {
                    val files = finished.produced.map { it.file }
                    if (files.isNotEmpty()) context.startActivity(viewModel.shareIntent(files))
                },
                onOpen = onOpenResult,
                onChainTool = onChainTool,
                // Clears the result and goes back to the viewfinder without leaving the
                // scanner. The pages are inside the PDF by now, so there is nothing to keep.
                onScanAnother = { viewModel.acknowledgeResult() },
                onDone = {
                    viewModel.acknowledgeResult()
                    onFinished()
                }
            )

            editing != null -> PageEditor(
                editing = editing,
                onMoveCorner = viewModel::moveCorner,
                onFilter = viewModel::setFilter,
                onKeep = viewModel::keepPage,
                onDiscard = viewModel::discardPage,
                modifier = Modifier.weight(1f)
            )

            !hasCamera -> CameraDenied(
                onRetry = { requestCamera.launch(Manifest.permission.CAMERA) },
                modifier = Modifier.weight(1f)
            )

            else -> Viewfinder(
                onCaptured = viewModel::captured,
                captureTarget = viewModel::captureTarget,
                onError = viewModel::captureFailed,
                modifier = Modifier.weight(1f)
            )
        }

        if (finished == null && editing == null && state.pages.isNotEmpty()) {
            ScannedPages(
                pages = state.pages,
                busy = state.busy || jobState is JobState.Running,
                onRemove = viewModel::removePage,
                onMakePdf = viewModel::makePdf
            )
        }

        // A crash building the PDF reported here rather than in a branch of its own, so the
        // captured pages and the Make PDF button above stay on screen: the work of scanning
        // them survives, and the retry is the button that is already there. Without this a
        // failed build dropped the user back to a viewfinder that said nothing had happened.
        val problem = state.error ?: (jobState as? JobState.Crashed)?.reason
        problem?.let { message ->
            ProblemRow(
                name = null,
                reason = message,
                isError = true,
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}

@Composable
private fun Viewfinder(
    onCaptured: (java.io.File) -> Unit,
    captureTarget: () -> java.io.File,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val lifecycleOwner = LocalLifecycleOwner.current

    // Three states rather than one: nothing bound yet, bound and ready, or the bind refused.
    // A cold scanner start is routinely half a second to two seconds, and without the first
    // of those the screen is black with a shutter on it that does nothing when pressed.
    var capture by remember { mutableStateOf<ImageCapture?>(null) }
    var bindFailed by remember { mutableStateOf(false) }

    // Bumped by the retry below. The provider is bound inside AndroidView's factory, which
    // runs once per view, so asking for a second attempt means asking for a second view.
    var attempt by remember { mutableIntStateOf(0) }

    // One background thread for the capture callback. CameraX will otherwise use its own and
    // the shutdown below is what keeps it from outliving this screen.
    val executor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { executor.shutdown() } }

    if (bindFailed) {
        CameraUnavailable(
            onRetry = {
                bindFailed = false
                capture = null
                attempt++
            },
            modifier = modifier
        )
        return
    }

    val ready = capture != null

    Box(modifier = modifier.fillMaxSize()) {
        key(attempt) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val previewView = PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }
                    val providerFuture = ProcessCameraProvider.getInstance(ctx)
                    providerFuture.addListener({
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }
                        val imageCapture = ImageCapture.Builder()
                            // Quality over latency: this is a document, and a slightly slower
                            // shutter is a better trade than a soft page nobody can read.
                            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                            .build()

                        val bound = runCatching {
                            val provider = providerFuture.get()
                            provider.unbindAll()
                            provider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                imageCapture
                            )
                        }

                        // Attached only on success. An ImageCapture that was never bound to a
                        // camera does not announce itself here; it fails on the first press of
                        // the shutter as ERROR_INVALID_CAMERA, which reads to the user as a
                        // problem with the photograph rather than with the camera.
                        if (bound.isSuccess) capture = imageCapture else bindFailed = true
                    }, ContextCompat.getMainExecutor(ctx))
                    previewView
                }
            )
        }

        if (!ready) {
            Text(
                text = "Starting the camera...",
                style = MaterialTheme.typography.bodyMedium,
                color = SheafColors.Muted,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 28.dp)
                .size(72.dp)
                // Faded rather than removed while the camera binds, so the shutter keeps its
                // place and the screen does not rearrange itself a second after it opens.
                .alpha(if (ready) 1f else 0.4f)
                // Ahead of the background, because indication is bounded by the node's clip
                // and not by what was painted into it - without this the shutter flashed a
                // square ripple over a circular button. Panel and PageThumb already clip
                // before they background; this was drift from a rule the app follows.
                .clip(CircleShape)
                .background(SheafColors.Band)
                .border(4.dp, SheafColors.OnBand, CircleShape)
                .clickable(enabled = ready) {
                    val target = captureTarget()
                    capture?.takePicture(
                        ImageCapture.OutputFileOptions.Builder(target).build(),
                        executor,
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                onCaptured(target)
                            }

                            override fun onError(exception: ImageCaptureException) {
                                target.delete()
                                onError(captureFailureMessage(exception))
                            }
                        }
                    )
                }
        )
    }
}

/**
 * What to tell someone whose photograph did not happen.
 *
 * Not `exception.message`, which is CameraX's wording for CameraX's maintainers - "Failed to
 * write to file." and the like. The error code is the part worth reading, and it separates
 * the one case the user can act on, a full disk, from the ones where the only answer is to
 * press the shutter again.
 */
private fun captureFailureMessage(exception: ImageCaptureException): String =
    when (exception.imageCaptureError) {
        ImageCapture.ERROR_FILE_IO ->
            "The photo could not be saved. This phone may be out of storage."
        ImageCapture.ERROR_CAMERA_CLOSED, ImageCapture.ERROR_INVALID_CAMERA ->
            "The camera closed before the photo was taken. Try again."
        else -> "The photo could not be taken. Try again."
    }

@Composable
private fun CameraDenied(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Panel(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Sheaf needs the camera to scan a page",
                style = MaterialTheme.typography.titleMedium,
                color = SheafColors.Text
            )
            Text(
                text = "The photo is processed on this phone and never sent anywhere - Sheaf " +
                    "has no permission to use the internet at all.",
                style = MaterialTheme.typography.bodyMedium,
                color = SheafColors.Muted,
                modifier = Modifier.padding(top = 8.dp)
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(
                    containerColor = SheafColors.Band,
                    contentColor = SheafColors.OnBand
                )
            ) { Text("Allow the camera") }
        }
    }
}

/**
 * Permitted, and still no camera. In practice this is another app holding it, which is why
 * the message names that rather than leaving someone to conclude the phone is broken.
 */
@Composable
private fun CameraUnavailable(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Panel(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "The camera could not be started",
                style = MaterialTheme.typography.titleMedium,
                color = SheafColors.Text
            )
            Text(
                text = "Another app may be using it - close it and come back.",
                style = MaterialTheme.typography.bodyMedium,
                color = SheafColors.Muted,
                modifier = Modifier.padding(top = 8.dp)
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(
                    containerColor = SheafColors.Band,
                    contentColor = SheafColors.OnBand
                )
            ) { Text("Try again") }
        }
    }
}

@Composable
private fun ScanResult(
    finished: JobState.Finished,
    onShare: () -> Unit,
    onOpen: (SheafFile) -> Unit,
    onChainTool: (ToolId, SheafFile) -> Unit,
    onScanAnother: () -> Unit,
    onDone: () -> Unit
) {
    val produced = finished.produced.firstOrNull()?.file
    var chaining by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
        Text(
            text = if (produced != null) "Your scan is ready" else "Nothing was produced",
            style = MaterialTheme.typography.titleLarge,
            color = SheafColors.Text
        )
        if (produced != null) {
            Text(
                text = produced.displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = SheafColors.Muted,
                textAlign = TextAlign.Start,
                modifier = Modifier.padding(top = 6.dp)
            )
        } else {
            Spacer(Modifier.height(10.dp))
            ProblemRow(
                name = null,
                reason = finished.failed.firstOrNull()?.reason ?: "Try scanning again.",
                isError = true
            )
        }

        Spacer(Modifier.height(16.dp))
        if (produced != null) {
            Button(
                onClick = onShare,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SheafColors.Band,
                    contentColor = SheafColors.OnBand
                )
            ) { Text("Save or share it") }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { onOpen(produced) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SheafColors.SurfaceDim,
                    contentColor = SheafColors.Text
                )
            ) { Text("Open it") }
            Spacer(Modifier.height(8.dp))
            // The reason this screen was a dead end. A scan is far more often the start of
            // something - making it searchable, most of all - than the end of it, and until
            // this was here the only route onward was to save to Files and pick it up again.
            Button(
                onClick = { chaining = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SheafColors.SurfaceDim,
                    contentColor = SheafColors.Text
                )
            ) { Text("Use another tool on this") }
            Spacer(Modifier.height(16.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onDone,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SheafColors.SurfaceDim,
                    contentColor = SheafColors.Muted
                )
            ) { Text("Done") }
            OutlinedButton(onClick = onScanAnother) {
                Text("Scan another page", color = SheafColors.Muted)
            }
        }
    }

    if (chaining && produced != null) {
        ToolPickerSheet(
            heading = "Use another tool on this",
            documentName = produced.displayName,
            onDismiss = { chaining = false },
            onPick = { next ->
                chaining = false
                onChainTool(next, produced)
            }
        )
    }
}
