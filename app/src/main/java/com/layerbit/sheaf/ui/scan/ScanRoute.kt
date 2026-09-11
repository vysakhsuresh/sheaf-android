package com.layerbit.sheaf.ui.scan

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.layerbit.sheaf.jobs.JobState
import com.layerbit.sheaf.ui.components.Panel
import com.layerbit.sheaf.ui.theme.SheafColors
import java.util.concurrent.Executors

/**
 * The scanner, end to end: viewfinder, corner adjustment, page strip, make a PDF.
 *
 * CameraX rather than an ACTION_IMAGE_CAPTURE hand-off, for the same reason Abhyas made that
 * call. Photographing a page is the entry point to the whole feature, so it gets a viewfinder
 * built for it - not whatever camera app happens to be installed, returning whatever it
 * decides to return.
 */
@Composable
fun ScanRoute(
    modifier: Modifier = Modifier,
    viewModel: ScanViewModel = viewModel(),
    onFinished: () -> Unit
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
                onError = { /* surfaced through state below */ },
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

        state.error?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = SheafColors.Failed,
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
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var capture by remember { mutableStateOf<ImageCapture?>(null) }

    // One background thread for the capture callback. CameraX will otherwise use its own and
    // the shutdown below is what keeps it from outliving this screen.
    val executor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { executor.shutdown() } }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
                val providerFuture = ProcessCameraProvider.getInstance(ctx)
                providerFuture.addListener({
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val imageCapture = ImageCapture.Builder()
                        // Quality over latency: this is a document, and a slightly slower
                        // shutter is a better trade than a soft page nobody can read.
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .build()

                    runCatching {
                        provider.unbindAll()
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            imageCapture
                        )
                    }
                    capture = imageCapture
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            }
        )

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 28.dp)
                .size(72.dp)
                .background(SheafColors.Band, CircleShape)
                .border(4.dp, SheafColors.OnBand, CircleShape)
                .clickable {
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
                                onError(exception.message ?: "The photo could not be taken.")
                            }
                        }
                    )
                }
        )
    }
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

@Composable
private fun ScanResult(finished: JobState.Finished, onShare: () -> Unit, onDone: () -> Unit) {
    val produced = finished.produced.firstOrNull()
    Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
        Text(
            text = if (produced != null) "Your scan is ready" else "Nothing was produced",
            style = MaterialTheme.typography.titleLarge,
            color = SheafColors.Text
        )
        Text(
            text = produced?.file?.displayName
                ?: finished.failed.firstOrNull()?.reason
                ?: "Try scanning again.",
            style = MaterialTheme.typography.bodyMedium,
            color = SheafColors.Muted,
            textAlign = TextAlign.Start,
            modifier = Modifier.padding(top = 6.dp)
        )
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
        }
        Button(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = SheafColors.SurfaceDim,
                contentColor = SheafColors.Muted
            )
        ) { Text("Scan something else") }
    }
}
