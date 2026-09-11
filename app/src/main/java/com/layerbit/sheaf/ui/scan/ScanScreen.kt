package com.layerbit.sheaf.ui.scan

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.layerbit.sheaf.scan.PageProcessor
import com.layerbit.sheaf.ui.components.SectionHeading
import com.layerbit.sheaf.ui.theme.SheafColors
import kotlin.math.hypot

/**
 * Adjusting a captured page: drag the corners onto the paper, pick a look, keep it.
 *
 * The handles are the whole interaction. There is no automatic edge detection, because a
 * detector that is right most of the time is worse than none - the times it is wrong it crops
 * away a signature without telling anyone. Four handles on a sensible default is quick to
 * confirm and can never quietly destroy a page.
 */
@Composable
fun PageEditor(
    editing: EditingPage,
    onMoveCorner: (CornerHandle, Float, Float) -> Unit,
    onFilter: (PageProcessor.Filter) -> Unit,
    onKeep: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        SectionHeading("Drag the corners onto the page")
        Spacer(Modifier.height(10.dp))

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            val bitmap = editing.bitmap
            val imageAspect = bitmap.width.toFloat() / bitmap.height
            val boxAspect = maxWidth / maxHeight

            // The image is letterboxed inside the box by ContentScale.Fit, so the drawn area
            // is not the box. Every handle position has to be mapped through the same maths
            // the image was drawn with, or the handles sit next to the page rather than on it.
            val drawnWidth = if (imageAspect > boxAspect) maxWidth else maxHeight * imageAspect
            val drawnHeight = if (imageAspect > boxAspect) maxWidth / imageAspect else maxHeight
            val offsetX = (maxWidth - drawnWidth) / 2
            val offsetY = (maxHeight - drawnHeight) / 2

            val density = androidx.compose.ui.platform.LocalDensity.current
            val drawnWidthPx = with(density) { drawnWidth.toPx() }
            val drawnHeightPx = with(density) { drawnHeight.toPx() }
            val offsetXPx = with(density) { offsetX.toPx() }
            val offsetYPx = with(density) { offsetY.toPx() }
            val handleHalfPx = with(density) { (HANDLE_SIZE / 2).toPx() }
            val grabRadiusPx = with(density) { GRAB_RADIUS.toPx() }

            fun toScreen(p: PageProcessor.Point): Offset = Offset(
                offsetXPx + p.x / bitmap.width * drawnWidthPx,
                offsetYPx + p.y / bitmap.height * drawnHeightPx
            )

            fun toImage(o: Offset): Pair<Float, Float> = Pair(
                (o.x - offsetXPx) / drawnWidthPx * bitmap.width,
                (o.y - offsetYPx) / drawnHeightPx * bitmap.height
            )

            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Captured page",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )

            val handles = listOf(
                CornerHandle.TOP_LEFT to editing.corners.topLeft,
                CornerHandle.TOP_RIGHT to editing.corners.topRight,
                CornerHandle.BOTTOM_RIGHT to editing.corners.bottomRight,
                CornerHandle.BOTTOM_LEFT to editing.corners.bottomLeft
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(bitmap, drawnWidthPx, drawnHeightPx) {
                        var dragging: CornerHandle? = null
                        detectDragGestures(
                            onDragStart = { start ->
                                // Whichever handle is nearest the finger, as long as it is
                                // close enough to be a deliberate grab.
                                dragging = handles
                                    .map { (handle, point) -> handle to distance(toScreen(point), start) }
                                    .filter { (_, d) -> d < grabRadiusPx }
                                    .minByOrNull { (_, d) -> d }
                                    ?.first
                            },
                            onDragEnd = { dragging = null },
                            onDragCancel = { dragging = null },
                            onDrag = { change, _ ->
                                val handle = dragging ?: return@detectDragGestures
                                change.consume()
                                val (x, y) = toImage(change.position)
                                onMoveCorner(handle, x, y)
                            }
                        )
                    }
            ) {
                handles.forEach { (_, point) ->
                    val screen = toScreen(point)
                    Box(
                        modifier = Modifier
                            .offsetPx(screen.x - handleHalfPx, screen.y - handleHalfPx)
                            .size(HANDLE_SIZE)
                            .clip(CircleShape)
                            .background(SheafColors.Band.copy(alpha = 0.55f))
                            .border(2.dp, SheafColors.OnBand, CircleShape)
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PageProcessor.Filter.entries.forEach { filter ->
                val active = filter == editing.filter
                val shape = RoundedCornerShape(999.dp)
                Box(
                    modifier = Modifier
                        .background(if (active) SheafColors.BandDim else SheafColors.SurfaceDim, shape)
                        .border(1.dp, if (active) SheafColors.Band else SheafColors.Border, shape)
                        .clickable { onFilter(filter) }
                        .padding(horizontal = 16.dp, vertical = 9.dp)
                ) {
                    Text(
                        filter.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (active) SheafColors.BandBright else SheafColors.Muted
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onKeep,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SheafColors.Band,
                    contentColor = SheafColors.OnBand
                )
            ) { Text("Keep this page") }
            OutlinedButton(onClick = onDiscard) { Text("Retake", color = SheafColors.Muted) }
        }
    }
}

/** The strip of pages already accepted, and the button that turns them into a document. */
@Composable
fun ScannedPages(
    pages: List<ScannedPage>,
    busy: Boolean,
    onRemove: (Int) -> Unit,
    onMakePdf: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth().padding(16.dp)) {
        SectionHeading(if (pages.size == 1) "1 page scanned" else "${pages.size} pages scanned")
        Spacer(Modifier.height(10.dp))

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(pages, key = { _, page -> page.file.path }) { index, page ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(
                        bitmap = page.thumbnail.asImageBitmap(),
                        contentDescription = "Scanned page ${index + 1}",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .height(96.dp)
                            .aspectRatio(0.72f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(SheafColors.Paper)
                    )
                    TextButton(onClick = { onRemove(index) }) {
                        Text("Remove", style = MaterialTheme.typography.bodySmall, color = SheafColors.Dim)
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onMakePdf,
            enabled = pages.isNotEmpty() && !busy,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = SheafColors.Band,
                contentColor = SheafColors.OnBand,
                disabledContainerColor = SheafColors.SurfaceDim,
                disabledContentColor = SheafColors.Dim
            )
        ) {
            if (busy) {
                CircularProgressIndicator(
                    color = SheafColors.OnBand,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp)
                )
            } else {
                Text("Make a PDF", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

private fun distance(a: Offset, b: Offset) = hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()).toFloat()

/** Absolute pixel offset, since handle positions are computed rather than laid out. */
private fun Modifier.offsetPx(x: Float, y: Float): Modifier =
    this.then(
        androidx.compose.ui.layout.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            layout(placeable.width, placeable.height) {
                placeable.place(x.toInt(), y.toInt())
            }
        }
    )

private val HANDLE_SIZE = 30.dp

/** Generous on purpose: a fingertip is about 9mm and the handle is smaller than that. */
private val GRAB_RADIUS = 44.dp
