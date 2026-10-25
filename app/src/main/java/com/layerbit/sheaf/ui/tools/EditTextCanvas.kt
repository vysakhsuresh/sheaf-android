package com.layerbit.sheaf.ui.tools

import androidx.compose.ui.res.stringResource
import com.layerbit.sheaf.R
import android.graphics.Bitmap
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.layerbit.sheaf.pdf.EditablePage
import com.layerbit.sheaf.pdf.OcrScript
import com.layerbit.sheaf.pdf.TextEdit
import com.layerbit.sheaf.pdf.TypeFamily
import com.layerbit.sheaf.ui.components.Panel
import com.layerbit.sheaf.ui.components.SecondaryButton
import com.layerbit.sheaf.ui.components.SectionHeading
import com.layerbit.sheaf.ui.components.ToggleRow
import com.layerbit.sheaf.ui.theme.SheafColors

/**
 * A page with every line that can be changed outlined, cell by cell in a table. Tap one to
 * change it below; the page shows the change in the page's own paper and ink as it will be
 * written.
 */
@Composable
fun EditTextCanvas(
    pageIndex: Int,
    pageCount: Int,
    page: Bitmap?,
    editable: EditablePage?,
    edits: List<TextEdit>,
    selected: Int?,
    selectedText: String?,
    reading: Boolean,
    script: OcrScript,
    onPage: (Int) -> Unit,
    onSelect: (Int?) -> Unit,
    onText: (String) -> Unit,
    onUndo: () -> Unit,
    onRead: () -> Unit,
    onScript: (OcrScript) -> Unit,
    onSharpen: (Float) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val lines = editable?.lines.orEmpty()
    val currentLines by rememberUpdatedState(lines)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { onPage(pageIndex - 1) }, enabled = pageIndex > 0) { Text(stringResource(R.string.tools_previous), color = SheafColors.Muted) }
            Text(stringResource(R.string.tools_page_of, pageIndex + 1, pageCount), style = MaterialTheme.typography.bodyMedium, color = SheafColors.Text)
            OutlinedButton(onClick = { onPage(pageIndex + 1) }, enabled = pageIndex < pageCount - 1) { Text(stringResource(R.string.tools_next), color = SheafColors.Muted) }
        }
        val zoom = rememberZoomState(Unit)
        ZoomControls(zoom, Modifier.padding(top = 4.dp))

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
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(pageIndex) {
                        detectTapGestures { p ->
                            val x = p.x / size.width
                            val y = p.y / size.height
                            val hit = currentLines.indices.firstOrNull { i ->
                                val a = currentLines[i].area
                                x in (a.left - 0.01f)..(a.left + a.width + 0.01f) && y in a.top..(a.top + a.height)
                            }
                            onSelect(hit)
                        }
                    }
            ) {
                if (page != null) drawImage(page.asImageBitmap(), dstSize = IntSize(size.width.toInt(), size.height.toInt()))
                val pointsWide = editable?.widthPoints?.takeIf { it > 0f } ?: 595f
                val pxPerPoint = size.width / pointsWide
                for (e in edits) {
                    drawRect(
                        Color(e.paper),
                        topLeft = Offset(e.cover.left * size.width, e.cover.top * size.height),
                        size = Size(e.cover.width * size.width, e.cover.height * size.height)
                    )
                    if (e.text.isBlank()) continue
                    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        color = e.ink
                        typeface = Typeface.create(
                            when (e.family) {
                                TypeFamily.SERIF -> Typeface.SERIF
                                TypeFamily.MONO -> Typeface.MONOSPACE
                                TypeFamily.SANS -> Typeface.SANS_SERIF
                            },
                            (if (e.bold) Typeface.BOLD else 0) or (if (e.italic) Typeface.ITALIC else 0)
                        )
                        textSize = e.sizePoints * pxPerPoint
                    }
                    val natural = paint.measureText(e.text)
                    val room = (if (e.alignRight) e.right - e.roomLeft else e.roomRight - e.left) * size.width
                    if (room > 0f && natural > room) {
                        val squeeze = maxOf(0.85f, room / natural)
                        paint.textScaleX = squeeze
                        if (natural * squeeze > room) paint.textSize *= maxOf(0.5f, room / (natural * squeeze))
                    }
                    val drawn = paint.measureText(e.text)
                    val x = if (e.alignRight) e.right * size.width - drawn else e.left * size.width
                    drawContext.canvas.nativeCanvas.drawText(e.text, x, e.baseline * size.height, paint)
                }
                for ((i, line) in lines.withIndex()) {
                    val a = line.area
                    drawRect(
                        SheafColors.Band.copy(alpha = if (i == selected) 1f else 0.45f),
                        topLeft = Offset(a.left * size.width, a.top * size.height),
                        size = Size(a.width * size.width, a.height * size.height),
                        style = Stroke(width = if (i == selected) 3.5f else 1.4f)
                    )
                }
            }
            }
        }

        val hint = when {
            editable == null -> stringResource(R.string.tools_edit_reading_page)
            editable.scanned && lines.isEmpty() && reading -> stringResource(R.string.tools_edit_reading_words)
            editable.scanned && lines.isEmpty() ->
                stringResource(R.string.tools_edit_scanned_unread)
            editable.scanned ->
                stringResource(R.string.tools_edit_scanned)
            lines.isEmpty() ->
                stringResource(R.string.tools_edit_no_text)
            else -> stringResource(R.string.tools_edit_hint)
        }
        Text(hint, style = MaterialTheme.typography.bodySmall, color = SheafColors.Dim, modifier = Modifier.padding(top = 10.dp))

        if (editable?.scanned == true && lines.isEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                if (reading) {
                    CircularProgressIndicator(color = SheafColors.Band, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                } else {
                    SecondaryButton(text = stringResource(R.string.tools_ocr_read), onClick = onRead, tall = false, modifier = Modifier.weight(1f))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                for (s in OcrScript.entries) {
                    val on = s == script
                    OutlinedButton(onClick = { onScript(s) }) {
                        Text(if (s == OcrScript.LATIN) stringResource(R.string.tools_script_latin) else stringResource(R.string.tools_script_devanagari), color = if (on) SheafColors.Band else SheafColors.Muted)
                    }
                }
            }
        }

        if (selected != null && selectedText != null) {
            Field(
                value = selectedText,
                label = stringResource(R.string.tools_line_tapped),
                keyboardType = KeyboardType.Text,
                onChange = onText
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                SecondaryButton(text = stringResource(R.string.tools_put_back), onClick = onUndo, tall = false)
                SecondaryButton(text = stringResource(R.string.tools_done_line), onClick = { onSelect(null) }, tall = false)
            }
        }
    }
}

