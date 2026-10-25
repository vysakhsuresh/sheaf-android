package com.layerbit.sheaf.ui.viewer

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.layerbit.sheaf.pdf.PageArea
import com.layerbit.sheaf.pdf.PageLink
import com.layerbit.sheaf.pdf.PageSize
import com.layerbit.sheaf.ui.theme.SheafColors
import androidx.compose.ui.res.stringResource
import com.layerbit.sheaf.R
import kotlin.math.roundToInt

/**
 * The pages themselves, in each of the three reading modes, and the one page composable all
 * three are built from.
 *
 * A page is a rendered bitmap with everything else drawn over it rather than into it: the
 * search marks, the selected words, the faint line under each link. One cached render then
 * serves a page whether it is selected, searched, tinted or cropped, and none of those costs
 * a second render to switch on or off.
 */

/** How a page is drawn: the tint and dimming as one colour filter, and the paper under it. */
@Immutable
internal data class PageLook(
    val filter: ColorFilter?,
    val paper: Color,
    val dark: Boolean,
    val cropMargins: Boolean
)

/** What a page reports back about the reader's fingers, in page fractions. */
internal class PageActions(
    val onTap: (page: Int, x: Float, y: Float, links: List<PageLink>) -> Unit,
    val onDoubleTap: () -> Unit,
    val onLongPress: (page: Int, x: Float, y: Float) -> Unit,
    val onSelectDrag: (page: Int, x: Float, y: Float) -> Unit,
    val onHandleStart: (isStart: Boolean) -> Unit,
    val onHandleDrag: (page: Int, x: Float, y: Float) -> Unit
)

/** Everything the three layouts share, so each takes one argument for it. */
internal class PagesInput(
    val state: ViewerState.Ready,
    val look: PageLook,
    val highlights: Map<Int, List<PageArea>>,
    val emphasisedPage: Int?,
    val selection: TextSelection?,
    val renderPage: suspend (index: Int, widthPx: Int) -> Bitmap?,
    val controls: ViewerControls,
    val actions: PageActions
)

/**
 * Acts on a jump only when it is newer than the one standing when this layout appeared.
 *
 * The last jump stays in the reading state after it has been carried out, and a layout that
 * has just replaced another - the reader switched modes - would otherwise carry it out again
 * and throw them back to wherever they last jumped from the contents.
 */
@Composable
private fun JumpEffect(jump: Jump?, onJump: suspend (Int) -> Unit) {
    val seen = remember { jump?.token }
    LaunchedEffect(jump?.token) {
        val wanted = jump ?: return@LaunchedEffect
        if (wanted.token != seen) onJump(wanted.pageIndex.coerceAtLeast(0))
    }
}

/** One long scroll, the way the viewer has always read. */
@Composable
internal fun ContinuousPages(
    input: PagesInput,
    viewportWidth: Dp,
    zoom: Float,
    startPage: Int,
    jump: Jump?,
    onCurrentPage: (Int) -> Unit
) {
    val state = input.state
    val density = LocalDensity.current
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = startPage.coerceIn(0, (state.pageCount - 1).coerceAtLeast(0)))
    val horizontal = rememberScrollState()

    JumpEffect(jump) { listState.scrollToItem(it.coerceAtMost(state.pageCount - 1)) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { onCurrentPage(it) }
    }

    // The width a page is drawn at. Fixed per zoom step rather than per gesture frame, so the
    // LRU cache never fills with forty slightly different renders of the same page.
    val renderWidthPx = with(density) {
        ((viewportWidth - PAGE_MARGIN * 2) * renderStepFor(zoom)).toPx().toInt().coerceAtLeast(1)
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxHeight()
            .horizontalScroll(horizontal)
            .width(viewportWidth * zoom),
        contentPadding = PaddingValues(vertical = PAGE_MARGIN)
    ) {
        items(count = state.pageCount, key = { "${state.generation}:$it" }) { index ->
            PageItem(
                index = index,
                renderWidthPx = renderWidthPx,
                input = input,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PAGE_MARGIN, vertical = PAGE_GAP / 2)
            )
        }
    }
}

/**
 * A page at a time, swiped sideways like a book.
 *
 * Each page is fitted whole to the screen. Zoomed in, the swipe is switched off and the page
 * pans in both directions instead - a sideways drag across a zoomed page is a reader looking
 * along a line, not one wanting the next page.
 */
