package com.layerbit.sheaf.ui.tools

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.layerbit.sheaf.R
import com.layerbit.sheaf.files.SheafFile
import com.layerbit.sheaf.jobs.JobState
import com.layerbit.sheaf.ops.OpOutcome
import com.layerbit.sheaf.ops.ToolId
import com.layerbit.sheaf.ui.components.ListRhythm
import com.layerbit.sheaf.ui.components.Panel
import com.layerbit.sheaf.ui.components.ProblemRow
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
    /** Starred tools, so the chain sheet offers them first. */
    favourites: Set<String> = emptySet(),
    onAddFiles: () -> Unit,
    onRemoveFile: (Int) -> Unit,
    onMoveFile: (Int, Int) -> Unit,
    onSetPassword: (Int, String) -> Unit,
    onVerifyPassword: (Int) -> Unit,
    onConfigChange: (((ToolConfig) -> ToolConfig)) -> Unit,
    onLoadPages: () -> Unit,
    onTogglePage: (Int) -> Unit,
    onSelectAllPages: () -> Unit,
    onClearPageSelection: () -> Unit,
    onRotateSelected: (Int) -> Unit,
    onDeleteSelected: () -> Unit,
    onMovePage: (Int, Int) -> Unit,
    onSigned: (java.io.File) -> Unit,
    makeSignatureFile: () -> java.io.File,
    onLoadSignatures: () -> Unit,
    onKeepSignature: () -> Unit,
    onUseSignature: (java.io.File) -> Unit,
    onForgetSignature: (java.io.File) -> Unit,
    onUndoPageEdit: () -> Unit,
    onRedactPage: (Int) -> Unit,
    onMarkupPage: (Int) -> Unit,
    onHighlight: (Int, com.layerbit.sheaf.pdf.PageArea) -> Unit,
    onInk: (Int, List<com.layerbit.sheaf.pdf.PagePoint>) -> Unit,
    onUndoMark: (Int) -> Unit,
    onClearMarks: (Int) -> Unit,
    onAddRedaction: (Int, com.layerbit.sheaf.pdf.PageArea) -> Unit,
    onClearRedactions: (Int) -> Unit,
    onUndoRedaction: (Int) -> Unit,
    onRun: () -> Unit,
    onCancel: () -> Unit,
    onSave: (SheafFile) -> Unit,
    onSaveAll: (List<SheafFile>) -> Unit,
    onShare: (List<SheafFile>) -> Unit,
    onOpenResult: (SheafFile) -> Unit,
    /** Hands a finished file straight to another tool, with nothing saved in between. */
    onChainTool: (ToolId, SheafFile) -> Unit,
    onLoadPreview: (SheafFile) -> Unit,
    onLoadPreviewPage: () -> Unit,
    onLoadForm: () -> Unit,
    onFormValue: (String, String) -> Unit,
    onCheckSize: () -> Unit,
    onCopy: (String) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Organise and Remove areas both draw on rendered pages, and only once a document is
    // actually present.
    //
    // The password is a key as well. Unlocking a protected document throws away the page
    // order it could not read, and this effect is the only thing that ever asks for a new
    // one - so without it the prompt disappears, the Run button goes live, and the grid that
    // is the entire point of the screen never arrives. Both loaders return early when their
    // work is already done, so re-running this costs nothing where nothing changed.
    LaunchedEffect(
        tool,
        state.documents.firstOrNull()?.file?.file?.path,
        state.documents.firstOrNull()?.needsPassword,
        state.config.markPage
    ) {
        if (state.documents.isEmpty()) return@LaunchedEffect
        when (tool) {
            // These edit pages, so they need the pages themselves.
            // Extract shows them to be tapped rather than typed.
            ToolId.ORGANISE, ToolId.REDACT, ToolId.EXTRACT, ToolId.ANNOTATE -> onLoadPages()
            // These change how a page looks, so one page is enough to show it.
            ToolId.CROP, ToolId.WATERMARK, ToolId.PAGE_NUMBERS,
            ToolId.ADD_TEXT -> onLoadPreviewPage()
            // Filling a form needs to know whether there is one.
            ToolId.FORMS -> onLoadForm()
            // Signing offers the ones kept earlier before offering the pad.
            ToolId.SIGN -> {
                onLoadPreviewPage()
                onLoadSignatures()
            }
            else -> Unit
        }
    }

    val finished = jobState as? JobState.Finished
    val running = jobState as? JobState.Running
    val crashed = jobState as? JobState.Crashed

    /** The finished file waiting for the user to pick the next tool for it. */
    var chaining by remember { mutableStateOf<SheafFile?>(null) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = ListRhythm,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        when {
            running != null -> item { RunningPanel(running, onCancel) }

            finished != null -> {
                item { SectionHeading("Result") }
                item { ResultSummary(finished, state.saved) }
                items(finished.produced, key = { it.file.file.path }) { produced ->
                    ResultRow(
                        outcome = produced,
                        preview = state.previews[produced.file.file.path],
                        saved = produced.file.file.path in state.saved,
                        onSave = { onSave(produced.file) },
                        onOpen = { onOpenResult(produced.file) },
                        onChain = { chaining = produced.file },
                        onCopy = onCopy,
                        onNeedPreview = { onLoadPreview(produced.file) }
                    )
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
                        // One folder for the whole batch. Forty files through the system
                        // picker one at a time is the thing that makes an export tool useless.
                        if (finished.produced.size > 1) {
                            OutlinedButton(
                                onClick = { onSaveAll(finished.produced.map { it.file }) }
                            ) {
                                Text("Save all", color = SheafColors.Muted)
                            }
                        }
                        OutlinedButton(onClick = onDone) { Text("Done", color = SheafColors.Muted) }
                    }
                }
            }

            // The job could not run at all - out of memory, or the system refusing to start
            // the foreground service. Four places publish this and nothing used to read it,
            // so the screen fell through to the ordinary setup form and a compress killed
            // while the app was in the background returned the user to a live Run button
            // with nothing said. Cancelled is deliberately left out: the runner reports only
            // a cancel the user asked for, and returning someone who pressed Stop to their
            // untouched form is the right answer.
            crashed != null -> {
                item { SectionHeading("It did not run") }
                item { ProblemRow(null, crashed.reason, isError = true) }
                item {
                    // onRun clears the crashed state before it submits, which is what gets
                    // the screen out of here even when the run cannot start a second time.
                    Button(
                        onClick = onRun,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SheafColors.Band,
                            contentColor = SheafColors.OnBand
                        )
                    ) { Text("Try again") }
                }
            }

            else -> {
                item { SectionHeading(if (tool.acceptsMultiple) "Files" else "File") }

                // Keyed by the file, which is what makes the reorder legible: without a key
                // these rows are identified by position, so moving one up swapped two sets
                // of contents in place and animated nothing. Merge order is the whole point
                // of this list, so seeing which row moved is the feature.
                itemsIndexedInColumn(
                    state.documents,
                    key = { _, doc -> doc.file.file.path }
                ) { index, doc ->
                    DocumentRow(
                        doc = doc,
                        canReorder = tool == ToolId.MERGE && state.documents.size > 1,
                        isFirst = index == 0,
                        isLast = index == state.documents.lastIndex,
                        onRemove = { onRemoveFile(index) },
                        onUp = { onMoveFile(index, index - 1) },
                        onDown = { onMoveFile(index, index + 1) },
                        onPasswordChange = { onSetPassword(index, it) },
                        onPasswordDone = { onVerifyPassword(index) },
                        modifier = Modifier.animateItem(placementSpec = tween(220))
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
                    // The wait belongs to the file that was just added, so it is said here
                    // rather than under the Run button - which is greyed and, because
                    // blockedReason returns null while busy, silent for as long as it lasts.
                    state.busyLabel?.let { label ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.padding(top = 10.dp)
                        ) {
                            CircularProgressIndicator(
                                color = SheafColors.Band,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                label,
                                style = MaterialTheme.typography.bodySmall,
                                color = SheafColors.Muted
                            )
                        }
                    }
                }

                if (tool == ToolId.ORGANISE && state.pageOrder.isNotEmpty()) {
                    item {
                        OrganisePanel(
                            pageOrder = state.pageOrder,
                            selectedPages = state.selectedPages,
                            rotations = state.rotations,
                            thumbnails = state.thumbnails,
                            thumbnailsCapped = state.thumbnailsCapped,
                            canUndoPageEdit = state.canUndoPageEdit,
                            onTogglePage = onTogglePage,
                            onRotate = onRotateSelected,
                            onDelete = onDeleteSelected,
                            onMovePage = onMovePage,
                            onUndo = onUndoPageEdit
                        )
                    }
                } else if (tool == ToolId.SIGN && state.documents.isNotEmpty()) {
                    if (state.savedSignatures.isNotEmpty()) {
                        item {
                            SavedSignatures(
                                files = state.savedSignatures,
                                onUse = onUseSignature,
                                onForget = onForgetSignature
                            )
                        }
                    }
                    item {
                        SignaturePad(onSigned = onSigned, makeFile = makeSignatureFile)
                    }
                    if (state.signatureFile != null) {
                        item {
                            TextButton(onClick = onKeepSignature) {
                                Text(
                                    "Keep this signature for next time",
                                    color = SheafColors.BandBright,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                    item {
                        ToolOptions(
                            tool = tool,
                            config = state.config,
                            pageCount = state.documents.firstOrNull()?.pageCount,
                            sizeCheck = state.sizeCheck,
                            onCheckSize = onCheckSize,
                            onChange = onConfigChange
                        )
                    }
                    item {
                        EffectPreview(
                            tool = tool,
                            config = state.config,
                            page = state.previewPage,
                            signature = state.signatureBitmap,
                            locked = state.documents.firstOrNull()?.needsPassword == true,
                            previewFailed = state.previewFailed
                        )
                    }
                } else if (tool == ToolId.EXTRACT && state.pageOrder.isNotEmpty()) {
                    item {
                        PageChooser(
                            pageOrder = state.pageOrder,
                            selectedPages = state.selectedPages,
                            rotations = state.rotations,
                            thumbnails = state.thumbnails,
                            thumbnailsCapped = state.thumbnailsCapped,
                            onTogglePage = onTogglePage,
                            onAll = onSelectAllPages,
                            onNone = onClearPageSelection
                        )
                    }
                    item {
                        ToolOptions(
                            tool = tool,
                            config = state.config,
                            pageCount = state.documents.firstOrNull()?.pageCount,
                            sizeCheck = state.sizeCheck,
                            onCheckSize = onCheckSize,
                            onChange = onConfigChange
                        )
                    }
                } else if (tool == ToolId.FORMS && state.documents.isNotEmpty()) {
                    if (!state.formRead) {
                        item { Hint("Looking for form fields…") }
                    } else if (state.formFields.isEmpty()) {
                        item { NoFormFields() }
                    } else {
                        item {
                            FormEditor(
                                fields = state.formFields,
                                values = state.formValues,
                                onValue = onFormValue
                            )
                        }
                        item {
                            ToolOptions(
                                tool = tool,
                                config = state.config,
                                pageCount = state.documents.firstOrNull()?.pageCount,
                                sizeCheck = state.sizeCheck,
                                onCheckSize = onCheckSize,
                                onChange = onConfigChange
                            )
                        }
                    }
                } else if (tool == ToolId.ANNOTATE && state.pageOrder.isNotEmpty()) {
                    item {
                        MarkupCanvas(
                            pageIndex = state.canvasPage,
                            pageCount = state.pageOrder.size,
                            page = state.thumbnails[state.canvasPage],
                            marks = state.marks[state.canvasPage].orEmpty(),
                            pen = state.config.markupPen,
                            colour = state.config.markupColour,
                            onPage = onMarkupPage,
                            onHighlight = { area -> onHighlight(state.canvasPage, area) },
                            onInk = { points -> onInk(state.canvasPage, points) },
                            onUndo = { onUndoMark(state.canvasPage) },
                            onClear = { onClearMarks(state.canvasPage) },
                            documentTotal = state.marks.values.sumOf { it.size },
                            pageFailed = state.canvasPage in state.failedPages
                        )
                    }
                    item {
                        ToolOptions(
                            tool = tool,
                            config = state.config,
                            pageCount = state.documents.firstOrNull()?.pageCount,
                            sizeCheck = state.sizeCheck,
                            onCheckSize = onCheckSize,
                            onChange = onConfigChange
                        )
                    }
                } else if (tool == ToolId.REDACT && state.pageOrder.isNotEmpty()) {
                    item {
                        RedactCanvas(
                            pageIndex = state.canvasPage,
                            pageCount = state.pageOrder.size,
                            page = state.thumbnails[state.canvasPage],
                            areas = state.redactions[state.canvasPage].orEmpty(),
                            onPage = onRedactPage,
                            onAdd = { area -> onAddRedaction(state.canvasPage, area) },
                            onClear = { onClearRedactions(state.canvasPage) },
                            documentTotal = state.redactions.values.sumOf { it.size },
                            onUndo = { onUndoRedaction(state.canvasPage) },
                            pageFailed = state.canvasPage in state.failedPages
                        )
                    }
                    item {
                        ToolOptions(
                            tool = tool,
                            config = state.config,
                            pageCount = state.documents.firstOrNull()?.pageCount,
                            sizeCheck = state.sizeCheck,
                            onCheckSize = onCheckSize,
                            onChange = onConfigChange
                        )
                    }
                } else if (state.documents.isNotEmpty()) {
                    item {
                        ToolOptions(
                            tool = tool,
                            config = state.config,
                            pageCount = state.documents.firstOrNull()?.pageCount,
                            sizeCheck = state.sizeCheck,
                            onCheckSize = onCheckSize,
                            onChange = onConfigChange
                        )
                    }
                    if (tool == ToolId.CROP || tool == ToolId.WATERMARK ||
                        tool == ToolId.PAGE_NUMBERS || tool == ToolId.ADD_TEXT
                    ) {
                        item {
                            EffectPreview(
                                tool = tool,
                                config = state.config,
                                page = state.previewPage,
                                signature = null,
                                locked = state.documents.firstOrNull()?.needsPassword == true,
                                previewFailed = state.previewFailed,
                                // Only Add text is placed by hand; the others sit where their
                                // own settings put them, and a stray tap should not move them.
                                onPlace = if (tool == ToolId.ADD_TEXT) {
                                    { x, y -> onConfigChange { it.copy(noteLeft = x, noteTop = y) } }
                                } else {
                                    null
                                }
                            )
                        }
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
                        Text(tool.action, style = MaterialTheme.typography.titleMedium)
                    }
                    // Saying why beats a grey button that looks broken.
                    state.blockedReason?.takeIf { state.documents.isNotEmpty() || !state.busy }
                        ?.let { reason ->
                            Text(
                                reason,
                                style = MaterialTheme.typography.bodySmall,
                                color = SheafColors.Dim,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }
                }
            }
        }
    }

    // The same tool is left in the list on purpose: a second note on another page, or a
    // second range out of the file just split, are both things people want next.
    chaining?.let { file ->
        ToolPickerSheet(
            heading = "Use another tool on this",
            documentName = file.displayName,
            favourites = favourites,
            onDismiss = { chaining = null },
            onPick = { next ->
                chaining = null
                onChainTool(next, file)
            }
        )
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
            // Glided between units instead of jumped.
            //
            // Progress arrives one unit at a time, so a twelve-page compress used to make
            // twelve 8% leaps and stand perfectly still in between - and a bar that is not
            // moving is what makes someone force-quit an operation that is working fine.
            //
            // Linear, not an ease: this stands for real work, and an ease-out would claim
            // the end is nearer than it is. The "N of M" line below is left unanimated on
            // purpose, so the exact count sits beside the approximate bar. Note the panel is
            // replaced the moment the job finishes, so the last glide is cut off part way
            // and the bar is never seen to reach the end - which is fine, because what
            // replaces it says the job is done, but it is better said than discovered.
            val shown by animateFloatAsState(
                targetValue = running.fraction,
                animationSpec = tween(320, easing = LinearEasing),
                label = "progress"
            )
            LinearProgressIndicator(
                progress = { shown },
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

/**
 * What was produced, and whether any of it is anywhere the user can find it.
 *
 * A result lives in Sheaf's workspace, which is trimmed on the next launch, and nothing in
 * the app lists what is in there - so Done is the last moment the file is reachable. Settings
 * already carries the right sentence, "Results waiting to be saved", on the wrong screen. The
 * second sentence drops away as the files are saved rather than staying as a standing warning.
 */
@Composable
private fun ResultSummary(finished: JobState.Finished, saved: Set<String>) {
    val text = when {
        finished.produced.isEmpty() && finished.failed.isNotEmpty() -> "Nothing was produced."
        finished.allSucceeded && finished.produced.size == 1 -> "One file is ready."
        finished.allSucceeded -> "${finished.produced.size} files are ready."
        else -> "${finished.produced.size} of ${finished.outcomes.size} finished."
    }
    val unsaved = finished.produced.count { it.file.file.path !in saved }
    val note = when {
        unsaved == 0 -> null
        finished.produced.size == 1 -> "It is not saved to your phone yet."
        else -> "They are not saved to your phone yet."
    }
    Text(
        listOfNotNull(text, note).joinToString(" "),
        style = MaterialTheme.typography.bodyMedium,
        color = SheafColors.Muted
    )
}

/**
 * One finished file, and enough of it to see that something happened.
 *
 * The detail line and the preview are the point. Several tools produce a file that looks
 * exactly like its input - Make searchable most of all - and a row saying only "2.1 MB" reads
 * as the tool having done nothing at all.
 */
@Composable
private fun ResultRow(
    outcome: OpOutcome.Produced,
    preview: ResultPreview?,
    saved: Boolean,
    onSave: () -> Unit,
    onOpen: () -> Unit,
    onChain: () -> Unit,
    onCopy: (String) -> Unit,
    onNeedPreview: () -> Unit
) {
    val file = outcome.file
    LaunchedEffect(file.file.path) {
        if (file.isText || file.isImage) onNeedPreview()
    }

    Panel(modifier = Modifier.fillMaxWidth()) {
        Text(
            file.displayName,
            style = MaterialTheme.typography.titleMedium,
            color = SheafColors.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        outcome.detail?.let { detail ->
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                color = SheafColors.Done,
                modifier = Modifier.padding(top = 3.dp)
            )
        }

        when (preview) {
            is ResultPreview.Text -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .heightIn(max = 220.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(SheafColors.SurfaceDim)
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp)
                ) {
                    // Selectable, so a phone number or an address can be lifted out without
                    // copying the whole document and hunting through it afterwards.
                    SelectionContainer {
                        Text(
                            preview.content.ifBlank { "(nothing was found)" },
                            style = MaterialTheme.typography.bodySmall,
                            color = SheafColors.Muted
                        )
                    }
                }
                TextButton(onClick = { onCopy(preview.content) }) {
                    Text("Copy text", color = SheafColors.BandBright)
                }
            }

            is ResultPreview.Picture -> Image(
                bitmap = preview.bitmap.asImageBitmap(),
                contentDescription = file.displayName,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .padding(top = 10.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(SheafColors.Paper)
            )

            null -> Unit
        }

        RowBetween(modifier = Modifier.padding(top = 6.dp)) {
            // Weighted so the three buttons measure first and this gives way rather than
            // pushing Save off the edge: "Next tool…" and "Saved" together are close to all
            // of a 360dp row.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f, fill = false)
            ) {
                Text(
                    formatBytes(file.sizeBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = SheafColors.Dim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // Said on the row rather than in a snackbar, which is gone by the time the
                // second file of a batch is saved and cannot be gone back to.
                if (saved) {
                    Text(
                        "Saved",
                        style = MaterialTheme.typography.bodySmall,
                        color = SheafColors.Done,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Row {
                if (file.isPdf) {
                    TextButton(onClick = onOpen) { Text("Open", color = SheafColors.Muted) }
                    // The chain. Without this, compressing something you just merged means
                    // saving it, leaving, and finding the same file again in the picker.
                    // Named for what it opens: beside Open and Save, "Then…" read as a third
                    // thing to do with the file rather than as another tool.
                    TextButton(onClick = onChain) { Text("Next tool…", color = SheafColors.Muted) }
                }
                TextButton(onClick = onSave) { Text("Save", color = SheafColors.BandBright) }
            }
        }
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
    onPasswordDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    Panel(modifier = modifier.fillMaxWidth()) {
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
            // The same bordered shape a failed job gets, rather than a line of red text that
            // reads as part of the detail line above it. A file that cannot be used is the
            // most important thing in its own row.
            Spacer(Modifier.height(8.dp))
            ProblemRow(name = null, reason = it, isError = true)
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
            // Named for a screen reader, because a list of files is a column of buttons all
            // reading "Remove" with nothing to say which file each one takes away.
            TextButton(
                onClick = onRemove,
                modifier = Modifier.semantics {
                    contentDescription = "Remove ${doc.file.displayName}"
                }
            ) { Text("Remove", color = SheafColors.Dim) }
        }
    }
}

@Composable
private fun PasswordPrompt(value: String, onChange: (String) -> Unit, onSubmit: () -> Unit) {
    Column {
        PasswordField(value = value, label = "Password", onChange = onChange)
        TextButton(onClick = onSubmit) { Text("Unlock", color = SheafColors.BandBright) }
    }
}

/**
 * The page grid for Organise.
 *
 * Tap to select, then rotate or delete the selection. Reordering is by arrows rather than drag
 * and drop: drag inside a scrolling grid is finicky to get right and easy to trigger by
 * accident, and getting page order wrong is not a small mistake. Drag can come later, once
 * there is a reason to trust it.
 *
 * Takes the fields it draws rather than the whole ToolUiState. Every keystroke anywhere on the
 * screen emits a fresh state object, and a parameter of that type is compared by instance, so a
 * panel holding one recomposes the grid and every cell in it for an edit that touched none of
 * these collections.
 */
@Composable
private fun OrganisePanel(
    pageOrder: List<Int>,
    selectedPages: Set<Int>,
    rotations: Map<Int, Int>,
    thumbnails: Map<Int, Bitmap>,
    thumbnailsCapped: Boolean,
    canUndoPageEdit: Boolean,
    onTogglePage: (Int) -> Unit,
    onRotate: (Int) -> Unit,
    onDelete: () -> Unit,
    onMovePage: (Int, Int) -> Unit,
    onUndo: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        RowBetween {
            SectionHeading("${pageOrder.size} pages")
            if (selectedPages.isNotEmpty()) {
                Text(
                    "${selectedPages.size} selected",
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
            // The lookups belong in here rather than above the grid: position and the
            // last-page test are per-cell, and reading them outside the item lambda leaves
            // them stale the moment the order changes.
            itemsIndexed(pageOrder, key = { _, page -> page }) { position, page ->
                PageThumb(
                    position = position,
                    pageIndex = page,
                    thumb = thumbnails[page],
                    selected = page in selectedPages,
                    rotation = rotations[page] ?: 0,
                    isLast = position == pageOrder.lastIndex,
                    onTap = { onTogglePage(page) },
                    onLeft = { onMovePage(position, position - 1) },
                    onRight = { onMovePage(position, position + 1) },
                    // Placement only. The grid is keyed by page, so a tap on an arrow now
                    // slides two thumbnails past each other instead of swapping their
                    // contents in place. The comment above this panel rejected drag because
                    // getting page order wrong is not a small mistake - which makes seeing
                    // what the arrow actually did the thing carrying the whole feature.
                    modifier = Modifier.animateItem(placementSpec = tween(220))
                )
            }
        }

        if (thumbnailsCapped) {
            Hint(
                "Only the first pages are shown as pictures - a few hundred thumbnails is " +
                    "more memory than this app is allowed. The rest are still here and keep " +
                    "their place in the order."
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { onRotate(90) },
                enabled = selectedPages.isNotEmpty()
            ) { Text("Rotate", color = SheafColors.Text) }
            OutlinedButton(
                onClick = onDelete,
                enabled = selectedPages.isNotEmpty()
            ) { Text("Delete", color = SheafColors.Failed) }
            // Nothing here has been written yet - the whole screen is a plan - so stepping
            // back is just the previous plan, and deleting the wrong page costs a tap.
            OutlinedButton(
                onClick = onUndo,
                enabled = canUndoPageEdit
            ) {
                Text(
                    "Undo",
                    color = if (canUndoPageEdit) SheafColors.Text else SheafColors.Dim
                )
            }
        }
    }
}

/**
 * Signatures kept from before, so one is drawn once rather than once per document.
 *
 * Tap to use, and the small cross forgets one. Decoded here rather than in the view model
 * because these are a few hundred kilobytes between them and holding them in state would mean
 * owning their recycling for the life of the screen.
 */
@Composable
private fun SavedSignatures(
    files: List<java.io.File>,
    onUse: (java.io.File) -> Unit,
    onForget: (java.io.File) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeading("Your signatures")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            files.forEach { file ->
                val bitmap = remember(file.path, file.lastModified()) {
                    runCatching { android.graphics.BitmapFactory.decodeFile(file.path) }.getOrNull()
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(width = 108.dp, height = 56.dp)
                            .clip(MaterialTheme.shapes.small)
                            .background(SheafColors.Paper)
                            .border(1.dp, SheafColors.Border, MaterialTheme.shapes.small)
                            .clickable { onUse(file) },
                        contentAlignment = Alignment.Center
                    ) {
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "A signature you kept",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize().padding(4.dp)
                            )
                        }
                    }
                    TextButton(onClick = { onForget(file) }) {
                        Text(
                            "Forget",
                            style = MaterialTheme.typography.bodySmall,
                            color = SheafColors.Dim
                        )
                    }
                }
            }
        }
    }
}

/**
 * The pages, to be tapped rather than typed.
 *
 * "1-3, 7, 12-" is faster than tapping on a four-hundred-page document and is unreadable on a
 * twelve-page one, where the question is really "which of these do I want" and the answer is
 * in front of you. Both work and both write the same value, so the typed field below is not a
 * fallback - it is the other half of the same control.
 *
 * The fields are taken one by one for the same reason as OrganisePanel, and here it is the whole
 * point: typing in the range field is exactly what used to redraw this grid on every keystroke.
 */
@Composable
private fun PageChooser(
    pageOrder: List<Int>,
    selectedPages: Set<Int>,
    rotations: Map<Int, Int>,
    thumbnails: Map<Int, Bitmap>,
    thumbnailsCapped: Boolean,
    onTogglePage: (Int) -> Unit,
    onAll: () -> Unit,
    onNone: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        RowBetween {
            SectionHeading(
                if (selectedPages.isEmpty()) {
                    "Tap the pages to keep"
                } else {
                    "${selectedPages.size} of ${pageOrder.size} pages"
                }
            )
            Row {
                TextButton(onClick = onAll) {
                    Text("All", style = MaterialTheme.typography.bodySmall, color = SheafColors.Muted)
                }
                TextButton(onClick = onNone) {
                    Text("None", style = MaterialTheme.typography.bodySmall, color = SheafColors.Muted)
                }
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
            itemsIndexed(pageOrder, key = { _, page -> page }) { position, page ->
                PageThumb(
                    position = position,
                    pageIndex = page,
                    thumb = thumbnails[page],
                    selected = page in selectedPages,
                    rotation = rotations[page] ?: 0,
                    isLast = position == pageOrder.lastIndex,
                    onTap = { onTogglePage(page) },
                    onLeft = {},
                    onRight = {},
                    reorderable = false
                )
            }
        }

        if (thumbnailsCapped) {
            Hint(
                "Only the first pages are shown as pictures - a few hundred thumbnails is " +
                    "more memory than this app is allowed. The rest are still here, numbered " +
                    "and tappable, and typing a range covers them too."
            )
        }
    }
}

@Composable
private fun PageThumb(
    position: Int,
    pageIndex: Int,
    thumb: Bitmap?,
    selected: Boolean,
    rotation: Int,
    isLast: Boolean,
    onTap: () -> Unit,
    onLeft: () -> Unit,
    onRight: () -> Unit,
    /** Off where the order is not the user's to change - picking pages out, for instance. */
    reorderable: Boolean = true,
    modifier: Modifier = Modifier
) {
    val shape = MaterialTheme.shapes.small
    val turn = ((rotation % 360) + 360) % 360
    // The rotation is said rather than coloured. Selected and rotated used to paint the same
    // accent on the same number, so a page that was one looked exactly like a page that was
    // the other, and a page that was both looked like either.
    val caption = if (turn == 0) "${position + 1}" else "${position + 1}, $turn°"
    val edge by animateColorAsState(
        targetValue = if (selected) SheafColors.Band else SheafColors.Border,
        animationSpec = tween(120),
        label = "pageEdge"
    )

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.707f)
                .clip(shape)
                .background(SheafColors.Paper)
                .border(2.dp, edge, shape)
                .toggleable(value = selected, role = Role.Checkbox, onValueChange = { onTap() })
                // On the frame rather than on the picture, so a page past the thumbnail cap
                // has something to announce as well - those cells used to say nothing at all.
                // Position, not page index, because that is what the caption on the cell and
                // the two reorder buttons beside it both say. Announcing the original page
                // number here meant that after any reorder a screen reader called a cell
                // "Page 3" while it was captioned "1" and its button said "Move page 1 later".
                .semantics {
                    contentDescription = if (turn == 0) {
                        "Page ${position + 1}"
                    } else {
                        "Page ${position + 1}, rotated $turn degrees"
                    }
                    stateDescription = if (selected) "kept" else "not kept"
                },
            contentAlignment = Alignment.Center
        ) {
            if (thumb != null) {
                // asImageBitmap() hands back a new wrapper every call, and that wrapper is the
                // key Image remembers its painter by: without this the cell rebuilds the
                // painter and redraws the page on any recomposition at all.
                val image = remember(thumb) { thumb.asImageBitmap() }
                Image(
                    bitmap = image,
                    // Named on the frame above. Both, and TalkBack reads "Page 4" twice.
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        // Shows the chosen rotation without re-rendering the page to apply it.
                        .rotate(rotation.toFloat())
                )
            }
            // Selection has to read across the whole grid rather than at the one 2dp edge
            // under the fingertip that just set it, which is invisible from reading distance.
            if (selected) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(SheafColors.Band.copy(alpha = 0.14f))
                )
            }
            // The number sits on the page rather than under it. The row below is now the
            // reorder strip, and two buttons at the 48dp Material lays them out at - which
            // .size(34.dp) only squeezed the content box of, never the button - plus a
            // number came to more than a 96dp cell holds, so the chevrons drew outside it.
            Text(
                caption,
                style = MaterialTheme.typography.bodySmall,
                color = if (selected) SheafColors.BandBright else SheafColors.Dim,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(SheafColors.Surface.copy(alpha = 0.88f))
                    .padding(horizontal = 5.dp, vertical = 1.dp)
            )
        }
        if (reorderable) {
            Row(Modifier.fillMaxWidth()) {
                PageArrow(
                    icon = R.drawable.ic_chevron_left,
                    label = "Move page ${position + 1} earlier",
                    enabled = position > 0,
                    onClick = onLeft,
                    modifier = Modifier.weight(1f)
                )
                PageArrow(
                    icon = R.drawable.ic_chevron_right,
                    label = "Move page ${position + 1} later",
                    enabled = !isLast,
                    onClick = onRight,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/**
 * One half of a cell's reorder strip.
 *
 * Not [com.layerbit.sheaf.ui.components.IconAction], which keeps a text button's 24dp side
 * padding: two of those need 136dp and the cell is 96dp wide. Here the touch target is the
 * weighted half itself, which is 48dp high and at least 48dp wide, so dropping the content
 * padding costs nothing.
 */
@Composable
private fun PageArrow(
    icon: Int,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(0.dp),
        modifier = modifier
            .height(48.dp)
            .semantics { contentDescription = label }
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = if (enabled) SheafColors.Muted else SheafColors.Dim,
            modifier = Modifier.size(20.dp)
        )
    }
}
