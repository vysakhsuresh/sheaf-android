package com.layerbit.sheaf.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.layerbit.sheaf.R
import com.layerbit.sheaf.brand.BrandLinks
import com.layerbit.sheaf.data.db.RecentEntity
import com.layerbit.sheaf.ops.ToolId
import com.layerbit.sheaf.ui.components.IconTile
import com.layerbit.sheaf.ui.components.Panel
import com.layerbit.sheaf.ui.components.Pill
import com.layerbit.sheaf.ui.components.PrimaryButton
import com.layerbit.sheaf.ui.components.RoundIconButton
import com.layerbit.sheaf.ui.components.SecondaryButton
import com.layerbit.sheaf.ui.components.SectionHeading
import com.layerbit.sheaf.ui.components.formatBytes
import com.layerbit.sheaf.ui.components.formatPageCount
import com.layerbit.sheaf.ui.theme.GroupAccents
import com.layerbit.sheaf.ui.theme.SheafColors
import com.layerbit.sheaf.ui.tools.iconFor

/**
 * The home screen: a board of everything Sheaf does.
 *
 * The top of it is the two things people come for most - opening a document and scanning
 * one - and the documents they were in the middle of. Below that, every tool, grouped by
 * what someone is trying to do and coloured by group so forty cards can be scanned by eye.
 * Groups are headings, not navigation: nothing is hidden behind a tap.
 *
 * The search field finds tools by name and by what they say they do, which is how someone
 * who knows the word "shrink" finds Compress. A long press stars a tool, which pins it to the
 * top, because the four tools one person uses are not the same four as anyone else's.
 */
