package com.layerbit.sheaf.ui.viewer

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.layerbit.sheaf.R
import com.layerbit.sheaf.data.db.BookmarkEntity
import com.layerbit.sheaf.ops.ToolId
import com.layerbit.sheaf.pdf.BitmapBudget
import com.layerbit.sheaf.pdf.OutlineEntry
import com.layerbit.sheaf.pdf.PageArea
import com.layerbit.sheaf.pdf.PageSize
import com.layerbit.sheaf.ui.components.IconAction
import com.layerbit.sheaf.ui.components.RowBetween
import com.layerbit.sheaf.ui.components.SectionHeading
import com.layerbit.sheaf.ui.components.SheafTopBar
import com.layerbit.sheaf.ui.theme.SheafColors
import com.layerbit.sheaf.ui.tools.ToolPickerSheet
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Continuous vertical scroll through a document.
 *
 * Each page is its own list item, sized from the page dimensions read at open time. The list
 * therefore has correct extents before a single page is rendered, which is what makes the
 * scrollbar honest and stops the content jumping as pages arrive.
 *
 * Rendering happens per item, when the item composes. The bitmap is dropped when it leaves
 * the cache.
 *
 * Each page is drawn in two layers. Underneath is the whole page at screen width, which is
 * what the page cache holds and what the reader sees the instant an item composes. Over it,
 * once the view has settled and only while zoomed in, goes one tile: the part of the page
 * that is actually on the screen, rendered at the screen's own resolution. Zoom therefore
 * costs what the screen costs, not what the page costs - which at 3x used to be a 54 MB
 * bitmap, larger than the whole cache, re-rendered on every scroll.
 *
 * P0's exit gate is measured here: a five-hundred-page document, scrolled end to end, at
 * sixty frames per second, returning to a flat heap afterward.
 */
