package com.layerbit.sheaf.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
 * group, coloured like the others.
 *
 * The cards are grouped by what someone is trying to do rather than left as one flat wall of
 * twenty-odd. Groups are headings, not navigation: nothing is hidden behind a tap.
 *
 * Two things cut through the grid once it is this big. The field at the top searches the tools
 * by name and by what they say they do, which is how someone who knows the word "shrink"
 * finds a tool called Compress. And a long press stars a tool, which pins it to the top -
 * because the four tools a particular person actually uses are not the same four as anyone
 * else's, and no amount of grouping fixes that.
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
    onAbout: () -> Unit,
    modifier: Modifier = Modifier
) {
    var query by remember { mutableStateOf("") }
    val matches = remember(query) { toolsMatching(query) }
    val starred = remember(favourites) {
        ToolId.entries.filter { it.name in favourites }
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = {
                    Text(
                        "Search ${ToolId.entries.size} tools",
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                singleLine = true,
                trailingIcon = {
                    if (query.isNotBlank()) {
                        TextButton(onClick = { query = "" }) {
                            Text(
                                "Clear",
                                style = MaterialTheme.typography.bodySmall,
                                color = SheafColors.Dim
                            )
                        }
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = SheafColors.Band,
                    unfocusedBorderColor = SheafColors.Border,
                    focusedTextColor = SheafColors.Text,
                    unfocusedTextColor = SheafColors.Text,
                    cursorColor = SheafColors.Band
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (query.isNotBlank()) {
            if (matches.isEmpty()) {
                item {
                    Text(
                        "Nothing matches that. Every tool is listed below when the search is " +
                            "empty - or tell us what is missing from the About screen.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SheafColors.Muted,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            } else {
                item(key = "search-results") {
                    Spacer(Modifier.height(4.dp))
                    SectionHeading(if (matches.size == 1) "1 tool" else "${matches.size} tools")
                    Spacer(Modifier.height(8.dp))
                    ToolCards(
                        tools = matches,
                        favourites = favourites,
                        onTool = onTool,
                        onToggleFavourite = onToggleFavourite
                    )
                }
            }
        } else {
            if (starred.isNotEmpty()) {
                item(key = "favourites") {
                    Spacer(Modifier.height(4.dp))
                    SectionHeading("Yours")
                    Spacer(Modifier.height(8.dp))
                    ToolCards(
                        tools = starred,
                        favourites = favourites,
                        onTool = onTool,
                        onToggleFavourite = onToggleFavourite
                    )
                }
            }

            item {
                Text(
                    text = "Everything happens on this phone. Nothing is uploaded, because Sheaf " +
                        "cannot use the internet at all.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SheafColors.Muted,
                    modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                )
            }

            ToolId.Group.entries.forEach { group ->
                item(key = "heading-${group.name}") {
                    Spacer(Modifier.height(6.dp))
                    SectionHeading(group.title)
                    Spacer(Modifier.height(8.dp))
                }
                item(key = "grid-${group.name}") {
                    ToolCards(
                        tools = ToolId.entries.filter { it.group == group },
                        favourites = favourites,
                        onTool = onTool,
                        onToggleFavourite = onToggleFavourite,
                        leadingReadCard = group == ToolId.Group.READ,
                        onRead = onOpenDocument
                    )
                }
            }
        }

        if (recents.isNotEmpty() && query.isBlank()) {
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
    onToggleFavourite: (ToolId) -> Unit,
    leadingReadCard: Boolean = false,
    onRead: () -> Unit = {}
) {
    val count = tools.size + if (leadingReadCard) 1 else 0

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        maxItemsInEachRow = 2,
        modifier = Modifier.fillMaxWidth()
    ) {
        if (leadingReadCard) {
            Card(
                iconRes = R.drawable.ic_tool_read,
                title = "Read a PDF",
                summary = "Open a document, then use any tool on it from there",
                accent = true,
                starred = false,
                onClick = onRead,
                onLongClick = null,
                modifier = Modifier.weight(1f)
            )
        }
        tools.forEach { tool ->
            Card(
                iconRes = iconFor(tool),
                title = tool.title,
                summary = tool.summary,
                accent = false,
                starred = tool.name in favourites,
                onClick = { onTool(tool) },
                onLongClick = { onToggleFavourite(tool) },
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
    starred: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    Panel(
        modifier = modifier.height(TOOL_CARD_HEIGHT),
        onClick = onClick,
        onLongClick = onLongClick,
        highlighted = starred
    ) {
        RowBetween {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(
                        // The reading card carries the band colour at low opacity. It is the
                        // most common thing people do, and this marks it without lifting it
                        // out of the grid the way a separate button did.
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
            if (starred) {
                Text("★", style = MaterialTheme.typography.bodySmall, color = SheafColors.BandBright)
            }
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
            TextButton(onClick = onForget) {
                Text("Remove", style = MaterialTheme.typography.bodySmall, color = SheafColors.Dim)
            }
        }
    }
}

/** Tall enough for the icon tile, a one-line title and a three-line summary. */
private val TOOL_CARD_HEIGHT = 158.dp
