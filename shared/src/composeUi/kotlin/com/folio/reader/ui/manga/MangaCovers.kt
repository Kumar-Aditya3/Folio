package com.folio.reader.ui.manga

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.folio.reader.manga.MangaBackend
import com.folio.reader.ui.components.COVER_TARGET_WIDTH_PX
import com.folio.reader.ui.components.decodeCoverImage
import com.folio.reader.ui.components.folioShimmer
import com.folio.reader.ui.components.coverHalo
import com.folio.reader.ui.theme.FolioShapes
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens
import com.folio.reader.ui.theme.atmosphere
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

private const val MANGA_COVER_CACHE_MAX_BYTES = 48L * 1024L * 1024L

// Cap concurrent cover fetch+decode. A multi-source ("all sources") result grid can
// list hundreds of covers, and each MangaCover cell launches its own load; without a
// gate that fans out into hundreds of simultaneous network fetches and full-size
// bitmap decodes — the memory spike that got the app reclaimed under OEM low-memory
// pressure while browsing. Six in flight keeps the visible grid filling quickly while
// bounding peak memory.
private val coverLoadGate = Semaphore(6)

// Access-order LRU bounded by decoded-bitmap bytes (not entry count), guarded by its own monitor.
// The old structure never cached local (coverPath) covers and evicted FIFO by count, so scrolling a
// CBZ library re-read+re-decoded every local cover each pass and a count bound could hold hundreds
// of MB of full-size bitmaps resident.
private val mangaCoverCache = LinkedHashMap<String, ImageBitmap>(64, 0.75f, true)
private var mangaCoverBytes = 0L

private fun coverBytesOf(bmp: ImageBitmap): Long = bmp.width.toLong() * bmp.height.toLong() * 4L

private fun cachedCover(key: String): ImageBitmap? = synchronized(mangaCoverCache) { mangaCoverCache[key] }

private fun cacheCover(key: String, bmp: ImageBitmap) {
    synchronized(mangaCoverCache) {
        val prev = mangaCoverCache.put(key, bmp)
        if (prev != null) mangaCoverBytes -= coverBytesOf(prev)
        mangaCoverBytes += coverBytesOf(bmp)
        val it = mangaCoverCache.entries.iterator()
        // accessOrder=true iterates least-recently-used first, so this drops the coldest covers.
        while (mangaCoverBytes > MANGA_COVER_CACHE_MAX_BYTES && mangaCoverCache.size > 1 && it.hasNext()) {
            val e = it.next()
            mangaCoverBytes -= coverBytesOf(e.value)
            it.remove()
        }
    }
}

private suspend fun loadMangaCover(
    backend: MangaBackend,
    sourceId: Long,
    thumbnailUrl: String?,
    coverPath: String?,
): ImageBitmap? {
    coverPath?.let { path ->
        val file = java.io.File(path)
        if (file.isFile) {
            // Cache local covers too, keyed on path+mtime, so a scroll pass doesn't re-read and
            // re-decode every locally-imported (CBZ) cover.
            val key = "local:$path:${file.lastModified()}"
            cachedCover(key)?.let { return it }
            val bmp = coverLoadGate.withPermit {
                // Re-check under the permit: another cell may have loaded it while we waited.
                cachedCover(key)
                    ?: withContext(Dispatchers.IO) { decodeCoverImage(file.readBytes(), COVER_TARGET_WIDTH_PX) }
            } ?: return null
            cacheCover(key, bmp)
            return bmp
        }
    }
    val key = "$sourceId:$thumbnailUrl"
    cachedCover(key)?.let { return it }
    if (thumbnailUrl.isNullOrBlank()) return null
    // Gate fetch+decode so a large multi-source grid can't spike memory (see coverLoadGate).
    return coverLoadGate.withPermit {
        cachedCover(key)?.let { return@withPermit it }
        // Disk before network: a cover seen in any previous session is a plain file
        // read, which is what keeps library thumbnails loaded across cold starts.
        // Only a genuine miss goes to the source, and its bytes are written through
        // so the next start starts warm.
        var bytes = MangaCoverDiskCache.read(key)
        if (bytes == null) {
            bytes = try {
                backend.fetchCover(sourceId, thumbnailUrl)
            } catch (_: Throwable) {
                null
            }
            if (bytes != null) MangaCoverDiskCache.write(key, bytes)
        }
        val raw = bytes ?: return@withPermit null
        val bitmap = withContext(Dispatchers.IO) { decodeCoverImage(raw, COVER_TARGET_WIDTH_PX) }
            ?: return@withPermit null
        cacheCover(key, bitmap)
        bitmap
    }
}

