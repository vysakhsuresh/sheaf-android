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
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.input.pointer.PointerType
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
                        // Palm rejection: once a stylus has touched the pad, only a stylus
                        // draws. A hand resting on the glass while signing is otherwise a
                        // second, smudged signature.
                        var stylusSeen = false
                        awaitPointerEventScope {
                            while (true) {
                                val down = awaitPointerEvent().changes.firstOrNull { it.pressed }
                                    ?: continue
                                if (down.type == PointerType.Stylus) stylusSeen = true
                                if (stylusSeen && down.type != PointerType.Stylus) continue

                                val stroke = SignatureStroke(size.width, size.height)
                                stroke.start(down.position, down.stylusPressure())
                                strokes += stroke
                                hasInk = true
                                saved = false
                                version++
                                down.consume()

                                // Drained here rather than through detectDragGestures so every
                                // historical point the system batched is used. A fast stroke
                                // reports several positions per frame, and dropping them is
                                // what makes handwriting look like a series of corners. Only
                                // the pointer that started the stroke is followed, so a palm
                                // landing mid-signature cannot end it.
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) {
                                        change.consume()
                                        break
                                    }
                                    val pressure = change.stylusPressure()
                                    for (historical in change.historical) {
                                        stroke.lineTo(historical.position, pressure)
                                    }
                                    stroke.lineTo(change.position, pressure)
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

                for (stroke in strokes) drawStroke(stroke, Color(0xFF101A2B), INK_WIDTH_PX)
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

    /**
     * A width multiplier per point, from a stylus's pressure. All 1 for a finger, which reports
     * a pressure too but one that measures contact area rather than how hard it pressed.
     */
    val widths = mutableListOf<Float>()

    /** True once a stylus drew any of this, which is when the widths mean something. */
    var pressured = false
        private set

    fun start(offset: Offset, pressure: Float? = null) {
        path.moveTo(offset.x, offset.y)
        points += normalise(offset, width, height)
        widths += widthFor(pressure)
    }

    fun lineTo(offset: Offset, pressure: Float? = null) {
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
        widths += widthFor(pressure)
    }

    /** The stroke's overall weight, for a mark that has one width rather than one per point. */
    val averageWidth: Float get() = if (widths.isEmpty()) 1f else widths.average().toFloat()

    private fun widthFor(pressure: Float?): Float {
        if (pressure == null) return 1f
        pressured = true
        return stylusWidth(pressure)
    }
}

/**
 * A stylus's pressure, 0 to 1, as a multiple of the normal pen width: a light touch draws at
 * under half, a firm one at nearly double. The floor keeps the lightest stroke visible.
 */
internal fun stylusWidth(pressure: Float): Float = (0.35f + pressure.coerceIn(0f, 1f) * 1.6f)

