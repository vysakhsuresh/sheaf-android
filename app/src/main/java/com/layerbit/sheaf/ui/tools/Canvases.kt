package com.layerbit.sheaf.ui.tools

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint as AndroidPaint
import android.graphics.Path as AndroidPath
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.layerbit.sheaf.pdf.PageArea
import com.layerbit.sheaf.ui.components.SectionHeading
import com.layerbit.sheaf.ui.theme.SheafColors
import java.io.File
import java.io.FileOutputStream

/**
 * A pad to sign on, and the PNG it produces.
 *
 * The strokes are kept as normalised points rather than as pixels, so the signature can be
 * rendered at whatever resolution the page needs without going soft. Someone signs on a pad
 * about 300 px tall and the result is written at 1200, which is what keeps it sharp when it
 * lands on an A4 page.
 */
@Composable
fun SignaturePad(
    onSigned: (File) -> Unit,
    makeFile: () -> File,
    modifier: Modifier = Modifier
) {
    // A list of strokes, each a list of normalised points. Kept in a snapshot list so drawing
    // a stroke redraws without rebuilding the whole screen.
    val strokes = remember { mutableStateListOf<MutableList<Offset>>() }
    var current by remember { mutableStateOf<MutableList<Offset>?>(null) }
    var saved by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeading("Sign here")

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .padding(top = 8.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(SheafColors.Paper)
                .border(1.dp, SheafColors.Border, RoundedCornerShape(10.dp))
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { start ->
                                saved = false
                                current = mutableListOf(normalise(start, size.width, size.height))
                                strokes.add(current!!)
                            },
                            onDragEnd = { current = null },
                            onDragCancel = { current = null },
                            onDrag = { change, _ ->
                                change.consume()
                                current?.add(normalise(change.position, size.width, size.height))
                                // Reassigning the last entry is what tells Compose the list
                                // changed; mutating a list in place is invisible to it.
                                if (strokes.isNotEmpty()) strokes[strokes.lastIndex] = strokes.last()
                            }
                        )
                    }
            ) {
                for (stroke in strokes) {
                    if (stroke.size < 2) continue
                    val path = Path().apply {
                        moveTo(stroke[0].x * size.width, stroke[0].y * size.height)
                        for (point in stroke.drop(1)) {
                            lineTo(point.x * size.width, point.y * size.height)
                        }
                    }
                    drawPath(path, color = Color(0xFF101A2B), style = Stroke(width = INK_WIDTH_PX))
                }
            }
        }

        Row(modifier = Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val file = makeFile()
                    writeSignature(strokes, file)
                    saved = true
                    onSigned(file)
                },
                enabled = strokes.any { it.size > 1 },
                colors = ButtonDefaults.buttonColors(
                    containerColor = SheafColors.Band,
                    contentColor = SheafColors.OnBand,
                    disabledContainerColor = SheafColors.SurfaceDim,
                    disabledContentColor = SheafColors.Dim
                )
            ) { Text(if (saved) "Saved" else "Use this signature") }

            OutlinedButton(onClick = { strokes.clear(); saved = false }) {
                Text("Clear", color = SheafColors.Muted)
            }
        }
    }
}

private fun normalise(offset: Offset, width: Int, height: Int) = Offset(
    (offset.x / width.coerceAtLeast(1)).coerceIn(0f, 1f),
    (offset.y / height.coerceAtLeast(1)).coerceIn(0f, 1f)
)

/**
 * Renders the strokes to a transparent PNG.
 *
 * Transparent, not white: a signature on a white rectangle would cover whatever it was placed
 * over, which on a form is the line it is meant to sit on.
 */
