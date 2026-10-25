package com.layerbit.sheaf.ui.scan

import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.layerbit.sheaf.scan.AutoCaptureTracker
import com.layerbit.sheaf.scan.LumaSampler
import com.layerbit.sheaf.scan.PageDetector
import com.layerbit.sheaf.scan.Quad
import com.layerbit.sheaf.scan.QuadGeometry
import com.layerbit.sheaf.ui.components.Pill
import com.layerbit.sheaf.ui.components.pressScale
import com.layerbit.sheaf.ui.theme.SheafColors
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.math.min
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.layerbit.sheaf.R
import com.layerbit.sheaf.text.Strings

/**
 * What the analyser saw in one viewfinder frame.
 *
 * @param quad the page outline, upright and normalised to the visible frame, or null.
 * @param frameWidth the visible frame's upright width, in camera pixels. Only the ratio of
 *   width to height matters, for mapping the outline onto the screen.
 */
class DetectionFrame(
    val quad: Quad?,
    val frameWidth: Int,
    val frameHeight: Int,
    val signature: IntArray,
    val autoProgress: Float,
    val fire: Boolean
)

/**
 * Runs page detection on each preview frame, on the analysis thread.
 *
 * Only the brightness plane is read - the camera's YUV frames carry it as the first plane, so
 * there is no colour conversion at all - and it is copied into one buffer this class keeps,
 * then averaged down to about 320 pixels by [LumaSampler], also into a kept buffer. Nothing
 * frame-sized is allocated per frame, and no bitmap is ever made. With
 * STRATEGY_KEEP_ONLY_LATEST a slow frame simply means the next one is skipped, never a queue.
 *
 * The auto-capture decision is made here too, beside the detection it depends on, so the main
 * thread only learns "now" rather than replaying every frame.
 */
class PageAnalyzer(
    private val mainExecutor: Executor,
    /** Held by the screen, not the analyser, so it remembers the last capture across viewfinders. */
    private val tracker: AutoCaptureTracker,
    private val onFrame: (DetectionFrame) -> Unit
) : ImageAnalysis.Analyzer {

    private val detector = PageDetector()
    private val sampler = LumaSampler(maxSide = 320)
    private var plane = ByteArray(0)
    private val signature = IntArray(64)

    /** Off while a capture is in flight, or the person has turned auto-capture off. */
    @Volatile var autoEnabled: Boolean = false

    override fun analyze(image: ImageProxy) {
        try {
            val y = image.planes[0]
            val buffer = y.buffer
            buffer.rewind()
            val size = buffer.remaining()
            if (plane.size < size) plane = ByteArray(size)
            buffer.get(plane, 0, size)

            // The crop rectangle is the part of the sensor the viewfinder actually shows, set
            // from the PreviewView's viewport. Detecting only inside it means the outline is
            // in the same frame of reference as what is on screen and what is captured.
            val crop = image.cropRect
            sampler.sample(plane, y.rowStride, y.pixelStride, crop.left, crop.top, crop.width(), crop.height())
            val found = detector.detect(sampler.pixels, sampler.width, sampler.height)
            PageDetector.signature(sampler.pixels, sampler.width, sampler.height, signature)

            val rotation = image.imageInfo.rotationDegrees
            val quad = found?.let { QuadGeometry.rotateUpright(it, rotation) }
            val sideways = rotation % 180 != 0
            val width = if (sideways) crop.height() else crop.width()
            val height = if (sideways) crop.width() else crop.height()

            val status = synchronized(tracker) {
                if (autoEnabled) tracker.update(quad, signature, System.currentTimeMillis())
                else AutoCaptureTracker.Status(0f, false)
            }
            val frame = DetectionFrame(quad, width, height, signature.copyOf(), status.progress, status.fire)
            mainExecutor.execute { onFrame(frame) }
        } catch (_: RuntimeException) {
            // A frame that cannot be read - the camera closing mid-frame, usually - is skipped.
        } finally {
            image.close()
        }
    }

    /** A picture was taken by hand: do not take the same page again a moment later. */
    fun capturedByHand(frame: DetectionFrame?) {
        synchronized(tracker) { tracker.captured(frame?.quad, frame?.signature, System.currentTimeMillis()) }
    }
}

