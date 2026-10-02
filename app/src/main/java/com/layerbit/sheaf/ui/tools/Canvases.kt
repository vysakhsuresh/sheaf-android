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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.layerbit.sheaf.pdf.PageArea
import com.layerbit.sheaf.pdf.PageMark
import com.layerbit.sheaf.pdf.PagePoint
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
// PointerInputChange.historical is still marked experimental, and this opts in deliberately.
// Those are the touch positions the system batched between frames; a fast stroke reports
// several per frame, and ignoring them is precisely what made the ink look cornered. The
// alternative is not "safer code", it is the jagged signature this rewrite exists to fix.
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SignaturePad(
    onSigned: (File) -> Unit,
    makeFile: () -> File,
    modifier: Modifier = Modifier
) {
    // WHY THIS IS NOT A SNAPSHOT LIST.
    //
    // The first version kept strokes in a mutableStateListOf and rebuilt a Path from every
    // point on every frame. Touching it recomposed the buttons and the heading as well as the
    // canvas, so the ink arrived late and in jagged chunks - a stroke would appear only after
    // the finger had already moved on.
    //
    // Now the geometry lives in a plain list that Compose does not observe, strokes are built
    // into Path objects once and appended to as points arrive, and a single counter is read
    // inside the draw lambda to invalidate it. Only the canvas redraws, and only the new
    // segment is added rather than the whole signature rebuilt.
    val strokes = remember { mutableListOf<SignatureStroke>() }
    var version by remember { mutableIntStateOf(0) }
    var hasInk by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeading("Sign here")

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .padding(top = 8.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(SheafColors.Paper)
                .border(1.dp, SheafColors.Border, RoundedCornerShape(10.dp))
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val down = awaitPointerEvent().changes.firstOrNull { it.pressed }
                                    ?: continue

                                val stroke = SignatureStroke(size.width, size.height)
                                stroke.start(down.position)
                                strokes += stroke
                                hasInk = true
                                saved = false
                                version++
                                down.consume()

                                // Drained here rather than through detectDragGestures so every
                                // historical point the system batched is used. A fast stroke
                                // reports several positions per frame, and dropping them is
                                // what makes handwriting look like a series of corners.
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull() ?: break
                                    if (!change.pressed) {
                                        change.consume()
                                        break
                                    }
                                    for (historical in change.historical) {
                                        stroke.lineTo(historical.position)
                                    }
                                    stroke.lineTo(change.position)
                                    change.consume()
                                    version++
                                }
                            }
                        }
                    }
            ) {
                // Read so the draw is invalidated as points arrive. The strokes themselves are
                // invisible to Compose on purpose.
                @Suppress("UNUSED_EXPRESSION") version

                for (stroke in strokes) {
                    drawPath(
                        path = stroke.path,
                        color = Color(0xFF101A2B),
                        style = Stroke(width = INK_WIDTH_PX, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
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
                enabled = hasInk,
                colors = ButtonDefaults.buttonColors(
                    containerColor = SheafColors.Band,
                    contentColor = SheafColors.OnBand,
                    disabledContainerColor = SheafColors.SurfaceDim,
                    disabledContentColor = SheafColors.Dim
                )
            ) { Text(if (saved) "Signature saved" else "Use this signature") }

            OutlinedButton(
                onClick = {
                    strokes.clear()
                    hasInk = false
                    saved = false
                    version++
                }
            ) { Text("Clear", color = SheafColors.Muted) }
        }
    }
}

/**
 * One stroke, as a Path that is appended to rather than rebuilt.
 *
 * The points are also kept, normalised to the pad, because the exported PNG is drawn at a much
 * higher resolution than the pad - rendering the screen-sized Path would give a signature that
 * looks soft on an A4 page.
 */
class SignatureStroke(private val width: Int, private val height: Int) {
    val path = Path()
    val points = mutableListOf<Offset>()

    fun start(offset: Offset) {
        path.moveTo(offset.x, offset.y)
        points += normalise(offset, width, height)
    }