@Composable
internal fun SinglePages(
    input: PagesInput,
    zoom: Float,
    startPage: Int,
    jump: Jump?,
    onCurrentPage: (Int) -> Unit
) {
    val state = input.state
    val density = LocalDensity.current
    val pagerState = rememberPagerState(initialPage = startPage.coerceIn(0, (state.pageCount - 1).coerceAtLeast(0))) { state.pageCount }
    val zoomed = zoom > 1.05f

    JumpEffect(jump) { pagerState.scrollToPage(it.coerceAtMost(state.pageCount - 1)) }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { onCurrentPage(it) }
    }

    HorizontalPager(
        state = pagerState,
        userScrollEnabled = !zoomed,
        beyondViewportPageCount = 1,
        key = { "${state.generation}:$it" },
        modifier = Modifier.fillMaxSize()
    ) { index ->
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().padding(PAGE_MARGIN),
            contentAlignment = Alignment.Center
        ) {
            val aspect = aspectOf(state.pageSizes.getOrNull(index))
            val fitWidth = if (maxHeight.value <= 0f) maxWidth else minOf(maxWidth, maxHeight * aspect)
            val pageZoom = if (zoomed && index == pagerState.currentPage) zoom else 1f
            val renderWidthPx = with(density) { (fitWidth * renderStepFor(pageZoom)).toPx().toInt().coerceAtLeast(1) }
            if (pageZoom > 1f) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                ) {
                    PageItem(index = index, renderWidthPx = renderWidthPx, input = input, modifier = Modifier.width(fitWidth * pageZoom))
                }
            } else {
                PageItem(index = index, renderWidthPx = renderWidthPx, input = input, modifier = Modifier)
            }
        }
    }
}

/**
 * Two pages side by side - the spread a printed book opens to - for a landscape phone or a
 * tablet, where a single page leaves two-thirds of the screen empty.
 *
 * Each pair shares a row, sized so both pages come out the same height whatever their
 * proportions; an odd last page keeps the left half, as it would in the book.
 */
@Composable
internal fun SpreadPages(
    input: PagesInput,
    viewportWidth: Dp,
    zoom: Float,
    startPage: Int,
    jump: Jump?,
    onCurrentPage: (Int) -> Unit
) {
    val state = input.state
    val density = LocalDensity.current
    val spreads = remember(state.generation, state.pageCount) { Spreads.pairs(state.pageCount) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = Spreads.spreadOf(startPage, state.pageCount))
    val horizontal = rememberScrollState()

    JumpEffect(jump) { listState.scrollToItem(Spreads.spreadOf(it, state.pageCount)) }
    LaunchedEffect(listState, spreads) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { onCurrentPage(spreads.getOrNull(it)?.first() ?: 0) }
    }

    val rowWidth = (viewportWidth - PAGE_MARGIN * 2 - PAGE_GAP / 2) * zoom
    val step = renderStepFor(zoom)

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxHeight()
            .horizontalScroll(horizontal)
            .width(viewportWidth * zoom),
        contentPadding = PaddingValues(vertical = PAGE_MARGIN)
    ) {
        items(count = spreads.size, key = { "${state.generation}:spread:$it" }) { s ->
            val pages = spreads[s]
            val aspects = pages.map { aspectOf(state.pageSizes.getOrNull(it)) }
            // A lone page is given the share of a pair of pages like itself, so it is the
            // size it would have been beside a partner.
            val total = if (pages.size == 2) aspects.sum() else aspects[0] * 2
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PAGE_MARGIN, vertical = PAGE_GAP / 2),
                horizontalArrangement = Arrangement.spacedBy(PAGE_GAP / 2)
            ) {
                pages.forEachIndexed { i, page ->
                    val renderWidthPx = with(density) {
                        (rowWidth / zoom * (aspects[i] / total) * step).toPx().toInt().coerceAtLeast(1)
                    }
                    PageItem(index = page, renderWidthPx = renderWidthPx, input = input, modifier = Modifier.weight(aspects[i]))
                }
                if (pages.size == 1) Spacer(Modifier.weight(aspects[0]))
            }
        }
    }
}

/**
 * One page: its render, what is drawn over it, and the gestures on it.
 *
 * [renderWidthPx] is the width the page is shown at, in device pixels. Cropping the margins
 * shows less of the page at that width, so the render is asked for proportionally wider - the
 * text is bigger on screen and would otherwise be a blown-up blur.
 */
