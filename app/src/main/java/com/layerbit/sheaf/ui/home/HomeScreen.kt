package com.layerbit.sheaf.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.layerbit.sheaf.R
import com.layerbit.sheaf.brand.BrandLinks
import com.layerbit.sheaf.data.db.RecentEntity
import com.layerbit.sheaf.ops.ToolId
import com.layerbit.sheaf.ui.components.ListGap
import com.layerbit.sheaf.ui.components.ListRhythm
import com.layerbit.sheaf.ui.components.Panel
import com.layerbit.sheaf.ui.components.RowBetween
import com.layerbit.sheaf.ui.components.SectionHeading
import com.layerbit.sheaf.ui.components.formatBytes
import com.layerbit.sheaf.ui.components.formatPageCount
import com.layerbit.sheaf.ui.theme.SheafColors
import com.layerbit.sheaf.ui.theme.ToolAccent
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
        contentPadding = ListRhythm,
        verticalArrangement = Arrangement.spacedBy(ListGap)
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
                    Spacer(Modifier.height(HEADING_ABOVE))
                    SectionHeading(if (matches.size == 1) "1 tool" else "${matches.size} tools")
                    Spacer(Modifier.height(HEADING_BELOW))
                    ToolCards(
                        tools = matches,
                        favourites = favourites,
                        onTool = onTool,
                        onToggleFavourite = onToggleFavourite
                    )
                }
            }
        } else {
            // One row high, however many documents there are, because it scrolls sideways.
            //
            // This was a single row showing only the newest document, and the argument for
            // that was sound as far as it went: the full Recent list stacked up here would be
            // three or four hundred dp of documents above the board, which would rebuild the
            // false hierarchy described at the top of this file and turn the tools into a
            // list of things you read about.
            //
            // But that left the other documents at the bottom, under about 2,400dp of cards,
            // which is to say nowhere. Going sideways settles it: the vertical cost is the
            // same one row it always was, and eight documents fit in it instead of one. The
            // full section stays at the bottom, and is now about managing them - forgetting
            // one, seeing what it costs - rather than the only way to reach them.
            if (recents.isNotEmpty()) {
                item(key = "resume") {
                    RecentStrip(recents = recents, onOpen = onOpenRecent)
                }
            }

            if (starred.isNotEmpty()) {
                item(key = "favourites") {
                    Spacer(Modifier.height(HEADING_ABOVE))
                    SectionHeading("Yours")
                    Spacer(Modifier.height(HEADING_BELOW))
                    ToolCards(
                        tools = starred,
                        favourites = favourites,
                        onTool = onTool,
                        onToggleFavourite = onToggleFavourite
                    )
                }
            }

            item {
                if (favourites.isEmpty()) {
                    // Starring is the one thing that answers "this is a lot", and until you
                    // have found the gesture there is nothing on screen that mentions it: the
                    // "Yours" heading only appears once you have used it and the star only
                    // shows on a card that already has one. It is the one line left above the
                    // board, and it earns that because it is onboarding rather than furniture
                    // - it goes the moment it has been acted on and never comes back.
                    Text(
                        text = "Hold a tool to keep it at the top.",
                        style = MaterialTheme.typography.bodySmall,
                        color = SheafColors.Dim,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }

            // Heading and grid in one item, so the gap above a heading cannot be collapsed by
            // the list's own spacing while the gap below it is not. Every group now reads 24dp
            // above and 8dp below, against the 12dp gap between the cards themselves.
            ToolId.Group.entries.forEach { group ->
                item(key = "group-${group.name}") {
                    Spacer(Modifier.height(HEADING_ABOVE))
                    SectionHeading(group.title, color = accentFor(group).ink)
                    Spacer(Modifier.height(HEADING_BELOW))
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
                Spacer(Modifier.height(HEADING_ABOVE))
                SectionHeading("Recent")
                // This one keeps a heading item of its own. Its rows are separate items so
                // that a forgotten document can animate out, which means the gap below the
                // heading is the list's own and cannot be brought down to 8 without folding
                // the heading into the first row.
                Spacer(Modifier.height(4.dp))
            }
            items(recents, key = { it.uri }) { recent ->
                RecentRow(
                    recent = recent,
                    onOpen = { onOpenRecent(recent) },
                    onForget = { onForgetRecent(recent) },
                    // The full animateItem, not just placement: a forgotten document should
                    // fade out where it stood and let the list close up afterwards, rather
                    // than vanish and have everything below it jump.
                    modifier = Modifier.animateItem()
                )
            }
        }

        item {
            Spacer(Modifier.height(22.dp))
            // The promise reads here rather than above the board.
            //
            // It is the most important sentence in the app and it was costing 46dp at the top
            // of every launch to say a thing that does not change. Someone deciding whether to
            // trust an offline PDF tool reads it once; after that it is furniture standing
            // between them and the tools. Down here it is still on the first screen, still
            // unmissable to anyone who scrolls, and it is no longer rent.
            Text(
                text = "Everything happens on this phone. Nothing is uploaded, because Sheaf " +
                    "cannot use the internet at all.",
                style = MaterialTheme.typography.bodyMedium,
                color = SheafColors.Muted
            )
            // Zero horizontal content padding, so this starts on the same gutter as the
            // sentence above it. A TextButton brings 12dp of its own, which is right when it
            // sits in a row of buttons and wrong when it is the second line of a paragraph.
            TextButton(
                onClick = onAbout,
                contentPadding = PaddingValues(vertical = 8.dp),
                modifier = Modifier.padding(top = 2.dp)
            ) {
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
 * Cards, as many to a row as the width honestly holds, and all the same height.
 *
 * The height is a floor rather than a fixed figure. Summaries run to two or three lines
 * depending on the tool, and letting each card size itself leaves neighbouring cards with
 * mismatched bottoms - but the tallest summary still clears the floor comfortably at the
 * default font scale, so every card is exactly the floor and the bottoms match anyway. A
 * fixed height bought the same thing and cropped the third line through the glyphs the moment
 * the system font size was turned up, because the clip happens outside the Text. A minimum
 * gives up nothing anyone sees and lets the cards grow instead.
 *
 * A short row gets empty slots rather than one card stretched across, which would read as a
 * different kind of thing.
 *
 * The column count is worked out here and not at each caller, so there is one rule. 180dp is
 * the narrowest a card can be and still wrap the summary to three lines: at a 320dp content
 * width that gives two columns, so a phone in portrait is exactly as it was, and at 760dp it
 * gives four. The manifest handles orientation changes itself, so landscape, foldables and
 * tablets all reach this - and without it they got two letterboxes 340-480dp wide holding a
 * 36dp icon and one line of text.
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

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val columns = (maxWidth / TOOL_CARD_MIN_WIDTH).toInt().coerceAtLeast(2)

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            maxItemsInEachRow = columns,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (leadingReadCard) {
                Card(
                    iconRes = R.drawable.ic_tool_read,
                    title = "Read a PDF",
                    summary = "Open a document, then use any tool on it from there",
                    accent = accentFor(ToolId.Group.READ),
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
                    // Its own group's hue, even in search results and in "Yours", where the
                    // cards are not sitting under their heading. The colour has to mean the
                    // same thing wherever a card turns up or it means nothing anywhere.
                    accent = accentFor(tool.group),
                    starred = tool.name in favourites,
                    onClick = { onTool(tool) },
                    onLongClick = { onToggleFavourite(tool) },
                    modifier = Modifier.weight(1f)
                )
            }
            // Enough fillers to complete the last row, however many columns there are. The
            // cards are weighted, so one missing slot is not an empty gap but a card stretched
            // to twice the width of every other card on the screen.
            repeat((columns - count % columns) % columns) {
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
/**
 * The group hue for a tool, by position in [ToolId.Group].
 *
 * Indexed rather than matched in a `when`, so adding a group without adding its colour is a
 * crash on the first draw instead of a sixth grey card nobody notices for a month.
 */
private fun accentFor(group: ToolId.Group): ToolAccent =
    SheafColors.ToolAccents[group.ordinal]

@Composable
private fun Card(
    iconRes: Int,
    title: String,
    summary: String,
    accent: ToolAccent,
    starred: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    Panel(
        modifier = modifier.heightIn(min = TOOL_CARD_HEIGHT),
        onClick = onClick,
        onLongClick = onLongClick,
        highlighted = starred
    ) {
        RowBetween {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(accent.tile, MaterialTheme.shapes.small),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = accent.ink,
                    modifier = Modifier.size(20.dp)
                )
            }
            if (starred) {
                // The drawn star, not the "★" character, so it belongs to the same set as the
                // twenty-seven tool icons it sits beside. It is a mark on the card rather than
                // a control of its own - the gesture that sets it is the long press on the
                // whole card - so it carries a description instead of a click.
                Icon(
                    painter = painterResource(R.drawable.ic_star),
                    contentDescription = "Kept at the top",
                    tint = SheafColors.BandBright,
                    modifier = Modifier.size(16.dp)
                )
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
private fun RecentRow(
    recent: RecentEntity,
    onOpen: () -> Unit,
    onForget: () -> Unit,
    modifier: Modifier = Modifier
) {
    Panel(modifier = modifier.fillMaxWidth(), onClick = onOpen) {
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
            // The word on screen stays short, because the row above it already says which
            // document this is. A screen reader does not get that: TalkBack's Actions list
            // and its controls granularity read button labels on their own, so a list of
            // recents was a column of identical "Remove" buttons. Nothing changes visually.
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

/**
 * The way back into what you were reading, one row high and scrolling sideways.
 *
 * No Remove button and no file size: this is not the Recent list in miniature, it is the door
 * back into the document you had open ten minutes ago. Managing them - forgetting one, seeing
 * what the copies cost - is the full section at the bottom, and that is the right place for
 * it, because managing is a thing you do on purpose and resuming is a thing you do constantly.
 *
 * [RECENT_STRIP_MAX] rather than all of them. The strip is a shortcut and a shortcut with
 * forty entries is a list; past the first handful nobody is scrolling sideways to find
 * anything, they are going to the section at the bottom that has a heading and a search above
 * it. Capping it also keeps the row cheap: eight composed cards, not however many documents
 * this phone has opened since the app was installed.
 */
@Composable
private fun RecentStrip(
    recents: List<RecentEntity>,
    onOpen: (RecentEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        items(recents.take(RECENT_STRIP_MAX), key = { it.uri }) { recent ->
            Panel(
                modifier = Modifier.width(RECENT_CARD_WIDTH),
                onClick = { onOpen(recent) }
            ) {
                Text(
                    text = recent.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = SheafColors.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (recent.lastPage > 0) {
                        "read to page ${recent.lastPage + 1}"
                    } else {
                        formatPageCount(recent.pageCount)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = SheafColors.Dim,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

/** Tall enough for the icon tile, a one-line title and a three-line summary. */
private val TOOL_CARD_HEIGHT = 158.dp

/** The narrowest a card can be and still wrap a summary to three lines. */
private val TOOL_CARD_MIN_WIDTH = 180.dp

/**
 * The air around a section heading. The list itself already spaces items by [ListGap], so the
 * figure here is what is left to make up 24dp above; below the heading there is no list gap to
 * subtract, because the heading and the grid it labels sit in the same item.
 */
/**
 * How many documents the strip offers before it stops.
 *
 * Eight is about three screens of sideways scroll on a phone, which is as far as anyone will
 * push a shortcut before going to look at the proper list instead.
 */
private const val RECENT_STRIP_MAX = 8

/** Wide enough for a real filename to be recognised, narrow enough that the next one peeks. */
private val RECENT_CARD_WIDTH = 176.dp

private val HEADING_ABOVE = 12.dp
private val HEADING_BELOW = 8.dp
