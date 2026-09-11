package com.layerbit.sheaf.ui.viewer

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import kotlinx.coroutines.launch
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.layerbit.sheaf.ops.ToolId
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import com.layerbit.sheaf.pdf.PageSize
import com.layerbit.sheaf.ui.components.RowBetween
import com.layerbit.sheaf.ui.components.SectionHeading
import com.layerbit.sheaf.ui.components.formatPageCount
import com.layerbit.sheaf.ui.theme.SheafColors
import com.layerbit.sheaf.ui.tools.iconFor

/**
 * Continuous vertical scroll through a document.
 *
 * Each page is its own list item sized from the page dimensions read at open time, so the list
 * has correct extents before a single page is rendered - which is what makes the scrollbar
 * honest and stops the content jumping as pages arrive. Rendering happens per item, when the
 * item composes, and the bitmap is dropped when it leaves the cache.
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
    modifier: Modifier = Modifier
) {
    var showTools by remember { mutableStateOf(false) }
    var showOutline by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    Column(modifier = modifier.fillMaxSize().background(SheafColors.Background)) {
        if (state is ViewerState.Ready) {
            ViewerHeader(
                state = state,
                reading = reading,
                hasOutline = reading.outline.isNotEmpty(),
                onBack = onBack,
                onTools = { showTools = true },
                onOutline = { showOutline = true },
                onToggleNight = onToggleNight,
                onSearchToggle = {
                    searching = !searching
                    if (!searching) onClearSearch()
                }
            )
            if (searching) {
                SearchBar(
                    reading = reading,
                    onSearch = onSearch,
                    onJump = { page ->
                        searching = false
                        onClearSearch()
                        scope.launch { listState.scrollToItem(page) }
                    }
                )
            }
        }

        when (state) {
            is ViewerState.Loading ->
                CentredMessage("Opening…", showSpinner = true, modifier = Modifier.weight(1f))

            is ViewerState.NeedsPassword -> CentredMessage(
                "This document is password-protected.\n" +
                    "Use Remove a password from the home screen to unlock a copy first.",
                modifier = Modifier.weight(1f)
            )

            is ViewerState.Failed -> CentredMessage(state.reason, modifier = Modifier.weight(1f))

            is ViewerState.Ready ->
                PageList(state, reading.nightMode, listState, renderPage, Modifier.weight(1f))
        }
    }

    if (showOutline && state is ViewerState.Ready) {
        OutlineSheet(
            entries = reading.outline,
            onDismiss = { showOutline = false },
            onJump = { page ->
                showOutline = false
                scope.launch { listState.scrollToItem(page) }
            }
        )
    }

    if (showTools && state is ViewerState.Ready) {
        ToolSheet(
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
 */