@Composable
fun ViewerScreen(
    state: ViewerState,
    reading: ReadingState,
    /** Starred tools, so the tool sheet offers them first. */
    favourites: Set<String> = emptySet(),
    renderPage: suspend (index: Int, widthPx: Int) -> Bitmap?,
    renderTile: suspend (
        index: Int,
        fullWidthPx: Int,
        tile: Rect,
        outWidthPx: Int,
        outHeightPx: Int
    ) -> Bitmap?,
    onUseTool: (ToolId, String) -> Unit,
    onBack: () -> Unit,
    onToggleNight: () -> Unit,
    onSearch: (String) -> Unit,
    onClearSearch: () -> Unit,
    onStepHit: (Boolean) -> Unit,
    onJump: (Int) -> Unit,
    onToggleBookmark: (Int) -> Unit,
    onRemoveBookmark: (Int) -> Unit,
    onPageChanged: (Int) -> Unit,
    onPrint: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
    keepScreenOn: Boolean = true,
    showPageBadge: Boolean = true
) {
    var showTools by remember { mutableStateOf(false) }
    var showContents by remember { mutableStateOf(false) }
    var showGoToPage by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    // The result list folds away once a result is chosen, while the search itself stays on.
    // Clearing the query there would take the highlights off the page the reader just asked
    // to be shown - the one thing the jump was for.
    var showResults by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Reading is the one thing people do without touching the screen, so the display timeout
    // works against it. Set on the view rather than through a window flag, so it is undone
    // automatically when the viewer leaves the composition.
    val view = LocalView.current
    val onPage = state is ViewerState.Ready
    DisposableEffect(keepScreenOn, onPage) {
        view.keepScreenOn = keepScreenOn && onPage
        onDispose { view.keepScreenOn = false }
    }

    val pageCount = (state as? ViewerState.Ready)?.pageCount ?: 0
    val currentPage by remember(pageCount) {
        derivedStateOf { listState.firstVisibleItemIndex.coerceAtMost((pageCount - 1).coerceAtLeast(0)) }
    }
    // Reporting the position is held back until the document has been put where the reader
    // left it. Without that, the first frame reports page one and overwrites the very
    // position it is about to scroll to.
    val generation = (state as? ViewerState.Ready)?.generation
    val initialPage = (state as? ViewerState.Ready)?.initialPage ?: 0
    var resumed by remember(generation) { mutableStateOf(initialPage == 0) }

    LaunchedEffect(generation) {
        if (generation != null && initialPage > 0) {
            listState.scrollToItem(initialPage)
            resumed = true
        }
    }

    LaunchedEffect(currentPage, resumed) { if (resumed) onPageChanged(currentPage) }

    // Everything that can move the reader arrives as a jump, so there is one scroll here
    // rather than one per caller.
    LaunchedEffect(reading.jump?.token) {
        reading.jump?.let { listState.scrollToItem(it.pageIndex.coerceAtLeast(0)) }
    }

    val marked = reading.bookmarks.any { it.pageIndex == currentPage }

    Column(modifier = modifier.fillMaxSize().background(SheafColors.Background)) {
        if (state is ViewerState.Ready) {
            ViewerHeader(
                state = state,
                reading = reading,
                currentPage = currentPage,
                marked = marked,
                onBack = onBack,
                onTools = { showTools = true },
                onContents = { showContents = true },
                onGoToPage = { showGoToPage = true },
                onToggleNight = onToggleNight,
                onToggleBookmark = { onToggleBookmark(currentPage) },
                onPrint = onPrint,
                onShare = onShare,
                onSearchToggle = {
                    searching = !searching
                    showResults = true
                    if (!searching) onClearSearch()
                }
            )
            // Expanded and collapsed rather than inserted and removed. The bar is about
            // 56dp tall and the page list sits directly under it, so switching search on
            // used to shunt the whole document down the screen in one frame.
            //
            // Deliberately only the bar's arrival and departure. There is no
            // animateContentSize on the bar's own column: its status line, hint and result
            // rows change on every keystroke, and a container easing to a new height per
            // letter typed would turn a crisp result list into a wobbling one - and the page
            // list below would ride that wobble for as long as you kept typing, which is the
            // very complaint this is here to fix.
            AnimatedVisibility(
                visible = searching,
                enter = expandVertically(tween(180)) + fadeIn(tween(180)),
                exit = shrinkVertically(tween(140)) + fadeOut(tween(100))
            ) {
                SearchBar(
                    reading = reading,
                    showResults = showResults,
                    onSearch = { query ->
                        showResults = true
                        onSearch(query)
                    },
                    onShowResults = { showResults = true },
                    onStep = { forward ->
                        showResults = false
                        onStepHit(forward)
                    },
                    onJump = { page ->
                        showResults = false
                        onJump(page)
                    }
                )
            }
        } else {
            // Every state that is not Ready needs its own way out, and none of them has a
            // document to name. The ordinary route into one is tapping a Recent whose Uri
            // grant has lapsed: without a bar the only exit from that is the system gesture,
            // on a screen that has just told the reader to go and do something else.
            SheafTopBar(
                title = when (state) {
                    is ViewerState.Loading -> "Opening…"
                    is ViewerState.NeedsPassword -> "Locked document"
                    else -> "Could not open"
                },
                onBack = onBack
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            when (state) {
                // No words here any more: the bar above already says "Opening…", and saying
                // it twice on an otherwise empty screen reads as a stutter.
                is ViewerState.Loading ->
                    CentredMessage("", modifier = Modifier.fillMaxSize(), showSpinner = true)

                is ViewerState.NeedsPassword -> CentredMessage(
                    text = if (state.sourceUri.isNotEmpty()) {
                        "This document is password-protected. Unlock a copy of it first, " +
                            "and Sheaf will open that."
                    } else {
                        "This document is password-protected.\n" +
                            "Use Remove a password from the home screen to unlock a copy first."
                    },
                    modifier = Modifier.fillMaxSize(),
                    action = {
                        // Offered only where there is a Uri to hand on. A document opened from
                        // a tool's own result list has none, and the sentence above sends the
                        // reader the long way round instead.
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            if (state.sourceUri.isNotEmpty()) {
                                TextButton(
                                    onClick = {
                                        onUseTool(ToolId.REMOVE_PASSWORD, state.sourceUri)
                                    }
                                ) {
                                    Text(
                                        "Unlock a copy",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = SheafColors.BandBright
                                    )
                                }
                            }
                            TextButton(onClick = onBack) {
                                Text("Go back", color = SheafColors.Muted)
                            }
                        }
                    }
                )

                is ViewerState.Failed -> CentredMessage(
                    text = state.reason,
                    modifier = Modifier.fillMaxSize(),
                    action = {
                        TextButton(onClick = onBack) {
                            Text("Go back", color = SheafColors.Muted)
                        }
                    }
                )

                is ViewerState.Ready -> {
                    PageList(
                        state = state,
                        nightMode = reading.nightMode,
                        highlights = if (searching) reading.highlights else emptyMap(),
                        emphasisedPage = reading.hits.getOrNull(reading.hitIndex)?.pageIndex,
                        listState = listState,
                        renderPage = renderPage,
                        renderTile = renderTile,
                        modifier = Modifier.fillMaxSize()
                    )

                    if (showPageBadge && state.pageCount > 1) {
                        PageBadge(
                            label = "${currentPage + 1} / ${state.pageCount}",
                            onClick = { showGoToPage = true },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                // 8 rather than 16: the badge now carries its own 48dp tap
                                // target around the pill, so the pill sits where it always did.
                                .padding(bottom = 8.dp)
                        )
                    }
                }
            }
        }
    }

    if (showContents && state is ViewerState.Ready) {
        ContentsSheet(
            outline = reading.outline,
            bookmarks = reading.bookmarks,
            onDismiss = { showContents = false },
            onJump = { page ->
                showContents = false
                onJump(page)
            },
            onRemoveBookmark = onRemoveBookmark
        )
    }

    if (showGoToPage && state is ViewerState.Ready) {
        GoToPageSheet(
            pageCount = state.pageCount,
            onDismiss = { showGoToPage = false },
            onGo = { page ->
                showGoToPage = false
                scope.launch { listState.scrollToItem(page) }
            }
        )
    }

    if (showTools && state is ViewerState.Ready) {
        ToolPickerSheet(
            heading = "Use on this document",
            documentName = state.displayName,
            favourites = favourites,
            onDismiss = { showTools = false },
            onPick = { tool ->
                showTools = false
                onUseTool(tool, state.sourceUri)
            }
        )
    }
}

/**
 * Name, page count, and the way through to every tool.
 *
 * This bar is what makes reading a document the start of something rather than the end of it.
 * Without it, opening a PDF shows you pages and stops, and the tools each make you find the
 * same file again through the system picker.
 *
 * Everything that is not Find or the tool button lives behind the one menu. A phone-width bar
 * fits three labels; printing, sharing, marking a page, night mode and the contents are nine,
 * and a bar that wraps to two rows takes space away from the page.
 */