@Composable
private fun PageItem(
    index: Int,
    renderWidthPx: Int,
    input: PagesInput,
    modifier: Modifier
) {
    val generation = input.state.generation
    val look = input.look
    val controls = input.controls
    var bitmap by remember(generation, index) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(generation, index) { mutableStateOf(false) }
    var links by remember(generation, index) { mutableStateOf(emptyList<PageLink>()) }
    var crop by remember(generation, index) { mutableStateOf<PageArea?>(null) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(generation, index, look.cropMargins) {
        crop = if (look.cropMargins) controls.marginCropFor(index) else null
    }

    val requestWidth = crop?.let { (renderWidthPx / it.width).toInt().coerceAtMost(renderWidthPx * 3) } ?: renderWidthPx
    LaunchedEffect(generation, index, requestWidth) {
        val rendered = input.renderPage(index, requestWidth)
        if (rendered == null) {
            failed = bitmap == null
        } else {
            bitmap = rendered
            failed = false
        }
    }

    // Links are read when the page first appears, off the main thread, and only for pages
    // that do appear: a five-hundred-page document scrolled through to page ten has had ten
    // pages' links read, not five hundred.
    LaunchedEffect(generation, index) { links = controls.linksOn(index) }

    // A4 is the sane default for a page whose size could not be read - it keeps the list
    // extents roughly right rather than collapsing the item to nothing.
    val pageAspect = aspectOf(input.state.pageSizes.getOrNull(index))
    val shownCrop = crop
    val aspect = if (shownCrop != null) pageAspect * shownCrop.width / shownCrop.height else pageAspect

    val image: ImageBitmap? = remember(bitmap) { bitmap?.asImageBitmap() }
    val pageLabel = stringResource(R.string.reading_page_n, index + 1)
    val highlights = input.highlights[index].orEmpty()
    val emphasised = input.emphasisedPage == index
    val selection = input.selection?.takeIf { it.pageIndex == index }
    val bands = remember(selection) {
        selection?.let { SelectionMath.lineBoxes(it.words, it.first, it.last) }.orEmpty()
    }

    val currentCrop by rememberUpdatedState(shownCrop)
    val currentLinks by rememberUpdatedState(links)
    val actions by rememberUpdatedState(input.actions)

    Box(
        modifier = modifier
            .aspectRatio(aspect)
            // A sheet of paper, not a rectangle: a little shadow and a hairline edge.
            .shadow(if (look.dark) 0.dp else 6.dp, RoundedCornerShape(6.dp))
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, SheafColors.Border, RoundedCornerShape(6.dp))
            // The paper colour sits under the render, so a page that has not arrived yet is a
            // blank sheet rather than a hole in the list.
            .background(if (failed) SheafColors.SurfaceDim else look.paper)
            .onSizeChanged { boxSize = it }
            .semantics { contentDescription = pageLabel }
            .pointerInput(generation, index) {
                fun fraction(at: Offset): Pair<Float, Float> {
                    val w = size.width.toFloat().coerceAtLeast(1f)
                    val h = size.height.toFloat().coerceAtLeast(1f)
                    return pagePoint(at.x / w, at.y / h, currentCrop)
                }
                pageGestures(
                    onTap = { at -> val (x, y) = fraction(at); actions.onTap(index, x, y, currentLinks) },
                    onDoubleTap = { actions.onDoubleTap() },
                    onLongPress = { at -> val (x, y) = fraction(at); actions.onLongPress(index, x, y) },
                    onLongPressDrag = { at -> val (x, y) = fraction(at); actions.onSelectDrag(index, x, y) }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val drawn = image
            if (drawn != null) {
                // The crop is taken from the bitmap at draw time, so switching it on and off
                // is a change of rectangle and not a new render.
                val c = shownCrop
                val srcLeft = if (c == null) 0 else (c.left * drawn.width).roundToInt().coerceIn(0, drawn.width - 1)
                val srcTop = if (c == null) 0 else (c.top * drawn.height).roundToInt().coerceIn(0, drawn.height - 1)
                val srcWidth = if (c == null) drawn.width else (c.width * drawn.width).roundToInt().coerceIn(1, drawn.width - srcLeft)
                val srcHeight = if (c == null) drawn.height else (c.height * drawn.height).roundToInt().coerceIn(1, drawn.height - srcTop)
                drawImage(
                    image = drawn,
                    srcOffset = IntOffset(srcLeft, srcTop),
                    srcSize = IntSize(srcWidth, srcHeight),
                    dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                    // Tinted and dimmed at draw time rather than re-rendered. Changing either is
                    // then instant and costs no memory, and the cached page serves every look.
                    colorFilter = look.filter
                )
            }
            if (drawn == null) return@Canvas

            fun box(area: PageArea): Pair<Offset, Size> {
                val a = area.within(shownCrop)
                return Offset(a.left * size.width, a.top * size.height) to
                    Size((a.width * size.width).coerceAtLeast(2f), (a.height * size.height).coerceAtLeast(2f))
            }

            // A faint rule under each link, the way a link looks in running text. Faint,
            // because a contents page is nothing but links and should still read as text.
            val stroke = 1.2.dp.toPx()
            currentLinks.forEach { link ->
                val (origin, extent) = box(link.area)
                val y = origin.y + extent.height
                drawLine(LINK_LINE, Offset(origin.x, y), Offset(origin.x + extent.width, y), strokeWidth = stroke)
            }

            highlights.forEach { area ->
                val (origin, extent) = box(area)
                drawRect(if (emphasised) MATCH_MARK_STEPPED else MATCH_MARK, origin, extent)
            }

            bands.forEach { area ->
                val (origin, extent) = box(area)
                drawRect(SELECTION_BAND, origin, extent)
            }
        }

        if (failed) {
            Text(
                text = stringResource(R.string.reading_page_not_displayed, index + 1),
                style = MaterialTheme.typography.bodySmall,
                color = SheafColors.Dim,
                textAlign = TextAlign.Center
            )
        }

        if (selection != null && bands.isNotEmpty() && boxSize.width > 0) {
            val firstBand = bands.first().within(shownCrop)
            val lastBand = bands.last().within(shownCrop)
            val w = boxSize.width.toFloat()
            val h = boxSize.height.toFloat()
            SelectionHandle(
                anchor = Offset(firstBand.left * w, (firstBand.top + firstBand.height) * h),
                onStart = { actions.onHandleStart(true) },
                onDrag = { at -> val (x, y) = pagePoint(at.x / w, at.y / h, shownCrop); actions.onHandleDrag(index, x, y) }
            )
            SelectionHandle(
                anchor = Offset((lastBand.left + lastBand.width) * w, (lastBand.top + lastBand.height) * h),
                onStart = { actions.onHandleStart(false) },
                onDrag = { at -> val (x, y) = pagePoint(at.x / w, at.y / h, shownCrop); actions.onHandleDrag(index, x, y) }
            )
        }
    }
}

/**
 * A round handle hanging from one end of the selection, dragged to move that end.
 *
 * Its touch area is larger than the dot, and it reports a point a little above the finger -
 * the line of text the handle hangs under - so the finger never covers the word being chosen.
 */
@Composable
private fun BoxScope.SelectionHandle(anchor: Offset, onStart: () -> Unit, onDrag: (Offset) -> Unit) {
    val density = LocalDensity.current
    val touch = HANDLE_TOUCH
    val half = with(density) { touch.toPx() / 2f }
    val lift = with(density) { 14.dp.toPx() }
    val currentAnchor by rememberUpdatedState(anchor)
    val start by rememberUpdatedState(onStart)
    val drag by rememberUpdatedState(onDrag)

    Box(
        modifier = Modifier
            .align(Alignment.TopStart)
            .offset { IntOffset((anchor.x - half).roundToInt(), anchor.y.roundToInt()) }
            .size(touch)
            .pointerInput(Unit) {
                var at = Offset.Zero
                detectDragGestures(
                    onDragStart = { local ->
                        at = Offset(currentAnchor.x - half + local.x, currentAnchor.y + local.y)
                        start()
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        at += amount
                        drag(Offset(at.x, at.y - half - lift))
                    }
                )
            }
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .size(HANDLE_DOT)
                .background(SheafColors.Band, CircleShape)
        )
    }
}

