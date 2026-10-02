package com.layerbit.sheaf.ui.viewer

import android.graphics.Bitmap
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.layerbit.sheaf.data.db.BookmarkEntity
import com.layerbit.sheaf.ops.ToolId
import com.layerbit.sheaf.pdf.OutlineEntry
import com.layerbit.sheaf.pdf.PageArea
import com.layerbit.sheaf.pdf.PageSize
import com.layerbit.sheaf.ui.components.RowBetween
import com.layerbit.sheaf.ui.components.SectionHeading
import com.layerbit.sheaf.ui.theme.SheafColors
import com.layerbit.sheaf.ui.tools.ToolPickerSheet
import kotlinx.coroutines.launch

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
 * P0's exit gate is measured here: a five-hundred-page document, scrolled end to end, at
 * sixty frames per second, returning to a flat heap afterward.
 */
@Composable
fun ViewerScreen(
    state: ViewerState,
    reading: ReadingState,
    renderPage: suspend (index: Int, widthPx: Int) -> Bitmap?,
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
            if (searching) {
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
        }

        Box(modifier = Modifier.weight(1f)) {
            when (state) {
                is ViewerState.Loading ->
                    CentredMessage("Opening…", modifier = Modifier.fillMaxSize(), showSpinner = true)

                is ViewerState.NeedsPassword -> CentredMessage(
                    "This document is password-protected.\n" +
                        "Use Remove a password from the home screen to unlock a copy first.",
                    modifier = Modifier.fillMaxSize()
                )

                is ViewerState.Failed -> CentredMessage(state.reason, modifier = Modifier.fillMaxSize())

                is ViewerState.Ready -> {
                    PageList(
                        state = state,
                        nightMode = reading.nightMode,
                        highlights = if (searching) reading.highlights else emptyMap(),
                        emphasisedPage = reading.hits.getOrNull(reading.hitIndex)?.pageIndex,
                        listState = listState,
                        renderPage = renderPage,
                        modifier = Modifier.fillMaxSize()
                    )

                    if (showPageBadge && state.pageCount > 1) {
                        PageBadge(
                            label = "${currentPage + 1} / ${state.pageCount}",
                            onClick = { showGoToPage = true },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 16.dp)
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
            TextButton(onClick = onBack, modifier = Modifier.padding(end = 2.dp)) {
                Text("‹", style = MaterialTheme.typography.titleLarge, color = SheafColors.Muted)
            }
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
                    TextButton(onClick = onToggleBookmark) {
                        Text(
                            if (marked) "★" else "☆",
                            style = MaterialTheme.typography.titleMedium,
                            color = if (marked) SheafColors.BandBright else SheafColors.Muted
                        )
                    }
                }
                TextButton(onClick = onSearchToggle) {
                    Text("Find", color = SheafColors.Muted)
                }
                Box {
                    TextButton(onClick = { menuOpen = true }) {
                        Text("⋯", style = MaterialTheme.typography.titleLarge, color = SheafColors.Muted)
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false }
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
                    color = SheafColors.Band,
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

/** Where the reader is, over the page rather than beside it. Tapping it offers the jump. */
@Composable
private fun PageBadge(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(SheafColors.Surface.copy(alpha = 0.92f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = SheafColors.Muted)
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
                TextButton(onClick = { onStep(false) }) {
                    Text("‹", style = MaterialTheme.typography.titleLarge, color = SheafColors.Muted)
                }
                TextButton(onClick = { onStep(true) }) {
                    Text("›", style = MaterialTheme.typography.titleLarge, color = SheafColors.Muted)
                }
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

        // Bounded, because a search for "the" in a book matches every page and an unbounded
        // list inside a header would take the whole screen.
        if (showResults) {
            reading.hits.take(MAX_VISIBLE_HITS).forEach { hit ->
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
                            .padding(start = (entry.depth * 14).dp, top = 10.dp, bottom = 10.dp)
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
        Column(modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            SectionHeading("Go to page")
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it.filter { character -> character.isDigit() }.take(6) },
                label = { Text("1 to $pageCount", style = MaterialTheme.typography.bodySmall) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
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
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val screenWidthDp = LocalConfiguration.current.screenWidthDp.dp

    var zoom by remember(state.generation) { mutableFloatStateOf(1f) }
    val horizontal = rememberScrollState()

    // The width a page is drawn at. Fixed per zoom step rather than per gesture frame, so the
    // LRU cache never fills with forty slightly different renders of the same page.
    val renderWidthPx = remember(screenWidthDp, density, zoom) {
        with(density) {
            ((screenWidthDp - PAGE_MARGIN * 2) * renderStepFor(zoom)).toPx().toInt().coerceAtLeast(1)
        }
    }

    val onDoubleTap = rememberUpdatedState {
        zoom = if (zoom > 1.05f) 1f else DOUBLE_TAP_ZOOM
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SheafColors.Background)
            // Two gesture handlers, deliberately separate. The pinch one takes over only once
            // a second finger is down, so a one-finger drag stays the list's to scroll.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var pinching = false
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.count { it.pressed } > 1) {
                            val change = event.calculateZoom()
                            if (change != 1f) {
                                pinching = true
                                zoom = (zoom * change).coerceIn(1f, MAX_ZOOM)
                            }
                            // Consumed only while actually pinching, so a two-finger scroll
                            // that never changes the distance still reaches the list.
                            if (pinching) event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
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
                    renderWidthPx = renderWidthPx,
                    nightMode = nightMode,
                    highlights = highlights[index].orEmpty(),
                    emphasised = emphasisedPage == index,
                    renderPage = renderPage
                )
            }
        }

        if (zoom > 1.05f) {
            // A way back that does not need a second accurate pinch. Readers zoom in to look
            // at one figure and then want the page again.
            PageBadge(
                label = "${(zoom * 100).toInt()}% · reset",
                onClick = { zoom = 1f },
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 10.dp)
            )
        }
    }
}

@Composable
private fun PageItem(
    generation: Int,
    index: Int,
    size: PageSize?,
    renderWidthPx: Int,
    nightMode: Boolean,
    highlights: List<PageArea>,
    emphasised: Boolean,
    renderPage: suspend (index: Int, widthPx: Int) -> Bitmap?
) {
    var bitmap by remember(generation, index) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(generation, index) { mutableStateOf(false) }

    LaunchedEffect(generation, index, renderWidthPx) {
        val rendered = renderPage(index, renderWidthPx)
        if (rendered == null) failed = true else bitmap = rendered
    }

    // A4 is the sane default for a page whose size could not be read - it keeps the list
    // extents roughly right rather than collapsing the item to nothing.
    val aspect = size?.aspectRatio?.takeIf { it.isFinite() && it > 0f } ?: (595.3f / 841.9f)

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
            ),
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
                modifier = Modifier.fillMaxSize()
            )

            failed -> Text(
                text = "Page ${index + 1} could not be displayed",
                style = MaterialTheme.typography.bodySmall,
                color = SheafColors.Dim,
                textAlign = TextAlign.Center
            )
        }

        // Drawn over the page rather than into the bitmap, so the same cached render serves a
        // page whether it is a search result or not, and the marks come and go for free.
        if (current != null && highlights.isNotEmpty()) {
            MatchMarks(bitmap = current, areas = highlights, emphasised = emphasised)
        }
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

@Composable
private fun CentredMessage(
    text: String,
    modifier: Modifier = Modifier,
    showSpinner: Boolean = false
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
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = SheafColors.Muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = if (showSpinner) 16.dp else 0.dp)
            )
        }
    }
}

/** Whole render steps. A page drawn at 2x and shown at 1.6x is sharp; the reverse is not. */
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