@Composable
private fun ViewerHeader(
    state: ViewerState.Ready,
    reading: ReadingState,
    currentPage: Int,
    marked: Boolean,
    onBack: () -> Unit,
    onTools: () -> Unit,
    onContents: () -> Unit,
    onGoToPage: () -> Unit,
    onToggleNight: () -> Unit,
    onToggleBookmark: () -> Unit,
    onPrint: () -> Unit,
    onShare: () -> Unit,
    onSearchToggle: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val canMark = state.sourceUri.isNotEmpty()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SheafColors.Surface)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        RowBetween {
            IconAction(
                icon = R.drawable.ic_chevron_left,
                label = "Back",
                onClick = onBack,
                modifier = Modifier.padding(end = 2.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = SheafColors.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Page ${currentPage + 1} of ${state.pageCount}",
                    style = MaterialTheme.typography.bodySmall,
                    color = SheafColors.Dim
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (canMark) {
                    // One drawn star in both states, coloured rather than filled: there is not
                    // a solid shape anywhere in the other twenty-seven icons, and the state is
                    // carried to a screen reader by stateDescription rather than by the glyph.
                    IconAction(
                        icon = R.drawable.ic_star,
                        label = "Mark this page",
                        state = if (marked) "Marked" else "Not marked",
                        tint = if (marked) SheafColors.BandBright else SheafColors.Muted,
                        onClick = onToggleBookmark
                    )
                }
                TextButton(onClick = onSearchToggle) {
                    Text("Find", color = SheafColors.Muted)
                }
                Box {
                    IconAction(
                        icon = R.drawable.ic_more,
                        label = "More",
                        onClick = { menuOpen = true }
                    )
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                        // Flat, like everything else here. Grep the ui/ package for
                        // elevation, shadow or Divider and nothing comes back: the bordered
                        // flat language is the app's, and Material's default 3dp drop shadow
                        // under this one menu was the only place it was contradicted.
                        shadowElevation = 0.dp
                    ) {
                        if (reading.outline.isNotEmpty() || reading.bookmarks.isNotEmpty()) {
                            MenuRow("Contents and marks") {
                                menuOpen = false
                                onContents()
                            }
                        }
                        MenuRow("Go to page…") {
                            menuOpen = false
                            onGoToPage()
                        }
                        if (canMark) {
                            MenuRow(if (marked) "Remove this mark" else "Mark this page") {
                                menuOpen = false
                                onToggleBookmark()
                            }
                        }
                        MenuRow(if (reading.nightMode) "Day colours" else "Night colours") {
                            menuOpen = false
                            onToggleNight()
                        }
                        MenuRow("Print or save as PDF…") {
                            menuOpen = false
                            onPrint()
                        }
                        MenuRow("Share this document…") {
                            menuOpen = false
                            onShare()
                        }
                    }
                }
            }
        }
        // A document opened from a tool's result list has no source Uri to hand on, so the
        // tool sheet is offered only for documents that came in from outside.
        if (state.sourceUri.isNotEmpty()) {
            TextButton(onClick = onTools, modifier = Modifier.padding(top = 2.dp)) {
                Text(
                    "Use a tool on this",
                    color = SheafColors.BandBright,
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }
}

@Composable
private fun MenuRow(label: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = SheafColors.Text)
        },
        onClick = onClick
    )
}

/**
 * Where the reader is, over the page rather than beside it. Tapping it offers the jump.
 *
 * The pill is drawn at the size it has always been; what is tappable is the 48dp box around
 * it. The pill on its own is 31dp tall, and a near miss on a badge that floats over the
 * document does not do nothing - it lands on the page and scrolls or pinches it. That matters
 * most for the zoom reset, which is reached for exactly when the reader is already fumbling.
 * The outer box is clipped to the same shape so the ripple is a capsule rather than a slab.
 */
@Composable
private fun PageBadge(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .clip(RoundedCornerShape(999.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(SheafColors.Surface.copy(alpha = 0.92f))
                .padding(horizontal = 14.dp, vertical = 7.dp)
        ) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = SheafColors.Muted)
        }
    }
}

/**
 * Find across the document. Results are pages, and tapping one scrolls to it.
 *
 * Choosing a result folds the list away rather than closing the search, so what is left on
 * screen is the page with its matches marked and a field still holding the query. The arrows
 * step match by match for the document where the word is on every page and the list of pages
 * is therefore no help at all.
 */
