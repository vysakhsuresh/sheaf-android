package com.layerbit.sheaf.ui.tools

import androidx.compose.ui.res.stringResource
import com.layerbit.sheaf.text.Strings
import com.layerbit.sheaf.R
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.layerbit.sheaf.ui.theme.SheafColors
import kotlinx.coroutines.delay

/**
 * Zoom wherever a page is worked on.
 *
 * The page and everything drawn on it are scaled together, so a mark is still stored as a
 * fraction of the page and lands where the finger was at any zoom. Two fingers zoom and move
 * the page; one finger keeps drawing, and a second finger arriving cancels what the first
 * had started, so a pinch never leaves a stray mark.
 */
@Stable
class ZoomState {
    var zoom by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set
    var frame by mutableStateOf(IntSize.Zero)
        internal set

    val zoomed: Boolean get() = zoom > 1.001f

    fun step(up: Boolean) {
        val next = if (up) STEPS.firstOrNull { it > zoom + 0.01f } ?: MAX else STEPS.lastOrNull { it < zoom - 0.01f } ?: 1f
        // Buttons zoom about the middle of what is on screen.
        val middle = Offset(frame.width / 2f, frame.height / 2f)
        set(next, middle, Offset.Zero)
    }

    fun fit() {
        zoom = 1f
        offset = Offset.Zero
    }

    /** Scales by [factor] about [centre], keeping the point under it there, then moves by [pan]. */
    fun set(target: Float, centre: Offset, pan: Offset) {
        val z = target.coerceIn(1f, MAX)
        val k = z / zoom
        val moved = centre - (centre - offset) * k + pan
        zoom = z
        offset = clamp(moved)
    }

    private fun clamp(o: Offset): Offset {
        val w = frame.width.toFloat()
        val h = frame.height.toFloat()
        return Offset(o.x.coerceIn(w - w * zoom, 0f), o.y.coerceIn(h - h * zoom, 0f))
    }

    companion object {
        val STEPS = listOf(1f, 1.25f, 1.5f, 2f, 2.5f, 3f, 4f)
        const val MAX = 4f
    }
}

@Composable
fun rememberZoomState(key: Any?): ZoomState = remember(key) { ZoomState() }

/**
 * The frame a page is zoomed inside. Put the page and its canvas in [content]; they are laid
 * out at the frame's size and scaled, so their own pointer positions stay in page fractions.
 * [onSettled] is told the zoom once it has stopped changing, to draw the page sharp.
 */
@Composable
fun ZoomFrame(
    state: ZoomState,
    modifier: Modifier = Modifier,
    onSettled: (Float) -> Unit = {},
    content: @Composable BoxScope.() -> Unit
) {
    LaunchedEffect(state.zoom) {
        delay(180)
        onSettled(state.zoom)
    }
    Box(
        modifier = modifier
            .onSizeChanged { state.frame = it }
            .pointerInput(state) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.count { it.pressed } >= 2) {
                            state.set(state.zoom * event.calculateZoom(), event.calculateCentroid(useCurrent = true), event.calculatePan())
                            // Taken here, before the page's own gestures see them: a pinch is
                            // never also a mark.
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = state.zoom
                    scaleY = state.zoom
                    translationX = state.offset.x
                    translationY = state.offset.y
                },
            content = content
        )
    }
}

/** − 100% +, the size label going back to fit when tapped. */
@Composable
fun ZoomControls(state: ZoomState, modifier: Modifier = Modifier) {
    val zoomOut = stringResource(R.string.tools_zoom_out)
    val fitPage = stringResource(R.string.tools_fit_page)
    val zoomIn = stringResource(R.string.tools_zoom_in)
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { state.step(up = false) }, enabled = state.zoomed, modifier = Modifier.semantics { contentDescription = zoomOut }) {
            Text("−", color = SheafColors.Text)
        }
        TextButton(onClick = state::fit, modifier = Modifier.semantics { contentDescription = fitPage }) {
            Text("${(state.zoom * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium, color = SheafColors.Muted)
        }
        OutlinedButton(onClick = { state.step(up = true) }, enabled = state.zoom < ZoomState.MAX, modifier = Modifier.semantics { contentDescription = zoomIn }) {
            Text("+", color = SheafColors.Text)
        }
    }
}

/** The hint a page editor adds while zoomed. */
val ZOOM_HINT: String get() = " " + Strings.get(R.string.tools_zoom_hint)
