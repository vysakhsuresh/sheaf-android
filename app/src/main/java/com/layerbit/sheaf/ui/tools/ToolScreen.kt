package com.layerbit.sheaf.ui.tools

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed as itemsIndexedInColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.layerbit.sheaf.files.SheafFile
import com.layerbit.sheaf.jobs.JobState
import com.layerbit.sheaf.ops.OpOutcome
import com.layerbit.sheaf.ops.ToolId
import com.layerbit.sheaf.ui.components.Panel
import com.layerbit.sheaf.ui.components.RowBetween
import com.layerbit.sheaf.ui.components.SectionHeading
import com.layerbit.sheaf.ui.components.formatBytes
import com.layerbit.sheaf.ui.components.formatPageCount
import com.layerbit.sheaf.ui.theme.SheafColors

/**
 * One screen for every tool: choose files, set options, run, keep the result.
 *
 * The four steps are always in the same order and always look the same, which is the point.
 * Nine tools that each invent their own layout is how an app like this becomes something you
 * have to relearn per tool.
 */
@Composable
fun ToolScreen(
    tool: ToolId,
    state: ToolUiState,
    jobState: JobState,
    onAddFiles: () -> Unit,
    onRemoveFile: (Int) -> Unit,
    onMoveFile: (Int, Int) -> Unit,
    onSetPassword: (Int, String) -> Unit,
    onVerifyPassword: (Int) -> Unit,
    onConfigChange: (((ToolConfig) -> ToolConfig)) -> Unit,
    onLoadPages: () -> Unit,
    onTogglePage: (Int) -> Unit,
    onRotateSelected: (Int) -> Unit,
    onDeleteSelected: () -> Unit,
    onMovePage: (Int, Int) -> Unit,
    onRun: () -> Unit,
    onCancel: () -> Unit,
    onSave: (SheafFile) -> Unit,
    onShare: (List<SheafFile>) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    // The organise grid needs page thumbnails, and only once a document is actually present.
    LaunchedEffect(tool, state.documents.firstOrNull()?.file?.file?.path) {
        if (tool == ToolId.ORGANISE && state.documents.isNotEmpty()) onLoadPages()
    }

    val finished = jobState as? JobState.Finished
    val running = jobState as? JobState.Running

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column {
                Text(tool.title, style = MaterialTheme.typography.titleLarge, color = SheafColors.Text)
                Text(tool.summary, style = MaterialTheme.typography.bodyMedium, color = SheafColors.Muted)
            }
        }

        when {
            running != null -> item { RunningPanel(running, onCancel) }

            finished != null -> {
                item { SectionHeading("Result") }
                item { ResultSummary(finished) }
                items(finished.produced, key = { it.file.file.path }) { produced ->
                    ResultRow(produced.file, onSave = { onSave(produced.file) })
                }
                items(finished.failed) { failure -> ProblemRow(failure.inputName, failure.reason, isError = true) }
                items(finished.skipped) { skip -> ProblemRow(skip.inputName, skip.reason, isError = false) }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (finished.produced.isNotEmpty()) {
                            Button(
                                onClick = { onShare(finished.produced.map { it.file }) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = SheafColors.Band,
                                    contentColor = SheafColors.OnBand
                                )
                            ) { Text(if (finished.produced.size > 1) "Share all" else "Share") }
                        }
                        OutlinedButton(onClick = onDone) { Text("Done", color = SheafColors.Muted) }
                    }
                }
            }

            else -> {
                item { SectionHeading(if (tool.acceptsMultiple) "Files" else "File") }

                itemsIndexedInColumn(state.documents) { index, doc ->
                    DocumentRow(
                        doc = doc,
                        canReorder = tool == ToolId.MERGE && state.documents.size > 1,
                        isFirst = index == 0,
                        isLast = index == state.documents.lastIndex,
                        onRemove = { onRemoveFile(index) },
                        onUp = { onMoveFile(index, index - 1) },
                        onDown = { onMoveFile(index, index + 1) },
                        onPasswordChange = { onSetPassword(index, it) },
                        onPasswordDone = { onVerifyPassword(index) }
                    )
                }

                item {
                    OutlinedButton(onClick = onAddFiles, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            when {
                                state.documents.isEmpty() && tool.input == ToolId.InputKind.IMAGES -> "Choose images"
                                state.documents.isEmpty() -> "Choose a file"
                                tool.acceptsMultiple -> "Add another"
                                else -> "Choose a different file"
                            },
                            color = SheafColors.Text
                        )
                    }
                }

                if (tool == ToolId.ORGANISE && state.pageOrder.isNotEmpty()) {
                    item {
                        OrganisePanel(
                            state = state,
                            onTogglePage = onTogglePage,
                            onRotate = onRotateSelected,
                            onDelete = onDeleteSelected,
                            onMovePage = onMovePage
                        )
                    }
                } else if (state.documents.isNotEmpty()) {
                    item {
                        ToolOptions(
                            tool = tool,
                            config = state.config,
                            pageCount = state.documents.firstOrNull()?.pageCount,
                            onChange = onConfigChange
                        )
                    }
                }

                state.error?.let { message ->
                    item { ProblemRow(null, message, isError = true) }
                }

                item {
                    Spacer(Modifier.height(4.dp))
                    Button(
                        onClick = onRun,
                        enabled = state.canRun,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SheafColors.Band,
                            contentColor = SheafColors.OnBand,
                            disabledContainerColor = SheafColors.SurfaceDim,
                            disabledContentColor = SheafColors.Dim
                        )
                    ) {
                        Text(tool.title, style = MaterialTheme.typography.titleMedium)
                    }
                }

                if (state.busy) {
                    item {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = SheafColors.Band, strokeWidth = 2.dp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RunningPanel(running: JobState.Running, onCancel: () -> Unit) {
    Panel(modifier = Modifier.fillMaxWidth()) {
        Text(running.opTitle, style = MaterialTheme.typography.titleMedium, color = SheafColors.Text)
        if (running.label.isNotEmpty()) {
            Text(
                running.label,
                style = MaterialTheme.typography.bodySmall,
                color = SheafColors.Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(12.dp))
        if (running.unitsTotal > 0) {
            LinearProgressIndicator(
                progress = { running.fraction },
                color = SheafColors.Band,
                trackColor = SheafColors.SurfaceDim,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "${running.unitsDone} of ${running.unitsTotal}",
                style = MaterialTheme.typography.bodySmall,
                color = SheafColors.Dim,
                modifier = Modifier.padding(top = 6.dp)
            )
        } else {
            LinearProgressIndicator(
                color = SheafColors.Band,
                trackColor = SheafColors.SurfaceDim,
                modifier = Modifier.fillMaxWidth()
            )
        }
        TextButton(onClick = onCancel, modifier = Modifier.padding(top = 4.dp)) {
            Text("Cancel", color = SheafColors.Muted)
        }
    }
}

@Composable
private fun ResultSummary(finished: JobState.Finished) {
    val text = when {
        finished.produced.isEmpty() && finished.failed.isNotEmpty() -> "Nothing was produced."
        finished.allSucceeded && finished.produced.size == 1 -> "One file is ready."
        finished.allSucceeded -> "${finished.produced.size} files are ready."
        else -> "${finished.produced.size} of ${finished.outcomes.size} finished."
    }
    Text(text, style = MaterialTheme.typography.bodyMedium, color = SheafColors.Muted)
}

@Composable
private fun ResultRow(file: SheafFile, onSave: () -> Unit) {
    Panel(modifier = Modifier.fillMaxWidth()) {
        Text(
            file.displayName,
            style = MaterialTheme.typography.titleMedium,
            color = SheafColors.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        RowBetween(modifier = Modifier.padding(top = 4.dp)) {
            Text(formatBytes(file.sizeBytes), style = MaterialTheme.typography.bodySmall, color = SheafColors.Dim)
            TextButton(onClick = onSave) { Text("Save", color = SheafColors.Band) }
        }
    }
}

@Composable
private fun ProblemRow(name: String?, reason: String, isError: Boolean) {
    val accent = if (isError) SheafColors.Failed else SheafColors.Skipped
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(SheafColors.SurfaceDim)
            .border(1.dp, accent.copy(alpha = 0.45f), shape)
            .padding(14.dp)
    ) {
        if (!name.isNullOrBlank()) {
            Text(name, style = MaterialTheme.typography.titleMedium, color = accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(reason, style = MaterialTheme.typography.bodyMedium, color = SheafColors.Muted)
    }
}

@Composable
private fun DocumentRow(
    doc: SelectedDoc,
    canReorder: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    onRemove: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onPasswordChange: (String) -> Unit,
    onPasswordDone: () -> Unit
) {
    Panel(modifier = Modifier.fillMaxWidth()) {
        Text(
            doc.file.displayName,
            style = MaterialTheme.typography.titleMedium,
            color = SheafColors.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        val detail = buildString {
            doc.pageCount?.let { append(formatPageCount(it)).append(" · ") }
            append(formatBytes(doc.file.sizeBytes))
            if (doc.encrypted && !doc.needsPassword) append(" · protected")
        }
        Text(detail, style = MaterialTheme.typography.bodySmall, color = SheafColors.Dim)

        doc.unreadableReason?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = SheafColors.Failed, modifier = Modifier.padding(top = 6.dp))
        }

        if (doc.needsPassword) {
            Spacer(Modifier.height(8.dp))
            Text(
                "This document is password-protected.",
                style = MaterialTheme.typography.bodySmall,
                color = SheafColors.Skipped
            )
            PasswordPrompt(
                value = doc.password.orEmpty(),
                onChange = onPasswordChange,
                onSubmit = onPasswordDone
            )
        }

        RowBetween(modifier = Modifier.padding(top = 4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (canReorder) {
                    TextButton(onClick = onUp, enabled = !isFirst) { Text("Up", color = SheafColors.Muted) }
                    TextButton(onClick = onDown, enabled = !isLast) { Text("Down", color = SheafColors.Muted) }
                }
            }
            TextButton(onClick = onRemove) { Text("Remove", color = SheafColors.Dim) }
        }
    }
}

@Composable
private fun PasswordPrompt(value: String, onChange: (String) -> Unit, onSubmit: () -> Unit) {
    Column {
        PasswordField(value = value, label = "Password", onChange = onChange)
        TextButton(onClick = onSubmit) { Text("Unlock", color = SheafColors.Band) }
    }
}

/**
 * The page grid for Organise.
 *
 * Tap to select, then rotate or delete the selection. Reordering is by arrows rather than drag
 * and drop: drag inside a scrolling grid is finicky to get right and easy to trigger by
 * accident, and getting page order wrong is not a small mistake. Drag can come later, once
 * there is a reason to trust it.
 */
@Composable
private fun OrganisePanel(
    state: ToolUiState,
    onTogglePage: (Int) -> Unit,
    onRotate: (Int) -> Unit,
    onDelete: () -> Unit,
    onMovePage: (Int, Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        RowBetween {
            SectionHeading("${state.pageOrder.size} pages")
            if (state.selectedPages.isNotEmpty()) {
                Text(
                    "${state.selectedPages.size} selected",
                    style = MaterialTheme.typography.bodySmall,
                    color = SheafColors.BandBright
                )
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(96.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            // A nested scrollable needs a bounded height or it cannot measure inside a
            // LazyColumn. Tall enough for three rows, and it scrolls within itself.
            modifier = Modifier.heightIn(max = 420.dp)
        ) {
            itemsIndexed(state.pageOrder, key = { _, page -> page }) { position, page ->
                PageThumb(
                    position = position,
                    pageIndex = page,
                    state = state,
                    onTap = { onTogglePage(page) },
                    onLeft = { onMovePage(position, position - 1) },
                    onRight = { onMovePage(position, position + 1) }
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { onRotate(90) },
                enabled = state.selectedPages.isNotEmpty()
            ) { Text("Rotate", color = SheafColors.Text) }
            OutlinedButton(
                onClick = onDelete,
                enabled = state.selectedPages.isNotEmpty()
            ) { Text("Delete", color = SheafColors.Failed) }
        }
    }
}

@Composable
private fun PageThumb(
    position: Int,
    pageIndex: Int,
    state: ToolUiState,
    onTap: () -> Unit,
    onLeft: () -> Unit,
    onRight: () -> Unit
) {
    val selected = pageIndex in state.selectedPages
    val rotation = state.rotations[pageIndex] ?: 0
    val shape = RoundedCornerShape(6.dp)

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.707f)
                .clip(shape)
                .background(SheafColors.Paper)
                .border(2.dp, if (selected) SheafColors.Band else SheafColors.Border, shape)
                .clickable(onClick = onTap),
            contentAlignment = Alignment.Center
        ) {
            val thumb = state.thumbnails[pageIndex]
            if (thumb != null) {
                Image(
                    bitmap = thumb.asImageBitmap(),
                    contentDescription = "Page ${pageIndex + 1}",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        // Shows the chosen rotation without re-rendering the page to apply it.
                        .rotate(rotation.toFloat())
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onLeft, enabled = position > 0, modifier = Modifier.size(34.dp)) {
                Text("‹", color = SheafColors.Muted)
            }
            Text(
                "${position + 1}",
                style = MaterialTheme.typography.bodySmall,
                color = if (rotation != 0) SheafColors.BandBright else SheafColors.Dim
            )
            TextButton(
                onClick = onRight,
                enabled = position < state.pageOrder.lastIndex,
                modifier = Modifier.size(34.dp)
            ) { Text("›", color = SheafColors.Muted) }
        }
    }
}