@Composable
private fun SearchBar(
    reading: ReadingState,
    showResults: Boolean,
    onSearch: (String) -> Unit,
    onShowResults: () -> Unit,
    onStep: (Boolean) -> Unit,
    onJump: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SheafColors.SurfaceDim)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = reading.query,
                onValueChange = onSearch,
                placeholder = { Text("Find in this document", style = MaterialTheme.typography.bodyMedium) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = SheafColors.Band,
                    unfocusedBorderColor = SheafColors.Border,
                    focusedTextColor = SheafColors.Text,
                    unfocusedTextColor = SheafColors.Text,
                    cursorColor = SheafColors.Band
                ),
                modifier = Modifier.weight(1f)
            )
            if (reading.hits.isNotEmpty()) {
                IconAction(
                    icon = R.drawable.ic_chevron_left,
                    label = "Previous match",
                    onClick = { onStep(false) }
                )
                IconAction(
                    icon = R.drawable.ic_chevron_right,
                    label = "Next match",
                    onClick = { onStep(true) }
                )
            }
        }

        val status = when {
            reading.searching -> "Searching…"
            reading.query.isBlank() -> null
            reading.hits.isEmpty() -> "No pages contain that."
            reading.hitIndex >= 0 ->
                "Page ${reading.hits[reading.hitIndex].pageIndex + 1} · " +
                    "${reading.hitIndex + 1} of ${reading.hits.size} pages with a match"
            reading.hits.size == 1 -> "1 page"
            else -> "${reading.hits.size} pages"
        }
        status?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = SheafColors.Dim,
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        if (!showResults && reading.hits.isNotEmpty()) {
            Text(
                "Matches are marked on the page. Tap to see the list again.",
                style = MaterialTheme.typography.bodySmall,
                color = SheafColors.BandBright,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onShowResults() }
                    .padding(top = 8.dp, bottom = 2.dp)
            )
        }

        // Bounded twice over, and the height is the half that matters.
        //
        // These rows are an unweighted sibling of the page area inside one Column, so they are
        // measured first against the whole remaining height. Twelve rows at about 64dp each is
        // most of a phone screen, which left the weighted page Box with almost nothing - and
        // nothing scrolled, because the Column holding both is not scrollable. Capped at 240dp
        // the rows are a list of their own that scrolls within itself, which is the same shape
        // ToolScreen uses for its page grid and for the same reason.
        //
        // The count stays bounded as well, at twelve. A lazy list no longer pays for rows
        // nobody scrolls to, but the search itself walks every page of the document and the
        // arrows are the honest way through a hundred matches.
        if (showResults && reading.hits.isNotEmpty()) {
            LazyColumn(modifier = Modifier.heightIn(max = 240.dp)) {
                items(
                    count = minOf(reading.hits.size, MAX_VISIBLE_HITS),
                    key = { reading.hits[it].pageIndex }
                ) { position ->
                    val hit = reading.hits[position]
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onJump(hit.pageIndex) }
                            .padding(vertical = 8.dp)
                    ) {
                        Text(
                            text = "Page ${hit.pageIndex + 1}" +
                                if (hit.matchCount > 1) " · ${hit.matchCount} matches" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = SheafColors.BandBright
                        )
                        // The match is highlighted inside the snippet. A result that only says
                        // "page 4" makes you find the word again once you get there.
                        Text(
                            text = highlight(hit.snippet, reading.query),
                            style = MaterialTheme.typography.bodySmall,
                            color = SheafColors.Muted,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // A list that stops at twelve with nothing said about it reads as though the
                // thirteenth match does not exist - while the status line above says how many
                // pages matched. The arrows walk all of them, so this points at the arrows.
                if (reading.hits.size > MAX_VISIBLE_HITS) {
                    item(key = "overflow") {
                        Text(
                            text = "The first $MAX_VISIBLE_HITS of ${reading.hits.size}. " +
                                "Use ‹ › to step through them all.",
                            style = MaterialTheme.typography.bodySmall,
                            color = SheafColors.Dim,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * The document's own table of contents, and the reader's marks above it.
 *
 * Two kinds of place in one sheet rather than two sheets, because they answer the same
 * question. The author's structure is read out of the file; the marks are the reader's and
 * are kept beside it, which is why one can be removed here and the other cannot.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContentsSheet(
    outline: List<OutlineEntry>,
    bookmarks: List<BookmarkEntity>,
    onDismiss: () -> Unit,
    onJump: (Int) -> Unit,
    onRemoveBookmark: (Int) -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = SheafColors.Surface) {
        Column(modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            if (bookmarks.isNotEmpty()) {
                SectionHeading("Your marks")
                Spacer(Modifier.height(6.dp))
                bookmarks.forEach { mark ->
                    RowBetween {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onJump(mark.pageIndex) }
                                .padding(vertical = 10.dp)
                        ) {
                            Text(
                                mark.label,
                                style = MaterialTheme.typography.bodyLarge,
                                color = SheafColors.Text,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "Page ${mark.pageIndex + 1}",
                                style = MaterialTheme.typography.bodySmall,
                                color = SheafColors.Dim
                            )
                        }
                        TextButton(onClick = { onRemoveBookmark(mark.pageIndex) }) {
                            Text(
                                "Remove",
                                style = MaterialTheme.typography.bodySmall,
                                color = SheafColors.Dim
                            )
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
            }

            if (outline.isNotEmpty()) {
                SectionHeading("Contents")
                Spacer(Modifier.height(6.dp))
                outline.forEach { entry ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onJump(entry.pageIndex) }
                            // 14 rather than 10, which brings these to 48dp. Contents entries
                            // stack flush against each other with nothing to miss into, and
                            // they sat shorter than the bookmark rows directly above them in
                            // the same sheet. A long table of contents scrolls further for it,
                            // and the sheet already scrolls.
                            .padding(start = (entry.depth * 14).dp, top = 14.dp, bottom = 14.dp)
                    ) {
                        Text(
                            entry.title,
                            style = MaterialTheme.typography.bodyLarge,
                            color = SheafColors.Text,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "${entry.pageIndex + 1}",
                            style = MaterialTheme.typography.bodySmall,
                            color = SheafColors.Dim
                        )
                    }
                }
            } else if (bookmarks.isEmpty()) {
                Text(
                    "This document has no table of contents, and you have not marked a page " +
                        "in it yet. The star in the bar marks the page you are on.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SheafColors.Muted
                )
            }
        }
    }
}

/** A page number, typed, for the document whose contents are no help. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GoToPageSheet(pageCount: Int, onDismiss: () -> Unit, onGo: (Int) -> Unit) {
    var typed by remember { mutableStateOf("") }
    val target = typed.filter { it.isDigit() }.toIntOrNull()

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = SheafColors.Surface) {
        // material3's sheet insets leave the keyboard out, and this is the one sheet that
        // opens with a keyboard already up: without this the Go button sits behind it.
        Column(
            modifier = Modifier
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            SectionHeading("Go to page")
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it.filter { character -> character.isDigit() }.take(6) },
                label = { Text("1 to $pageCount", style = MaterialTheme.typography.bodySmall) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Go
                ),
                // The keyboard is already open and already has a key for this, so the button
                // below is the fallback rather than the path.
                keyboardActions = KeyboardActions(
                    onGo = { target?.takeIf { it in 1..pageCount }?.let { onGo(it - 1) } }
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = SheafColors.Band,
                    unfocusedBorderColor = SheafColors.Border,
                    focusedTextColor = SheafColors.Text,
                    unfocusedTextColor = SheafColors.Text,
                    cursorColor = SheafColors.Band
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            TextButton(
                enabled = target != null && target in 1..pageCount,
                onClick = { target?.let { onGo(it - 1) } }
            ) {
                Text(
                    "Go",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (target != null && target in 1..pageCount) {
                        SheafColors.Band
                    } else {
                        SheafColors.Dim
                    }
                )
            }
        }
    }
}

/**
 * Marks every occurrence of [query] inside [text].
 *
 * Case-insensitive, because that is how the search itself matches - highlighting only exact
 * case would leave a hit that plainly matched looking as though it had not.
 */
private fun highlight(text: String, query: String): AnnotatedString {
    if (query.isBlank()) return AnnotatedString(text)

    return buildAnnotatedString {
        var index = 0
        while (index < text.length) {
            val found = text.indexOf(query, index, ignoreCase = true)
            if (found < 0) {
                append(text.substring(index))
                break
            }
            append(text.substring(index, found))
            withStyle(
                SpanStyle(
                    color = SheafColors.OnBand,
                    background = SheafColors.Band,
                    fontWeight = FontWeight.SemiBold
                )
            ) {
                append(text.substring(found, found + query.length))
            }
            index = found + query.length
        }
    }
}

private const val MAX_VISIBLE_HITS = 12

@Composable
private fun PageList(
    state: ViewerState.Ready,
    nightMode: Boolean,
    highlights: Map<Int, List<PageArea>>,
    emphasisedPage: Int?,
    listState: LazyListState,
    renderPage: suspend (index: Int, widthPx: Int) -> Bitmap?,
    renderTile: suspend (
        index: Int,
        fullWidthPx: Int,
        tile: Rect,
        outWidthPx: Int,
        outHeightPx: Int
    ) -> Bitmap?,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val screenWidthDp = LocalConfiguration.current.screenWidthDp.dp

    var zoom by remember(state.generation) { mutableFloatStateOf(1f) }
    val horizontal = rememberScrollState()

    // The width a whole page is rendered at, and it no longer moves with zoom.
    //
    // It used to: a page was re-rendered whole at two or three times this, which is four or
    // nine times the pixels. On a 1080p phone that is a 54 MB bitmap for one A4 page - larger
    // than the entire page cache, so at that zoom the cache held nothing and every scroll
    // re-rendered from scratch. What the reader can actually see is one screen's worth, and
    // that is what [PageItem] now asks for separately, as a tile.
    //
    // So this is the whole page at screen width: the layer underneath, cheap enough to keep a
    // dozen of, and the only thing on the screen for the moment a tile takes to arrive.
    val baseWidthPx = remember(screenWidthDp, density) {
        with(density) { (screenWidthDp - PAGE_MARGIN * 2).toPx().toInt().coerceAtLeast(1) }
    }

    // The coordinate space tiles are cut from: the page as it would be rendered whole at this
    // width. No bitmap of this size is ever allocated - that is the point - but the number has
    // to be stable, because it is part of what decides whether a tile already on screen is
    // still the right one. Hence the zoom step rather than the gesture's own zoom.
    val tileSpaceWidthPx = remember(baseWidthPx, zoom) {
        (baseWidthPx * renderStepFor(zoom)).toInt().coerceAtLeast(1)
    }

    // Raised for as long as two fingers are changing the zoom, so that a pinch does not ask
    // for a tile on every frame of itself. Zoom steps are coarse, but the page's width on
    // the screen is not, and it is the page's width that decides where the tile edges fall.
    var pinching by remember { mutableStateOf(false) }

    // Tiles are cut only once everything has stopped moving. A fling at high zoom would
    // otherwise ask for a new one every few pixels, and every one of them but the last is
    // for a position the reader has already gone past. What shows in the meantime is the
    // last tile, still in its right place on the page, over the whole-page render.
    val settled = !listState.isScrollInProgress && !horizontal.isScrollInProgress && !pinching

    // Where the list itself is, so a page can work out how much of it is on the screen.
    // Written on layout rather than per frame: this moves when the window does, not when the
    // content scrolls past it.
    var viewport by remember { mutableStateOf(ComposeRect.Zero) }

    val onDoubleTap = rememberUpdatedState {
        zoom = if (zoom > 1.05f) 1f else DOUBLE_TAP_ZOOM
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                val origin = coordinates.positionInWindow()
                viewport = ComposeRect(
                    left = origin.x,
                    top = origin.y,
                    right = origin.x + coordinates.size.width,
                    bottom = origin.y + coordinates.size.height
                )
            }
            .background(SheafColors.Background)
            // Two gesture handlers, deliberately separate. The pinch one takes over only once
            // a second finger is down, so a one-finger drag stays the list's to scroll.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var gesturePinched = false
                    try {
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.count { it.pressed } > 1) {
                                val change = event.calculateZoom()
                                if (change != 1f) {
                                    gesturePinched = true
                                    pinching = true
                                    zoom = (zoom * change).coerceIn(1f, MAX_ZOOM)
                                }
                                // Consumed only while actually pinching, so a two-finger
                                // scroll that never changes the distance still reaches the
                                // list.
                                if (gesturePinched) event.changes.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    } finally {
                        // In a finally because the gesture can also end by being cancelled,
                        // and a flag left raised there would stop this page ever being
                        // sharpened again.
                        if (gesturePinched) pinching = false
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { onDoubleTap.value() })
            }
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxHeight()
                .horizontalScroll(horizontal)
                .width(screenWidthDp * zoom),
            contentPadding = PaddingValues(vertical = PAGE_MARGIN)
        ) {
            items(count = state.pageCount, key = { "${state.generation}:$it" }) { index ->
                PageItem(
                    generation = state.generation,
                    index = index,
                    size = state.pageSizes.getOrNull(index),
                    baseWidthPx = baseWidthPx,
                    tileSpaceWidthPx = tileSpaceWidthPx,
                    viewport = viewport,
                    settled = settled,
                    nightMode = nightMode,
                    highlights = highlights[index].orEmpty(),
                    emphasised = emphasisedPage == index,
                    renderPage = renderPage,
                    renderTile = renderTile
                )
            }
        }

        if (zoom > 1.05f) {
            // A way back that does not need a second accurate pinch. Readers zoom in to look
            // at one figure and then want the page again.
            PageBadge(
                label = "${(zoom * 100).toInt()}% · reset",
                onClick = { zoom = 1f },
                // Same arithmetic as the bottom badge: the tap target is taller than the pill
                // now, so the outer padding comes down to keep the pill where it was.
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 10.dp)
            )
        }
    }
}