/**
 * Tap, double tap, and long-press-then-drag, in one detector.
 *
 * One rather than the stock pair because they fight: a tap detector on the page consumes the
 * lift and a double-tap detector above it never sees it, and a long-press detector that does
 * not then follow the finger leaves the drag to the list, which scrolls instead of selecting.
 * Nothing is consumed until the gesture is known to be a long press, so a drag that starts
 * at once is still the list's to scroll and a pinch is still the zoom's.
 */
private suspend fun PointerInputScope.pageGestures(
    onTap: (Offset) -> Unit,
    onDoubleTap: (Offset) -> Unit,
    onLongPress: (Offset) -> Unit,
    onLongPressDrag: (Offset) -> Unit
) = awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false)
    val slop = viewConfiguration.touchSlop

    // 1 for a lift, 2 for anything that makes this not ours; null when the press outlasted the
    // long-press timeout without moving.
    val outcome = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
        var result = 0
        while (result == 0) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id }
            result = when {
                change == null -> 2
                event.changes.count { it.pressed } > 1 -> 2
                !change.pressed -> 1
                change.isConsumed -> 2
                (change.position - down.position).getDistance() > slop -> 2
                else -> 0
            }
        }
        result
    }

    when (outcome) {
        null -> {
            onLongPress(down.position)
            // From here the finger is selecting, so every move is consumed: the list must not
            // scroll out from under a selection being dragged across it.
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) {
                    change.consume()
                    break
                }
                if (change.positionChanged()) onLongPressDrag(change.position)
                change.consume()
            }
        }

        1 -> {
            val second = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) {
                awaitFirstDown(requireUnconsumed = false)
            }
            if (second == null) {
                onTap(down.position)
            } else {
                var lifted = false
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == second.id } ?: break
                    if (!change.pressed) {
                        lifted = true
                        break
                    }
                    if ((change.position - second.position).getDistance() > slop) break
                }
                if (lifted) onDoubleTap(second.position)
            }
        }

        else -> Unit
    }
}

