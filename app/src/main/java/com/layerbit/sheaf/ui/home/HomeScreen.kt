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
import com.layerbit.sheaf.R
import com.layerbit.sheaf.brand.BrandLinks
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
 * The home screen: a board of everything Sheaf does.
 *
 * There is deliberately no big primary button above the tools.
 *
 * An earlier version had one, "Open a PDF", and it created a false hierarchy. It read as *the*
 * way to use the app, which turned the tools underneath into a list of things you might read
 * about rather than things you would tap. Reading is now simply the first card, in the first
 * group, colored like the others.
 *
 * The cards are grouped by what someone is trying to do rather than left as one flat wall of
 * twelve. Groups are headings, not navigation: nothing is hidden behind a tap.
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
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text(
                text = "Everything happens on this phone. Nothing is uploaded, because Sheaf " +
                    "cannot use the internet at all.",
                style = MaterialTheme.typography.bodyMedium,
                color = SheafColors.Muted,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }

        ToolId.Group.entries.forEach { group ->
            item(key = "heading-${group.name}") {
                Spacer(Modifier.height(6.dp))
                SectionHeading(group.title)
                Spacer(Modifier.height(8.dp))
            }
            item(key = "grid-${group.name}") {
                ToolGrid(
                    group = group,
                    onTool = onTool,
                    onRead = onOpenDocument
                )
            }
        }

        if (recents.isNotEmpty()) {
            item {
                Spacer(Modifier.height(14.dp))
                SectionHeading("Recent")
                Spacer(Modifier.height(4.dp))
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
            Spacer(Modifier.height(22.dp))
            TextButton(onClick = onAbout) {
                Text(
                    "Free, offline, and from ${BrandLinks.BRAND_LABEL}",
                    style = MaterialTheme.typography.bodySmall,
                    color = SheafColors.Dim
                )
            }
        }
    }
}

/**
 * One group's cards, two to a row and all the same height.
 *
 * The height is fixed rather than left to the content. Summaries run to two or three lines
 * depending on the tool, and letting each card size itself leaves neighboring cards with
 * mismatched bottoms. An odd group gets an empty half-width slot rather than one card
 * stretched across, which would read as a different kind of thing.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToolGrid(group: ToolId.Group, onTool: (ToolId) -> Unit, onRead: () -> Unit) {
    val tools = ToolId.entries.filter { it.group == group }
    // Reading is a card like any other, and it belongs at the front of Read and capture.
    val readFirst = group == ToolId.Group.READ
    val count = tools.size + if (readFirst) 1 else 0

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        maxItemsInEachRow = 2,
        modifier = Modifier.fillMaxWidth()
    ) {
        if (readFirst) {
            Card(
                iconRes = R.drawable.ic_tool_read,
                title = "Read a PDF",
                summary = "Open a document, then use any tool on it from there",
                accent = true,
                onClick = onRead,
                modifier = Modifier.weight(1f)
            )
        }
        tools.forEach { tool ->
            Card(
                iconRes = iconFor(tool),
                title = tool.title,
                summary = tool.summary,
                accent = false,
                onClick = { onTool(tool) },
                modifier = Modifier.weight(1f)
            )
        }
        if (count % 2 != 0) {
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun Card(
    iconRes: Int,
    title: String,
    summary: String,
    accent: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Panel(modifier = modifier.height(TOOL_CARD_HEIGHT), onClick = onClick) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(
                    // The reading card carries the band color at low opacity. It is the most
                    // common thing people do, and this marks it without lifting it out of the
                    // grid the way a separate button did.
                    if (accent) SheafColors.Band.copy(alpha = 0.16f) else SheafColors.SurfaceDim,
                    RoundedCornerShape(10.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = if (accent) SheafColors.BandBright else SheafColors.Muted,
                modifier = Modifier.size(20.dp)
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = SheafColors.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 10.dp)
        )
        Text(
            text = summary,
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
