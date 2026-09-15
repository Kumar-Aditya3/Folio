package com.folio.reader.ui.manga

import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.forEachGesture
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.folio.reader.manga.MangaChapter
import com.folio.reader.manga.MangaPageRef
import com.folio.reader.ui.theme.FolioTokens
import kotlinx.coroutines.launch

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun WebtoonReader(
    viewModel: MangaReaderViewModel,
    pages: List<MangaPageRef>,
    onPageKeyVisible: (String) -> Unit,
    onTap: () -> Unit,
    onLongPressPage: (Int) -> Unit,
    extendingForward: Boolean,
    targetWidthPx: Int,
) {
    // Seed from the saved position so the flow opens exactly where the reader left off
    // instead of reporting page 0 and clobbering the resume index.
    val initialIndex = remember { viewModel.currentIndex.value }
    val zoom by viewModel.zoom.collectAsState()
    val listState = rememberLazyListState(initialIndex)
    val hState = rememberScrollState()
    // The scrollers' shared viewport frame. The page-level pinch handler sits
    // deep inside the list, so its centroids arrive in window space and are
    // mapped back into this frame; the reader-level fallback's Box *is* the
    // frame, so its local centroids pass straight through.
    var viewportCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }

    // Webtoon zoom is a layout change: every item width/height scales with
    // zoom, so the exact compensation for the content under the pinch
    // centroid is (ratio - 1) * (offset + centroid) on each axis. Applied
    // in the same pointer event, after setZoom and before the next layout
    // pass, the anchor stays pinned on screen. Both pinch handlers (the
    // page-level winner inside the list and the reader-level fallback) and
    // the double-tap reset route through here.
    fun applyWebtoonZoom(ratio: Float, centroidInWindow: Offset) {
        val frame = viewportCoords
        val centroid = if (frame != null) frame.windowToLocal(centroidInWindow) else centroidInWindow
        val oldZoom = viewModel.zoom.value
        viewModel.setZoom(oldZoom * ratio)
        val applied = viewModel.zoom.value / oldZoom
        if (applied == 1f) return
        val anchorX = hState.value + centroid.x
        val anchorY = listState.firstVisibleItemScrollOffset + centroid.y
        hState.dispatchRawDelta(zoomScrollDelta(applied, anchorX))
        listState.dispatchRawDelta(zoomScrollDelta(applied, anchorY))
    }

    // Reader-level (fallback) handler: centroid local to the outer Box == the
    // viewport frame, so use it directly.
    fun applyWebtoonZoomLocal(ratio: Float, centroidLocal: Offset) {
        applyWebtoonZoom(ratio, viewportCoords?.localToWindow(centroidLocal) ?: centroidLocal)
    }

    // Double-tap reset: same compensation with k = 1/oldZoom and the tap
    // point as anchor, so resetting out of a zoom doesn't jump the strip.
    fun resetZoomAt(centroidInWindow: Offset) {
        val oldZoom = viewModel.zoom.value
        if (oldZoom == 1f) return
        applyWebtoonZoom(1f / oldZoom, centroidInWindow)
    }

    // The reading position is the page at the top edge of the viewport, tracked by its
    // stable key. Composition/prefetch used to report pages the reader had not reached
    // yet, which crossed chapter boundaries early and stranded chapters as unread.
    LaunchedEffect(listState) {
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo
                .firstOrNull { item -> item.key is String && (item.key as String).lastIndexOf(':') > 0 }
                ?.key as? String
        }.collect { key ->
            if (key != null) onPageKeyVisible(key)
        }
    }
    // Only explicit seeks (resume, slider) scroll the list. Scrolling off the position
    // tracker itself snapped the viewport whenever a prepend shifted every index or the
    // loading item churned — the visible page jumps while reading.
    LaunchedEffect(Unit) {
        viewModel.seekRequests.collect { target ->
            listState.scrollToItem(target.coerceIn(0, (viewModel.pages.value.size - 1).coerceAtLeast(0)))
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewport = maxWidth
        val density = LocalDensity.current
        val columnWidth = viewport * maxOf(zoom, 1f)
        val imageWidth = viewport * zoom
        // Unloaded pages reserve the viewport height (or their learned exact height
        // inside ReaderPage) so the list's geometry stays truthful while images load.
        val placeholderHeight = viewport
        val placeholderWidthPx = with(density) { imageWidth.roundToPx() }
        Box(
            Modifier
                .fillMaxSize()
                .onGloballyPositioned { viewportCoords = it }
                .pinchZoom(
                    viewModel,
                    fallback = true,
                    onZoom = { ratio, centroid -> applyWebtoonZoomLocal(ratio, centroid) },
                )
                .horizontalScroll(hState)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.width(columnWidth).fillMaxHeight(),
            ) {
                items(pages.size, key = { viewModel.pageKey(it) }) { index ->
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        ReaderPage(
                            viewModel = viewModel,
                            index = index,
                            modifier = Modifier.width(imageWidth),
                            zoomable = false,
                            targetWidthPx = targetWidthPx,
                            placeholderHeight = placeholderHeight,
                            placeholderWidthPx = placeholderWidthPx,
                            onTap = { onTap() },
                            onDoubleTap = { resetZoomAt(it) },
                            onLongPress = { onLongPressPage(index) },
                        )
                    }
                }
                if (extendingForward) {
                    item(key = "loading-indicator") {
                        Box(Modifier.fillMaxWidth().padding(FolioTokens.space4), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(32.dp))
                        }
                    }
                }
                if (!extendingForward && viewModel.isAtEndOfNavList()) {
                    item(key = "end-of-chapters") {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(FolioTokens.space4),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text("End of chapters", color = Color.White.copy(alpha = 0.7f))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun PagedReader(
    viewModel: MangaReaderViewModel,
    pages: List<MangaPageRef>,
    rtl: Boolean,
    onPageChanged: (Int) -> Unit,
    onTap: () -> Unit,
    onLongPressPage: (Int) -> Unit,
    onOpenChapter: (MangaChapter) -> Unit,
    targetWidthPx: Int,
) {
    val pagerState = rememberPagerState(
        initialPage = viewModel.currentIndex.value.coerceIn(0, (pages.size - 1).coerceAtLeast(0)),
        pageCount = { pages.size },
    )
    val scope = rememberCoroutineScope()
    val zoom by viewModel.zoom.collectAsState()

    fun goNext() {
        val current = pagerState.currentPage
        if (current < pages.size - 1) scope.launch { pagerState.animateScrollToPage(current + 1) }
    }

    fun goPrev() {
        val current = pagerState.currentPage
        if (current > 0) {
            scope.launch { pagerState.animateScrollToPage(current - 1) }
        } else {
            viewModel.previousBeyond()?.let { onOpenChapter(it) }
        }
    }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { onPageChanged(it) }
    }
    // Explicit seeks (resume, slider) drive the pager; the position tracker itself
    // never scrolls, so natural page turns cannot fight back.
    LaunchedEffect(Unit) {
        viewModel.seekRequests.collect { target ->
            pagerState.scrollToPage(target.coerceIn(0, (viewModel.pages.value.size - 1).coerceAtLeast(0)))
        }
    }

    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        reverseLayout = rtl,
        beyondViewportPageCount = 1,
        // While zoomed the pan gesture owns the finger; paging returns after reset.
        userScrollEnabled = zoom <= 1f,
    ) { index ->
        ReaderPage(
            viewModel = viewModel,
            index = index,
            modifier = Modifier.fillMaxSize(),
            fit = true,
            zoomable = true,
            tapZones = true,
            rtl = rtl,
            targetWidthPx = targetWidthPx,
            onTap = { zone ->
                when (zone) {
                    -1 -> if (rtl) goNext() else goPrev()
                    1 -> if (rtl) goPrev() else goNext()
                    else -> onTap()
                }
            },
            onDoubleTap = { viewModel.resetZoom() },
            onLongPress = { onLongPressPage(index) },
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun VerticalReader(
    viewModel: MangaReaderViewModel,
    pages: List<MangaPageRef>,
    onPageChanged: (Int) -> Unit,
    onTap: () -> Unit,
    onLongPressPage: (Int) -> Unit,
    onOpenChapter: (MangaChapter) -> Unit,
    targetWidthPx: Int,
) {
    val pagerState = rememberPagerState(
        initialPage = viewModel.currentIndex.value.coerceIn(0, (pages.size - 1).coerceAtLeast(0)),
        pageCount = { pages.size },
    )
    val zoom by viewModel.zoom.collectAsState()

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { onPageChanged(it) }
    }
    // Explicit seeks (resume, slider) drive the pager; the position tracker itself
    // never scrolls, so natural page turns cannot fight back.
    LaunchedEffect(Unit) {
        viewModel.seekRequests.collect { target ->
            pagerState.scrollToPage(target.coerceIn(0, (viewModel.pages.value.size - 1).coerceAtLeast(0)))
        }
    }

    VerticalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        beyondViewportPageCount = 1,
        userScrollEnabled = zoom <= 1f,
    ) { index ->
        ReaderPage(
            viewModel = viewModel,
            index = index,
            modifier = Modifier.fillMaxSize(),
            fit = true,
            zoomable = true,
            targetWidthPx = targetWidthPx,
            onTap = { onTap() },
            onDoubleTap = { viewModel.resetZoom() },
            onLongPress = { onLongPressPage(index) },
        )
    }
}

/**
 * Pinch-to-zoom for the whole reading flow. Pages sit below the scroll containers, so
 * the page-level handler (fallback = false) wins gesture arbitration; the reader adds a
 * second handler above the scrollers (fallback = true) that only acts when the inner
 * one was starved, so pinch in/out never dies mid-session.
 *
 * [onZoom] receives each step's zoom ratio and the gesture centroid; the default just
 * scales the shared zoom (paged modes, where zoom is a graphicsLayer transform).
 * Webtoon passes a compensating lambda instead: there zoom is a *layout* change, and
 * without compensation the content under the centroid slides by
 * [zoomScrollDelta] on every step.
 */
internal fun Modifier.pinchZoom(
    viewModel: MangaReaderViewModel,
    fallback: Boolean,
    onPinch: (Boolean) -> Unit = {},
    onZoom: ((ratio: Float, centroidLocal: Offset) -> Unit)? = null,
): Modifier =
    // Keyed on Unit (as before), never on onZoom: a pinch recomposes the tree
    // every step (zoom is state); a lambda key would kill the gesture mid-pinch.
    // The captured handler's references are stable, so a stale closure reads
    // the same live state.
    pointerInput(Unit) {
        forEachGesture {
            awaitPointerEventScope {
                awaitFirstDown(requireUnconsumed = false)
                var previousDistance: Float? = null
                var pinching = false
                do {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.filter { it.pressed }
                    val active = pressed.size >= 2 && (!fallback || event.changes.none { it.isConsumed })
                    if (active) {
                        if (!pinching) {
                            pinching = true
                            onPinch(true)
                        }
                        val dx = pressed[0].position.x - pressed[1].position.x
                        val dy = pressed[0].position.y - pressed[1].position.y
                        val distance = kotlin.math.hypot(dx, dy)
                        val centroid = Offset(
                            pressed.map { it.position.x }.average().toFloat(),
                            pressed.map { it.position.y }.average().toFloat(),
                        )
                        event.changes.forEach { it.consume() }
                        val prev = previousDistance
                        if (prev != null && prev > 0f && distance > 0f) {
                            val handler = onZoom
                            if (handler != null) {
                                handler(distance / prev, centroid)
                            } else {
                                viewModel.setZoom(viewModel.zoom.value * (distance / prev))
                            }
                        }
                        previousDistance = distance
                    } else if (pinching) {
                        // Pinch tail: a finger lifted mid-gesture. Keep consuming
                        // every remaining event so the surviving finger's movement
                        // cannot scroll the list (post-pinch drift); zoom itself
                        // only updates while two pointers are down.
                        event.changes.forEach { it.consume() }
                        previousDistance = null
                    } else if (pressed.size < 2) {
                        previousDistance = null
                    }
                } while (event.changes.any { it.pressed })
                if (pinching) onPinch(false)
            }
        }
    }

/**
 * Exact scroll compensation for one layout-zoom step. Every webtoon item height
 * scales linearly with the zoom ratio, so the content at `anchorPx` (measured
 * from the scroll origin, in pre-zoom pixels) ends up displaced by
 * `(ratio - 1) * anchorPx`; dispatching the same amount of raw scroll in the
 * same pointer event — after `setZoom`, before the next layout pass — keeps the
 * anchor pinned on screen. The order (zoom, then delta) is what makes it exact.
 */
internal fun zoomScrollDelta(ratio: Float, anchorPx: Float): Float = (ratio - 1f) * anchorPx
