package com.layerbit.sheaf.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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

/**
 * The home screen: open something to read, or pick a tool.
 *
 * Reading comes first because it is what people do most often and because a viewer that works
 * is what earns the trust to try the tools. The tools sit below it as one flat grid rather
 * than behind categories - there are nine, and a person looking for "compress" should see the
 * word without navigating first.
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
        modifier = modifier.fillMaxSize(),
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
                Spacer(Modifier.height(20.dp))
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
            }
        }

        item {
            Spacer(Modifier.height(10.dp))
            SectionHeading("Tools")
            Spacer(Modifier.height(8.dp))
            ToolGrid(onTool)
        }

        if (recents.isNotEmpty()) {
            item {
                Spacer(Modifier.height(8.dp))
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
            Spacer(Modifier.height(20.dp))
            TextButton(onClick = onAbout) {
                Text("About Sheaf", color = SheafColors.Muted)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToolGrid(onTool: (ToolId) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth(),
        maxItemsInEachRow = 2
    ) {
        ToolId.entries.forEach { tool ->
            Panel(
                modifier = Modifier.weight(1f),
                onClick = { onTool(tool) }
            ) {
                Text(
                    text = tool.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = SheafColors.Text
                )
                Text(
                    text = tool.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = SheafColors.Dim,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
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
