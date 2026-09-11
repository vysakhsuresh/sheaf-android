package com.layerbit.sheaf.ui.viewer

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.layerbit.sheaf.pdf.PageSize
import com.layerbit.sheaf.ui.components.SectionHeading
import com.layerbit.sheaf.ui.theme.SheafColors

/**
 * Continuous vertical scroll through a document.
 *
 * Each page is its own list item sized from the page dimensions read at open time, so the list
 * has correct extents before a single page is rendered - which is what makes the scrollbar
 * honest and stops the content jumping as pages arrive. Rendering happens per item, when the
 * item composes, and the bitmap is dropped when it leaves the cache.
 *
 * P0's exit gate is measured here: a five-hundred-page document, scrolled end to end, at
 * sixty frames per second, returning to a flat heap afterwards.
 */
@Composable
fun ViewerScreen(
    state: ViewerState,
    renderPage: suspend (index: Int, widthPx: Int) -> Bitmap?,
    modifier: Modifier = Modifier
) {
    when (state) {
        is ViewerState.Loading -> CentredMessage("Opening…", showSpinner = true, modifier = modifier)

        is ViewerState.NeedsPassword -> CentredMessage(
            "This document is password-protected.\nOpening encrypted documents arrives in the next release.",
            modifier = modifier
        )

        is ViewerState.Failed -> CentredMessage(state.reason, modifier = modifier)

        is ViewerState.Ready -> PageList(state, renderPage, modifier)
    }
}

@Composable
private fun PageList(
    state: ViewerState.Ready,
    renderPage: suspend (index: Int, widthPx: Int) -> Bitmap?,
    modifier: Modifier
) {
    val listState = rememberLazyListState()
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
            items(count = state.pageCount, key = { it }) { index ->
                PageItem(
                    index = index,
                    size = state.pageSizes.getOrNull(index),
                    renderWidthPx = renderWidthPx,
                    renderPage = renderPage
                )
            }
        }
    }
}

@Composable
private fun PageItem(
    index: Int,
    size: PageSize?,
    renderWidthPx: Int,
    renderPage: suspend (index: Int, widthPx: Int) -> Bitmap?
) {
    var bitmap by remember(index) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(index) { mutableStateOf(false) }

    LaunchedEffect(index, renderWidthPx) {
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
            // The paper colour sits under the render, so a page that has not arrived yet is a
            // blank sheet rather than a hole in the list.
            .background(if (failed) SheafColors.SurfaceDim else SheafColors.Paper),
        contentAlignment = Alignment.Center
    ) {
        val current = bitmap
        when {
            current != null -> Image(
                bitmap = current.asImageBitmap(),
                contentDescription = "Page ${index + 1}",
                contentScale = ContentScale.Fit,
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