@Composable
private fun PageItem(
    generation: Int,
    index: Int,
    size: PageSize?,
    baseWidthPx: Int,
    tileSpaceWidthPx: Int,
    viewport: ComposeRect,
    settled: Boolean,
    nightMode: Boolean,
    highlights: List<PageArea>,
    emphasised: Boolean,
    renderPage: suspend (index: Int, widthPx: Int) -> Bitmap?,
    renderTile: suspend (
        index: Int,
        fullWidthPx: Int,
        tile: Rect,
        outWidthPx: Int,
        outHeightPx: Int
    ) -> Bitmap?
) {
    // Set the first time the engine answers a tile request with nothing. From then on this
    // page goes back to being rendered whole at the zoomed width, which is what every page
    // used to do - an engine with no tile path should be slower here, not blurrier.
    var tilesRefused by remember(generation, index) { mutableStateOf(false) }

    val tiling = tileSpaceWidthPx > baseWidthPx && !tilesRefused
    val wholeWidthPx = if (tiling) baseWidthPx else tileSpaceWidthPx

    var bitmap by remember(generation, index) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(generation, index) { mutableStateOf(false) }

    LaunchedEffect(generation, index, wholeWidthPx) {
        val rendered = renderPage(index, wholeWidthPx)
        if (rendered == null) failed = true else bitmap = rendered
    }

    // A4 is the sane default for a page whose size could not be read - it keeps the list
    // extents roughly right rather than collapsing the item to nothing.
    val aspect = size?.aspectRatio?.takeIf { it.isFinite() && it > 0f } ?: (595.3f / 841.9f)

    // Where this page sits in the window, which moves on every frame of a scroll.
    //
    // Held as state and read only inside the derivedStateOf below. That is the whole reason
    // it is arranged this way: a plain state read out here would recompose the item sixty
    // times a second while the list moves, when what is wanted is a recomposition only where
    // the answer - which tile to draw - has actually changed.
    val pageBounds = remember(generation, index) { mutableStateOf(ComposeRect.Zero) }

    // The page's full-scale height, by the same arithmetic the engine uses to turn a width
    // into one. Tiles line up with the page only if both sides agree on this exactly.
    val tileSpaceHeightPx = remember(tileSpaceWidthPx, aspect) {
        BitmapBudget.heightFor(tileSpaceWidthPx, aspect)
    }

    val request by remember(
        generation, index, tiling, settled, tileSpaceWidthPx, tileSpaceHeightPx, viewport
    ) {
        derivedStateOf {
            if (!tiling || !settled) {
                null
            } else {
                PageTiling.requestFor(
                    pageBounds.value, viewport, tileSpaceWidthPx, tileSpaceHeightPx
                )
            }
        }
    }

    var tile by remember(generation, index) { mutableStateOf<PageTile?>(null) }

    LaunchedEffect(generation, index, tiling, request) {
        if (!tiling) {
            // Zoomed back out, or the engine has refused. Either way the whole-page render
            // is the picture now and this is just pixels nobody is looking at.
            tile = null
            return@LaunchedEffect
        }
        // No request means the view is still moving, or the page has scrolled nearly off.
        // The tile that is already there stays: it is drawn at its own place on the page, so
        // it travels with the page and stays true wherever that ends up. The memory is
        // bounded all the same, because the list disposes an item that scrolls away and the
        // tile goes with it.
        val wanted = request ?: return@LaunchedEffect
        if (tile?.request == wanted) return@LaunchedEffect

        val rendered = renderTile(
            index,
            wanted.fullWidthPx,
            Rect(wanted.left, wanted.top, wanted.right, wanted.bottom),
            wanted.outWidthPx,
            wanted.outHeightPx
        )
        if (rendered == null) {
            tilesRefused = true
            tile = null
        } else {
            // The outgoing tile is left to the collector rather than recycled: it may still
            // be on the screen for another frame, and a recycled bitmap drawn is a crash.
            // Which is also why no bitmap is offered back to the engine to draw into here,
            // though the engine would take one - the only safe sheet is one nothing can be
            // showing, and every bitmap this composable has ever held fails that test.
            tile = PageTile(wanted, rendered)
        }
    }

    // The page arriving, over 140ms, instead of appearing between one frame and the next.
    //
    // Keyed on the page and the document, deliberately not on the bitmap instance. The render
    // effect above is keyed on wholeWidthPx as well, so on an engine with no tile path a zoom
    // change replaces the bitmap of a page that is already on the screen - and an
    // instance-keyed fade would blink that page back to blank paper and bring it in again.
    // Keyed this way, a page fades once, when it first has something to show.
    //
    // graphicsLayer rather than Crossfade, which would hold the outgoing and incoming bitmaps
    // at the same time and so double the memory this file works hard to bound. 140ms because
    // a fast fling should not leave a trail of half-faded pages behind it.
    val appear = remember(generation, index) { Animatable(0f) }
    LaunchedEffect(generation, index, bitmap != null) {
        if (bitmap != null) appear.animateTo(1f, tween(140))
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PAGE_MARGIN, vertical = PAGE_GAP / 2)
            .aspectRatio(aspect)
            .clip(RoundedCornerShape(3.dp))
            // The paper color sits under the render, so a page that has not arrived yet is a
            // blank sheet rather than a hole in the list.
            .background(
                when {
                    failed -> SheafColors.SurfaceDim
                    nightMode -> NIGHT_PAPER
                    else -> SheafColors.Paper
                }
            )
            // positionInWindow and size, not boundsInWindow: the latter has already had the
            // off-screen part of the page taken off it, and the off-screen part is precisely
            // what the sum below has to know about.
            .onGloballyPositioned { coordinates ->
                val origin = coordinates.positionInWindow()
                pageBounds.value = ComposeRect(
                    left = origin.x,
                    top = origin.y,
                    right = origin.x + coordinates.size.width,
                    bottom = origin.y + coordinates.size.height
                )
            },
        contentAlignment = Alignment.Center
    ) {
        val current = bitmap
        when {
            current != null -> Image(
                bitmap = current.asImageBitmap(),
                contentDescription = "Page ${index + 1}",
                contentScale = ContentScale.Fit,
                // Inverted at draw time rather than re-rendered. Toggling night mode is then
                // instant and costs no memory, and the cached page is shared by both modes.
                colorFilter = if (nightMode) INVERT_FILTER else null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = appear.value }
            )

            failed -> Text(
                text = "Page ${index + 1} could not be displayed",
                style = MaterialTheme.typography.bodySmall,
                color = SheafColors.Dim,
                textAlign = TextAlign.Center
            )
        }

        // The sharp part, laid exactly over its own patch of the blurry one. Drawn even when
        // it is no longer the tile the current position would ask for: it is still a true
        // picture of the part of the page it covers, and taking it away to put another one
        // back a moment later is a flicker for nothing.
        val sharp = tile
        if (current != null && sharp != null) {
            TileOverlay(tile = sharp, nightMode = nightMode)
        }

        // Drawn over the page rather than into the bitmap, so the same cached render serves a
        // page whether it is a search result or not, and the marks come and go for free.
        if (current != null && highlights.isNotEmpty()) {
            MatchMarks(bitmap = current, areas = highlights, emphasised = emphasised)
        }
    }
}

