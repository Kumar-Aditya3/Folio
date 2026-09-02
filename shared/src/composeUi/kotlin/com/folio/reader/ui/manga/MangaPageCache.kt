package com.folio.reader.ui.manga

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import com.folio.reader.ui.components.decodePageImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Decoded page bitmaps, bounded by pixel memory rather than entry count. A fixed
 * entry cap evicted full-size pages after a handful of scrolls, forcing a network
 * re-fetch and re-decode every time the reader revisited a page.
 */
private const val BITMAP_CACHE_MAX_BYTES = 96L * 1024 * 1024
private val pageBitmapCache = LinkedHashMap<String, ImageBitmap>(16, 0.75f, true)
private var bitmapCacheBytes = 0L

private fun bitmapByteSize(bitmap: ImageBitmap): Long =
    bitmap.width.toLong() * bitmap.height.toLong() * 4L

private fun pageCacheGet(key: String): ImageBitmap? = synchronized(pageBitmapCache) { pageBitmapCache[key] }

private fun pageCachePut(key: String, bitmap: ImageBitmap) = synchronized(pageBitmapCache) {
    pageBitmapCache[key]?.let { bitmapCacheBytes -= bitmapByteSize(it) }
    pageBitmapCache[key] = bitmap
    bitmapCacheBytes += bitmapByteSize(bitmap)
    while (bitmapCacheBytes > BITMAP_CACHE_MAX_BYTES && pageBitmapCache.isNotEmpty()) {
        val eldest = pageBitmapCache.entries.firstOrNull() ?: break
        pageBitmapCache.remove(eldest.key)
        bitmapCacheBytes -= bitmapByteSize(eldest.value)
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun ReaderPage(
    viewModel: MangaReaderViewModel,
    index: Int,
    modifier: Modifier = Modifier,
    fit: Boolean = false,
    zoomable: Boolean = true,
    tapZones: Boolean = false,
    rtl: Boolean = false,
    targetWidthPx: Int = 0,
    onTap: (Int) -> Unit = {},
    onDoubleTap: () -> Unit = {},
    onLongPress: () -> Unit = {},
) {
    var bitmap by remember(index) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(index) { mutableStateOf(false) }
    val cacheKey = viewModel.pageKey(index)

    LaunchedEffect(index) {
        pageCacheGet(cacheKey)?.let {
            bitmap = it
            return@LaunchedEffect
        }
        val bytes = viewModel.resolvePageImage(index)
        if (bytes == null) {
            failed = true
        } else {
            val decoded = withContext(Dispatchers.IO) { decodePageImage(bytes, targetWidthPx) }
            if (decoded == null) {
                failed = true
            } else {
                pageCachePut(cacheKey, decoded)
                bitmap = decoded
            }
        }
    }

    val zoom by viewModel.zoom.collectAsState()
    var offsetX by remember(index) { mutableFloatStateOf(0f) }
    var offsetY by remember(index) { mutableFloatStateOf(0f) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var pinchActive by remember { mutableStateOf(false) }

    // Zooming back toward fit must recenter: stale pan offsets from a deeper zoom
    // otherwise leave the page translated partly or fully off-screen. An axis with
    // no pan room (content smaller than the viewport, e.g. webtoon zoomed below
    // fit-width) has an inverted clamp range and must recenter outright.
    LaunchedEffect(zoom, viewSize) {
        val maxTx = ((zoom - 1f) * viewSize.width) / 2f
        val maxTy = ((zoom - 1f) * viewSize.height) / 2f
        offsetX = if (maxTx <= 0f) 0f else offsetX.coerceIn(-maxTx, maxTx)
        offsetY = if (maxTy <= 0f) 0f else offsetY.coerceIn(-maxTy, maxTy)
    }

    Box(
        modifier = modifier
            .background(Color.Black)
            // A zoomed sheet must never draw into the neighbouring page.
            .clip(RectangleShape)
            .pinchZoom(viewModel, fallback = false, onPinch = { pinchActive = it })
            .onSizeChanged { viewSize = it }
            .pointerInput(tapZones, rtl) {
                detectTapGestures(
                    onTap = { pos ->
                        val zone = if (!tapZones) 0 else when {
                            pos.x < size.width / 3f -> -1
                            pos.x > size.width * 2f / 3f -> 1
                            else -> 0
                        }
                        onTap(zone)
                    },
                    onDoubleTap = {
                        offsetX = 0f
                        offsetY = 0f
                        onDoubleTap()
                    },
                    // A two-finger gesture is a zoom, not a hold: no note/save sheet.
                    onLongPress = { if (!pinchActive) onLongPress() },
                )
            }
            .then(
                if (zoomable && zoom > 1f) Modifier.pointerInput(zoom, viewSize) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        val maxTx = ((zoom - 1f) * viewSize.width) / 2f
                        val maxTy = ((zoom - 1f) * viewSize.height) / 2f
                        offsetX = (offsetX + dragAmount.x).coerceIn(-maxTx, maxTx)
                        offsetY = (offsetY + dragAmount.y).coerceIn(-maxTy, maxTy)
                    }
                } else Modifier
            ),
        contentAlignment = Alignment.Center,
    ) {
        when {
            bitmap != null -> Image(
                bitmap = bitmap!!,
                contentDescription = null,
                modifier = Modifier
                    .let { m ->
                        if (zoomable) m.graphicsLayer {
                            scaleX = zoom
                            scaleY = zoom
                            translationX = offsetX
                            translationY = offsetY
                        } else m
                    }
                    .fillMaxWidth()
                    .let { base -> if (fit) base.fillMaxSize() else base },
                contentScale = if (fit) ContentScale.Fit else ContentScale.FillWidth,
            )
            failed -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = Color.White.copy(alpha = 0.7f))
                Text(
                    "Page failed to load",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            else -> CircularProgressIndicator(color = Color.White.copy(alpha = 0.8f))
        }
    }
}
