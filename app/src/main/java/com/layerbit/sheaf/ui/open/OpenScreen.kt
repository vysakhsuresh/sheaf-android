package com.layerbit.sheaf.ui.open

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.layerbit.sheaf.R
import com.layerbit.sheaf.files.SheafFile
import com.layerbit.sheaf.ops.Suggest
import com.layerbit.sheaf.ops.ToolId
import com.layerbit.sheaf.ui.components.IconTile
import com.layerbit.sheaf.ui.components.Panel
import com.layerbit.sheaf.ui.components.SecondaryButton
import com.layerbit.sheaf.ui.components.SectionHeading
import com.layerbit.sheaf.ui.components.formatBytes
import com.layerbit.sheaf.ui.components.formatPageCount
import com.layerbit.sheaf.ui.theme.GroupAccents
import com.layerbit.sheaf.ui.theme.SheafColors
import com.layerbit.sheaf.ui.tools.iconFor

/**
 * Open a file: pick anything, and Sheaf says what can be done with it.
 *
 * Nothing is uploaded and nothing is changed; the files are copied in, looked at, and handed
 * to whichever tool is picked.
 */
@Composable
fun OpenRoute(
    onTool: (ToolId, String?) -> Unit,
    onRead: (SheafFile) -> Unit,
    onRecipes: () -> Unit,
    onAllTools: () -> Unit,
    /** Opens the file picker as soon as the screen appears, the first time. */
    pickOnStart: Boolean = true,
    viewModel: OpenViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> viewModel.add(uris) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree -> if (tree != null) viewModel.addFolder(tree) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        // Files shared in from another app arrive already chosen, so the picker would only be
        // in the way of them.
        val shared = viewModel.takeShared()
        if (shared.isNotEmpty()) {
            viewModel.add(shared)
        } else if (pickOnStart && state.files.isEmpty() && !state.busy) {
            pick.launch(arrayOf("*/*"))
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (state.files.isEmpty()) {
            item {
                Panel(modifier = Modifier.fillMaxWidth(), onClick = { pick.launch(arrayOf("*/*")) }, tint = SheafColors.Band, inset = 22.dp) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        IconTile(iconRes = R.drawable.ic_tool_read, accent = SheafColors.Band, size = 52.dp, filled = true)
                        Text("Pick your files", style = MaterialTheme.typography.titleMedium, color = SheafColors.Text, modifier = Modifier.padding(top = 12.dp))
                        Text(
                            "PDFs, photos, Word and Excel. Sheaf looks at them on this phone and suggests what to do. Nothing is uploaded.",
                            style = MaterialTheme.typography.bodySmall,
                            color = SheafColors.Muted,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
            item {
                TextButton(onClick = { pickFolder.launch(null) }) {
                    Text("Pick a whole folder", color = SheafColors.Band)
                }
            }
        } else {
            item { SectionHeading(if (state.files.size == 1) "Your file" else "Your ${state.files.size} files") }
            items(state.files.take(MAX_ROWS)) { opened -> FileRow(opened) }
            if (state.files.size > MAX_ROWS) {
                item {
                    val more = state.files.size - MAX_ROWS
                    Text("and $more more ${if (more == 1) "file" else "files"}", style = MaterialTheme.typography.bodySmall, color = SheafColors.Dim)
                }
            }
        }

        state.status?.let { status ->
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = SheafColors.Band, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    Text(status, style = MaterialTheme.typography.bodySmall, color = SheafColors.Muted, modifier = Modifier.padding(start = 10.dp))
                }
            }
        }
        state.error?.let { error ->
            item { Text(error, style = MaterialTheme.typography.bodyMedium, color = SheafColors.Failed) }
        }

        if (state.files.isNotEmpty()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    SectionHeading(
                        if (state.files.size == 1) "What to do with it" else "What to do with these ${state.files.size}",
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = viewModel::startOver) { Text("Start over", color = SheafColors.Band) }
                }
            }
            if (state.suggestions.isEmpty() && !state.busy) {
                item { Text(Suggest.noSuggestion(state.files.map { it.facts }), style = MaterialTheme.typography.bodyMedium, color = SheafColors.Muted) }
            }
            items(state.suggestions, key = { it.tool?.name ?: "READ" }) { suggestion ->
                SuggestionCard(suggestion) {
                    if (suggestion.isRead) {
                        state.files.getOrNull(suggestion.takes.first())?.file?.let(onRead)
                    } else {
                        viewModel.handOver(suggestion)
                        onTool(suggestion.tool!!, suggestion.preset)
                    }
                }
            }
            if (state.files.any { it.file.isPdf }) {
                item {
                    SecondaryButton(
                        text = "Run one of your recipes on them",
                        onClick = {
                            viewModel.handOverPdfs()
                            onRecipes()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        tall = false
                    )
                }
            }
            item {
                TextButton(onClick = onAllTools) { Text("Or pick any tool →", color = SheafColors.Band) }
            }
        }
    }
}