/**
 * One rendered tile and the request that produced it.
 *
 * The request is kept because it is the only record of where on the page the pixels belong -
 * the bitmap is a rectangle that no longer knows the shape of the page it came from.
 */
private class PageTile(val request: TileRequest, val bitmap: Bitmap)

/**
 * Draws a tile over the page it was cut from.
 *
 * The placement has to agree with the ContentScale.Fit underneath, because the two are the
 * same picture and any disagreement shows as a seam. Both fit a page of one shape into a box
 * of very nearly that shape, so the letterboxing is sub-pixel - but it is computed rather
 * than assumed away, for the page whose stored size and rendered size do not quite agree.
 */
@Composable
private fun TileOverlay(tile: PageTile, nightMode: Boolean) {
    // asImageBitmap wraps afresh on every call and the wrapper is what the draw is keyed by,
    // so without this the tile is re-wrapped on every recomposition of the page.
    val image = remember(tile.bitmap) { tile.bitmap.asImageBitmap() }

    Canvas(modifier = Modifier.fillMaxSize()) {
        val request = tile.request
        val scale = minOf(
            size.width / request.fullWidthPx,
            size.height / request.fullHeightPx
        )
        if (!scale.isFinite() || scale <= 0f) return@Canvas

        val originX = (size.width - request.fullWidthPx * scale) / 2f
        val originY = (size.height - request.fullHeightPx * scale) / 2f

        drawImage(
            image = image,
            dstOffset = IntOffset(
                (originX + request.left * scale).roundToInt(),
                (originY + request.top * scale).roundToInt()
            ),
            dstSize = IntSize(
                ((request.right - request.left) * scale).roundToInt().coerceAtLeast(1),
                ((request.bottom - request.top) * scale).roundToInt().coerceAtLeast(1)
            ),
            // The same inversion the page underneath gets, for the same reason: night mode is
            // a filter at draw time and never a second render.
            colorFilter = if (nightMode) INVERT_FILTER else null,
            // Drawn at very nearly the size it was rendered at, so there is nothing to gain
            // from resampling it.
            filterQuality = FilterQuality.Low
        )
    }
}

