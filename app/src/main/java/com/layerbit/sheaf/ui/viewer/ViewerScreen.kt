package com.layerbit.sheaf.ui.viewer

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import com.layerbit.sheaf.ui.components.LivelyIcon as Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.layerbit.sheaf.data.db.BookmarkEntity
import com.layerbit.sheaf.ops.ToolId
import com.layerbit.sheaf.pdf.Comment
import com.layerbit.sheaf.pdf.LinkTarget
import com.layerbit.sheaf.pdf.OutlineEntry
import com.layerbit.sheaf.ui.components.ChipRow
import com.layerbit.sheaf.ui.components.Pill
import com.layerbit.sheaf.ui.components.PrimaryButton
import com.layerbit.sheaf.ui.components.RoundIconButton
import com.layerbit.sheaf.ui.components.RowBetween
import com.layerbit.sheaf.ui.components.SectionHeading
import com.layerbit.sheaf.ui.components.ToggleRow
import com.layerbit.sheaf.ui.theme.SheafColors
import com.layerbit.sheaf.ui.tools.ToolPickerSheet
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.layerbit.sheaf.R
import com.layerbit.sheaf.text.Strings

/**
 * The reader: a document's pages, and everything that happens on them.
 *
 * Each page is sized from the page dimensions read at open time, so the layout has correct
 * extents before a single page is rendered, which is what makes the scrollbar honest and stops
 * the content jumping as pages arrive. Rendering happens per page, when the page composes; the
 * bitmap is dropped when it leaves the cache.
 *
 * Three layouts share one page composable (see ViewerPages.kt): one long scroll, a page at a
 * time, and two side by side. Which one is the document's own choice, remembered in recents.
 *
 * P0's exit gate is measured here: a five-hundred-page document, scrolled end to end, at
 * sixty frames per second, returning to a flat heap afterward.
 */