/**
 * The live camera with the detected page drawn over it, a shutter, and the controls for
 * auto-capture and the way of working.
 */
@Composable
fun Viewfinder(
    mode: ScanMode,
    onMode: (ScanMode) -> Unit,
    autoCapture: Boolean,
    onToggleAuto: () -> Unit,
    /** False while the scanner is busy with the last photo, so auto-capture holds off. */
    ready: Boolean,
    pageCount: Int,
    processing: Int,
    waitingForBack: Boolean,
    captureTarget: () -> File,
    onCaptured: (File, Quad?) -> Unit,
    onCaptureFailed: (String) -> Unit,
    tracker: AutoCaptureTracker,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var capture by remember { mutableStateOf<ImageCapture?>(null) }
    var detection by remember { mutableStateOf<DetectionFrame?>(null) }
    var capturing by remember { mutableStateOf(false) }

    val captureTargetNow by rememberUpdatedState(captureTarget)
    val onCapturedNow by rememberUpdatedState(onCaptured)
    val onCaptureFailedNow by rememberUpdatedState(onCaptureFailed)

    // One background thread for the capture callback and one for analysis, so a slow frame
    // never delays a photo being saved. The shutdown below keeps them from outliving the screen.
    val captureExecutor = remember { Executors.newSingleThreadExecutor() }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }

    val takePicture = remember {
        { frame: DetectionFrame? ->
            val imageCapture = capture
            if (imageCapture != null && !capturing) {
                capturing = true
                val target = captureTargetNow()
                imageCapture.takePicture(
                    ImageCapture.OutputFileOptions.Builder(target).build(),
                    captureExecutor,
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                            mainExecutor.execute {
                                capturing = false
                                onCapturedNow(target, frame?.quad)
                            }
                        }

                        override fun onError(exception: ImageCaptureException) {
                            target.delete()
                            mainExecutor.execute {
                                capturing = false
                                onCaptureFailedNow(Strings.get(R.string.reading_scan_photo_failed))
                            }
                        }
                    }
                )
            }
        }
    }
    val takePictureNow by rememberUpdatedState(takePicture)

    val analyzer = remember {
        PageAnalyzer(mainExecutor, tracker) { frame ->
            detection = frame
            if (frame.fire) takePictureNow(frame)
        }
    }
    LaunchedEffect(autoCapture, ready, capturing) {
        analyzer.autoEnabled = autoCapture && ready && !capturing
    }

    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            // Unbinding here, not only on the next bind, is what stops the camera and the
            // analyser running on behind the corner editor.
            runCatching { provider?.unbindAll() }
            captureExecutor.shutdown()
            analysisExecutor.shutdown()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
                val providerFuture = ProcessCameraProvider.getInstance(ctx)
                providerFuture.addListener({
                    // Posted, so the view has been laid out and can say what part of the
                    // sensor it shows.
                    previewView.post {
                        val cameraProvider = providerFuture.get()
                        provider = cameraProvider
                        // All three at 4:3, the sensor's own shape, so what is analysed, what is
                        // shown and what is captured are the same field of view.
                        val fourByThree = ResolutionSelector.Builder()
                            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                            .build()
                        val preview = Preview.Builder().setResolutionSelector(fourByThree).build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }
                        val imageCapture = ImageCapture.Builder()
                            // Quality over latency: this is a document, and a slightly slower
                            // shutter is a better trade than a soft page nobody can read.
                            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                            .setResolutionSelector(fourByThree)
                            .build()
                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .setResolutionSelector(
                                ResolutionSelector.Builder()
                                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                                    // Small on purpose: the detector shrinks it to 320 anyway,
                                    // and a smaller frame is less to copy.
                                    .setResolutionStrategy(
                                        ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                                    )
                                    .build()
                            )
                            .build()
                            .also { it.setAnalyzer(analysisExecutor, analyzer) }

                        runCatching {
                            cameraProvider.unbindAll()
                            // The viewport crops every use case to what the viewfinder shows,
                            // so the outline found in an analysis frame lands on the same spot
                            // of the photo. Without one (the view not measured yet) all three
                            // still share the 4:3 frame, which is nearly as good.
                            val viewPort = previewView.viewPort
                            if (viewPort != null) {
                                val group = UseCaseGroup.Builder()
                                    .setViewPort(viewPort)
                                    .addUseCase(preview)
                                    .addUseCase(imageCapture)
                                    .addUseCase(analysis)
                                    .build()
                                cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, group)
                            } else {
                                cameraProvider.bindToLifecycle(
                                    lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture, analysis
                                )
                            }
                            capture = imageCapture
                        }.onFailure {
                            onCaptureFailedNow(Strings.get(R.string.reading_scan_camera_failed))
                        }
                    }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            }
        )

        // Only whether a page is in view reaches composition, so the hints change when it
        // comes and goes rather than recomposing on every frame.
        val pageInView by remember { derivedStateOf { detection?.quad != null } }

        // The page outline, drawn where the page is on screen. Reading the detection inside the
        // draw block means a new frame redraws this canvas and recomposes nothing.
        Canvas(modifier = Modifier.fillMaxSize()) {
            val current = detection
            val q = current?.quad
            if (current == null || q == null) {
                drawCornerGuides()
                return@Canvas
            }
            val p = QuadGeometry.fillCenter(q, current.frameWidth.toFloat(), current.frameHeight.toFloat(), size.width, size.height)
            val path = Path().apply {
                moveTo(p[0], p[1]); lineTo(p[2], p[3]); lineTo(p[4], p[5]); lineTo(p[6], p[7]); close()
            }
            drawPath(path, SheafColors.Band.copy(alpha = 0.22f))
            drawPath(path, SheafColors.BandBright, style = Stroke(width = 3.dp.toPx(), join = StrokeJoin.Round))
            for (i in 0 until 4) drawCircle(Color.White, radius = 5.dp.toPx(), center = Offset(p[2 * i], p[2 * i + 1]))

            // A ring that closes in on the middle of the page as the hold completes, so it
            // is clear the shutter is about to go and that keeping still is what does it.
            if (current.autoProgress > 0f) {
                val centre = Offset((p[0] + p[2] + p[4] + p[6]) / 4f, (p[1] + p[3] + p[5] + p[7]) / 4f)
                val full = min(size.width, size.height) * 0.16f
                val radius = full * (1f - current.autoProgress) + 6.dp.toPx()
                drawCircle(Color.White.copy(alpha = 0.9f), radius = radius, center = centre, style = Stroke(4.dp.toPx()))
                drawArc(
                    color = SheafColors.BandBright,
                    startAngle = -90f,
                    sweepAngle = 360f * current.autoProgress,
                    useCenter = false,
                    topLeft = centre - Offset(full + 6.dp.toPx(), full + 6.dp.toPx()),
                    size = androidx.compose.ui.geometry.Size((full + 6.dp.toPx()) * 2, (full + 6.dp.toPx()) * 2),
                    style = Stroke(3.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }

        // A dark scrim top and bottom, so the hint and the controls read over any page.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(132.dp)
                .align(Alignment.TopCenter)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)))
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(170.dp)
                .align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f))))
        )

        Column(
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp, start = 12.dp, end = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ScanMode.entries.forEach { option ->
                    OverlayChip(option.label, active = option == mode, onClick = { onMode(option) })
                }
                OverlayChip(
                    text = stringResource(if (autoCapture) R.string.reading_scan_auto_on else R.string.reading_scan_auto_off),
                    active = autoCapture,
                    onClick = onToggleAuto,
                    description = stringResource(R.string.reading_scan_auto_description)
                )
            }
            Row(
                modifier = Modifier.padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val hint = when {
                    mode == ScanMode.ID_CARD && waitingForBack -> R.string.reading_scan_card_back
                    mode == ScanMode.ID_CARD -> R.string.reading_scan_card_front
                    !pageInView -> R.string.reading_scan_fill_frame
                    autoCapture && ready -> R.string.reading_scan_hold_still
                    else -> R.string.reading_scan_page_found
                }.let { stringResource(it) }
                Pill(hint, Color.White, filled = false)
                if (pageCount > 0) Pill(pluralStringResource(R.plurals.reading_scan_kept, pageCount, pageCount), SheafColors.BandBright, filled = true)
                if (processing > 0) Pill(stringResource(R.string.reading_scan_saving, processing), Color.White, filled = false)
            }
        }

        Column(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val interaction = remember { MutableInteractionSource() }
            val takePictureLabel = stringResource(R.string.reading_scan_take_picture)
            Box(
                modifier = Modifier
                    .pressScale(interaction, 0.9f)
                    .size(78.dp)
                    .semantics { contentDescription = takePictureLabel; role = Role.Button }
                    .clickable(interactionSource = interaction, indication = null) {
                        val latest = detection
                        analyzer.capturedByHand(latest)
                        takePicture(latest)
                    },
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawCircle(Color.White, style = Stroke(4.dp.toPx()))
                    val progress = detection?.autoProgress ?: 0f
                    if (progress > 0f) {
                        drawArc(
                            color = SheafColors.BandBright,
                            startAngle = -90f,
                            sweepAngle = 360f * progress,
                            useCenter = false,
                            style = Stroke(4.dp.toPx(), cap = StrokeCap.Round)
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .padding(8.dp)
                        .fillMaxSize()
                        .background(if (capturing) SheafColors.BandBright else SheafColors.Band, CircleShape)
                )
            }
            Text(
                when {
                    capturing -> R.string.reading_scan_taking
                    autoCapture && pageInView -> R.string.reading_scan_hold_or_tap
                    else -> R.string.reading_scan_tap_to_capture
                }.let { stringResource(it) },
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.padding(top = 10.dp)
            )
        }
    }
}

