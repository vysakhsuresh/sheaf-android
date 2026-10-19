package com.layerbit.sheaf.ui.tools

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed as itemsIndexedInColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.layerbit.sheaf.files.SheafFile
import com.layerbit.sheaf.jobs.JobState
import com.layerbit.sheaf.ops.OpOutcome
import com.layerbit.sheaf.ops.ToolId
import com.layerbit.sheaf.R
import com.layerbit.sheaf.ui.components.IconTile
import com.layerbit.sheaf.ui.components.Panel
import com.layerbit.sheaf.ui.components.Pill
import com.layerbit.sheaf.ui.components.PrimaryButton
import com.layerbit.sheaf.ui.components.RoundIconButton
import com.layerbit.sheaf.ui.components.SecondaryButton
import com.layerbit.sheaf.ui.theme.GroupAccents
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
    onLine: (Int, com.layerbit.sheaf.pdf.PagePoint, com.layerbit.sheaf.pdf.PagePoint) -> Unit,
    onTap: (Int, com.layerbit.sheaf.pdf.PagePoint) -> Unit,
    onUndoMark: (Int) -> Unit,
    onClearMarks: (Int) -> Unit,
    onAddRedaction: (Int, com.layerbit.sheaf.pdf.PageArea) -> Unit,
    onClearRedactions: (Int) -> Unit,
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
    onLoadOutline: () -> Unit,
    onAddBookmark: (Int) -> Unit,
    onSetBookmark: (Int, String?, Int?) -> Unit,
    onRemoveBookmark: (Int) -> Unit,
    onMoveBookmark: (Int, Int) -> Unit,
    onIndentBookmark: (Int, Boolean) -> Unit,
    onClearBookmarks: () -> Unit,
    onLoadAttachments: () -> Unit,
    onFindSensitive: () -> Unit,
    onLoadAnswers: () -> Unit,
    onKeepAnswers: () -> Unit,
    onUseAnswers: (java.io.File) -> Unit,
    onForgetAnswers: (java.io.File) -> Unit,
    onLoadStamp: () -> Unit,
    onAddField: (Int, com.layerbit.sheaf.pdf.PageArea) -> Unit,
    onSetField: (Int, String?, com.layerbit.sheaf.pdf.NewField.Kind?, String?) -> Unit,
    onRemoveField: (Int) -> Unit,
    onClearFields: (Int) -> Unit,
    onPicturesPage: (Int) -> Unit = {},
    onSelectPicture: (Int?) -> Unit = {},
    onMovePicture: (Int, Int, com.layerbit.sheaf.pdf.PageArea) -> Unit = { _, _, _ -> },
    onToggleRemovePicture: (Int, Int) -> Unit = { _, _ -> },
    onPickReplacement: () -> Unit = {},
    /** Bundles every result into one ZIP and saves that. */
    onSaveZip: (List<SheafFile>) -> Unit,
    onCheckSize: () -> Unit,
    onCopy: (String) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Organise and Remove areas both draw on rendered pages, and only once a document is
    // actually present.
    LaunchedEffect(tool, state.documents.firstOrNull()?.file?.file?.path, state.documents.size, state.config.markPage) {
        if (state.documents.isEmpty()) return@LaunchedEffect
        when (tool) {
            // These edit pages, so they need the pages themselves.
            // Extract shows them to be tapped rather than typed.
            ToolId.ORGANISE, ToolId.REDACT, ToolId.EXTRACT, ToolId.ANNOTATE, ToolId.CREATE_FORM,
            ToolId.EDIT_IMAGES -> onLoadPages()
            // These change how a page looks, so one page is enough to show it.
            ToolId.CROP, ToolId.WATERMARK, ToolId.PAGE_NUMBERS,
            ToolId.ADD_TEXT, ToolId.BACKGROUND -> onLoadPreviewPage()
            // Filling a form needs to know whether there is one, and what was kept before.
            ToolId.FORMS -> {
                onLoadForm()
                onLoadAnswers()
            }
            // The picture and the page it goes on, for the placement preview.
            ToolId.ADD_IMAGE -> {
                onLoadPreviewPage()
                onLoadStamp()
            }
            // The bookmark editor starts from what the document already has.
            ToolId.BOOKMARKS -> onLoadOutline()
            // Attachments shows what is already inside before offering to add or take out.
            ToolId.ATTACHMENTS -> onLoadAttachments()
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

    /** The finished file waiting for the user to pick the next tool for it. */
    var chaining by remember { mutableStateOf<SheafFile?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
    LazyColumn(
        modifier = Modifier.weight(1f).fillMaxWidth(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        when {
            running != null -> item { RunningPanel(running, onCancel) }

            finished != null -> {
                item { ResultSummary(finished) }
                items(finished.produced, key = { it.file.file.path }) { produced ->
                    ResultRow(
                        outcome = produced,
                        preview = state.previews[produced.file.file.path],
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
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            if (finished.produced.isNotEmpty()) {
                                PrimaryButton(
                                    text = if (finished.produced.size > 1) "Share all" else "Share",
                                    icon = Icons.Filled.Share,
                                    onClick = { onShare(finished.produced.map { it.file }) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            // One folder for the whole batch. Forty files through the system
                            // picker one at a time is the thing that makes an export tool useless.
                            if (finished.produced.size > 1) {
                                SecondaryButton(
                                    text = "Save all",
                                    onClick = { onSaveAll(finished.produced.map { it.file }) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            // One file to send on, for the cases where a folder of forty is not
                            // what the other end wants: an upload box, an email, a chat.
                            if (finished.produced.size > 1) {
                                SecondaryButton(
                                    text = "Save as one ZIP",
                                    onClick = { onSaveZip(finished.produced.map { it.file }) },
                                    modifier = Modifier.weight(1f),
                                    tall = false
                                )
                            }
                            SecondaryButton(text = "Done", onClick = onDone, modifier = Modifier.weight(1f), tall = false)
                        }
                    }
                }
            }

            tool.input == ToolId.InputKind.NONE -> {
                item {
                    ToolOptions(
                        tool = tool,
                        config = state.config,
                        pageCount = null,
                        onChange = onConfigChange
                    )
                }
                state.error?.let { message ->
                    item { ProblemRow(null, message, isError = true) }
                }
            }

            else -> {
                item { SectionHeading(if (tool.acceptsMultiple) "Files" else "File") }

                itemsIndexedInColumn(state.documents) { index, doc ->
                    DocumentRow(
                        doc = doc,
                        canReorder = (tool == ToolId.MERGE || tool == ToolId.ATTACHMENTS || tool == ToolId.COMPARE) && state.documents.size > 1,
                        role = when {
                            tool == ToolId.DIGITAL_SIGN && doc.file.isPdf -> "The PDF"
                            tool == ToolId.DIGITAL_SIGN -> "Certificate"
                            tool == ToolId.COMPARE && index == 0 -> "Older version"
                            tool == ToolId.COMPARE && index == 1 -> "Newer version"
                            tool == ToolId.ADD_IMAGE && doc.file.isImage -> "The image"
                            tool == ToolId.ADD_IMAGE && doc.file.isPdf -> "The PDF"
                            tool == ToolId.ATTACHMENTS && index == 0 -> "The PDF"
                            tool == ToolId.ATTACHMENTS -> "To attach"
                            tool == ToolId.INSERT && index == 0 -> "Being added to"
                            tool == ToolId.INSERT -> "Goes in"
                            else -> null
                        },
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
                    val empty = state.documents.isEmpty()
                    val label = when {
                        empty && tool.input == ToolId.InputKind.IMAGES -> "Choose images"
                        empty -> "Choose a file"
                        tool.acceptsMultiple -> "Add another"
                        else -> "Choose a different file"
                    }
                    if (empty) {
                        // An empty tool screen's whole job is this one tap, so it is drawn as
                        // a drop zone rather than a button lost under a heading.
                        Panel(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = onAddFiles,
                            tint = GroupAccents.of(tool.group),
                            inset = 22.dp
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                                IconTile(iconRes = iconFor(tool), accent = GroupAccents.of(tool.group), size = 52.dp, filled = true)
                                Text(
                                    label,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = SheafColors.Text,
                                    modifier = Modifier.padding(top = 12.dp)
                                )
                                Text(
                                    when (tool.input) {
                                        ToolId.InputKind.IMAGES -> "Photos and scans, as many as you like"
                                        ToolId.InputKind.SEVERAL_PDFS -> "One PDF, or several"
                                        ToolId.InputKind.PDF_AND_FILES -> "The PDF first, then the files to put in it"
                                        ToolId.InputKind.PDF_AND_IMAGE -> "The PDF, then the image"
                                        ToolId.InputKind.PDF_AND_CERT -> "The PDF, then your .pfx or .p12"
                                        ToolId.InputKind.OFFICE -> ".docx, .xlsx, .txt, .md or .csv"
                                        else -> "It is never changed; the result is a new file"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SheafColors.Muted,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                    } else {
                        SecondaryButton(
                            text = label,
                            icon = Icons.Filled.Add,
                            onClick = onAddFiles,
                            modifier = Modifier.fillMaxWidth(),
                            tall = false
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
                                    color = SheafColors.Band,
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
                            signature = state.signatureBitmap
                        )
                    }
                } else if (tool == ToolId.EXTRACT && state.pageOrder.isNotEmpty()) {
                    item {
                        PageChooser(
                            state = state,
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
                        if (state.savedAnswers.isNotEmpty()) {
                            item {
                                SavedAnswers(
                                    files = state.savedAnswers,
                                    onUse = onUseAnswers,
                                    onForget = onForgetAnswers
                                )
                            }
                        }
                        item {
                            FormEditor(
                                fields = state.formFields,
                                values = state.formValues,
                                onValue = onFormValue
                            )
                        }
                        if (state.formValues.values.any { it.isNotBlank() }) {
                            item {
                                TextButton(onClick = onKeepAnswers) {
                                    Text(
                                        "Keep these answers for next time",
                                        color = SheafColors.Band,
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
                    }
                } else if (tool == ToolId.ADD_IMAGE && state.documents.isNotEmpty()) {
                    item {
                        ToolOptions(
                            tool = tool,
                            config = state.config,
                            pageCount = state.documents.firstOrNull { it.file.isPdf }?.pageCount,
                            onChange = onConfigChange
                        )
                    }
                    if (state.stampBitmap != null) {
                        item {
                            EffectPreview(
                                tool = tool,
                                config = state.config,
                                page = state.previewPage,
                                signature = state.stampBitmap
                            )
                        }
                    }
                } else if (tool == ToolId.BOOKMARKS && state.documents.isNotEmpty()) {
                    if (!state.outlineRead) {
                        item { Hint("Reading the bookmarks…") }
                    } else {
                        item {
                            BookmarksEditor(
                                entries = state.outline,
                                pageCount = state.documents.firstOrNull()?.pageCount,
                                onAdd = onAddBookmark,
                                onSet = onSetBookmark,
                                onRemove = onRemoveBookmark,
                                onMove = onMoveBookmark,
                                onIndent = onIndentBookmark,
                                onClear = onClearBookmarks
                            )
                        }
                        item {
                            ToolOptions(
                                tool = tool,
                                config = state.config,
                                pageCount = state.documents.firstOrNull()?.pageCount,
                                onChange = onConfigChange
                            )
                        }
                    }
                } else if (tool == ToolId.ATTACHMENTS && state.documents.isNotEmpty()) {
                    item {
                        AttachmentsPanel(
                            read = state.attachmentsRead,
                            attachments = state.attachments,
                            extras = state.documents.drop(1).map { it.file.displayName }
                        )
                    }
                    item {
                        ToolOptions(
                            tool = tool,
                            config = state.config,
                            pageCount = state.documents.firstOrNull()?.pageCount,
                            onChange = onConfigChange
                        )
                    }
                } else if (tool == ToolId.ANNOTATE && state.pageOrder.isNotEmpty()) {
                    item {
                        MarkupCanvas(
                            pageIndex = state.canvasPage,
                            pageCount = state.pageOrder.size,
                            page = state.thumbnails[state.canvasPage],
                            marks = state.marks[state.canvasPage].orEmpty(),
                            tool = state.config.markupTool,
                            colour = state.config.markupColour,
                            onPage = onMarkupPage,
                            onHighlight = { area -> onHighlight(state.canvasPage, area) },
                            onInk = { points -> onInk(state.canvasPage, points) },
                            onLine = { from, to -> onLine(state.canvasPage, from, to) },
                            onTap = { at -> onTap(state.canvasPage, at) },
                            onUndo = { onUndoMark(state.canvasPage) },
                            onClear = { onClearMarks(state.canvasPage) }
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
                } else if (tool == ToolId.CREATE_FORM && state.pageOrder.isNotEmpty()) {
                    val onThisPage = state.newFields.withIndex().filter { it.value.pageIndex == state.canvasPage }
                    item {
                        RedactCanvas(
                            pageIndex = state.canvasPage,
                            pageCount = state.pageOrder.size,
                            page = state.thumbnails[state.canvasPage],
                            areas = onThisPage.map { it.value.area },
                            onPage = onRedactPage,
                            onAdd = { area -> onAddField(state.canvasPage, area) },
                            onClear = { onClearFields(state.canvasPage) },
                            boxColour = SheafColors.Band.copy(alpha = 0.22f),
                            labels = onThisPage.map { it.value.name },
                            emptyHint = "Drag a box where a field should go"
                        )
                    }
                    item {
                        ToolOptions(
                            tool = tool,
                            config = state.config,
                            pageCount = state.documents.firstOrNull()?.pageCount,
                            onChange = onConfigChange
                        )
                    }
                    if (state.newFields.isNotEmpty()) {
                        item {
                            NewFieldsList(
                                fields = state.newFields,
                                onSet = onSetField,
                                onRemove = onRemoveField
                            )
                        }
                    }
                } else if (tool == ToolId.EDIT_IMAGES && state.pageOrder.isNotEmpty()) {
                    item {
                        PicturesCanvas(
                            pageIndex = state.canvasPage,
                            pageCount = state.pageOrder.size,
                            page = state.thumbnails[state.canvasPage],
                            pictures = state.pictures[state.canvasPage],
                            changes = state.pictureChanges[state.canvasPage].orEmpty(),
                            selected = state.selectedPicture,
                            onPage = onPicturesPage,
                            onSelect = onSelectPicture,
                            onMove = { i, area -> onMovePicture(state.canvasPage, i, area) },
                            onToggleRemove = { i -> onToggleRemovePicture(state.canvasPage, i) },
                            onReplace = onPickReplacement
                        )
                    }
                    item {
                        Hint(
                            "Nothing else on the page changes. A picture taken out is no longer drawn, " +
                                "but its data may still be inside the file: for something private, use " +
                                "Remove areas. A picture that is part of a scan cannot be edited on its own."
                        )
                    }
                } else if (tool == ToolId.REDACT && state.pageOrder.isNotEmpty()) {
                    item {
                        FindSensitivePanel(
                            config = state.config,
                            finding = state.finding,
                            report = state.findReport,
                            onChange = onConfigChange,
                            onFind = onFindSensitive
                        )
                    }
                    item {
                        RedactCanvas(
                            pageIndex = state.canvasPage,
                            pageCount = state.pageOrder.size,
                            page = state.thumbnails[state.canvasPage],
                            areas = state.redactions[state.canvasPage].orEmpty(),
                            onPage = onRedactPage,
                            onAdd = { area -> onAddRedaction(state.canvasPage, area) },
                            onClear = { onClearRedactions(state.canvasPage) }
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
                        tool == ToolId.PAGE_NUMBERS || tool == ToolId.ADD_TEXT ||
                        tool == ToolId.BACKGROUND
                    ) {
                        item {
                            EffectPreview(
                                tool = tool,
                                config = state.config,
                                page = state.previewPage,
                                pageSize = state.previewPageSize,
                                signature = null,
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

    // The one coloured button on the screen sits under the list, always in reach, and says
    // why it is waiting when it is.
    if (running == null && finished == null) {
        ActionBar(
            label = tool.action,
            enabled = state.canRun,
            busy = state.busy,
            reason = state.blockedReason?.takeIf { state.documents.isNotEmpty() || tool.input == ToolId.InputKind.NONE || !state.busy },
            onRun = onRun
        )
    }
    }

    // The same tool is left in the list on purpose: a second note on another page, or a
    // second range out of the file just split, are both things people want next.
    chaining?.let { file ->
        ToolPickerSheet(
            heading = "Use another tool on this",
            documentName = file.displayName,
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
    Panel(modifier = Modifier.fillMaxWidth(), tint = SheafColors.Running, inset = 18.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(color = SheafColors.Running, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            Text(
                running.opTitle,
                style = MaterialTheme.typography.titleMedium,
                color = SheafColors.Text,
                modifier = Modifier.padding(start = 10.dp)
            )
        }
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
                color = SheafColors.Running,
                trackColor = SheafColors.SurfaceDim,
                strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
            )
            Text(
                "${running.unitsDone} of ${running.unitsTotal}",
                style = MaterialTheme.typography.bodySmall,
                color = SheafColors.Dim,
                modifier = Modifier.padding(top = 6.dp)
            )
        } else {
            LinearProgressIndicator(
                color = SheafColors.Running,
                trackColor = SheafColors.SurfaceDim,
                strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
            )
        }
        Text(
            "Keeps going if you switch to another app.",
            style = MaterialTheme.typography.bodySmall,
            color = SheafColors.Dim,
            modifier = Modifier.padding(top = 10.dp)
        )
        SecondaryButton(text = "Cancel", onClick = onCancel, modifier = Modifier.padding(top = 12.dp), tall = false)
    }
}

@Composable
private fun ResultSummary(finished: JobState.Finished) {
    val nothing = finished.produced.isEmpty() && finished.failed.isNotEmpty()
    val text = when {
        nothing -> "Nothing was produced"
        finished.allSucceeded && finished.produced.size == 1 -> "Your file is ready"
        finished.allSucceeded -> "${finished.produced.size} files are ready"
        else -> "${finished.produced.size} of ${finished.outcomes.size} finished"
    }
    val accent = if (nothing) SheafColors.Failed else SheafColors.Done
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        Box(
            modifier = Modifier.size(44.dp).background(accent.copy(alpha = 0.18f), RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (nothing) Icons.Filled.Warning else Icons.Filled.Check,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(22.dp)
            )
        }
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(text, style = MaterialTheme.typography.headlineSmall, color = SheafColors.Text)
            Text(
                if (nothing) "What went wrong is listed below." else "Nothing was uploaded. The original is untouched.",
                style = MaterialTheme.typography.bodySmall,
                color = SheafColors.Dim
            )
        }
    }
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

    Panel(modifier = Modifier.fillMaxWidth(), tint = SheafColors.Done) {
        Row(verticalAlignment = Alignment.Top) {
            IconTile(iconRes = fileIcon(file), accent = SheafColors.Done, filled = true, size = 40.dp)
            Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                Text(
                    file.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = SheafColors.Text,
                    maxLines = 2,
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
            }
        }

        when (preview) {
            is ResultPreview.Text -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .heightIn(max = 220.dp)
                        .clip(RoundedCornerShape(8.dp))
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
                    Text("Copy text", color = SheafColors.Band)
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
                    .clip(RoundedCornerShape(8.dp))
                    .background(SheafColors.Paper)
            )

            null -> Unit
        }

        RowBetween(modifier = Modifier.padding(top = 10.dp)) {
            Pill(formatBytes(file.sizeBytes), SheafColors.Muted)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (file.isPdf) {
                    SecondaryButton(text = "Open", onClick = onOpen, tall = false)
                    // The chain. Without this, compressing something you just merged means
                    // saving it, leaving, and finding the same file again in the picker.
                    SecondaryButton(text = "Then…", onClick = onChain, tall = false)
                }
                PrimaryButton(text = "Save", onClick = onSave, tall = false)
            }
        }
    }
}

@Composable
private fun ProblemRow(name: String?, reason: String, isError: Boolean) {
    val accent = if (isError) SheafColors.Failed else SheafColors.Skipped
    Panel(modifier = Modifier.fillMaxWidth(), tint = accent, inset = 14.dp) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = if (isError) Icons.Filled.Warning else Icons.Filled.Info,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(18.dp).padding(top = 1.dp)
            )
            Column(modifier = Modifier.padding(start = 10.dp)) {
                if (!name.isNullOrBlank()) {
                    Text(name, style = MaterialTheme.typography.titleMedium, color = accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(reason, style = MaterialTheme.typography.bodyMedium, color = SheafColors.Muted)
            }
        }
    }
}

/** The run button and, under it, why it is waiting. */
@Composable
private fun ActionBar(label: String, enabled: Boolean, busy: Boolean, reason: String?, onRun: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SheafColors.Background)
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        if (reason != null && !busy) {
            Text(
                reason,
                style = MaterialTheme.typography.bodySmall,
                color = SheafColors.Dim,
                modifier = Modifier.padding(bottom = 8.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        PrimaryButton(
            text = if (busy) "Reading the file…" else label,
            onClick = onRun,
            enabled = enabled && !busy,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun DocumentRow(
    doc: SelectedDoc,
    canReorder: Boolean,
    role: String? = null,
    isFirst: Boolean,
    isLast: Boolean,
    onRemove: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onPasswordChange: (String) -> Unit,
    onPasswordDone: () -> Unit
) {
    Panel(modifier = Modifier.fillMaxWidth(), inset = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(
                iconRes = fileIcon(doc.file),
                accent = if (doc.unreadableReason != null) SheafColors.Failed else if (doc.file.isPdf) SheafColors.Band else SheafColors.Running
            )
            Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                Text(
                    doc.file.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = SheafColors.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val detail = buildString {
                    role?.let { append(it).append(" · ") }
                    doc.pageCount?.let { append(formatPageCount(it)).append(" · ") }
                    append(formatBytes(doc.file.sizeBytes))
                    if (doc.encrypted && !doc.needsPassword) append(" · protected")
                }
                Text(detail, style = MaterialTheme.typography.bodySmall, color = SheafColors.Dim)
            }
            RoundIconButton(icon = Icons.Filled.Close, contentDescription = "Remove", onClick = onRemove, size = 32.dp)
        }

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

        if (canReorder) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 10.dp)) {
                SecondaryButton(text = "Up", onClick = onUp, enabled = !isFirst, tall = false, icon = Icons.Filled.KeyboardArrowUp)
                SecondaryButton(text = "Down", onClick = onDown, enabled = !isLast, tall = false, icon = Icons.Filled.KeyboardArrowDown)
            }
        }
    }
}

/** The drawing for a file in a list: a page, a picture, or a box for anything else. */
private fun fileIcon(file: SheafFile): Int = when {
    file.isPdf -> R.drawable.ic_tool_read
    file.isImage -> R.drawable.ic_tool_images_to_pdf
    file.isText -> R.drawable.ic_tool_extract_text
    else -> R.drawable.ic_tool_attachments
}

/**
 * What to look for and a button to look. The boxes it draws join the drawn ones on the canvas
 * below, where they can be checked page by page before anything is removed.
 */
@Composable
private fun FindSensitivePanel(
    config: ToolConfig,
    finding: Boolean,
    report: String?,
    onChange: (((ToolConfig) -> ToolConfig)) -> Unit,
    onFind: () -> Unit
) {
    Panel(modifier = Modifier.fillMaxWidth()) {
        SectionHeading("Find and mark")
        Text(
            "Finds these in the text and marks each one for removal, so an ID scan or a " +
                "statement can be shared without the numbers on it.",
            style = MaterialTheme.typography.bodySmall,
            color = SheafColors.Dim,
            modifier = Modifier.padding(top = 2.dp, bottom = 6.dp)
        )
        com.layerbit.sheaf.ui.components.ToggleRow(
            title = "Aadhaar numbers",
            checked = config.findAadhaar,
            onChange = { on -> onChange { it.copy(findAadhaar = on) } }
        )
        com.layerbit.sheaf.ui.components.ToggleRow(
            title = "PAN numbers",
            checked = config.findPan,
            onChange = { on -> onChange { it.copy(findPan = on) } }
        )
        com.layerbit.sheaf.ui.components.ToggleRow(
            title = "Phone numbers",
            checked = config.findPhone,
            onChange = { on -> onChange { it.copy(findPhone = on) } }
        )
        com.layerbit.sheaf.ui.components.ToggleRow(
            title = "Email addresses",
            checked = config.findEmail,
            onChange = { on -> onChange { it.copy(findEmail = on) } }
        )
        com.layerbit.sheaf.ui.components.ToggleRow(
            title = "Card numbers",
            checked = config.findCard,
            onChange = { on -> onChange { it.copy(findCard = on) } }
        )
        Spacer(Modifier.height(6.dp))
        Field(
            value = config.findText,
            label = "Or any words or number",
            keyboardType = androidx.compose.ui.text.input.KeyboardType.Text,
            onChange = { text -> onChange { it.copy(findText = text) } }
        )
        RowBetween(modifier = Modifier.padding(top = 6.dp)) {
            Text(
                report ?: if (finding) "Looking…" else "",
                style = MaterialTheme.typography.bodySmall,
                color = SheafColors.Done,
                modifier = Modifier.weight(1f).padding(end = 8.dp)
            )
            OutlinedButton(onClick = onFind, enabled = !finding) {
                Text("Find and mark", color = SheafColors.Band)
            }
        }
    }
}

/** The fields drawn so far: name, kind, and for a drop-down what it offers. */
@Composable
private fun NewFieldsList(
    fields: List<com.layerbit.sheaf.pdf.NewField>,
    onSet: (Int, String?, com.layerbit.sheaf.pdf.NewField.Kind?, String?) -> Unit,
    onRemove: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeading(if (fields.size == 1) "1 field" else "${fields.size} fields")
        fields.forEachIndexed { index, field ->
            Panel(modifier = Modifier.fillMaxWidth()) {
                Field(
                    value = field.name,
                    label = "Name · page ${field.pageIndex + 1}",
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Text,
                    onChange = { text -> onSet(index, text, null, null) }
                )
                Spacer(Modifier.height(6.dp))
                com.layerbit.sheaf.ui.components.ChipRow(
                    options = com.layerbit.sheaf.pdf.NewField.Kind.entries.map { it to it.label },
                    selected = field.kind,
                    onSelect = { kind -> onSet(index, null, kind, null) }
                )
                if (field.kind == com.layerbit.sheaf.pdf.NewField.Kind.CHOICE) {
                    Spacer(Modifier.height(6.dp))
                    Field(
                        value = field.options.joinToString(", "),
                        label = "Choices, separated by commas",
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Text,
                        onChange = { text -> onSet(index, null, null, text) }
                    )
                }
                RowBetween(modifier = Modifier.padding(top = 2.dp)) {
                    Spacer(Modifier.height(1.dp))
                    TextButton(onClick = { onRemove(index) }) { Text("Remove", color = SheafColors.Dim) }
                }
            }
        }
    }
}

/** Answer sets kept from earlier runs, to fill the same form again. */
@Composable
private fun SavedAnswers(
    files: List<java.io.File>,
    onUse: (java.io.File) -> Unit,
    onForget: (java.io.File) -> Unit
) {
    Panel(modifier = Modifier.fillMaxWidth()) {
        SectionHeading("Answers you kept")
        files.forEach { file ->
            RowBetween(modifier = Modifier.padding(top = 4.dp)) {
                Text(
                    file.nameWithoutExtension,
                    style = MaterialTheme.typography.bodyMedium,
                    color = SheafColors.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { onUse(file) }) { Text("Use", color = SheafColors.Band) }
                TextButton(onClick = { onForget(file) }) { Text("Forget", color = SheafColors.Dim) }
            }
        }
    }
}

/** What the document already carries inside it, and what is about to go in. */
@Composable
private fun AttachmentsPanel(
    read: Boolean,
    attachments: List<com.layerbit.sheaf.pdf.AttachmentInfo>,
    extras: List<String>
) {
    Panel(modifier = Modifier.fillMaxWidth()) {
        SectionHeading(
            when {
                !read -> "Looking inside…"
                attachments.isEmpty() -> "Nothing attached yet"
                attachments.size == 1 -> "1 file inside"
                else -> "${attachments.size} files inside"
            }
        )
        attachments.forEach { attachment ->
            RowBetween(modifier = Modifier.padding(top = 4.dp)) {
                Text(
                    attachment.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = SheafColors.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(formatBytes(attachment.sizeBytes), style = MaterialTheme.typography.bodySmall, color = SheafColors.Dim)
            }
        }
        if (extras.isNotEmpty()) {
            Text(
                "To add: " + extras.joinToString(", "),
                style = MaterialTheme.typography.bodySmall,
                color = SheafColors.Done,
                modifier = Modifier.padding(top = 8.dp)
            )
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
    onMovePage: (Int, Int) -> Unit,
    onUndo: () -> Unit
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

        if (state.thumbnailsCapped) {
            Hint(
                "Only the first pages are shown as pictures - a few hundred thumbnails is " +
                    "more memory than this app is allowed. The rest are still here and keep " +
                    "their place in the order."
            )
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
            // Nothing here has been written yet - the whole screen is a plan - so stepping
            // back is just the previous plan, and deleting the wrong page costs a tap.
            OutlinedButton(
                onClick = onUndo,
                enabled = state.canUndoPageEdit
            ) {
                Text(
                    "Undo",
                    color = if (state.canUndoPageEdit) SheafColors.Text else SheafColors.Dim
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
                            .clip(RoundedCornerShape(8.dp))
                            .background(SheafColors.Paper)
                            .border(1.dp, SheafColors.Border, RoundedCornerShape(8.dp))
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
 */
@Composable
private fun PageChooser(
    state: ToolUiState,
    onTogglePage: (Int) -> Unit,
    onAll: () -> Unit,
    onNone: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        RowBetween {
            SectionHeading(
                if (state.selectedPages.isEmpty()) {
                    "Tap the pages to keep"
                } else {
                    "${state.selectedPages.size} of ${state.pageOrder.size} pages"
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
            itemsIndexed(state.pageOrder, key = { _, page -> page }) { position, page ->
                PageThumb(
                    position = position,
                    pageIndex = page,
                    state = state,
                    onTap = { onTogglePage(page) },
                    onLeft = {},
                    onRight = {},
                    reorderable = false
                )
            }
        }

        if (state.thumbnailsCapped) {
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
    state: ToolUiState,
    onTap: () -> Unit,
    onLeft: () -> Unit,
    onRight: () -> Unit,
    /** Off where the order is not the user's to change - picking pages out, for instance. */
    reorderable: Boolean = true
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
            if (reorderable) {
                TextButton(
                    onClick = onLeft,
                    enabled = position > 0,
                    modifier = Modifier.size(34.dp)
                ) { Text("‹", color = SheafColors.Muted) }
            }
            Text(
                "${position + 1}",
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    selected -> SheafColors.BandBright
                    rotation != 0 -> SheafColors.BandBright
                    else -> SheafColors.Dim
                }
            )
            if (reorderable) {
                TextButton(
                    onClick = onRight,
                    enabled = position < state.pageOrder.lastIndex,
                    modifier = Modifier.size(34.dp)
                ) { Text("›", color = SheafColors.Muted) }
            }
        }
    }
}