/**
 * A small picture of one page, for the thumbnail sheet and the rail. The page being read is
 * outlined in the band colour, which is what lets the eye find "here" in a grid of forty.
 */
@Composable
internal fun PageThumbnail(
    generation: Int,
    index: Int,
    size: PageSize?,
    current: Boolean,
    controls: ViewerControls,
    look: PageLook,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var image by remember(generation, index) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(generation, index) { image = controls.thumbnail(index)?.asImageBitmap() }
    val shape = RoundedCornerShape(4.dp)

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspectOf(size))
                .clip(shape)
                .background(look.paper)
                .border(if (current) 2.dp else 1.dp, if (current) SheafColors.Band else SheafColors.Border, shape)
        ) {
            image?.let {
                Image(
                    bitmap = it,
                    contentDescription = stringResource(R.string.reading_page_n, index + 1),
                    contentScale = ContentScale.Fit,
                    colorFilter = look.filter,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Text(
            text = "${index + 1}",
            style = MaterialTheme.typography.labelSmall,
            color = if (current) SheafColors.BandBright else SheafColors.Dim,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

/** The thumbnail strip beside the pages on a wide screen, kept scrolled to where the reader is. */
@Composable
internal fun ThumbnailRail(
    state: ViewerState.Ready,
    currentPage: Int,
    controls: ViewerControls,
    look: PageLook,
    onJump: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val railState = rememberLazyListState(initialFirstVisibleItemIndex = (currentPage - 1).coerceAtLeast(0))
    LaunchedEffect(currentPage) {
        val visible = railState.layoutInfo.visibleItemsInfo
        val showing = visible.any { it.index == currentPage } &&
            visible.firstOrNull()?.index != currentPage && visible.lastOrNull()?.index != currentPage
        if (!showing) railState.animateScrollToItem((currentPage - 1).coerceAtLeast(0))
    }
    LazyColumn(
        state = railState,
        modifier = modifier.background(SheafColors.Surface),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(count = state.pageCount, key = { "${state.generation}:rail:$it" }) { index ->
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

internal fun aspectOf(size: PageSize?): Float =
    size?.aspectRatio?.takeIf { it.isFinite() && it > 0f } ?: (595.3f / 841.9f)

/** Whole render steps. A page drawn at 2x and shown at 1.6x is sharp; the reverse is not. */
internal fun renderStepFor(zoom: Float): Float = when {
    zoom <= 1.25f -> 1f
    zoom <= 2.25f -> 2f
    else -> 3f
}

internal val PAGE_MARGIN = 12.dp
internal val PAGE_GAP = 12.dp

private val HANDLE_TOUCH = 36.dp
private val HANDLE_DOT = 18.dp

/**
 * The wash over a search match.
 *
 * Translucent rather than solid, because the point is to find the words again, and a solid
 * band would hide the very text it is pointing at. The stepped-to page gets the stronger wash,
 * which is what tells the reader which of the matches the arrows have brought them to.
 */
private val MATCH_MARK = Color(0x55E0257A)
private val MATCH_MARK_STEPPED = Color(0x99FFC400)

/**
 * The selected words: the blue wash text selection has everywhere else on the phone, so it
 * reads as a selection at once, and light enough to read the words through.
 */
private val SELECTION_BAND = Color(0x553D7BFF)

/** The rule under a link. */
private val LINK_LINE = Color(0x803D7BFF)