@Composable
fun HomeScreen(
    recents: List<RecentEntity>,
    favourites: Set<String>,
    onOpenDocument: () -> Unit,
    onOpenRecent: (RecentEntity) -> Unit,
    onForgetRecent: (RecentEntity) -> Unit,
    onTool: (ToolId) -> Unit,
    onToggleFavourite: (ToolId) -> Unit,
    onSettings: () -> Unit,
    onAbout: () -> Unit,
    modifier: Modifier = Modifier
) {
    var query by remember { mutableStateOf("") }
    val matches = remember(query) { toolsMatching(query) }
    val starred = remember(favourites) { ToolId.entries.filter { it.name in favourites } }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "hero") { Hero(onSettings = onSettings, onAbout = onAbout) }

        item(key = "actions") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                PrimaryButton(
                    text = "Open a PDF",
                    iconRes = R.drawable.ic_tool_read,
                    onClick = onOpenDocument,
                    modifier = Modifier.weight(1.3f)
                )
                SecondaryButton(
                    text = "Scan",
                    iconRes = R.drawable.ic_tool_scan,
                    onClick = { onTool(ToolId.SCAN) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        item(key = "search") {
            SearchField(query = query, onChange = { query = it })
        }

        if (query.isNotBlank()) {
            if (matches.isEmpty()) {
                item {
                    Text(
                        "Nothing matches that. Every tool is listed when the search is empty - " +
                            "or tell us what is missing from the About screen.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SheafColors.Muted,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            } else {
                item(key = "search-results") {
                    SectionHeading(if (matches.size == 1) "1 tool" else "${matches.size} tools", modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
                    ToolCards(tools = matches, favourites = favourites, onTool = onTool, onToggleFavourite = onToggleFavourite)
                }
            }
        } else {
            if (recents.isNotEmpty()) {
                item(key = "recents") {
                    SectionHeading("Pick up where you left off", modifier = Modifier.padding(top = 6.dp, bottom = 8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(recents, key = { it.uri }) { recent ->
                            RecentCard(recent = recent, onOpen = { onOpenRecent(recent) }, onForget = { onForgetRecent(recent) })
                        }
                    }
                }
            }

            if (starred.isNotEmpty()) {
                item(key = "favourites") {
                    SectionHeading("Yours", accent = SheafColors.Band, modifier = Modifier.padding(top = 6.dp, bottom = 8.dp))
                    ToolCards(tools = starred, favourites = favourites, onTool = onTool, onToggleFavourite = onToggleFavourite)
                }
            }

            ToolId.Group.entries.forEach { group ->
                val tools = ToolId.entries.filter { it.group == group }
                if (tools.isEmpty()) return@forEach
                item(key = "group-${group.name}") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 10.dp, bottom = 8.dp)
                    ) {
                        SectionHeading(group.title, accent = GroupAccents.of(group), modifier = Modifier.weight(1f))
                        Text(
                            "${tools.size}",
                            style = MaterialTheme.typography.labelSmall,
                            color = SheafColors.Dim
                        )
                    }
                    ToolCards(
                        tools = tools,
                        favourites = favourites,
                        onTool = onTool,
                        onToggleFavourite = onToggleFavourite
                    )
                }
            }
        }

        item(key = "footer") {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Long-press a tool to pin it to the top.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SheafColors.Dim
                )
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
}

/**
 * The wordmark, the promise in one line, and the two screens that are not tools. A faint
 * wash of the band colour behind it is the one place the app allows itself some light.
 */
@Composable
private fun Hero(onSettings: () -> Unit, onAbout: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(SheafColors.Band.copy(alpha = if (SheafColors.isDark) 0.16f else 0.10f), Color.Transparent)
                ),
                RoundedCornerShape(24.dp)
            )
            .padding(top = 6.dp, bottom = 10.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                    Text("Sheaf", style = MaterialTheme.typography.displayMedium, color = SheafColors.Text)
                    Text(
                        "Every PDF tool. Nothing leaves your phone.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SheafColors.Muted
                    )
                }
                RoundIconButton(icon = Icons.Filled.Settings, contentDescription = "Settings", onClick = onSettings)
                Spacer(Modifier.width(8.dp))
                RoundIconButton(icon = Icons.Filled.Info, contentDescription = "About", onClick = onAbout)
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 12.dp, start = 4.dp)
            ) {
                Pill("Offline", SheafColors.Done)
                Pill("No internet permission", SheafColors.Running)
                Pill("${ToolId.entries.size} tools", SheafColors.Band)
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    val shape = RoundedCornerShape(999.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(SheafColors.Surface, shape)
            .border(1.dp, if (query.isNotBlank()) SheafColors.Band.copy(alpha = 0.7f) else SheafColors.Border, shape)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = SheafColors.Dim, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Box(modifier = Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    "Search tools: shrink, lock, sign, Hindi…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SheafColors.Dim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = SheafColors.Text),
                cursorBrush = SolidColor(SheafColors.Band),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (query.isNotBlank()) {
            RoundIconButton(icon = Icons.Filled.Close, contentDescription = "Clear", onClick = { onChange("") }, size = 28.dp)
        }
    }
}

/**
 * Searches the tools the way someone describes what they want rather than what it is called.
 *
 * Title, summary and the verb on the button are all matched, which is what makes "shrink"
 * find Compress and "lock" find Add a password. Word-prefix matching rather than plain
 * contains, so "page" does not rank "Pages per sheet" below something that merely mentions
 * the word in passing.
 */
private fun toolsMatching(query: String): List<ToolId> {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return emptyList()

    fun score(tool: ToolId): Int {
        val title = tool.title.lowercase()
        val haystack = "$title ${tool.summary.lowercase()} ${tool.action.lowercase()} ${tool.group.title.lowercase()}"
        return when {
            title.startsWith(needle) -> 3
            title.contains(needle) -> 2
            haystack.split(' ', ',', '-', '.').any { it.startsWith(needle) } -> 1
            haystack.contains(needle) -> 1
            else -> 0
        }
    }

    return ToolId.entries
        .map { it to score(it) }
        .filter { it.second > 0 }
        .sortedWith(compareByDescending<Pair<ToolId, Int>> { it.second }.thenBy { it.first.title })
        .map { it.first }
}

/**
 * Cards, two to a row and all the same height.
 *
 * The height is fixed rather than left to the content. Summaries run to two or three lines
 * depending on the tool, and letting each card size itself leaves neighbouring cards with
 * mismatched bottoms. An odd row gets an empty half-width slot rather than one card stretched
 * across, which would read as a different kind of thing.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToolCards(
    tools: List<ToolId>,
    favourites: Set<String>,
    onTool: (ToolId) -> Unit,
    onToggleFavourite: (ToolId) -> Unit
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        maxItemsInEachRow = 2,
        modifier = Modifier.fillMaxWidth()
    ) {
        tools.forEach { tool ->
            ToolCard(
                tool = tool,
                starred = tool.name in favourites,
                onClick = { onTool(tool) },
                onLongClick = { onToggleFavourite(tool) },
                modifier = Modifier.weight(1f)
            )
        }
        if (tools.size % 2 != 0) {
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun ToolCard(
    tool: ToolId,
    starred: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = GroupAccents.of(tool.group)
    Panel(
        modifier = modifier.height(TOOL_CARD_HEIGHT),
        onClick = onClick,
        onLongClick = onLongClick,
        highlighted = starred,
        inset = 14.dp
    ) {
        Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
            IconTile(iconRes = iconFor(tool), accent = accent)
            Spacer(Modifier.weight(1f))
            if (starred) {
                Icon(Icons.Filled.Star, contentDescription = "Pinned", tint = SheafColors.BandBright, modifier = Modifier.size(16.dp))
            }
        }
        Text(
            text = tool.title,
            style = MaterialTheme.typography.titleMedium,
            color = SheafColors.Text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 12.dp)
        )
        Text(
            text = tool.summary,
            style = MaterialTheme.typography.bodySmall,
            color = SheafColors.Dim,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 3.dp)
        )
    }
}

/** A document somebody was reading, with how far they got. */
@Composable
private fun RecentCard(recent: RecentEntity, onOpen: () -> Unit, onForget: () -> Unit) {
    val progress = if (recent.pageCount > 0) (recent.lastPage + 1).toFloat() / recent.pageCount else 0f
    Panel(modifier = Modifier.width(220.dp), onClick = onOpen, inset = 14.dp) {
        Row(verticalAlignment = Alignment.Top) {
            IconTile(iconRes = R.drawable.ic_tool_read, accent = SheafColors.Band, filled = true, size = 36.dp)
            Spacer(Modifier.weight(1f))
            RoundIconButton(icon = Icons.Filled.Close, contentDescription = "Remove from the list", onClick = onForget, size = 28.dp)
        }
        Text(
            text = recent.displayName,
            style = MaterialTheme.typography.titleMedium,
            color = SheafColors.Text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 10.dp)
        )
        Text(
            text = buildString {
                append(formatPageCount(recent.pageCount))
                append(" · ")
                append(formatBytes(recent.sizeBytes))
            },
            style = MaterialTheme.typography.bodySmall,
            color = SheafColors.Dim,
            modifier = Modifier.padding(top = 2.dp)
        )
        // Where they got to, as a bar: the list says which of two statements is the one
        // half-read rather than making the reader open both to find out.
        if (recent.lastPage > 0) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .height(4.dp)
                    .background(SheafColors.SurfaceDim, RoundedCornerShape(2.dp))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress.coerceIn(0.04f, 1f))
                        .height(4.dp)
                        .background(SheafColors.Band, RoundedCornerShape(2.dp))
                )
            }
            Text(
                "Page ${recent.lastPage + 1} of ${recent.pageCount}",
                style = MaterialTheme.typography.labelSmall,
                color = SheafColors.Muted,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

/** Tall enough for the icon tile, a two-line title and a two-line summary. */
private val TOOL_CARD_HEIGHT = 164.dp
