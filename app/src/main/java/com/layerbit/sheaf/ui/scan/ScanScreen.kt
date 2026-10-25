package com.layerbit.sheaf.ui.scan

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.Color
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.layerbit.sheaf.scan.PageProcessor
import com.layerbit.sheaf.ui.components.ChipRow
import com.layerbit.sheaf.ui.components.Pill
import com.layerbit.sheaf.ui.components.PrimaryButton
import com.layerbit.sheaf.ui.components.RoundIconButton
import com.layerbit.sheaf.ui.components.SecondaryButton
import com.layerbit.sheaf.ui.components.SectionHeading
import com.layerbit.sheaf.ui.theme.SheafColors
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.layerbit.sheaf.R
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Adjusting a captured page: check the corners on the paper, pick a look, keep it.
 *
 * The corners start on the outline the detector found, or on a default rectangle when it
 * found none, and the handles are always there. A detector that is right most of the time is
 * still wrong some of the time, and when it is, the person sees it here and drags it right
 * rather than finding a signature cropped off in the PDF.
 */
@Composable
fun PageEditor(
    editing: EditingPage,
    onMoveCorner: (CornerHandle, Float, Float) -> Unit,
    onWholePhoto: () -> Unit,
    onFilter: (PageProcessor.Filter) -> Unit,
    onKeep: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier
) {
    val purpose = editing.purpose
    val (tagRes, titleRes) = when {
        purpose == EditPurpose.IdFront -> R.string.reading_scan_tag_front to R.string.reading_scan_fit_card
        purpose == EditPurpose.IdBack -> R.string.reading_scan_tag_back to R.string.reading_scan_fit_card
        purpose is EditPurpose.Recrop && purpose.kind == PageKind.ID_CARD ->
            (if (purpose.side == 0) R.string.reading_scan_tag_front else R.string.reading_scan_tag_back) to R.string.reading_scan_fit_card
        purpose is EditPurpose.Recrop -> R.string.reading_scan_tag_recrop to R.string.reading_scan_move_corners
        editing.detected -> R.string.reading_scan_tag_found to R.string.reading_scan_check_corners
        else -> R.string.reading_scan_tag_adjust to R.string.reading_scan_drag_corners
    }
    val (keepRes, discardRes) = when (purpose) {
        EditPurpose.NewPage -> R.string.reading_scan_keep_page to R.string.reading_scan_retake
        EditPurpose.IdFront -> R.string.reading_scan_keep_front to R.string.reading_scan_retake
        EditPurpose.IdBack -> R.string.reading_scan_keep_back to R.string.reading_scan_retake
        is EditPurpose.Recrop ->
            (if (purpose.side + 1 < purpose.sources.size) R.string.reading_scan_next_back else R.string.reading_scan_save_crop) to
                R.string.reading_scan_cancel
    }
    val tag = stringResource(tagRes)
    val title = stringResource(titleRes)
    val keepLabel = stringResource(keepRes)
    val discardLabel = stringResource(discardRes)

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(tag, SheafColors.Band, filled = true)
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = SheafColors.Text,
                modifier = Modifier.padding(start = 10.dp).weight(1f)
            )
            TextButton(onClick = onWholePhoto) { Text(stringResource(R.string.reading_scan_whole_photo)) }
        }
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
                contentDescription = stringResource(R.string.reading_scan_captured_page),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )

            var loupe by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Offset?>(null) }

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
                            onDragEnd = { dragging = null; loupe = null },
                            onDragCancel = { dragging = null; loupe = null },
                            onDrag = { change, _ ->
                                val handle = dragging ?: return@detectDragGestures
                                change.consume()
                                val (x, y) = toImage(change.position)
                                loupe = change.position
                                onMoveCorner(handle, x, y)
                            }
                        )
                    }
            ) {
                handles.forEach { (_, point) ->
                    val screen = toScreen(point)
                    Box(
                        modifier = Modifier
                            // Positioned in the layout phase rather than by a padding
                            // modifier: the handle's place is computed from the drag, and
                            // this way moving it does not recompose the image behind it.
                            .offset {
                                IntOffset(
                                    (screen.x - handleHalfPx).roundToInt(),
                                    (screen.y - handleHalfPx).roundToInt()
                                )
                            }
                            .size(HANDLE_SIZE)
                            .clip(CircleShape)
                            .background(SheafColors.Band.copy(alpha = 0.55f))
                            .border(2.dp, SheafColors.OnBand, CircleShape)
                    )
                }
            }

            // While a corner is held, the photo round it at three times, above the finger, so
            // the corner can be set on the paper's edge the finger is covering.
            val loupeAt = loupe
            if (loupeAt != null) {
                val sidePx = with(density) { LOUPE_SIZE.toPx() }
                val gapPx = with(density) { 36.dp.toPx() }
                androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                    val above = loupeAt.y - gapPx - sidePx
                    val top = if (above >= 0f) above else loupeAt.y + gapPx
                    val left = (loupeAt.x - sidePx / 2f).coerceIn(-sidePx / 3f, size.width - 2f * sidePx / 3f)
                    val centre = Offset(left + sidePx / 2f, top + sidePx / 2f)
                    val (ix, iy) = toImage(loupeAt)
                    // Image pixels per screen pixel, then a third of that inside the loupe.
                    val perPx = bitmap.width / drawnWidthPx / 3f
                    val half = sidePx / 2f * perPx
                    val native = drawContext.canvas.nativeCanvas
                    val clip = android.graphics.Path().apply { addCircle(centre.x, centre.y, sidePx / 2f, android.graphics.Path.Direction.CW) }
                    native.save()
                    native.clipPath(clip)
                    native.drawColor(android.graphics.Color.rgb(10, 12, 16))
                    native.drawBitmap(
                        bitmap,
                        android.graphics.Rect((ix - half).toInt(), (iy - half).toInt(), (ix + half).toInt(), (iy + half).toInt()),
                        android.graphics.RectF(left, top, left + sidePx, top + sidePx),
                        android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
                    )
                    native.restore()
                    val arm = sidePx * 0.18f
                    for ((colour, width) in listOf(Color.White.copy(alpha = 0.9f) to 3.dp.toPx(), Color(0xFF0A0C10) to 1.25.dp.toPx())) {
                        drawLine(colour, centre - Offset(arm, 0f), centre + Offset(arm, 0f), strokeWidth = width)
                        drawLine(colour, centre - Offset(0f, arm), centre + Offset(0f, arm), strokeWidth = width)
                    }
                    drawCircle(Color.White.copy(alpha = 0.9f), radius = sidePx / 2f, center = centre, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        SectionHeading(stringResource(R.string.reading_scan_look))
        Spacer(Modifier.height(8.dp))
        ChipRow(
            options = PageProcessor.Filter.entries.map { it to it.label },
            selected = editing.filter,
            onSelect = onFilter
        )

        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton(text = keepLabel, icon = Icons.Filled.Check, onClick = onKeep, modifier = Modifier.weight(1.4f))
            SecondaryButton(
                text = discardLabel,
                icon = if (purpose is EditPurpose.Recrop) Icons.Filled.Close else Icons.Filled.Refresh,
                onClick = onDiscard,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/** The strip of pages already accepted, and the button that turns them into a document. */
@Composable
fun ScannedPages(
    pages: List<ScannedPage>,
    busy: Boolean,
    processing: Int,
    onReopen: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onMakePdf: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(SheafColors.Surface, RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionHeading(pluralStringResource(R.plurals.reading_scan_pages_scanned, pages.size, pages.size), modifier = Modifier.weight(1f))
            Text(stringResource(R.string.reading_scan_tap_to_recrop), style = MaterialTheme.typography.labelSmall, color = SheafColors.Dim)
        }
        Spacer(Modifier.height(10.dp))

        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            itemsIndexed(pages, key = { _, page -> page.file.path }) { index, page ->
                Box {
                    Image(
                        bitmap = page.thumbnail.asImageBitmap(),
                        contentDescription = stringResource(R.string.reading_scan_thumbnail, index + 1),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .height(104.dp)
                            .aspectRatio(0.72f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(SheafColors.Paper)
                            .border(1.dp, SheafColors.Border, RoundedCornerShape(10.dp))
                            // Reopens on the uncropped photo, so a crop that was a little off
                            // is moved rather than rescanned.
                            .clickable(enabled = !busy && page.sources.isNotEmpty()) { onReopen(index) }
                    )
                    Pill(
                        "${index + 1}",
                        SheafColors.Band,
                        filled = true,
                        modifier = Modifier.align(Alignment.BottomStart).padding(6.dp)
                    )
                    RoundIconButton(
                        icon = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.reading_scan_remove_page, index + 1),
                        onClick = { onRemove(index) },
                        size = 24.dp,
                        modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        PrimaryButton(
            text = when {
                busy -> stringResource(R.string.reading_scan_making_pdf)
                processing > 0 -> pluralStringResource(R.plurals.reading_scan_saving_last, processing, processing)
                else -> pluralStringResource(R.plurals.reading_scan_make_pdf, pages.size, pages.size)
            },
            onClick = onMakePdf,
            enabled = pages.isNotEmpty() && !busy && processing == 0,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private fun distance(a: Offset, b: Offset) = hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()).toFloat()

private val HANDLE_SIZE = 30.dp

/** Generous on purpose: a fingertip is about 9mm and the handle is smaller than that. */
private val GRAB_RADIUS = 44.dp
private val LOUPE_SIZE = 112.dp
