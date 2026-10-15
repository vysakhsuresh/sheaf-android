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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
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
import com.layerbit.sheaf.R
import com.layerbit.sheaf.ui.components.IconTile
import com.layerbit.sheaf.ui.components.Panel
import com.layerbit.sheaf.ui.components.Pill
import com.layerbit.sheaf.ui.components.PrimaryButton
import com.layerbit.sheaf.ui.components.SecondaryButton
import com.layerbit.sheaf.ui.components.pressScale
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
                pageCount = state.pages.size,
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
            Panel(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), tint = SheafColors.Failed, inset = 12.dp) {
                Text(text = message, style = MaterialTheme.typography.bodyMedium, color = SheafColors.Text)
            }
        }
    }
}

@Composable
private fun Viewfinder(
    onCaptured: (java.io.File) -> Unit,
    captureTarget: () -> java.io.File,
    onError: (String) -> Unit,
    pageCount: Int,
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

        // Corner guides: where the page should sit. A frame tells the hand what the words
        // underneath say, before the words are read.
        Canvas(modifier = Modifier.fillMaxSize().padding(28.dp)) {
            val arm = 34.dp.toPx()
            val stroke = 3.dp.toPx()
            val colour = Color.White.copy(alpha = 0.85f)
            val w = size.width
            val h = size.height
            val corners = listOf(
                Offset(0f, 0f) to Pair(Offset(arm, 0f), Offset(0f, arm)),
                Offset(w, 0f) to Pair(Offset(w - arm, 0f), Offset(w, arm)),
                Offset(0f, h) to Pair(Offset(arm, h), Offset(0f, h - arm)),
                Offset(w, h) to Pair(Offset(w - arm, h), Offset(w, h - arm))
            )
            for ((corner, arms) in corners) {
                drawLine(colour, corner, arms.first, stroke, StrokeCap.Round)
                drawLine(colour, corner, arms.second, stroke, StrokeCap.Round)
            }
        }

        // A dark scrim top and bottom, so the hint and the controls read over any page.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp)
                .align(Alignment.TopCenter)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent)))
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f))))
        )

        Row(
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Pill(if (pageCount == 0) "Fill the frame with the page" else "Page ${pageCount + 1}", Color.White, filled = false)
            if (pageCount > 0) Pill("$pageCount kept", SheafColors.BandBright, filled = true)
        }

        Column(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val interaction = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .pressScale(interaction, 0.9f)
                    .size(78.dp)
                    .border(4.dp, Color.White, CircleShape)
                    .padding(6.dp)
                    .background(SheafColors.Band, CircleShape)
                    .clickable(interactionSource = interaction, indication = null) {
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
            Text(
                "Tap to capture",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.padding(top = 10.dp)
            )
        }
    }
}

@Composable
private fun CameraDenied(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Panel(modifier = Modifier.fillMaxWidth(), tint = SheafColors.Band, inset = 22.dp) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                IconTile(iconRes = R.drawable.ic_tool_scan, accent = SheafColors.Band, filled = true, size = 56.dp)
                Text(
                    text = "Sheaf needs the camera to scan a page",
                    style = MaterialTheme.typography.titleMedium,
                    color = SheafColors.Text,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 14.dp)
                )
                Text(
                    text = "The photo is processed on this phone and never sent anywhere - Sheaf " +
                        "has no permission to use the internet at all.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SheafColors.Muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp)
                )
                PrimaryButton(text = "Allow the camera", onClick = onRetry, modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
            }
        }
    }
}

@Composable
private fun ScanResult(finished: JobState.Finished, onShare: () -> Unit, onDone: () -> Unit) {
    val produced = finished.produced.firstOrNull()
    val accent = if (produced != null) SheafColors.Done else SheafColors.Failed
    Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(44.dp).background(accent.copy(alpha = 0.18f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (produced != null) Icons.Filled.Check else Icons.Filled.Warning,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(22.dp)
                )
            }
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text(
                    text = if (produced != null) "Your scan is ready" else "Nothing was produced",
                    style = MaterialTheme.typography.headlineSmall,
                    color = SheafColors.Text
                )
                Text(
                    "Made on this phone. Nothing was uploaded.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SheafColors.Dim
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Panel(modifier = Modifier.fillMaxWidth(), tint = accent) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(iconRes = R.drawable.ic_tool_read, accent = accent, filled = true)
                Column(modifier = Modifier.padding(start = 12.dp)) {
                    Text(
                        text = produced?.file?.displayName
                            ?: finished.failed.firstOrNull()?.reason
                            ?: "Try scanning again.",
                        style = MaterialTheme.typography.titleMedium,
                        color = SheafColors.Text
                    )
                    produced?.detail?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = accent, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        if (produced != null) {
            PrimaryButton(text = "Save or share it", icon = Icons.Filled.Share, onClick = onShare, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
        }
        SecondaryButton(text = "Scan something else", onClick = onDone, modifier = Modifier.fillMaxWidth())
    }
}
