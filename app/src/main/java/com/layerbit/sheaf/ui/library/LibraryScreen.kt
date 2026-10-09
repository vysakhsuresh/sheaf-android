package com.layerbit.sheaf.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.layerbit.sheaf.data.db.OutputEntity
import com.layerbit.sheaf.ops.ToolId
import androidx.compose.ui.Alignment
import com.layerbit.sheaf.R
import com.layerbit.sheaf.ui.components.AccentTile
import com.layerbit.sheaf.ui.components.EmptyState
import com.layerbit.sheaf.ui.components.ListGap
import com.layerbit.sheaf.ui.components.ListRhythm
import com.layerbit.sheaf.ui.components.Panel
import com.layerbit.sheaf.ui.components.RowBetween
import com.layerbit.sheaf.ui.components.SheafTopBar
import com.layerbit.sheaf.ui.components.formatBytes
import com.layerbit.sheaf.ui.theme.SheafColors
import com.layerbit.sheaf.ui.tools.iconFor

/**
 * Everything Sheaf has made and the user has not thrown away.
 *
 * It exists because results used to live only in a scratch directory that got trimmed
 * oldest-first, so the app's own output was something you had to export immediately or risk
 * losing without being told. This is the list that was missing: what was made, by which tool,
 * how big it is, and whether a copy of it is already safe in the user's own Files.
 *
 * Deliberately not a file manager. Open it, save it somewhere real, or throw it away - three
 * actions, because a fourth would mean this screen had started competing with the Files app,
 * which does all of this better and is one tap away through Save.
 */
@Composable
fun LibraryScreen(
    outputs: List<OutputEntity>,
    onOpen: (OutputEntity) -> Unit,
    onSave: (OutputEntity) -> Unit,
    onDelete: (OutputEntity) -> Unit,
    onDeleted: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        SheafTopBar(
            title = "Library",
            subtitle = if (outputs.isEmpty()) null else "${outputs.size} made here",
            actions = {
                // The bin lives behind the thing it is the bin for, the way every operating
                // system does it, rather than taking a place in the bottom bar beside Tools.
                // It is somewhere you go on purpose, having missed something.
                TextButton(onClick = onDeleted) {
                    Text("Recently deleted", color = SheafColors.Muted)
                }
            }
        )

        if (outputs.isEmpty()) {
            Empty(
                "Nothing here yet.",
                "Anything a tool produces is kept here until you throw it away, so a result " +
                    "you have not saved anywhere cannot go missing."
            )
            return@Column
        }

        LazyColumn(
            contentPadding = ListRhythm,
            verticalArrangement = Arrangement.spacedBy(ListGap),
            modifier = Modifier.fillMaxSize()
        ) {
            items(outputs, key = { it.path }) { output ->
                OutputRow(
                    output = output,
                    onOpen = { onOpen(output) },
                    onSave = { onSave(output) },
                    onDelete = { onDelete(output) },
                    modifier = Modifier.animateItem()
                )
            }
        }
    }
}

@Composable
private fun OutputRow(
    output: OutputEntity,
    onOpen: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tool = ToolId.entries.firstOrNull { it.name == output.toolId }
    val accent = SheafColors.ToolAccents[(tool?.group ?: ToolId.Group.CONVERT).ordinal]
    Panel(modifier = modifier.fillMaxWidth(), onClick = onOpen, accent = accent) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The result wears the colour of the tool that made it, which is the one piece of
            // wayfinding this list was missing: a column of filenames in identical grey cards
            // says nothing about which of them is the signed copy and which is the compressed
            // one until you have read every second line.
            AccentTile(
                icon = tool?.let { iconFor(it) } ?: R.drawable.ic_tool_read,
                accent = accent,
                size = 32.dp
            )
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    text = output.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = SheafColors.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = buildString {
                        append(toolTitle(output.toolId))
                        append(" · ")
                        append(formatBytes(output.sizeBytes))
                        // Said plainly, because it is the difference between "I can throw this
                        // away" and "this is the only copy". Nothing else on the row carries
                        // that.
                        append(
                            if (output.savedAt != null) " · saved to your files" else " · only here"
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = SheafColors.Dim,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        RowBetween(modifier = Modifier.padding(top = 6.dp)) {
            TextButton(
                onClick = onSave,
                modifier = Modifier.semantics {
                    contentDescription = "Save ${output.displayName} to your files"
                }
            ) { Text("Save", color = SheafColors.BandBright) }
            TextButton(
                onClick = onDelete,
                modifier = Modifier.semantics {
                    contentDescription = "Delete ${output.displayName}"
                }
            ) { Text("Delete", color = SheafColors.Dim) }
        }
    }
}

/**
 * The bin.
 *
 * Restore is offered on everything, and refuses honestly: a file the trim has already given
 * up to free space keeps its row so this screen can still say what went, but there is nothing
 * left to put back. Saying so beats a button that appears to work and does nothing.
 */
@Composable
fun RecentlyDeletedScreen(
    outputs: List<OutputEntity>,
    onBack: () -> Unit,
    onRestore: (OutputEntity) -> Unit,
    onForget: (OutputEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        SheafTopBar(title = "Recently deleted", onBack = onBack)

        if (outputs.isEmpty()) {
            Empty(
                "Nothing has been thrown away.",
                "Anything you delete from the Library waits here, and can be put back until " +
                    "Sheaf needs the space."
            )
            return@Column
        }

        LazyColumn(
            contentPadding = ListRhythm,
            verticalArrangement = Arrangement.spacedBy(ListGap),
            modifier = Modifier.fillMaxSize()
        ) {
            items(outputs, key = { it.path }) { output ->
                Panel(modifier = Modifier.fillMaxWidth().animateItem()) {
                    Text(
                        text = output.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        color = SheafColors.Text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${toolTitle(output.toolId)} · ${formatBytes(output.sizeBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = SheafColors.Dim,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    RowBetween(modifier = Modifier.padding(top = 6.dp)) {
                        TextButton(
                            onClick = { onRestore(output) },
                            modifier = Modifier.semantics {
                                contentDescription = "Put ${output.displayName} back"
                            }
                        ) { Text("Put back", color = SheafColors.BandBright) }
                        TextButton(
                            onClick = { onForget(output) },
                            modifier = Modifier.semantics {
                                contentDescription = "Delete ${output.displayName} for good"
                            }
                        ) { Text("Delete for good", color = SheafColors.Failed) }
                    }
                }
            }
        }
    }
}

/** The title a tool had when it made this, or its stored name if the enum has since moved on. */
private fun toolTitle(toolId: String): String =
    ToolId.entries.firstOrNull { it.name == toolId }?.title ?: toolId

@Composable
private fun Empty(heading: String, detail: String) {
    // Convert's jade, because the Library is where a converted, compressed or signed result
    // lands. The bin reuses it: the bin is this same list one step further on.
    EmptyState(
        icon = R.drawable.ic_nav_library,
        accent = SheafColors.ToolAccents[ToolId.Group.CONVERT.ordinal],
        heading = heading,
        detail = detail
    )
}