/** Corner guides: where the page should sit, until a page is found. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCornerGuides() {
    val inset = 28.dp.toPx()
    val arm = 34.dp.toPx()
    val stroke = 3.dp.toPx()
    val colour = Color.White.copy(alpha = 0.85f)
    val l = inset
    val t = inset + 90.dp.toPx()
    val r = size.width - inset
    val b = size.height - inset - 110.dp.toPx()
    val corners = listOf(
        Offset(l, t) to Pair(Offset(l + arm, t), Offset(l, t + arm)),
        Offset(r, t) to Pair(Offset(r - arm, t), Offset(r, t + arm)),
        Offset(l, b) to Pair(Offset(l + arm, b), Offset(l, b - arm)),
        Offset(r, b) to Pair(Offset(r - arm, b), Offset(r, b - arm))
    )
    for ((corner, arms) in corners) {
        drawLine(colour, corner, arms.first, stroke, StrokeCap.Round)
        drawLine(colour, corner, arms.second, stroke, StrokeCap.Round)
    }
}

/** A chip that reads over the camera picture: white on dark, filled when chosen. */
@Composable
private fun OverlayChip(text: String, active: Boolean, onClick: () -> Unit, description: String? = null) {
    val shape = RoundedCornerShape(999.dp)
    val interaction = remember { MutableInteractionSource() }
    val onOff = stringResource(if (active) R.string.reading_scan_on else R.string.reading_scan_off)
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = if (active) SheafColors.OnBand else Color.White,
        modifier = Modifier
            .pressScale(interaction, 0.95f)
            .background(if (active) SheafColors.Band else Color.Black.copy(alpha = 0.35f), shape)
            .border(1.dp, if (active) SheafColors.Band else Color.White.copy(alpha = 0.5f), shape)
            .semantics {
                role = Role.Button
                stateDescription = onOff
                if (description != null) contentDescription = "$text. $description"
            }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    )
}