    fun lineTo(offset: Offset) {
        // Points closer together than this add nothing a finger can see and cost a path
        // segment each, which is what a fast scribble produces hundreds of.
        val last = points.lastOrNull()
        if (last != null) {
            val dx = last.x * width - offset.x
            val dy = last.y * height - offset.y
            if (dx * dx + dy * dy < MIN_SEGMENT_PX * MIN_SEGMENT_PX) return
        }
        path.lineTo(offset.x, offset.y)
        points += normalise(offset, width, height)
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
private fun writeSignature(strokes: List<SignatureStroke>, file: File) {
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
        val points = stroke.points
        if (points.size < 2) continue
        val path = AndroidPath().apply {
            moveTo(points[0].x * SIGNATURE_WIDTH_PX, points[0].y * SIGNATURE_HEIGHT_PX)
            // Quadratic through the midpoints rather than straight segments. Joining sampled
            // touch points with lines is what gives a signature its cornered, shaky look.
            for (i in 1 until points.size) {
                val previous = points[i - 1]
                val current = points[i]
                val midX = (previous.x + current.x) / 2f * SIGNATURE_WIDTH_PX
                val midY = (previous.y + current.y) / 2f * SIGNATURE_HEIGHT_PX
                quadTo(
                    previous.x * SIGNATURE_WIDTH_PX,
                    previous.y * SIGNATURE_HEIGHT_PX,
                    midX,
                    midY
                )
            }
            val last = points.last()
            lineTo(last.x * SIGNATURE_WIDTH_PX, last.y * SIGNATURE_HEIGHT_PX)
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

/**
 * Highlighting and drawing on a page.
 *
 * One page at a time with the page rendered underneath, like the redaction canvas - and for
 * the same reason, since a mark that lands a centimetre from where the finger was is worse
 * than no mark at all. Marks are stored normalised to the page, so the operation can draw
 * them into a document whose points bear no relation to the pixels on this screen.
 *
 * The pen path drains historical touch points, the way the signature pad does. A fast stroke
 * reports several positions per frame and dropping them is exactly what turns handwriting
 * into a row of corners.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun MarkupCanvas(
    pageIndex: Int,
    pageCount: Int,
    page: Bitmap?,
    marks: List<PageMark>,
    pen: Boolean,
    colour: Int,
    onPage: (Int) -> Unit,
    onHighlight: (PageArea) -> Unit,
    onInk: (List<PagePoint>) -> Unit,
    onUndo: () -> Unit,
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
            OutlinedButton(
                onClick = { onPage(pageIndex + 1) },
                enabled = pageIndex < pageCount - 1
            ) { Text("Next", color = SheafColors.Muted) }
        }

        // The in-progress gestures. Neither is snapshot state that Compose walks: the stroke
        // is a Path appended to as points arrive, and one counter read inside the draw lambda
        // is what invalidates it.
        val stroke = remember(pageIndex, pen) { mutableStateOf<SignatureStroke?>(null) }
        var version by remember(pageIndex, pen) { mutableIntStateOf(0) }
        var boxFrom by remember(pageIndex, pen) { mutableStateOf<Offset?>(null) }
        var boxTo by remember(pageIndex, pen) { mutableStateOf<Offset?>(null) }

        val gestures = if (pen) {
            Modifier.pointerInput(pageIndex, pen) {
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitPointerEvent().changes.firstOrNull { it.pressed }
                            ?: continue
                        val drawing = SignatureStroke(size.width, size.height)
                        drawing.start(down.position)
                        stroke.value = drawing
                        version++
                        down.consume()

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (!change.pressed) {
                                change.consume()
                                break
                            }
                            for (historical in change.historical) {
                                drawing.lineTo(historical.position)
                            }
                            drawing.lineTo(change.position)
                            change.consume()
                            version++
                        }

                        // A tap is not a stroke. Handing one over would put an invisible mark
                        // in the list and count towards the total.
                        if (drawing.points.size >= 2) {
                            onInk(drawing.points.map { PagePoint(it.x, it.y) })
                        }
                        stroke.value = null
                        version++
                    }
                }
            }
        } else {
            Modifier.pointerInput(pageIndex, pen) {
                detectDragGestures(
                    onDragStart = { start -> boxFrom = start; boxTo = start },
                    onDragEnd = {
                        val from = boxFrom
                        val to = boxTo
                        if (from != null && to != null) {
                            val left = minOf(from.x, to.x) / size.width
                            val top = minOf(from.y, to.y) / size.height
                            val width = kotlin.math.abs(to.x - from.x) / size.width
                            val height = kotlin.math.abs(to.y - from.y) / size.height
                            // A highlight is usually wide and short - one line of text - so
                            // only the long side has to clear the tap threshold.
                            if (width > MIN_BOX && height > MIN_BOX / 2f) {
                                onHighlight(PageArea(left, top, width, height))
                            }
                        }
                        boxFrom = null
                        boxTo = null
                    },
                    onDragCancel = { boxFrom = null; boxTo = null },
                    onDrag = { change, _ ->
                        change.consume()
                        boxTo = change.position
                    }
                )
            }
        }

        Box(
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

            Canvas(modifier = Modifier.fillMaxSize().then(gestures)) {
                // Read so the draw is invalidated as points arrive. The stroke itself is
                // invisible to Compose on purpose.
                @Suppress("UNUSED_EXPRESSION") version

                for (mark in marks) {
                    when (mark) {
                        is PageMark.Highlight -> drawRect(
                            color = Color(mark.colour).copy(alpha = HIGHLIGHT_PREVIEW_ALPHA),
                            topLeft = Offset(
                                mark.area.left * size.width,
                                mark.area.top * size.height
                            ),
                            size = Size(
                                mark.area.width * size.width,
                                mark.area.height * size.height
                            )
                        )

                        is PageMark.Ink -> {
                            val path = Path()
                            mark.points.forEachIndexed { index, point ->
                                val x = point.x * size.width
                                val y = point.y * size.height
                                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                            }
                            drawPath(
                                path = path,
                                color = Color(mark.colour),
                                style = Stroke(
                                    width = INK_WIDTH_PX,
                                    cap = StrokeCap.Round,
                                    join = StrokeJoin.Round
                                )
                            )
                        }
                    }
                }

                stroke.value?.let { drawing ->
                    drawPath(
                        path = drawing.path,
                        color = Color(colour),
                        style = Stroke(
                            width = INK_WIDTH_PX,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                }

                val from = boxFrom
                val to = boxTo
                if (from != null && to != null) {
                    drawRect(
                        color = Color(colour).copy(alpha = HIGHLIGHT_PREVIEW_ALPHA),
                        topLeft = Offset(minOf(from.x, to.x), minOf(from.y, to.y)),
                        size = Size(
                            kotlin.math.abs(to.x - from.x),
                            kotlin.math.abs(to.y - from.y)
                        )
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
                when {
                    marks.isEmpty() && pen -> "Draw on the page with your finger"
                    marks.isEmpty() -> "Drag across a line to highlight it"
                    else -> "${marks.size} on this page"
                },
                style = MaterialTheme.typography.bodySmall,
                color = SheafColors.Dim,
                modifier = Modifier.weight(1f)
            )
            if (marks.isNotEmpty()) {
                OutlinedButton(onClick = onUndo) { Text("Undo", color = SheafColors.Muted) }
                OutlinedButton(onClick = onClear) { Text("Clear page", color = SheafColors.Muted) }
            }
        }
    }
}

/** On screen a highlight can be a little stronger than on paper, and still read as ink. */
private const val HIGHLIGHT_PREVIEW_ALPHA = 0.4f

private const val INK_WIDTH_PX = 5f

/** Sub-pixel jitter costs a path segment and adds nothing anyone can see. */
private const val MIN_SEGMENT_PX = 2.2f
private const val SIGNATURE_WIDTH_PX = 1200
private const val SIGNATURE_HEIGHT_PX = 480

/** Below this a drag is a tap, and a zero-size box would sit invisibly in the list. */
private const val MIN_BOX = 0.01f