/** Find and replace, across every page whose words can be read. */
@Composable
fun ReplacePanel(
    find: String,
    with: String,
    matchCase: Boolean,
    busy: Boolean,
    status: String?,
    onFind: (String) -> Unit,
    onWith: (String) -> Unit,
    onMatchCase: (Boolean) -> Unit,
    onReplace: () -> Unit
) {
    Panel(modifier = Modifier.fillMaxWidth(), inset = 14.dp) {
        SectionHeading(stringResource(R.string.tools_find_replace))
        Field(value = find, label = stringResource(R.string.tools_find), keyboardType = KeyboardType.Text, onChange = onFind)
        Field(value = with, label = stringResource(R.string.tools_replace_with), keyboardType = KeyboardType.Text, onChange = onWith)
        ToggleRow(title = stringResource(R.string.tools_match_case), checked = matchCase, onChange = onMatchCase)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            SecondaryButton(text = stringResource(R.string.tools_replace_everywhere), onClick = onReplace, enabled = !busy && find.isNotBlank(), tall = false)
            if (busy) CircularProgressIndicator(color = SheafColors.Band, strokeWidth = 2.dp, modifier = Modifier.padding(start = 10.dp).size(18.dp))
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = SheafColors.Muted, modifier = Modifier.padding(top = 6.dp)) }
    }
}
