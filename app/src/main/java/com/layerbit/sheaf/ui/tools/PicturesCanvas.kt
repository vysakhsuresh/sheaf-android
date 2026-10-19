package com.layerbit.sheaf.ui.tools

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.layerbit.sheaf.pdf.PageArea
import com.layerbit.sheaf.ui.components.SecondaryButton
import com.layerbit.sheaf.ui.theme.SheafColors
import kotlin.math.roundToInt

/**
 * The pictures on a page, outlined, to move, resize, take out or swap.
 *
 * Tap one to select it; drag it to move it; drag its bottom-right corner to resize it, keeping
 * its shape; tap the cross at its top-right corner to take it out, and again to keep it. The
 * preview is the page's own pixels: a moved picture is shown at its new place and the old one
 * papered over, so what is on screen is what the file will look like.
 */
@Composable
fun PicturesCanvas(
    pageIndex: Int,
    pageCount: Int,
    page: Bitmap?,
    pictures: List<PageArea>?,
    changes: Map<Int, PictureChange>,
    selected: Int?,
    onPage: (Int) -> Unit,
    onSelect: (Int?) -> Unit,
    onMove: (Int, PageArea) -> Unit,
    onToggleRemove: (Int) -> Unit,
    onReplace: () -> Unit,
    onSharpen: (Float) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val currentPictures by rememberUpdatedState(pictures.orEmpty())
    val currentChanges by rememberUpdatedState(changes)
    val currentSelected by rememberUpdatedState(selected)

    fun areaOf(i: Int): PageArea = currentChanges[i]?.area ?: currentPictures[i]

    Column(modifier = modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { onPage(pageIndex - 1) }, enabled = pageIndex > 0) { Text("Previous", color = SheafColors.Muted) }
            Text("Page ${pageIndex + 1} of $pageCount", style = MaterialTheme.typography.bodyMedium, color = SheafColors.Text)
            OutlinedButton(onClick = { onPage(pageIndex + 1) }, enabled = pageIndex < pageCount - 1) { Text("Next", color = SheafColors.Muted) }
        }
        val zoom = rememberZoomState(Unit)
        ZoomControls(zoom, Modifier.padding(top = 4.dp))

        val handle = with(LocalDensity.current) { 22.dp.toPx() }
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .aspectRatio(if (page != null && page.height > 0) page.width.toFloat() / page.height else 0.707f)
                .clip(RoundedCornerShape(6.dp))
                .background(SheafColors.Paper)
                .border(1.dp, SheafColors.Border, RoundedCornerShape(6.dp))
        ) {
            ZoomFrame(zoom, Modifier.fillMaxSize(), onSettled = onSharpen) {
            // A drag in progress: which picture, how, and the box it would land in.
            var dragging by remember(pageIndex) { mutableStateOf<Int?>(null) }
            var resizing by remember(pageIndex) { mutableStateOf(false) }
            var live by remember(pageIndex) { mutableStateOf<PageArea?>(null) }

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(pageIndex) {
                        detectTapGestures { p ->
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            val sel = currentSelected
                            if (sel != null && sel in currentPictures.indices) {
                                val a = areaOf(sel)
                                val cross = Offset((a.left + a.width) * w, a.top * h)
                                if ((p - cross).getDistance() < handle) {
                                    onToggleRemove(sel)
                                    return@detectTapGestures
                                }
                            }
                            val hit = currentPictures.indices.reversed().firstOrNull { i ->
                                val a = areaOf(i)
                                p.x / w in a.left..(a.left + a.width) && p.y / h in a.top..(a.top + a.height)
                            }
                            onSelect(hit)
                        }
                    }
                    .pointerInput(pageIndex) {
                        detectDragGestures(
                            onDragStart = { p ->
                                val w = size.width.toFloat()
                                val h = size.height.toFloat()
                                val sel = currentSelected
                                val corner = sel?.takeIf { it in currentPictures.indices }?.let { areaOf(it) }
                                    ?.let { a -> Offset((a.left + a.width) * w, (a.top + a.height) * h) }
                                if (sel != null && corner != null && (p - corner).getDistance() < handle * 1.4f) {
                                    dragging = sel
                                    resizing = true
                                    live = areaOf(sel)
                                    return@detectDragGestures
                                }
                                val hit = currentPictures.indices.reversed().firstOrNull { i ->
                                    val a = areaOf(i)
                                    p.x / w in a.left..(a.left + a.width) && p.y / h in a.top..(a.top + a.height)
                                }
                                if (hit != null && currentChanges[hit]?.removed != true) {
                                    onSelect(hit)
                                    dragging = hit
                                    resizing = false
                                    live = areaOf(hit)
                                }
                            },
                            onDrag = { change, delta ->
                                val a = live ?: return@detectDragGestures
                                change.consume()
                                val dx = delta.x / size.width
                                val dy = delta.y / size.height
                                live = if (resizing) {
                                    val width = (a.width + dx).coerceIn(0.03f, 1f - a.left)
                                    val original = currentPictures.getOrNull(dragging ?: -1) ?: a
                                    PageArea(a.left, a.top, width, width * original.height / original.width)
                                } else {
                                    PageArea(
                                        (a.left + dx).coerceIn(0f, 1f - a.width),
                                        (a.top + dy).coerceIn(0f, 1f - a.height),
                                        a.width, a.height
                                    )
                                }
                            },
                            onDragEnd = {
                                val i = dragging
                                val a = live
                                if (i != null && a != null) onMove(i, a)
                                dragging = null
                                live = null
                            },
                            onDragCancel = { dragging = null; live = null }
                        )
                    }
            ) {
                if (page != null) {
                    drawImage(page.asImageBitmap(), dstSize = IntSize(size.width.toInt(), size.height.toInt()))
                }
                val list = pictures.orEmpty()
                // First paper over every picture that has left its place, then draw each where
                // it is now: a picture moved onto another's old place stays visible.
                for ((i, original) in list.withIndex()) {
                    val change = changes[i]
                    val moved = i == dragging || (change != null && change.area != original)
                    if (change?.removed == true || change?.replacement != null || moved) {
                        // A pixel wider all round, or the old picture's soft edge shows as a ghost outline.
                        drawRect(Color.White, topLeft = original.topLeft(size) - Offset(1.5f, 1.5f), size = original.size(size).let { Size(it.width + 3f, it.height + 3f) })
                    }
                }
                for ((i, original) in list.withIndex()) {
                    val change = changes[i]
                    val now = if (i == dragging) live ?: original else change?.area ?: original
                    val tl = now.topLeft(size)
                    val sz = now.size(size)
                    when {
                        change?.removed == true -> {
                            drawRect(SheafColors.Failed.copy(alpha = 0.08f), topLeft = original.topLeft(size), size = original.size(size))
                            drawRect(
                                SheafColors.Failed, topLeft = original.topLeft(size), size = original.size(size),
                                style = Stroke(width = 2.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
                            )
                            label("Taken out", original.topLeft(size))
                            continue
                        }
                        change?.replacementBitmap != null -> {
                            val bmp = change.replacementBitmap
                            val k = minOf(sz.width / bmp.width, sz.height / bmp.height)
                            val w = (bmp.width * k).roundToInt()
                            val h = (bmp.height * k).roundToInt()
                            drawImage(
                                bmp.asImageBitmap(),
                                dstOffset = IntOffset((tl.x + (sz.width - w) / 2).roundToInt(), (tl.y + (sz.height - h) / 2).roundToInt()),
                                dstSize = IntSize(w.coerceAtLeast(1), h.coerceAtLeast(1))
                            )
                        }
                        (change != null && change.area != original) || i == dragging -> if (page != null) {
                            // The picture's own pixels, cut from the page and drawn where it now goes.
                            val sx = page.width.toFloat()
                            val sy = page.height.toFloat()
                            drawImage(
                                page.asImageBitmap(),
                                srcOffset = IntOffset((original.left * sx).roundToInt(), (original.top * sy).roundToInt()),
                                srcSize = IntSize((original.width * sx).roundToInt().coerceAtLeast(1), (original.height * sy).roundToInt().coerceAtLeast(1)),
                                dstOffset = IntOffset(tl.x.roundToInt(), tl.y.roundToInt()),
                                dstSize = IntSize(sz.width.roundToInt().coerceAtLeast(1), sz.height.roundToInt().coerceAtLeast(1))
                            )
                        }
                    }
                    val isSelected = i == selected
                    drawRect(
                        SheafColors.Band.copy(alpha = if (isSelected) 1f else 0.7f), topLeft = tl, size = sz,
                        style = Stroke(width = if (isSelected) 4f else 2f)
                    )
                    if (change?.replacement != null) label("Replaced", tl)
                    if (isSelected) {
                        // Resize handle, bottom right; take-out cross, top right.
                        val corner = Offset(tl.x + sz.width, tl.y + sz.height)
                        drawCircle(SheafColors.Band, radius = handle * 0.45f, center = corner)
                        drawCircle(Color.White, radius = handle * 0.2f, center = corner)
                        val cross = Offset(tl.x + sz.width, tl.y)
                        drawCircle(SheafColors.Failed, radius = handle * 0.5f, center = cross)
                        val r = handle * 0.22f
                        drawLine(Color.White, cross + Offset(-r, -r), cross + Offset(r, r), strokeWidth = 4f)
                        drawLine(Color.White, cross + Offset(-r, r), cross + Offset(r, -r), strokeWidth = 4f)
                    }
                }
            }
            }
        }

        Text(
            when {
                pictures == null -> "Looking for pictures on this page…"
                pictures.isEmpty() -> "This page has no pictures to change. Go to another page, or add one with Add an image."
                else -> "Tap a picture to select it. Drag to move it, drag its corner to resize it, tap its × to take it out."
            },
            style = MaterialTheme.typography.bodySmall,
            color = SheafColors.Dim,
            modifier = Modifier.padding(top = 10.dp)
        )
        if (selected != null && !pictures.isNullOrEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                SecondaryButton(text = "Replace it with another picture", onClick = onReplace, tall = false, modifier = Modifier.weight(1f))
                SecondaryButton(
                    text = if (changes[selected]?.removed == true) "Keep it" else "Take it out",
                    onClick = { onToggleRemove(selected) },
                    tall = false
                )
            }
        }
    }
}

private fun PageArea.topLeft(size: Size) = Offset(left * size.width, top * size.height)
private fun PageArea.size(size: Size) = Size(width * size.width, height * size.height)

private fun DrawScope.label(text: String, at: Offset) {
    val paint = android.graphics.Paint().apply {
        color = android.graphics.Color.WHITE
        textSize = 26f
        isAntiAlias = true
        isFakeBoldText = true
    }
    val width = paint.measureText(text)
    drawRect(Color(0xCC1E2230), topLeft = at, size = Size(width + 16f, 36f))
    drawContext.canvas.nativeCanvas.drawText(text, at.x + 8f, at.y + 26f, paint)
}