@Composable
private fun ViewerHeader(
    state: ViewerState.Ready,
    reading: ReadingState,
    hasOutline: Boolean,
    onBack: () -> Unit,
    onTools: () -> Unit,
    onOutline: () -> Unit,
    onToggleNight: () -> Unit,
    onSearchToggle: () -> Unit
) {
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
                    text = formatPageCount(state.pageCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = SheafColors.Dim
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onSearchToggle) {
                    Text("Find", color = SheafColors.Muted)
                }
                if (hasOutline) {
                    TextButton(onClick = onOutline) {
                        Text("Contents", color = SheafColors.Muted)
                    }
                }
                TextButton(onClick = onToggleNight) {
                    Text(
                        if (reading.nightMode) "Day" else "Night",
                        color = if (reading.nightMode) SheafColors.BandBright else SheafColors.Muted
                    )
                }
            }
        }
        TextButton(onClick = onTools, modifier = Modifier.padding(top = 2.dp)) {
            Text("Use a tool on this", color = SheafColors.Band, style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** Find across the document. Results are pages, and tapping one scrolls to it. */
@Composable
private fun SearchBar(reading: ReadingState, onSearch: (String) -> Unit, onJump: (Int) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SheafColors.SurfaceDim)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
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
            modifier = Modifier.fillMaxWidth()
        )

        val status = when {
            reading.searching -> "Searching…"
            reading.query.isBlank() -> null
            reading.hits.isEmpty() -> "No pages contain that."
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

        // Bounded, because a search for "the" in a book matches every page and an unbounded
        // list inside a header would take the whole screen.
        reading.hits.take(MAX_VISIBLE_HITS).forEach { hit ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onJump(hit.pageIndex) }
                    .padding(vertical = 8.dp)
            ) {
                Text(
                    "Page ${hit.pageIndex + 1}" + if (hit.matchCount > 1) " · ${hit.matchCount} matches" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = SheafColors.BandBright
                )
                Text(
                    hit.snippet,
                    style = MaterialTheme.typography.bodySmall,
                    color = SheafColors.Muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** The document's own table of contents. Only offered when it actually has one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OutlineSheet(
    entries: List<com.layerbit.sheaf.pdf.OutlineEntry>,
    onDismiss: () -> Unit,
    onJump: (Int) -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = SheafColors.Surface) {
        Column(modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            SectionHeading("Contents")
            Spacer(Modifier.height(6.dp))
            entries.forEach { entry ->
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
        }
    }
}

private const val MAX_VISIBLE_HITS = 12

/**
 * The tools that can act on the document currently open.
 *
 * Images to PDF is left out: its input is photographs, so offering it here would be offering
 * something that cannot accept what is on screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolSheet(documentName: String, onDismiss: () -> Unit, onPick: (ToolId) -> Unit) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SheafColors.Surface
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            SectionHeading("Use on this document")
            Text(
                text = documentName,
                style = MaterialTheme.typography.titleMedium,
                color = SheafColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp, bottom = 10.dp)
            )
            ToolId.entries
                .filter { it != ToolId.IMAGES_TO_PDF }
                .forEach { tool ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(tool) }
                            .padding(vertical = 12.dp)
                    ) {
                        Icon(
                            painter = painterResource(iconFor(tool)),
                            contentDescription = null,
                            tint = SheafColors.Muted,
                            modifier = Modifier.size(22.dp)
                        )
                        Column(modifier = Modifier.padding(start = 14.dp)) {
                            Text(
                                tool.title,
                                style = MaterialTheme.typography.titleMedium,
                                color = SheafColors.Text
                            )
                            Text(
                                tool.summary,
                                style = MaterialTheme.typography.bodySmall,
                                color = SheafColors.Dim
                            )
                        }
                    }
                }
        }
    }
}

@Composable
private fun PageList(
    state: ViewerState.Ready,
    nightMode: Boolean,
    listState: androidx.compose.foundation.lazy.LazyListState,
    renderPage: suspend (index: Int, widthPx: Int) -> Bitmap?,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val screenWidthDp = LocalConfiguration.current.screenWidthDp

    // The width every page is rendered at. Fixed for the document rather than recomputed per
    // page, so the LRU cache never holds two sizes of the same page.
    val renderWidthPx = remember(screenWidthDp) {
        with(density) { (screenWidthDp.dp - PAGE_MARGIN * 2).toPx().toInt().coerceAtLeast(1) }
    }

    Column(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .background(SheafColors.Background),
            contentPadding = PaddingValues(vertical = PAGE_MARGIN)
        ) {
            items(count = state.pageCount, key = { "${state.generation}:$it" }) { index ->
                PageItem(
                    generation = state.generation,
                    index = index,
                    size = state.pageSizes.getOrNull(index),
                    renderWidthPx = renderWidthPx,
                    nightMode = nightMode,
                    renderPage = renderPage
                )
            }
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
    }
}

@Composable
private fun CentredMessage(text: String, showSpinner: Boolean = false, modifier: Modifier = Modifier) {
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

private val PAGE_MARGIN = 12.dp
private val PAGE_GAP = 12.dp

/** The placeholder behind a page that has not arrived, in night mode. */
private val NIGHT_PAPER = androidx.compose.ui.graphics.Color(0xFF15171B)

/**
 * Straight photographic inversion: white paper becomes near-black, black ink becomes white.
 *
 * A colour matrix rather than a second render, so the toggle is instant and the same cached
 * bitmap serves both modes. Colour in a document inverts too, which is the honest behaviour -
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