@Composable
fun MangaCover(
    backend: MangaBackend,
    sourceId: Long,
    thumbnailUrl: String?,
    coverPath: String? = null,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
) {
    var bitmap by remember(sourceId, thumbnailUrl, coverPath) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(sourceId, thumbnailUrl, coverPath) { mutableStateOf(false) }
    LaunchedEffect(sourceId, thumbnailUrl, coverPath) {
        bitmap = loadMangaCover(backend, sourceId, thumbnailUrl, coverPath)
        failed = bitmap == null
    }
    val colors = FolioTheme.colors
    Box(
        modifier = modifier.background(colors.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        when {
            bitmap != null -> Image(
                bitmap = bitmap!!,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                colorFilter = if (dimmed)
                    androidx.compose.ui.graphics.ColorFilter.colorMatrix(
                        androidx.compose.ui.graphics.ColorMatrix().apply { setToSaturation(0.35f) }
                    )
                else null,
            )
            failed -> Icon(
                imageVector = Icons.AutoMirrored.Filled.MenuBook,
                contentDescription = null,
                tint = colors.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(28.dp),
            )
            // Pending: shimmer the whole plate rather than centre a spinner in it.
            // A grid of spinners reports twenty separate activities; a grid of
            // shimmering plates reports one grid still filling in, and it is the
            // plate the cover is about to occupy.
            else -> Box(
                modifier = Modifier
                    .matchParentSize()
                    .folioShimmer(RectangleShape),
            )
        }
        if (dimmed && bitmap != null) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(colors.scrim.copy(alpha = 0.42f)),
            )
        }
    }
}

/**
 * A manga cover as a physical plate — mirroring [FolioCoverPlate] so manga
 * shelves read identically to book shelves: same trim, spine, contact shadow,
 * and optional halo.
 */
@Composable
fun MangaCoverPlate(
    backend: MangaBackend,
    sourceId: Long,
    thumbnailUrl: String?,
    coverPath: String? = null,
    modifier: Modifier = Modifier,
    /**
     * Fixed plate width; height follows at [FolioTokens.coverAspect]. Pass `null`
     * to fill the parent's width instead and derive the height from the same
     * aspect — which is what a grid cell needs.
     */
    width: Dp? = FolioTokens.coverShelf,
    shape: androidx.compose.ui.graphics.Shape = FolioShapes.plate,
    halo: Color? = null,
    elevation: Dp = FolioTokens.elevationVeil,
    dimmed: Boolean = false,
    overlay: (@Composable androidx.compose.foundation.layout.BoxScope.() -> Unit)? = null,
) {
    val atmos = FolioTheme.atmosphere
    val sizing = if (width != null) {
        Modifier.width(width).height(width * FolioTokens.coverAspect)
    } else {
        Modifier.fillMaxWidth().aspectRatio(1f / FolioTokens.coverAspect)
    }
    Box(
        modifier = modifier
            .then(sizing)
            .then(if (halo != null) Modifier.coverHalo(halo, strength = 0.30f) else Modifier)
            .shadow(
                elevation = elevation * atmos.shadowScale,
                shape = shape,
                ambientColor = atmos.shadowAmbient,
                spotColor = atmos.shadowSpot,
            )
            .clip(shape),
    ) {
        MangaCover(
            backend = backend,
            sourceId = sourceId,
            thumbnailUrl = thumbnailUrl,
            coverPath = coverPath,
            modifier = Modifier.fillMaxSize(),
            dimmed = dimmed,
        )
        // The spine: the cue that separates a book from a picture.
        Box(
            Modifier
                .fillMaxWidth(0.055f)
                .fillMaxHeight()
                .background(
                    Brush.horizontalGradient(
                        listOf(Color.Black.copy(alpha = 0.30f), Color.Transparent),
                    ),
                ),
        )
        // A hairline keeps a white cover from dissolving into a light page.
        Box(
            Modifier
                .matchParentSize()
                .border(0.5.dp, Color.Black.copy(alpha = if (atmos.isDark) 0.45f else 0.16f), shape),
        )
        overlay?.invoke(this)
    }
}