/** Draws a stroke on screen: the cheap single path for a finger, segment by segment for a stylus. */
private fun DrawScope.drawStroke(stroke: SignatureStroke, colour: Color, baseWidth: Float) {
    if (!stroke.pressured || stroke.points.size < 2) {
        drawPath(stroke.path, colour, style = Stroke(width = baseWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
        return
    }
    for (i in 1 until stroke.points.size) {
        val a = stroke.points[i - 1]
        val b = stroke.points[i]
        drawLine(
            color = colour,
            start = Offset(a.x * size.width, a.y * size.height),
            end = Offset(b.x * size.width, b.y * size.height),
            strokeWidth = baseWidth * (stroke.widths[i - 1] + stroke.widths[i]) / 2f,
            cap = StrokeCap.Round
        )
    }
}

/** The pressure to record for a pointer: a stylus's own, and nothing for a finger. */
private fun PointerInputChange.stylusPressure(): Float? =
    if (type == PointerType.Stylus) pressure else null

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
        if (stroke.pressured) {
            // A stylus signature keeps its pressure in the PNG: each segment at its own width,
            // which is what makes it look written rather than traced.
            val base = paint.strokeWidth
            for (i in 1 until points.size) {
                paint.strokeWidth = base * (stroke.widths[i - 1] + stroke.widths[i]) / 2f
                canvas.drawLine(
                    points[i - 1].x * SIGNATURE_WIDTH_PX, points[i - 1].y * SIGNATURE_HEIGHT_PX,
                    points[i].x * SIGNATURE_WIDTH_PX, points[i].y * SIGNATURE_HEIGHT_PX,
                    paint
                )
            }
            paint.strokeWidth = base
            continue
        }
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
    modifier: Modifier = Modifier,
    /** Create a form draws its boxes see-through with a label; Remove areas draws them black. */
    boxColour: Color = Color.Black,
    labels: List<String> = emptyList(),
    emptyHint: String = "Drag a box over anything that should go",
    /** Told the zoom once it settles, so the page can be drawn sharp at it. */
    onSharpen: (Float) -> Unit = {}
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
        val zoom = rememberZoomState(Unit)
        ZoomControls(zoom, Modifier.padding(top = 4.dp))

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
            ZoomFrame(zoom, Modifier.fillMaxSize(), onSettled = onSharpen) {
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
                    // Dragging a box is a gesture TalkBack cannot make, so the canvas also
                    // offers it as actions: a box across the middle of the page, which the
                    // user can then check in the preview, and clearing the page.
                    .semantics {
                        contentDescription = "Page ${pageIndex + 1}, " +
                            if (areas.isEmpty()) "no boxes" else "${areas.size} ${if (areas.size == 1) "box" else "boxes"}"
                        customActions = listOf(
                            CustomAccessibilityAction("Add a box across the middle of the page") {
                                onAdd(PageArea(0.1f, 0.45f, 0.8f, 0.1f)); true
                            },
                            CustomAccessibilityAction("Clear this page") { onClear(); true }
                        )
                    }
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
                for ((i, area) in areas.withIndex()) {
                    val topLeft = Offset(area.left * size.width, area.top * size.height)
                    val boxSize = Size(area.width * size.width, area.height * size.height)
                    drawRect(color = boxColour, topLeft = topLeft, size = boxSize)
                    if (boxColour != Color.Black) {
                        drawRect(color = boxColour.copy(alpha = 1f), topLeft = topLeft, size = boxSize, style = Stroke(width = 2f))
                    }
                    labels.getOrNull(i)?.let { label ->
                        val paint = AndroidPaint().apply {
                            color = android.graphics.Color.argb(255, 30, 50, 110)
                            textSize = (boxSize.height * 0.55f).coerceIn(10f, 26f)
                            isAntiAlias = true
                        }
                        drawContext.canvas.nativeCanvas.save()
                        drawContext.canvas.nativeCanvas.clipRect(topLeft.x, topLeft.y, topLeft.x + boxSize.width, topLeft.y + boxSize.height)
                        drawContext.canvas.nativeCanvas.drawText(label, topLeft.x + 4f, topLeft.y + boxSize.height * 0.7f, paint)
                        drawContext.canvas.nativeCanvas.restore()
                    }
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
        }

        Row(
            modifier = Modifier.padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (areas.isEmpty()) emptyHint else "${areas.size} on this page",
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
    tool: ToolConfig.MarkupTool,
    colour: Int,
    onPage: (Int) -> Unit,
    onHighlight: (PageArea) -> Unit,
    /** The stroke, and its width as a multiple of the pen's - above 1 for a firm stylus. */
    onInk: (List<PagePoint>, Float) -> Unit,
    onLine: (PagePoint, PagePoint) -> Unit,
    onTap: (PagePoint) -> Unit,
    onUndo: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    /** Told the zoom once it settles, so the page can be drawn sharp at it. */
    onSharpen: (Float) -> Unit = {}
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
        val zoom = rememberZoomState(Unit)
        ZoomControls(zoom, Modifier.padding(top = 4.dp))

        val pen = tool == ToolConfig.MarkupTool.PEN
        val lineTool = tool == ToolConfig.MarkupTool.LINE || tool == ToolConfig.MarkupTool.ARROW

        // The in-progress gestures. Neither is snapshot state that Compose walks: the stroke
        // is a Path appended to as points arrive, and one counter read inside the draw lambda
        // is what invalidates it.
        val stroke = remember(pageIndex, tool) { mutableStateOf<SignatureStroke?>(null) }
        var version by remember(pageIndex, tool) { mutableIntStateOf(0) }
        var boxFrom by remember(pageIndex, tool) { mutableStateOf<Offset?>(null) }
        var boxTo by remember(pageIndex, tool) { mutableStateOf<Offset?>(null) }

        val gestures = when {
            pen -> Modifier.pointerInput(pageIndex, tool) {
                // Palm rejection, as on the signature pad: once a stylus has drawn, a finger
                // no longer does - it is left to pinch and pan the page instead.
                var stylusSeen = false
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitPointerEvent().changes.firstOrNull { it.pressed }
                            ?: continue
                        if (down.type == PointerType.Stylus) stylusSeen = true
                        if (stylusSeen && down.type != PointerType.Stylus) continue
                        val isStylus = down.type == PointerType.Stylus
                        val drawing = SignatureStroke(size.width, size.height)
                        var cancelled = false
                        drawing.start(down.position, down.stylusPressure())
                        stroke.value = drawing
                        version++
                        down.consume()

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            // A second finger is a pinch: what the first had drawn is dropped.
                            // Not for a stylus, where the second contact is the hand holding it.
                            if (!isStylus && (event.changes.count { it.pressed } > 1 || change.isConsumed)) {
                                cancelled = true
                                while (awaitPointerEvent().changes.any { it.pressed }) Unit
                                break
                            }
                            if (!change.pressed) {
                                change.consume()
                                break
                            }
                            val pressure = change.stylusPressure()
                            for (historical in change.historical) {
                                drawing.lineTo(historical.position, pressure)
                            }
                            drawing.lineTo(change.position, pressure)
                            change.consume()
                            version++
                        }

                        // A tap is not a stroke. Handing one over would put an invisible mark
                        // in the list and count towards the total.
                        if (!cancelled && drawing.points.size >= 2) {
                            // The document's ink has one width per stroke, so a stylus stroke is
                            // written at its average pressure.
                            onInk(drawing.points.map { PagePoint(it.x, it.y) }, drawing.averageWidth)
                        }
                        stroke.value = null
                        version++
                    }
                }
            }

            tool.taps -> Modifier.pointerInput(pageIndex, tool) {
                detectTapGestures { offset ->
                    if (size.width > 0 && size.height > 0) {
                        onTap(PagePoint((offset.x / size.width).coerceIn(0f, 1f), (offset.y / size.height).coerceIn(0f, 1f)))
                    }
                }
            }

            else -> Modifier.pointerInput(pageIndex, tool) {
                detectDragGestures(
                    onDragStart = { start -> boxFrom = start; boxTo = start },
                    onDragEnd = {
                        val from = boxFrom
                        val to = boxTo
                        if (from != null && to != null && size.width > 0 && size.height > 0) {
                            if (lineTool) {
                                val length = kotlin.math.hypot(to.x - from.x, to.y - from.y)
                                if (length / size.width > MIN_BOX) {
                                    onLine(
                                        PagePoint(from.x / size.width, from.y / size.height),
                                        PagePoint(to.x / size.width, to.y / size.height)
                                    )
                                }
                            } else {
                                val left = minOf(from.x, to.x) / size.width
                                val top = minOf(from.y, to.y) / size.height
                                val width = kotlin.math.abs(to.x - from.x) / size.width
                                val height = kotlin.math.abs(to.y - from.y) / size.height
                                // A highlight is usually wide and short - one line of text -
                                // so only the long side has to clear the tap threshold.
                                if (width > MIN_BOX && height > MIN_BOX / 2f) {
                                    onHighlight(PageArea(left, top, width, height))
                                }
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
            ZoomFrame(zoom, Modifier.fillMaxSize(), onSettled = onSharpen) {
            if (page != null) {
                Image(
                    bitmap = page.asImageBitmap(),
                    contentDescription = "Page ${pageIndex + 1}",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // The same escape hatch as the redaction canvas: every tool's gesture has an action
            // that places one mark in the middle of the page, plus undo and clear.
            val a11y = Modifier.semantics {
                contentDescription = "Page ${pageIndex + 1}, " +
                    if (marks.isEmpty()) "no marks" else "${marks.size} ${if (marks.size == 1) "mark" else "marks"}"
                customActions = listOf(
                    CustomAccessibilityAction("Add a ${tool.label.lowercase()} in the middle of the page") {
                        when {
                            pen -> onInk(listOf(PagePoint(0.3f, 0.5f), PagePoint(0.7f, 0.5f)), 1f)
                            tool.taps -> onTap(PagePoint(0.5f, 0.5f))
                            lineTool -> onLine(PagePoint(0.3f, 0.5f), PagePoint(0.7f, 0.5f))
                            else -> onHighlight(PageArea(0.1f, 0.47f, 0.8f, 0.04f))
                        }
                        true
                    },
                    CustomAccessibilityAction("Undo the last mark") { onUndo(); true },
                    CustomAccessibilityAction("Clear this page") { onClear(); true }
                )
            }
            Canvas(modifier = Modifier.fillMaxSize().then(a11y).then(gestures)) {
                // Read so the draw is invalidated as points arrive. The stroke itself is
                // invisible to Compose on purpose.
                @Suppress("UNUSED_EXPRESSION") version

                // The preview is as many pixels wide as the page is points, near enough, so
                // this is the one ratio that turns a width in the document into one on screen.
                val pxPerPoint = size.width / A4_WIDTH_POINTS

                for (mark in marks) drawMark(mark, pxPerPoint)

                stroke.value?.let { drawing -> drawStroke(drawing, Color(colour), INK_WIDTH_PX) }

                val from = boxFrom
                val to = boxTo
                if (from != null && to != null) {
                    if (lineTool) {
                        drawLine(
                            color = Color(colour),
                            start = from,
                            end = to,
                            strokeWidth = INK_WIDTH_PX / 2f,
                            cap = StrokeCap.Round
                        )
                    } else {
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
            }
        }

        Row(
            modifier = Modifier.padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                when {
                    marks.isNotEmpty() -> "${marks.size} on this page"
                    pen -> "Draw on the page with your finger"
                    tool.taps -> "Tap the page where the ${tool.label.lowercase()} should go"
                    lineTool -> "Drag from where the ${tool.label.lowercase()} starts to where it ends"
                    else -> "Drag across a line to ${tool.label.lowercase()} it"
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

/** One mark, drawn the way the engine will draw it, near enough to judge by. */
private fun DrawScope.drawMark(mark: PageMark, pxPerPoint: Float) {
    val ink = Color(mark.colour)
    when (mark) {
        is PageMark.Highlight -> drawRect(
            color = ink.copy(alpha = HIGHLIGHT_PREVIEW_ALPHA),
            topLeft = Offset(mark.area.left * size.width, mark.area.top * size.height),
            size = Size(mark.area.width * size.width, mark.area.height * size.height)
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
                color = ink,
                style = Stroke(width = INK_WIDTH_PX, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }

        is PageMark.Shape -> {
            val left = mark.area.left * size.width
            val top = mark.area.top * size.height
            val right = left + mark.area.width * size.width
            val bottom = top + mark.area.height * size.height
            val width = (mark.widthPoints * pxPerPoint).coerceAtLeast(1.5f)
            when (mark.kind) {
                PageMark.Shape.Kind.UNDERLINE -> drawLine(ink, Offset(left, bottom), Offset(right, bottom), width, StrokeCap.Round)
                PageMark.Shape.Kind.STRIKETHROUGH -> {
                    val middle = (top + bottom) / 2f
                    drawLine(ink, Offset(left, middle), Offset(right, middle), width, StrokeCap.Round)
                }
                PageMark.Shape.Kind.BOX -> drawRect(
                    color = ink,
                    topLeft = Offset(left, top),
                    size = Size(right - left, bottom - top),
                    style = Stroke(width = width)
                )
                PageMark.Shape.Kind.CIRCLE -> drawOval(
                    color = ink,
                    topLeft = Offset(left, top),
                    size = Size(right - left, bottom - top),
                    style = Stroke(width = width)
                )
            }
        }

        is PageMark.Line -> {
            val from = Offset(mark.from.x * size.width, mark.from.y * size.height)
            val to = Offset(mark.to.x * size.width, mark.to.y * size.height)
            val width = (mark.widthPoints * pxPerPoint).coerceAtLeast(1.5f)
            drawLine(ink, from, to, width, StrokeCap.Round)
            if (mark.arrow) {
                val head = (width * 5f).coerceIn(10f, 40f)
                val angle = kotlin.math.atan2(to.y - from.y, to.x - from.x)
                val spread = Math.toRadians(26.0).toFloat()
                val path = Path().apply {
                    moveTo(to.x, to.y)
                    lineTo(to.x - head * kotlin.math.cos(angle - spread), to.y - head * kotlin.math.sin(angle - spread))
                    lineTo(to.x - head * kotlin.math.cos(angle + spread), to.y - head * kotlin.math.sin(angle + spread))
                    close()
                }
                drawPath(path, ink)
            }
        }

        is PageMark.Note -> {
            val textPx = mark.sizePoints * pxPerPoint
            val paint = AndroidPaint().apply {
                color = mark.colour or (0xFF shl 24)
                textSize = textPx
                isAntiAlias = true
            }
            val lines = mark.text.split('\n')
            val widest = lines.maxOf { paint.measureText(it) }
            val pad = textPx * 0.5f
            val cardWidth = (widest + pad * 2f).coerceAtMost(size.width * 0.5f)
            val cardHeight = lines.size * textPx * 1.25f + pad * 2f
            val left = (mark.at.x * size.width).coerceIn(0f, (size.width - cardWidth).coerceAtLeast(0f))
            val top = (mark.at.y * size.height).coerceIn(0f, (size.height - cardHeight).coerceAtLeast(0f))
            drawRect(Color(0xFFFFF8C6), Offset(left, top), Size(cardWidth, cardHeight))
            drawRect(Color(0xFFD9BF59), Offset(left, top), Size(cardWidth, cardHeight), style = Stroke(1f))
            drawContext.canvas.nativeCanvas.apply {
                save()
                clipRect(left, top, left + cardWidth, top + cardHeight)
                lines.forEachIndexed { i, line ->
                    drawText(line, left + pad, top + pad + textPx * 0.9f + i * textPx * 1.25f, paint)
                }
                restore()
            }
        }

        is PageMark.Stamp -> {
            val textPx = (minOf(size.width, size.height) * 0.045f).coerceIn(10f * pxPerPoint, 28f * pxPerPoint)
            val paint = AndroidPaint().apply {
                color = mark.colour or (0xFF shl 24)
                textSize = textPx
                isAntiAlias = true
                isFakeBoldText = true
            }
            val text = mark.text.uppercase()
            val textWidth = paint.measureText(text)
            val pad = textPx * 0.45f
            val boxWidth = textWidth + pad * 2f
            val boxHeight = textPx * 1.5f
            val cx = mark.at.x * size.width
            val cy = mark.at.y * size.height
            rotate(degrees = -8f, pivot = Offset(cx, cy)) {
                drawRect(
                    color = ink.copy(alpha = 0.85f),
                    topLeft = Offset(cx - boxWidth / 2f, cy - boxHeight / 2f),
                    size = Size(boxWidth, boxHeight),
                    style = Stroke(width = textPx * 0.12f)
                )
                drawContext.canvas.nativeCanvas.drawText(text, cx - textWidth / 2f, cy + textPx * 0.36f, paint)
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

/** The page the preview's stroke widths are reckoned against. */
private const val A4_WIDTH_POINTS = 595.28f
