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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
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
        val columnWidth = viewport * maxOf(zoom, 1f)
        val imageWidth = viewport * zoom
        Box(Modifier.fillMaxSize().pinchZoom(viewModel, fallback = true).horizontalScroll(hState)) {
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
                            onTap = { onTap() },
                            onDoubleTap = { viewModel.resetZoom() },
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
 */
internal fun Modifier.pinchZoom(
    viewModel: MangaReaderViewModel,
    fallback: Boolean,
    onPinch: (Boolean) -> Unit = {},
): Modifier =
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
                        event.changes.forEach { it.consume() }
                        val prev = previousDistance
                        if (prev != null && prev > 0f && distance > 0f) {
                            viewModel.setZoom(viewModel.zoom.value * (distance / prev))
                        }
                        previousDistance = distance
                    } else if (pressed.size < 2) {
                        previousDistance = null
                    }
                } while (event.changes.any { it.pressed })
                if (pinching) onPinch(false)
            }
        }
    }