/**
 * Boxes the search matches on a rendered page.
 *
 * The areas arrive normalised to the page, so they are laid over wherever the bitmap actually
 * landed inside the item - which is not quite the whole of it when the page's proportions and
 * the reserved space disagree by a pixel or two.
 */
@Composable
private fun MatchMarks(bitmap: Bitmap, areas: List<PageArea>, emphasised: Boolean) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val pageWidth = bitmap.width.toFloat()
        val pageHeight = bitmap.height.toFloat()
        if (pageWidth <= 0f || pageHeight <= 0f) return@Canvas

        val scale = minOf(size.width / pageWidth, size.height / pageHeight)
        val drawWidth = pageWidth * scale
        val drawHeight = pageHeight * scale
        val originX = (size.width - drawWidth) / 2f
        val originY = (size.height - drawHeight) / 2f

        areas.forEach { area ->
            // A zero-height area would draw nothing at all, and a glyph box that rounds to
            // nothing is still a match the reader is looking for.
            val markHeight = (area.height * drawHeight).coerceAtLeast(2f)
            val markWidth = (area.width * drawWidth).coerceAtLeast(2f)
            drawRect(
                color = if (emphasised) MATCH_MARK_STEPPED else MATCH_MARK,
                topLeft = Offset(
                    x = originX + area.left * drawWidth,
                    y = originY + area.top * drawHeight
                ),
                size = Size(markWidth, markHeight)
            )
        }
    }
}

