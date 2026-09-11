package com.folio.reader.ui.document

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.folio.reader.ui.theme.FolioShapes
import com.folio.reader.ui.theme.FolioTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

private const val PDF_CACHE_BYTES = 96L * 1024L * 1024L
private const val MAX_RENDER_DIMENSION = 8192
private const val MAX_RENDER_PIXELS = 16_777_216L

@Composable
internal fun FixedPageSurfaceImpl(
    path: String,
    documentId: String,
    pageIndex: Int,
    mode: DocumentReaderMode,
    rotationDegrees: Int,
    resetZoomKey: Int,
    modifier: Modifier,
    onDocumentOpened: (Int) -> Unit,
    onCurrentPageChanged: (Int) -> Unit,
    onTap: () -> Unit,
    onError: (DocumentReaderError) -> Unit
) {
    var document by remember(path) { mutableStateOf<FixedPageDocument?>(null) }
    val cache = remember(path) {
        PixelBudgetLruCache<FixedPageCacheKey, ImageBitmap>(PDF_CACHE_BYTES) {
            it.width.toLong() * it.height.toLong() * 4L
        }
    }
    LaunchedEffect(path) {
        try {
            val opened = openFixedPageDocument(path)
            try {
                coroutineContext.ensureActive()
                document = opened
                onDocumentOpened(opened.pageCount)
            } catch (cancelled: CancellationException) {
                opened.close()
                throw cancelled
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            error.toReaderErrorOrNull()?.let(onError)
        }
    }
    DisposableEffect(path) {
        onDispose {
            document?.close()
            document = null
            cache.clear()
        }
    }

    val opened = document
    if (opened == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = FolioTheme.colors.primary)
        }
        return
    }

    when (mode) {
        DocumentReaderMode.SINGLE_PAGE -> RenderedPdfPage(
            document = opened,
            documentId = documentId,
            pageIndex = pageIndex.coerceIn(0, opened.pageCount - 1),
            mode = mode,
            rotationDegrees = rotationDegrees,
            resetZoomKey = resetZoomKey,
            cache = cache,
            modifier = modifier.fillMaxSize(),
            onCurrentPageChanged = onCurrentPageChanged,
            onTap = onTap,
            onError = onError
        )
        DocumentReaderMode.CONTINUOUS -> {
            val listState = rememberLazyListState()
            var programmaticScrollTarget by remember(mode, opened) { mutableStateOf<Int?>(null) }
            var continuousZoom by remember(mode, resetZoomKey) {
                mutableFloatStateOf(1f)
            }

            LaunchedEffect(mode, opened, pageIndex) {
                val target = pageIndex.coerceIn(0, opened.pageCount - 1)
                if (listState.firstVisibleItemIndex != target) {
                    programmaticScrollTarget = target
                    listState.scrollToItem(target)
                }
            }
            LaunchedEffect(mode, opened, listState) {
                snapshotFlow { listState.firstVisibleItemIndex }
                    .distinctUntilChanged()
                    .collect { visiblePage ->
                        val target = programmaticScrollTarget
                        if (target != null) {
                            if (visiblePage == target) programmaticScrollTarget = null
                        } else {
                            onCurrentPageChanged(
                                visiblePage.coerceIn(0, opened.pageCount - 1)
                            )
                        }
                    }
            }

            val continuousGestureModifier = Modifier.pointerInput(
                mode,
                resetZoomKey
            ) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.size > 1) {
                            continuousZoom = (continuousZoom * event.calculateZoom())
                                .coerceIn(1f, 2.5f)
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }

            LazyColumn(
                modifier = modifier
                    .fillMaxSize()
                    .clip(FolioShapes.inset)
                    .then(continuousGestureModifier)
                    .graphicsLayer {
                        scaleX = continuousZoom
                        scaleY = continuousZoom
                    },
                state = listState,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    vertical = 12.dp
                )
            ) {
                items((0 until opened.pageCount).toList(), key = { it }) { page ->
                    RenderedPdfPage(
                        document = opened,
                        documentId = documentId,
                        pageIndex = page,
                        mode = mode,
                        rotationDegrees = rotationDegrees,
                        resetZoomKey = resetZoomKey,
                        cache = cache,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(0.74f),
                        onCurrentPageChanged = {},
                        onTap = onTap,
                        onError = onError
                    )
                }
            }
        }
    }
}