private fun writeSignature(strokes: List<List<Offset>>, file: File) {
    val bitmap = Bitmap.createBitmap(SIGNATURE_WIDTH_PX, SIGNATURE_HEIGHT_PX, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    val paint = AndroidPaint().apply {
        color = android.graphics.Color.BLACK
        style = AndroidPaint.Style.STROKE
        strokeWidth = SIGNATURE_WIDTH_PX * 0.012f
        strokeCap = AndroidPaint.Cap.ROUND
        strokeJoin = AndroidPaint.Join.ROUND
        isAntiAlias = true
    }

    for (stroke in strokes) {
        if (stroke.size < 2) continue
        val path = AndroidPath().apply {
            moveTo(stroke[0].x * SIGNATURE_WIDTH_PX, stroke[0].y * SIGNATURE_HEIGHT_PX)
            for (point in stroke.drop(1)) {
                lineTo(point.x * SIGNATURE_WIDTH_PX, point.y * SIGNATURE_HEIGHT_PX)
            }
        }
        canvas.drawPath(path, paint)
    }

    FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
    bitmap.recycle()
}

/**
 * Drawing boxes over what should be removed.
 *
 * One page at a time, with the page rendered underneath so the boxes land where the reader
 * means them to. Boxes are stored normalised to the page, which is what lets the operation
 * apply them at whatever resolution it renders at.
 */
@Composable
fun RedactCanvas(
    pageIndex: Int,
    pageCount: Int,
    page: Bitmap?,
    areas: List<PageArea>,
    onPage: (Int) -> Unit,
    onAdd: (PageArea) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(onClick = { onPage(pageIndex - 1) }, enabled = pageIndex > 0) {
                Text("Previous", color = SheafColors.Muted)
            }
            Text(
                "Page ${pageIndex + 1} of $pageCount",
                style = MaterialTheme.typography.bodyMedium,
                color = SheafColors.Text
            )
            OutlinedButton(onClick = { onPage(pageIndex + 1) }, enabled = pageIndex < pageCount - 1) {
                Text("Next", color = SheafColors.Muted)
            }
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .aspectRatio(
                    if (page != null && page.height > 0) page.width.toFloat() / page.height else 0.707f
                )
                .clip(RoundedCornerShape(6.dp))
                .background(SheafColors.Paper)
                .border(1.dp, SheafColors.Border, RoundedCornerShape(6.dp))
        ) {
            if (page != null) {
                Image(
                    bitmap = page.asImageBitmap(),
                    contentDescription = "Page ${pageIndex + 1}",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }

            var dragStart by remember(pageIndex) { mutableStateOf<Offset?>(null) }
            var dragNow by remember(pageIndex) { mutableStateOf<Offset?>(null) }

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(pageIndex) {
                        detectDragGestures(
                            onDragStart = { start -> dragStart = start; dragNow = start },
                            onDragEnd = {
                                val from = dragStart
                                val to = dragNow
                                if (from != null && to != null) {
                                    val left = minOf(from.x, to.x) / size.width
                                    val top = minOf(from.y, to.y) / size.height
                                    val width = kotlin.math.abs(to.x - from.x) / size.width
                                    val height = kotlin.math.abs(to.y - from.y) / size.height
                                    // A tap is not a box. Without this, every stray touch
                                    // leaves an invisible zero-size area in the list.
                                    if (width > MIN_BOX && height > MIN_BOX) {
                                        onAdd(PageArea(left, top, width, height))
                                    }
                                }
                                dragStart = null
                                dragNow = null
                            },
                            onDragCancel = { dragStart = null; dragNow = null },
                            onDrag = { change, _ ->
                                change.consume()
                                dragNow = change.position
                            }
                        )
                    }
            ) {
                for (area in areas) {
                    drawRect(
                        color = Color.Black,
                        topLeft = Offset(area.left * size.width, area.top * size.height),
                        size = Size(area.width * size.width, area.height * size.height)
                    )
                }
                val from = dragStart
                val to = dragNow
                if (from != null && to != null) {
                    drawRect(
                        color = SheafColors.Band.copy(alpha = 0.45f),
                        topLeft = Offset(minOf(from.x, to.x), minOf(from.y, to.y)),
                        size = Size(kotlin.math.abs(to.x - from.x), kotlin.math.abs(to.y - from.y))
                    )
                }
            }
        }

        Row(
            modifier = Modifier.padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (areas.isEmpty()) "Drag a box over anything that should go" else "${areas.size} on this page",
                style = MaterialTheme.typography.bodySmall,
                color = SheafColors.Dim,
                modifier = Modifier.weight(1f)
            )
            if (areas.isNotEmpty()) {
                OutlinedButton(onClick = onClear) { Text("Clear page", color = SheafColors.Muted) }
            }
        }
    }
}

private const val INK_WIDTH_PX = 5f
private const val SIGNATURE_WIDTH_PX = 1200
private const val SIGNATURE_HEIGHT_PX = 480

/** Below this a drag is a tap, and a zero-size box would sit invisibly in the list. */
private const val MIN_BOX = 0.01f