@Composable
fun ViewerScreen(
    state: ViewerState,
    reading: ReadingState,
    renderPage: suspend (index: Int, widthPx: Int) -> Bitmap?,
    controls: ViewerControls,
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
    onReadAloud: (Int) -> Unit,
    onStopReading: () -> Unit,
    modifier: Modifier = Modifier,
    keepScreenOn: Boolean = true,
    showPageBadge: Boolean = true
) {
    var showTools by remember { mutableStateOf(false) }
    var showContents by remember { mutableStateOf(false) }
    var showComments by remember { mutableStateOf(false) }
    var showGoToPage by remember { mutableStateOf(false) }
    var showThumbnails by remember { mutableStateOf(false) }
    var showOptions by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var searchFocused by remember { mutableStateOf(false) }
    // The result list folds away once a result is chosen, while the search itself stays on.
    // Clearing the query there would take the highlights off the page the reader just asked
    // to be shown - the one thing the jump was for.
    var showResults by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current

    // Reading is the one thing people do without touching the screen, so the display timeout
    // works against it. Set on the view rather than through a window flag, so it is undone
    // automatically when the viewer leaves the composition.
    val view = LocalView.current
    val onPage = state is ViewerState.Ready
    DisposableEffect(keepScreenOn, onPage) {
        view.keepScreenOn = keepScreenOn && onPage
        onDispose { view.keepScreenOn = false }
    }

    val ready = state as? ViewerState.Ready
    val pageCount = ready?.pageCount ?: 0
    val generation = ready?.generation

    // Where the reader is. Each layout reports it as it scrolls; starting from the resume
    // position means the first report is the page the document opened at, never page one
    // overwriting the place it was about to be put back to.
    var currentPage by remember(generation) { mutableIntStateOf(ready?.initialPage ?: 0) }
    LaunchedEffect(currentPage, generation) { if (generation != null) onPageChanged(currentPage) }

    var selection by remember(generation) { mutableStateOf<TextSelection?>(null) }
    // The end of the selection that stays put while a handle is dragged.
    var fixedEnd by remember(generation) { mutableIntStateOf(0) }
    // Keyed on the document only, and reset by hand when the layout changes: the gesture
    // handlers below hold on to this state, and a new one per mode would leave them zooming
    // a layout that is no longer on screen.
    var zoom by remember(generation) { mutableFloatStateOf(1f) }
    LaunchedEffect(reading.readingMode) { zoom = 1f }

    // A sentence that comes and goes: "Copied", "No text on this page". Short-lived, because
    // it is an answer to something just done and not something to read later.
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(note) {
        if (note != null) {
            delay(NOTE_MS)
            note = null
        }
    }

    val look = remember(reading.tint, reading.brightness, reading.cropMargins, SheafColors.Paper) {
        pageLookFor(reading.tint, reading.brightness, reading.cropMargins)
    }

    val marked = reading.bookmarks.any { it.pageIndex == currentPage }

    // ---- what fingers on a page do ----

    val currentSelection by rememberUpdatedState(selection)
    val actions = remember(generation) {
        PageActions(
            onTap = { _, x, y, links ->
                if (currentSelection != null) {
                    // A tap anywhere puts a selection away, as it does in every other app.
                    selection = null
                } else {
                    when (val target = linkAt(links, x, y)?.target) {
                        is LinkTarget.Page -> onJump(target.index)
                        is LinkTarget.Web -> if (!TextHandoff.openLink(context, target.uri)) {
                            note = Strings.get(R.string.reading_no_app_link)
                        }
                        null -> Unit
                    }
                }
            },
            onDoubleTap = { zoom = if (zoom > 1.05f) 1f else DOUBLE_TAP_ZOOM },
            onLongPress = { page, x, y ->
                scope.launch {
                    val words = controls.wordsOn(page)
                    if (words.isEmpty()) {
                        note = Strings.get(R.string.reading_no_text_to_select)
                        return@launch
                    }
                    val at = SelectionMath.wordAt(words, x, y) ?: return@launch
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    selection = TextSelection(page, words, at, at)
                }
            },
            onSelectDrag = { page, x, y ->
                val current = currentSelection
                if (current != null && current.pageIndex == page) {
                    SelectionMath.nearestWord(current.words, x, y)?.let { selection = current.copy(focus = it) }
                }
            },
            onHandleStart = { isStart ->
                currentSelection?.let { fixedEnd = if (isStart) it.last else it.first }
            },
            onHandleDrag = { page, x, y ->
                val current = currentSelection
                if (current != null && current.pageIndex == page) {
                    SelectionMath.nearestWord(current.words, x, y)?.let {
                        selection = current.copy(anchor = fixedEnd, focus = it)
                    }
                }
            }
        )
    }

    // ---- the keyboard, for a tablet with one attached ----

    val keyFocus = remember { FocusRequester() }
    LaunchedEffect(onPage, searching) {
        if (onPage && !searching) runCatching { keyFocus.requestFocus() }
    }
    val onKey: (KeyEvent) -> Boolean = handler@{ event ->
        if (event.type != KeyEventType.KeyDown || ready == null) return@handler false
        val ctrl = event.isCtrlPressed
        when {
            ctrl && event.key == Key.F -> {
                searching = true
                showResults = true
                true
            }
            ctrl && event.key == Key.P -> {
                onPrint()
                true
            }
            event.key == Key.Escape -> when {
                selection != null -> { selection = null; true }
                searching -> { searching = false; onClearSearch(); true }
                zoom > 1.05f -> { zoom = 1f; true }
                else -> false
            }
            // Arrows belong to the search field while it is being typed in.
            searchFocused -> false
            event.key in FORWARD_KEYS -> {
                onJump(steppedPage(currentPage, true, reading.readingMode, pageCount))
                true
            }
            event.key in BACK_KEYS -> {
                onJump(steppedPage(currentPage, false, reading.readingMode, pageCount))
                true
            }
            event.key == Key.MoveHome -> { onJump(0); true }
            event.key == Key.MoveEnd -> { onJump(pageCount - 1); true }
            else -> false
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SheafColors.Background)
            .onKeyEvent(onKey)
            .focusRequester(keyFocus)
            .focusable()
    ) {
        if (ready != null) {
            ViewerHeader(
                state = ready,
                reading = reading,
                currentPage = currentPage,
                marked = marked,
                onBack = onBack,
                onTools = { showTools = true },
                onContents = { showContents = true },
                onComments = { showComments = true },
                onGoToPage = { showGoToPage = true },
                onThumbnails = { showThumbnails = true },
                onOptions = { showOptions = true },
                onReadAloud = { onReadAloud(currentPage) },
                onStopReading = onStopReading,
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
            if (reading.readAloud.speaking || reading.readAloud.note != null) {
                ReadAloudBar(reading.readAloud, onStop = onStopReading)
            }
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
                    },
                    onFocusChanged = { searchFocused = it }
                )
            }
        } else if (state !is ViewerState.Loading) {
            // Locked or broken, there is no header to go back from, so the way out is here.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SheafColors.Surface)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                RoundIconButton(icon = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.reading_back), onClick = onBack)
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            when (state) {
                is ViewerState.Loading ->
                    CentredMessage(stringResource(R.string.reading_opening), modifier = Modifier.fillMaxSize(), showSpinner = true)

                is ViewerState.NeedsPassword -> PasswordPrompt(state = state, onUnlock = controls::unlock)

                is ViewerState.Failed -> CentredMessage(state.reason, modifier = Modifier.fillMaxSize())

                is ViewerState.Ready -> {
                    // A tablet in landscape, a foldable opened out: room for the pages and a
                    // strip of thumbnails beside them, which is how a reader on a big screen
                    // finds their way around without opening anything.
                    val wide = LocalConfiguration.current.screenWidthDp >= WIDE_SCREEN_DP
                    Row(modifier = Modifier.fillMaxSize()) {
                        if (wide && state.pageCount > 1) {
                            ThumbnailRail(
                                state = state,
                                currentPage = currentPage,
                                controls = controls,
                                look = look,
                                onJump = onJump,
                                modifier = Modifier.width(RAIL_WIDTH).fillMaxHeight()
                            )
                        }
                        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            Pages(
                                input = PagesInput(
                                    state = state,
                                    look = look,
                                    highlights = if (searching) reading.highlights else emptyMap(),
                                    emphasisedPage = reading.hits.getOrNull(reading.hitIndex)?.pageIndex,
                                    selection = selection,
                                    renderPage = renderPage,
                                    controls = controls,
                                    actions = actions
                                ),
                                mode = reading.readingMode,
                                zoom = zoom,
                                onZoom = { zoom = it },
                                startPage = currentPage,
                                jump = reading.jump,
                                onCurrentPage = { currentPage = it }
                            )

                            selection?.let { chosen ->
                                SelectionBar(
                                    onCopy = {
                                        val copied = TextHandoff.copy(context, chosen.text, state.displayName)
                                        if (!copied) note = Strings.get(R.string.reading_copy_failed)
                                        else if (!TextHandoff.systemConfirmsCopy) note = Strings.get(R.string.reading_copied)
                                        selection = null
                                    },
                                    onShare = { TextHandoff.share(context, chosen.text) },
                                    onFind = {
                                        val query = chosen.text.replace(Regex("\\s+"), " ").trim().take(MAX_FIND_LENGTH)
                                        selection = null
                                        searching = true
                                        showResults = true
                                        onSearch(query)
                                    },
                                    onReadAloud = {
                                        controls.readAloudFrom(chosen.pageIndex, chosen.first)
                                        selection = null
                                    },
                                    onDefine = {
                                        if (!TextHandoff.define(context, chosen.text)) note = Strings.get(R.string.reading_no_app_define)
                                    },
                                    onTranslate = {
                                        if (!TextHandoff.translate(context, chosen.text)) note = Strings.get(R.string.reading_no_app_translate)
                                    },
                                    onClose = { selection = null },
                                    modifier = Modifier.align(Alignment.TopCenter)
                                )
                            }

                            if (zoom > 1.05f) {
                                // A way back that does not need a second accurate pinch. Readers
                                // zoom in to look at one figure and then want the page again.
                                PageBadge(
                                    label = stringResource(R.string.reading_zoom_reset, (zoom * 100).toInt()),
                                    onClick = { zoom = 1f },
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(top = if (selection != null) 64.dp else 10.dp, end = 10.dp)
                                )
                            }

                            Column(
                                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                note?.let { NoteChip(it) }
                                if (showPageBadge && state.pageCount > 1) {
                                    PageBadge(
                                        label = "${currentPage + 1} / ${state.pageCount}",
                                        onClick = { showThumbnails = true }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showContents && ready != null) {
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

    if (showComments && ready != null) {
        CommentsSheet(
            comments = reading.comments,
            onDismiss = { showComments = false },
            onJump = { page ->
                showComments = false
                onJump(page)
            }
        )
    }

    if (showGoToPage && ready != null) {
        GoToPageSheet(
            pageCount = ready.pageCount,
            onDismiss = { showGoToPage = false },
            onGo = { page ->
                showGoToPage = false
                onJump(page)
            }
        )
    }

    if (showThumbnails && ready != null) {
        ThumbnailSheet(
            state = ready,
            currentPage = currentPage,
            controls = controls,
            look = look,
            onDismiss = { showThumbnails = false },
            onGoToPage = {
                showThumbnails = false
                showGoToPage = true
            },
            onJump = { page ->
                showThumbnails = false
                onJump(page)
            }
        )
    }

    if (showOptions && ready != null) {
        ReadingOptionsSheet(
            reading = reading,
            controls = controls,
            onDismiss = { showOptions = false }
        )
    }

    if (showTools && ready != null) {
        ToolPickerSheet(
            heading = stringResource(R.string.reading_use_on_document),
            documentName = ready.displayName,
            onDismiss = { showTools = false },
            onPick = { tool ->
                showTools = false
                onUseTool(tool, ready.sourceUri)
            }
        )
    }
}

/**
 * The pages in the chosen layout, with pinch-to-zoom over all three.
 *
 * The pinch handler takes over only once a second finger is down, so a one-finger drag stays
 * the list's or the pager's, and it is consumed only while the distance between the fingers
 * is actually changing, so a two-finger scroll still scrolls.
 */
@Composable
private fun Pages(
    input: PagesInput,
    mode: ReadingMode,
    zoom: Float,
    onZoom: (Float) -> Unit,
    startPage: Int,
    jump: Jump?,
    onCurrentPage: (Int) -> Unit
) {
    val currentZoom by rememberUpdatedState(zoom)
    val setZoom by rememberUpdatedState(onZoom)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(SheafColors.Background)
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
                                setZoom((currentZoom * change).coerceIn(1f, MAX_ZOOM))
                            }
                            if (pinching) event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
    ) {
        // The layouts are swapped wholesale, each starting from the page the last one was on,
        // so changing mode keeps the reader's place rather than sending them to page one. Keyed
        // on the document as well: the next document starts with fresh scroll state, put at
        // its own resume page, rather than inheriting the last one's offset.
        key(input.state.generation, mode) {
            when (mode) {
                ReadingMode.CONTINUOUS -> ContinuousPages(input, maxWidth, zoom, startPage, jump, onCurrentPage)
                ReadingMode.SINGLE -> SinglePages(input, zoom, startPage, jump, onCurrentPage)
                ReadingMode.TWO_UP -> SpreadPages(input, maxWidth, zoom, startPage, jump, onCurrentPage)
            }
        }
    }
}

/** The colour filter and paper for a tint and a brightness, worked out once per change. */
private fun pageLookFor(tint: PaperTint, brightness: Float, cropMargins: Boolean): PageLook {
    val matrix = pageColourMatrix(tint, brightness)
    val paper = if (matrix == null) {
        SheafColors.Paper
    } else {
        // What white paper comes out as through the filter: the colour a page shows before
        // its render has arrived, so a tinted document does not flash white while it loads.
        fun channel(row: Int) = ((matrix[row * 5] + matrix[row * 5 + 1] + matrix[row * 5 + 2]) * 255f + matrix[row * 5 + 4])
            .coerceIn(0f, 255f) / 255f
        Color(channel(0), channel(1), channel(2))
    }
    return PageLook(
        filter = matrix?.let { ColorFilter.colorMatrix(ColorMatrix(it)) },
        paper = paper,
        dark = tint.isDark,
        cropMargins = cropMargins
    )
}

/**
 * What can be done with the selected words, as a strip over the top of the pages.
 *
 * Over the top rather than beside the words, because beside the words is under the reader's
 * finger and on top of the next line they wanted to read; and a strip that scrolls sideways,
 * because six labels do not fit across a phone in portrait and icons alone would leave
 * "Define" and "Translate" to guesswork.
 */
@Composable
private fun SelectionBar(
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onFind: () -> Unit,
    onReadAloud: () -> Unit,
    onDefine: () -> Unit,
    onTranslate: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(999.dp)
    Row(
        modifier = modifier
            .padding(10.dp)
            .shadow(8.dp, shape)
            .clip(shape)
            .background(SheafColors.Surface)
            .border(1.dp, SheafColors.BorderStrong, shape)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SelectionAction(stringResource(R.string.reading_copy), onCopy)
        SelectionAction(stringResource(R.string.reading_share), onShare)
        SelectionAction(stringResource(R.string.reading_find), onFind)
        SelectionAction(stringResource(R.string.reading_read_aloud), onReadAloud)
        SelectionAction(stringResource(R.string.reading_define), onDefine)
        SelectionAction(stringResource(R.string.reading_translate), onTranslate)
        RoundIconButton(icon = Icons.Filled.Close, contentDescription = stringResource(R.string.reading_clear_selection), onClick = onClose, size = 32.dp)
    }
}

@Composable
private fun SelectionAction(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = SheafColors.Text)
    }
}

@Composable
private fun NoteChip(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = SheafColors.Text,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .padding(horizontal = 24.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(SheafColors.Surface.copy(alpha = 0.96f))
            .border(1.dp, SheafColors.BorderStrong, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp)
    )
}

/**
 * A locked document, opened here rather than in another tool.
 *
 * The password goes to the PDFBox engine, which can take one, and lives only as long as the
 * document stays open. Sending the reader to Remove a password first meant making an unlocked
 * copy of a file somebody locked on purpose, just to read it.
 */
@Composable
private fun PasswordPrompt(state: ViewerState.NeedsPassword, onUnlock: (String) -> Unit) {
    var typed by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val submit = { if (typed.isNotEmpty() && !state.checking) onUnlock(typed) }

    Box(
        modifier = Modifier.fillMaxSize().background(SheafColors.Background).padding(28.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth()) {
            Text(stringResource(R.string.reading_locked_title), style = MaterialTheme.typography.headlineSmall, color = SheafColors.Text)
            Text(
                stringResource(R.string.reading_locked_body),
                style = MaterialTheme.typography.bodyMedium,
                color = SheafColors.Muted,
                modifier = Modifier.padding(top = 6.dp)
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                placeholder = { Text(stringResource(R.string.reading_password), style = MaterialTheme.typography.bodyMedium) },
                singleLine = true,
                isError = state.wrong,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { submit() }),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = SheafColors.Band,
                    unfocusedBorderColor = SheafColors.Border,
                    focusedTextColor = SheafColors.Text,
                    unfocusedTextColor = SheafColors.Text,
                    cursorColor = SheafColors.Band,
                    focusedContainerColor = SheafColors.SurfaceDim,
                    unfocusedContainerColor = SheafColors.SurfaceDim
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(focus)
            )
            if (state.wrong) {
                Text(
                    stringResource(R.string.reading_password_wrong),
                    style = MaterialTheme.typography.bodySmall,
                    color = SheafColors.Skipped,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            Spacer(Modifier.height(14.dp))
            if (state.checking) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = SheafColors.Band, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.reading_checking), style = MaterialTheme.typography.bodyMedium, color = SheafColors.Muted, modifier = Modifier.padding(start = 10.dp))
                }
            } else {
                PrimaryButton(text = stringResource(R.string.reading_open), enabled = typed.isNotEmpty(), onClick = submit, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/**
 * Every page as a picture, to jump from. The page being read is marked and the grid opens
 * scrolled to it, so the question "where am I in this" is answered before it is asked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThumbnailSheet(
    state: ViewerState.Ready,
    currentPage: Int,
    controls: ViewerControls,
    look: PageLook,
    onDismiss: () -> Unit,
    onGoToPage: () -> Unit,
    onJump: (Int) -> Unit
) {
    val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = (currentPage - 3).coerceAtLeast(0))
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = SheafColors.Surface) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            RowBetween {
                Text(stringResource(R.string.reading_pages), style = MaterialTheme.typography.headlineSmall, color = SheafColors.Text)
                TextButton(onClick = onGoToPage) { Text(stringResource(R.string.reading_type_page_number), color = SheafColors.Band) }
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 96.dp),
                state = gridState,
                contentPadding = PaddingValues(bottom = 28.dp, top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().fillMaxHeight(0.85f)
            ) {
                items(count = state.pageCount, key = { "${state.generation}:grid:$it" }) { index ->
                    PageThumbnail(
                        generation = state.generation,
                        index = index,
                        size = state.pageSizes.getOrNull(index),
                        current = index == currentPage,
                        controls = controls,
                        look = look,
                        onClick = { onJump(index) }
                    )
                }
            }
        }
    }
}

/** Layout, paper colour, brightness and margins: how the document looks while it is read. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReadingOptionsSheet(
    reading: ReadingState,
    controls: ViewerControls,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = SheafColors.Surface) {
        Column(modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text(stringResource(R.string.reading_options_title), style = MaterialTheme.typography.headlineSmall, color = SheafColors.Text)
            Spacer(Modifier.height(14.dp))

            SectionHeading(stringResource(R.string.reading_layout))
            Spacer(Modifier.height(8.dp))
            ChipRow(
                options = ReadingMode.entries.map { it to it.label },
                selected = reading.readingMode,
                onSelect = controls::setReadingMode
            )
            Text(
                stringResource(R.string.reading_remembered),
                style = MaterialTheme.typography.bodySmall,
                color = SheafColors.Dim,
                modifier = Modifier.padding(top = 6.dp)
            )
            Spacer(Modifier.height(16.dp))

            SectionHeading(stringResource(R.string.reading_paper))
            Spacer(Modifier.height(8.dp))
            ChipRow(
                options = PaperTint.entries.map { it to it.label },
                selected = reading.tint,
                onSelect = controls::setTint
            )
            Spacer(Modifier.height(16.dp))

            SectionHeading(stringResource(R.string.reading_brightness))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = reading.brightness,
                    onValueChange = controls::setBrightness,
                    valueRange = MIN_BRIGHTNESS..1f,
                    colors = SliderDefaults.colors(thumbColor = SheafColors.Band, activeTrackColor = SheafColors.Band),
                    modifier = Modifier.weight(1f)
                )
                Text(
                    stringResource(R.string.reading_percent, (reading.brightness * 100).roundToInt()),
                    style = MaterialTheme.typography.labelLarge,
                    color = SheafColors.Muted,
                    modifier = Modifier.padding(start = 12.dp).width(44.dp),
                    textAlign = TextAlign.End
                )
            }
            Text(
                stringResource(R.string.reading_brightness_note),
                style = MaterialTheme.typography.bodySmall,
                color = SheafColors.Dim
            )
            Spacer(Modifier.height(12.dp))

            ToggleRow(
                title = stringResource(R.string.reading_crop_margins),
                checked = reading.cropMargins,
                onChange = controls::setCropMargins,
                detail = stringResource(R.string.reading_crop_margins_detail)
            )
        }
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
    onComments: () -> Unit,
    onGoToPage: () -> Unit,
    onThumbnails: () -> Unit,
    onOptions: () -> Unit,
    onReadAloud: () -> Unit,
    onStopReading: () -> Unit,
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
            RoundIconButton(icon = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.reading_back), onClick = onBack)
            Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
                Text(
                    text = state.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = SheafColors.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(R.string.reading_page_of, currentPage + 1, state.pageCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = SheafColors.Dim
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (canMark) {
                    RoundIconButton(
                        icon = Icons.Filled.Star,
                        contentDescription = stringResource(if (marked) R.string.reading_remove_mark else R.string.reading_mark_page),
                        onClick = onToggleBookmark,
                        tint = if (marked) SheafColors.BandBright else SheafColors.Dim
                    )
                }
                RoundIconButton(icon = Icons.Filled.Search, contentDescription = stringResource(R.string.reading_find), onClick = onSearchToggle)
                Box {
                    RoundIconButton(icon = Icons.Filled.MoreVert, contentDescription = stringResource(R.string.reading_more), onClick = { menuOpen = true })
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false }
                    ) {
                        if (state.pageCount > 1) {
                            MenuRow(stringResource(R.string.reading_pages)) {
                                menuOpen = false
                                onThumbnails()
                            }
                        }
                        if (reading.outline.isNotEmpty() || reading.bookmarks.isNotEmpty()) {
                            MenuRow(stringResource(R.string.reading_contents_and_marks)) {
                                menuOpen = false
                                onContents()
                            }
                        }
                        if (reading.comments.isNotEmpty()) {
                            MenuRow(stringResource(R.string.reading_comments_menu, reading.comments.size)) {
                                menuOpen = false
                                onComments()
                            }
                        }
                        MenuRow(stringResource(R.string.reading_go_to_page_menu)) {
                            menuOpen = false
                            onGoToPage()
                        }
                        if (reading.readAloud.available) {
                            MenuRow(stringResource(if (reading.readAloud.speaking) R.string.reading_stop_reading_aloud else R.string.reading_read_aloud_from_page)) {
                                menuOpen = false
                                if (reading.readAloud.speaking) onStopReading() else onReadAloud()
                            }
                        }
                        if (canMark) {
                            MenuRow(stringResource(if (marked) R.string.reading_remove_mark else R.string.reading_mark_page)) {
                                menuOpen = false
                                onToggleBookmark()
                            }
                        }
                        MenuRow(stringResource(R.string.reading_options_menu)) {
                            menuOpen = false
                            onOptions()
                        }
                        MenuRow(stringResource(if (reading.nightMode) R.string.reading_day_colours else R.string.reading_night_colours)) {
                            menuOpen = false
                            onToggleNight()
                        }
                        MenuRow(stringResource(R.string.reading_print)) {
                            menuOpen = false
                            onPrint()
                        }
                        MenuRow(stringResource(R.string.reading_share_document)) {
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
            PrimaryButton(
                text = stringResource(R.string.reading_use_tool),
                onClick = onTools,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                tall = false
            )
        }
        // How far through: a thread of the band colour under the bar, which is the one glance
        // that says whether this is page three of forty or thirty-seven.
        if (state.pageCount > 1) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .height(3.dp)
                    .background(SheafColors.SurfaceDim, RoundedCornerShape(2.dp))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(((currentPage + 1).toFloat() / state.pageCount).coerceIn(0.02f, 1f))
                        .height(3.dp)
                        .background(SheafColors.Band, RoundedCornerShape(2.dp))
                )
            }
        }
    }
}

/** What is being read out, and the one button that matters while it is. */
@Composable
private fun ReadAloudBar(readAloud: ReadAloud, onStop: () -> Unit) {
    RowBetween(
        modifier = Modifier
            .background(SheafColors.Band.copy(alpha = if (SheafColors.isDark) 0.14f else 0.1f))
            .padding(horizontal = 20.dp, vertical = 6.dp)
    ) {
        if (readAloud.speaking) {
            Box(Modifier.size(8.dp).background(SheafColors.BandBright, CircleShape))
            Spacer(Modifier.width(10.dp))
        }
        Text(
            text = when {
                readAloud.starting -> stringResource(R.string.reading_starting_voice)
                readAloud.speaking -> stringResource(R.string.reading_reading_page_aloud, readAloud.page + 1)
                else -> readAloud.note.orEmpty()
            },
            style = MaterialTheme.typography.bodyMedium,
            color = SheafColors.Muted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (readAloud.speaking) {
            TextButton(onClick = onStop) { Text(stringResource(R.string.reading_stop), color = SheafColors.Band) }
        }
    }
}

/** The comments on the pages: who said what, where. Tap one to go to its page. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommentsSheet(
    comments: List<Comment>,
    onDismiss: () -> Unit,
    onJump: (Int) -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = SheafColors.Surface) {
        Column(modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            SectionHeading(pluralStringResource(R.plurals.reading_comments, comments.size, comments.size))
            Spacer(Modifier.height(6.dp))
            comments.forEach { comment ->
                val onPage = stringResource(R.string.reading_comment_on_page, comment.pageIndex + 1)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onJump(comment.pageIndex) }
                        .padding(vertical = 10.dp)
                ) {
                    Text(
                        buildString {
                            append(comment.kind)
                            comment.author?.let { append(" · ").append(it) }
                            append(" · ").append(onPage)
                            comment.date?.let { append(" · ").append(it) }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = SheafColors.Dim
                    )
                    if (comment.text.isNotBlank()) {
                        Text(
                            comment.text,
                            style = MaterialTheme.typography.bodyLarge,
                            color = SheafColors.Text,
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
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

/** Where the reader is, over the page rather than beside it. Tapping it offers the pages. */
@Composable
private fun PageBadge(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(SheafColors.Surface.copy(alpha = 0.94f))
            .border(1.dp, SheafColors.BorderStrong, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = SheafColors.Text)
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
    onJump: (Int) -> Unit,
    onFocusChanged: (Boolean) -> Unit
) {
    // Focused as it opens: whoever opened it - the Find button, Ctrl+F, Find on a selection -
    // is about to type, or to read what was typed for them.
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SheafColors.Surface)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val shape = RoundedCornerShape(999.dp)
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .background(SheafColors.SurfaceDim, shape)
                    .border(1.dp, if (reading.query.isNotBlank()) SheafColors.Band.copy(alpha = 0.7f) else SheafColors.Border, shape)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Search, contentDescription = null, tint = SheafColors.Dim, modifier = Modifier.size(16.dp))
                Box(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                    if (reading.query.isEmpty()) {
                        Text(stringResource(R.string.reading_find_hint), style = MaterialTheme.typography.bodyMedium, color = SheafColors.Dim)
                    }
                    BasicTextField(
                        value = reading.query,
                        onValueChange = onSearch,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = SheafColors.Text),
                        cursorBrush = SolidColor(SheafColors.Band),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focus)
                            .onFocusChanged { onFocusChanged(it.isFocused) }
                    )
                }
            }
            if (reading.hits.isNotEmpty()) {
                RoundIconButton(icon = Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.reading_previous_match), onClick = { onStep(false) }, size = 36.dp)
                RoundIconButton(icon = Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.reading_next_match), onClick = { onStep(true) }, size = 36.dp, filled = true)
            }
        }

        val status = when {
            reading.searching -> stringResource(R.string.reading_searching)
            reading.query.isBlank() -> null
            reading.hits.isEmpty() -> stringResource(R.string.reading_no_matches)
            reading.hitIndex >= 0 -> stringResource(
                R.string.reading_match_position,
                reading.hits[reading.hitIndex].pageIndex + 1,
                reading.hitIndex + 1,
                reading.hits.size
            )
            else -> pluralStringResource(R.plurals.reading_pages_count, reading.hits.size, reading.hits.size)
        }
        status?.let {
            Pill(
                it,
                if (reading.hits.isEmpty() && reading.query.isNotBlank() && !reading.searching) SheafColors.Skipped else SheafColors.Muted,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        if (!showResults && reading.hits.isNotEmpty()) {
            Text(
                stringResource(R.string.reading_matches_marked),
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
            reading.hits.take(MAX_VISIBLE_HITS).forEachIndexed { n, hit ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = if (n == 0) 10.dp else 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(SheafColors.SurfaceDim)
                        .clickable { onJump(hit.pageIndex) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Pill(stringResource(R.string.reading_page_short, hit.pageIndex + 1), SheafColors.Band, filled = true)
                    Column(modifier = Modifier.padding(start = 10.dp).weight(1f)) {
                        if (hit.matchCount > 1) {
                            Text(
                                pluralStringResource(R.plurals.reading_matches_on_page, hit.matchCount, hit.matchCount),
                                style = MaterialTheme.typography.labelSmall,
                                color = SheafColors.Dim
                            )
                        }
                        // The match is highlighted inside the snippet. A result that only
                        // says "page 4" makes you find the word again once you get there.
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
                SectionHeading(stringResource(R.string.reading_your_marks))
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
                                stringResource(R.string.reading_page_n, mark.pageIndex + 1),
                                style = MaterialTheme.typography.bodySmall,
                                color = SheafColors.Dim
                            )
                        }
                        TextButton(onClick = { onRemoveBookmark(mark.pageIndex) }) {
                            Text(
                                stringResource(R.string.reading_remove),
                                style = MaterialTheme.typography.bodySmall,
                                color = SheafColors.Dim
                            )
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
            }

            if (outline.isNotEmpty()) {
                SectionHeading(stringResource(R.string.reading_contents))
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
                    stringResource(R.string.reading_no_contents),
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
            Text(stringResource(R.string.reading_go_to_page), style = MaterialTheme.typography.headlineSmall, color = SheafColors.Text)
            Text(pluralStringResource(R.plurals.reading_document_has_pages, pageCount, pageCount), style = MaterialTheme.typography.bodySmall, color = SheafColors.Dim)
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it.filter { character -> character.isDigit() }.take(6) },
                placeholder = { Text(stringResource(R.string.reading_page_range, pageCount), style = MaterialTheme.typography.bodyMedium) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                textStyle = MaterialTheme.typography.headlineSmall.copy(textAlign = TextAlign.Center),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = SheafColors.Band,
                    unfocusedBorderColor = SheafColors.Border,
                    focusedTextColor = SheafColors.Text,
                    unfocusedTextColor = SheafColors.Text,
                    cursorColor = SheafColors.Band,
                    focusedContainerColor = SheafColors.SurfaceDim,
                    unfocusedContainerColor = SheafColors.SurfaceDim
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            PrimaryButton(
                text = stringResource(R.string.reading_go),
                enabled = target != null && target in 1..pageCount,
                onClick = { target?.let { onGo(it - 1) } },
                modifier = Modifier.fillMaxWidth()
            )
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

private const val MAX_VISIBLE_HITS = 12

/** Far enough in to read small print, near enough out that one page is still one page. */
private const val MAX_ZOOM = 4f
private const val DOUBLE_TAP_ZOOM = 2.5f

/** Material's expanded width class begins here: a tablet in landscape, an open foldable. */
private const val WIDE_SCREEN_DP = 840
private val RAIL_WIDTH = 128.dp

/** A selection longer than this is a paragraph, not something anybody searches for. */
private const val MAX_FIND_LENGTH = 80

private const val NOTE_MS = 2600L

private val FORWARD_KEYS = setOf(Key.DirectionRight, Key.DirectionDown, Key.PageDown)
private val BACK_KEYS = setOf(Key.DirectionLeft, Key.DirectionUp, Key.PageUp)