@Composable
private fun RenderedPdfPage(
    document: FixedPageDocument,
    documentId: String,
    pageIndex: Int,
    mode: DocumentReaderMode,
    rotationDegrees: Int,
    resetZoomKey: Int,
    cache: PixelBudgetLruCache<FixedPageCacheKey, ImageBitmap>,
    modifier: Modifier,
    onCurrentPageChanged: (Int) -> Unit,
    onTap: () -> Unit,
    onError: (DocumentReaderError) -> Unit
) {
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var pageSize by remember(documentId, pageIndex, rotationDegrees) { mutableStateOf(IntSize.Zero) }
    var image by remember(documentId, pageIndex, rotationDegrees) { mutableStateOf<ImageBitmap?>(null) }
    var zoom by remember(mode, pageIndex, resetZoomKey) { mutableFloatStateOf(1f) }
    var offset by remember(mode, pageIndex, resetZoomKey) { mutableStateOf(Offset.Zero) }
    val width = viewportSize.width.coerceIn(1, MAX_RENDER_DIMENSION)
    val height = viewportSize.height.coerceIn(1, MAX_RENDER_DIMENSION)
    val bounded = boundPixels(width, height)
    val rasterSize = IntSize(bounded.first, bounded.second)
    val key = fixedPageRasterKey(documentId, pageIndex, rasterSize, rotationDegrees)

    LaunchedEffect(document, key) {
        cache[key]?.let {
            image = it
            return@LaunchedEffect
        }
        image = null
        try {
            val rendered = withContext(Dispatchers.IO) {
                document.render(FixedPageRenderRequest(pageIndex, bounded.first, bounded.second, rotationDegrees))
            }
            cache.put(key, rendered)
            image = rendered
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            error.toReaderErrorOrNull()?.let(onError)
        }
    }

    val gestureModifier = if (mode == DocumentReaderMode.SINGLE_PAGE) {
        Modifier.pointerInput(pageIndex, viewportSize, pageSize) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                var totalPan = Offset.Zero
                var transformed = false
                do {
                    val event = awaitPointerEvent()
                    val gestureZoom = event.calculateZoom()
                    val pan = event.calculatePan()
                    if (event.changes.size > 1 || abs(gestureZoom - 1f) > 0.01f) transformed = true
                    val nextZoom = (zoom * gestureZoom).coerceIn(1f, 4f)
                    if (transformed || zoom > 1f || nextZoom > 1f) {
                        zoom = nextZoom
                        offset = clampPageOffset(offset + pan, viewportSize, pageSize, zoom)
                        if (zoom == 1f) offset = Offset.Zero
                        event.changes.forEach { it.consume() }
                    } else {
                        totalPan += pan
                    }
                } while (event.changes.any { it.pressed })

                if (!transformed && zoom == 1f) {
                    when (singlePageGesture(totalPan.x, totalPan.y, size.width.toFloat())) {
                        FixedPageGesture.NEXT_PAGE -> onCurrentPageChanged((pageIndex + 1).coerceAtMost(document.pageCount - 1))
                        FixedPageGesture.PREVIOUS_PAGE -> onCurrentPageChanged((pageIndex - 1).coerceAtLeast(0))
                        FixedPageGesture.TAP -> onTap()
                        FixedPageGesture.NONE -> Unit
                    }
                }
            }
        }
    } else Modifier.pointerInput(pageIndex) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var pan = Offset.Zero
            do {
                val event = awaitPointerEvent()
                pan += event.calculatePan()
            } while (event.changes.any { it.pressed })
            if (singlePageGesture(pan.x, pan.y, size.width.toFloat()) == FixedPageGesture.TAP) onTap()
        }
    }

    Box(
        modifier = modifier
            .background(FolioTheme.colors.surfaceVariant)
            .padding(12.dp)
            .clip(FolioShapes.inset)
            .onSizeChanged { viewportSize = it }
            .then(gestureModifier),
        contentAlignment = Alignment.Center
    ) {
        val rendered = image
        if (rendered == null) {
            CircularProgressIndicator(color = FolioTheme.colors.primary)
        } else {
            Image(
                bitmap = rendered,
                contentDescription = "PDF page ${pageIndex + 1}",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().onSizeChanged { pageSize = fittedPageSize(rendered.width, rendered.height, it) }.graphicsLayer {
                    scaleX = zoom
                    scaleY = zoom
                    translationX = offset.x
                    translationY = offset.y
                }
            )
        }
    }
}

internal enum class FixedPageGesture { TAP, NEXT_PAGE, PREVIOUS_PAGE, NONE }

internal fun singlePageGesture(deltaX: Float, deltaY: Float, viewportWidth: Float): FixedPageGesture {
    val threshold = (viewportWidth * 0.16f).coerceIn(48f, 120f)
    return when {
        abs(deltaX) >= threshold && abs(deltaX) > abs(deltaY) * 1.25f ->
            if (deltaX < 0f) FixedPageGesture.NEXT_PAGE else FixedPageGesture.PREVIOUS_PAGE
        abs(deltaX) < 12f && abs(deltaY) < 12f -> FixedPageGesture.TAP
        else -> FixedPageGesture.NONE
    }
}

internal fun fittedPageSize(imageWidth: Int, imageHeight: Int, viewport: IntSize): IntSize {
    if (imageWidth <= 0 || imageHeight <= 0 || viewport.width <= 0 || viewport.height <= 0) return IntSize.Zero
    val scale = minOf(viewport.width.toFloat() / imageWidth, viewport.height.toFloat() / imageHeight)
    return IntSize((imageWidth * scale).toInt(), (imageHeight * scale).toInt())
}

internal fun clampPageOffset(offset: Offset, viewport: IntSize, page: IntSize, zoom: Float): Offset {
    val maxX = ((page.width * zoom - viewport.width) / 2f).coerceAtLeast(0f)
    val maxY = ((page.height * zoom - viewport.height) / 2f).coerceAtLeast(0f)
    return Offset(
        if (maxX == 0f) 0f else offset.x.coerceIn(-maxX, maxX),
        if (maxY == 0f) 0f else offset.y.coerceIn(-maxY, maxY)
    )
}

private fun boundPixels(width: Int, height: Int): Pair<Int, Int> {
    val pixels = width.toLong() * height.toLong()
    if (pixels <= MAX_RENDER_PIXELS) return width to height
    val scale = kotlin.math.sqrt(MAX_RENDER_PIXELS.toDouble() / pixels.toDouble())
    return (width * scale).toInt().coerceAtLeast(1) to (height * scale).toInt().coerceAtLeast(1)
}

internal fun Throwable.toReaderErrorOrNull(): DocumentReaderError? = when (this) {
    is CancellationException -> null
    is FixedPageException -> DocumentReaderError(kind, message ?: "The PDF could not be rendered")
    else -> DocumentReaderError(DocumentReaderErrorKind.RENDER, message ?: "The PDF could not be rendered")
}