/**
 * A sentence in the middle of an empty screen, and whatever the reader can do about it.
 *
 * [action] is where the way out goes. A screen that explains why there is no document and
 * then offers nothing leaves the system gesture as the only exit, which is not an exit the
 * screen itself has told anybody about.
 */
@Composable
private fun CentredMessage(
    text: String,
    modifier: Modifier = Modifier,
    showSpinner: Boolean = false,
    action: (@Composable () -> Unit)? = null
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SheafColors.Background)
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (showSpinner) {
                CircularProgressIndicator(color = SheafColors.Band, strokeWidth = 2.dp)
            }
            if (text.isNotEmpty()) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = SheafColors.Muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = if (showSpinner) 16.dp else 0.dp)
                )
            }
            if (action != null) {
                Spacer(Modifier.height(8.dp))
                action()
            }
        }
    }
}

/**
 * Whole render steps. A page drawn at 2x and shown at 1.6x is sharp; the reverse is not.
 *
 * This no longer sizes a bitmap - it sizes the coordinate space tiles are cut from, and
 * nothing that big is ever allocated. It is still stepped rather than continuous because a
 * tile is reused for as long as the request that produced it keeps comparing equal, and a
 * space that moved with every frame of a pinch would make every frame a new request.
 */
private fun renderStepFor(zoom: Float): Float = when {
    zoom <= 1.25f -> 1f
    zoom <= 2.25f -> 2f
    else -> 3f
}

private val PAGE_MARGIN = 12.dp
private val PAGE_GAP = 12.dp

/** Far enough in to read small print, near enough out that one page is still one page. */
private const val MAX_ZOOM = 4f
private const val DOUBLE_TAP_ZOOM = 2.5f

/**
 * The wash over a search match.
 *
 * Translucent rather than solid, because the point is to find the words again, and a solid
 * band would hide the very text it is pointing at. The stepped-to page gets the stronger wash,
 * which is what tells the reader which of the matches the arrows have brought them to.
 */
private val MATCH_MARK = androidx.compose.ui.graphics.Color(0x55E0257A)
private val MATCH_MARK_STEPPED = androidx.compose.ui.graphics.Color(0x99FFC400)

/** The placeholder behind a page that has not arrived, in night mode. */
private val NIGHT_PAPER = androidx.compose.ui.graphics.Color(0xFF15171B)

/**
 * Straight photographic inversion: white paper becomes near-black, black ink becomes white.
 *
 * A color matrix rather than a second render, so the toggle is instant and the same cached
 * bitmap serves both modes. Color in a document inverts too, which is the honest behavior -
 * anything cleverer would have to decide what counts as ink, and would get it wrong on charts.
 */
private val INVERT_FILTER = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f
        )
    )
)