@Composable
private fun FileRow(opened: OpenedFile) {
    val f = opened.facts
    val meta = buildList {
        add(formatBytes(opened.file.sizeBytes))
        f.pages?.let { add(formatPageCount(it)) }
        if (f.locked) add("locked")
        if (f.scanned) add("a scan")
        if (f.forms) add("a form")
        if (f.unreadable) add("couldn't be read")
        if (f.kind == Suggest.Kind.IMAGE) add("picture")
        if (f.kind == Suggest.Kind.OFFICE) add("Word, Excel or text")
    }.joinToString(" · ")
    Panel(modifier = Modifier.fillMaxWidth(), inset = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val thumbnail = opened.thumbnail
            if (thumbnail != null) {
                // Page one, small: enough to be sure this is the document meant, which a file
                // name from a scanner app ("Scan_20261025_0931.pdf") rarely is.
                androidx.compose.foundation.Image(
                    bitmap = thumbnail.asImageBitmap(),
                    contentDescription = "First page of ${opened.file.displayName}",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .size(width = 44.dp, height = 56.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(SheafColors.Paper)
                        .border(1.dp, SheafColors.Border, RoundedCornerShape(6.dp))
                )
            } else {
                IconTile(
                    iconRes = when (f.kind) {
                        Suggest.Kind.PDF -> R.drawable.ic_tool_read
                        Suggest.Kind.IMAGE -> R.drawable.ic_tool_images_to_pdf
                        Suggest.Kind.OFFICE -> R.drawable.ic_tool_office_to_pdf
                        Suggest.Kind.OTHER -> R.drawable.ic_tool_attachments
                    },
                    accent = if (f.unreadable) SheafColors.Failed else SheafColors.Band,
                    size = 36.dp
                )
            }
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text(opened.file.displayName, style = MaterialTheme.typography.titleMedium, color = SheafColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(meta, style = MaterialTheme.typography.bodySmall, color = SheafColors.Dim)
            }
        }
    }
}

@Composable
private fun SuggestionCard(suggestion: Suggest.Suggestion, onClick: () -> Unit) {
    val tool = suggestion.tool
    val accent = tool?.let { GroupAccents.of(it.group) } ?: SheafColors.Band
    Panel(modifier = Modifier.fillMaxWidth(), onClick = onClick, tint = accent, inset = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(iconRes = tool?.let { iconFor(it) } ?: R.drawable.ic_tool_read, accent = accent, size = 40.dp, filled = true)
            Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                Text(
                    when {
                        tool == null -> "Read"
                        suggestion.preset == Suggest.PRESET_EXCEL -> "PDF to Excel"
                        tool == ToolId.PDF_TO_OFFICE -> "PDF to Word"
                        tool == ToolId.OFFICE_TO_PDF -> "Word, Excel or text to PDF"
                        else -> tool.title
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = SheafColors.Text
                )
                Text(suggestion.why, style = MaterialTheme.typography.bodySmall, color = SheafColors.Muted)
            }
        }
    }
}

private const val MAX_ROWS = 12
