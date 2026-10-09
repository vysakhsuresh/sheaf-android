package com.layerbit.sheaf.ui.home

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.layerbit.sheaf.R
import com.layerbit.sheaf.data.db.RecentEntity
import com.layerbit.sheaf.ops.ToolId
import com.layerbit.sheaf.ui.components.AccentTile
import com.layerbit.sheaf.ui.components.EmptyState
import com.layerbit.sheaf.ui.components.ListGap
import com.layerbit.sheaf.ui.components.ListRhythm
import com.layerbit.sheaf.ui.components.Panel
import com.layerbit.sheaf.ui.components.RowBetween
import com.layerbit.sheaf.ui.components.SheafTopBar
import com.layerbit.sheaf.ui.components.formatBytes
import com.layerbit.sheaf.ui.components.formatPageCount
import com.layerbit.sheaf.ui.theme.SheafColors

/**
 * Documents that have been opened here, on a screen of their own.
 *
 * They used to be on the board twice - a strip across the top and a full list at the bottom -
 * and the board is a board of tools. Neither belongs there: a tool board that also lists your
 * files is two screens wearing one name, and the documents were the part that kept growing.
 *
 * Note these are documents OPENED, which is not what the Library holds. The Library is what
 * Sheaf has produced. Keeping them apart is the point: one is yours and the app only borrowed
 * it, the other the app made and is responsible for not losing. Putting them in one list
 * would blur which of those is true of any given row.
 */
@Composable
fun RecentScreen(
    recents: List<RecentEntity>,
    onBack: () -> Unit,
    onOpen: (RecentEntity) -> Unit,
    onForget: (RecentEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        SheafTopBar(
            title = "Recent",
            subtitle = if (recents.isEmpty()) null else "${recents.size} opened here",
            onBack = onBack
        )

        if (recents.isEmpty()) {
            EmptyState(
                icon = R.drawable.ic_tool_read,
                accent = SheafColors.ToolAccents[ToolId.Group.READ.ordinal],
                heading = "No documents yet.",
                detail = "Anything you open with Read a PDF turns up here, so you can get " +
                    "back to it without going through the picker again."
            )
            return@Column
        }

        LazyColumn(
            contentPadding = ListRhythm,
            verticalArrangement = Arrangement.spacedBy(ListGap),
            modifier = Modifier.fillMaxSize()
        ) {
            items(recents, key = { it.uri }) { recent ->
                RecentDocumentRow(
                    recent = recent,
                    onOpen = { onOpen(recent) },
                    onForget = { onForget(recent) },
                    modifier = Modifier.animateItem()
                )
            }
        }
    }
}

@Composable
private fun RecentDocumentRow(
    recent: RecentEntity,
    onOpen: () -> Unit,
    onForget: () -> Unit,
    modifier: Modifier = Modifier
) {
    // The Read group's colour, because that is what a recent document is for: these rows are
    // the Read card's history, and tapping one opens the viewer. It is the same hue the Read
    // tools wear on the board, which is the point - this list is not a sixth group.
    val accent = SheafColors.ToolAccents[ToolId.Group.READ.ordinal]
    Panel(modifier = modifier.fillMaxWidth(), onClick = onOpen, accent = accent) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AccentTile(icon = R.drawable.ic_tool_read, accent = accent, size = 32.dp)
            Text(
                text = recent.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = SheafColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 12.dp)
            )
        }
        RowBetween(modifier = Modifier.padding(top = 4.dp)) {
            Text(
                text = buildString {
                    append(formatPageCount(recent.pageCount))
                    append(" · ")
                    append(formatBytes(recent.sizeBytes))
                    // Where they got to, so the list says which of two statements is the one
                    // half-read rather than making the reader open both to find out.
                    if (recent.lastPage > 0) append(" · read to page ${recent.lastPage + 1}")
                },
                style = MaterialTheme.typography.bodySmall,
                color = SheafColors.Dim
            )
            // The word on screen stays short, because the row above it already says which
            // document this is. A screen reader does not get that: TalkBack's Actions list
            // and its controls granularity read button labels on their own, so a list of
            // recents was a column of identical "Remove" buttons.
            TextButton(
                onClick = onForget,
                modifier = Modifier.semantics {
                    contentDescription = "Remove ${recent.displayName} from recents"
                }
            ) {
                Text("Remove", style = MaterialTheme.typography.bodySmall, color = SheafColors.Dim)
            }
        }
    }
}
