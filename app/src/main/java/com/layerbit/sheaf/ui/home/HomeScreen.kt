package com.layerbit.sheaf.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.layerbit.sheaf.data.db.RecentEntity
import com.layerbit.sheaf.ops.ToolId
import com.layerbit.sheaf.ui.components.Panel
import com.layerbit.sheaf.ui.components.RowBetween
import com.layerbit.sheaf.ui.components.SectionHeading
import com.layerbit.sheaf.ui.components.formatBytes
import com.layerbit.sheaf.ui.components.formatPageCount
import com.layerbit.sheaf.ui.theme.SheafColors
import com.layerbit.sheaf.ui.tools.iconFor

/**
 * The home screen: open something to read, or pick a tool.
 *
 * Reading comes first because it is what people do most often with a PDF, and because opening
 * a document is also the way into every tool - the viewer hands the open document straight to
 * any of them, so "Open a PDF" and the recents list are the entry point to the whole app
 * rather than a dead end that only shows you pages.
 *
 * The tools sit below as one flat grid rather than behind categories. There are nine, and
 * someone looking for "compress" should see the word without navigating first.
 */
@Composable
fun HomeScreen(
    recents: List<RecentEntity>,
    onOpenDocument: () -> Unit,
    onOpenRecent: (RecentEntity) -> Unit,
    onForgetRecent: (RecentEntity) -> Unit,
    onTool: (ToolId) -> Unit,
    onAbout: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column {
                Text(
                    text = "Sheaf",
                    style = MaterialTheme.typography.displayMedium,
                    color = SheafColors.Text
                )
                Text(
                    text = "Everything happens on this phone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SheafColors.Muted
                )
                Spacer(Modifier.height(18.dp))
                Button(
                    onClick = onOpenDocument,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SheafColors.Band,
                        contentColor = SheafColors.OnBand
                    )
                ) {
                    Text("Open a PDF", style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    text = "Read it, then use any tool on it without picking the file again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SheafColors.Dim,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }

        item {
            Spacer(Modifier.height(8.dp))
            SectionHeading("Tools")
            Spacer(Modifier.height(10.dp))
            ToolGrid(onTool)
        }

        if (recents.isNotEmpty()) {
            item {
                Spacer(Modifier.height(10.dp))
                SectionHeading("Recent")
            }
            items(recents, key = { it.uri }) { recent ->
                RecentRow(
                    recent = recent,
                    onOpen = { onOpenRecent(recent) },
                    onForget = { onForgetRecent(recent) }
                )
            }
        }

        item {
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onAbout) {
                Text("About Sheaf", color = SheafColors.Muted)
            }
        }
    }
}

/**
 * Two columns of equal-height cards.
 *
 * The height is fixed rather than left to the content. Summaries run to two or three lines
 * depending on the tool, and letting each card size itself leaves neighbouring cards with
 * mismatched bottoms and the grid looking as though it was assembled by accident.
 *
 * There are nine tools, so the last row holds one. It takes a half-width slot with an empty
 * one beside it rather than stretching across, which would read as a different kind of thing
 * rather than as the ninth of nine.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToolGrid(onTool: (ToolId) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        maxItemsInEachRow = 2,
        modifier = Modifier.fillMaxWidth()
    ) {
        ToolId.entries.forEach { tool ->
            ToolCard(
                tool = tool,
                onClick = { onTool(tool) },
                modifier = Modifier.weight(1f)
            )
        }
        if (ToolId.entries.size % 2 != 0) {
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun ToolCard(tool: ToolId, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Panel(
        modifier = modifier.height(TOOL_CARD_HEIGHT),
        onClick = onClick
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(SheafColors.SurfaceDim, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(iconFor(tool)),
                contentDescription = null,
                tint = SheafColors.Muted,
                modifier = Modifier.size(20.dp)
            )
        }
        Text(
            text = tool.title,
            style = MaterialTheme.typography.titleMedium,
            color = SheafColors.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 10.dp)
        )
        Text(
            text = tool.summary,
            style = MaterialTheme.typography.bodySmall,
            color = SheafColors.Dim,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

@Composable
private fun RecentRow(recent: RecentEntity, onOpen: () -> Unit, onForget: () -> Unit) {
    Panel(modifier = Modifier.fillMaxWidth(), onClick = onOpen) {
        Text(
            text = recent.displayName,
            style = MaterialTheme.typography.titleMedium,
            color = SheafColors.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        RowBetween(modifier = Modifier.padding(top = 4.dp)) {
            Text(
                text = "${formatPageCount(recent.pageCount)} · ${formatBytes(recent.sizeBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = SheafColors.Dim
            )
            TextButton(onClick = onForget) {
                Text("Remove", style = MaterialTheme.typography.bodySmall, color = SheafColors.Dim)
            }
        }
    }
}

/** Tall enough for the icon tile, a one-line title and a three-line summary. */
private val TOOL_CARD_HEIGHT = 158.dp
