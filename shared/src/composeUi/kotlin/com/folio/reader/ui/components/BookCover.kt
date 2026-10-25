package com.folio.reader.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.reader.ui.theme.FolioTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Decoded-cover cache bounded by decoded bytes (access-order LRU), not entry count. A fixed
 *  120-entry cap could hold hundreds of MB of full-size bitmaps (covers + inline EPUB images
 *  share this cache); byte-bounding caps the resident cost regardless of image size. */
private const val COVER_CACHE_MAX_BYTES = 40L * 1024L * 1024L
private val coverCache = LinkedHashMap<String, ImageBitmap>(64, 0.75f, true)
private var coverCacheBytes = 0L

private fun coverBytesOf(bitmap: ImageBitmap): Long = bitmap.width.toLong() * bitmap.height.toLong() * 4L

private fun cacheGet(path: String): ImageBitmap? = synchronized(coverCache) { coverCache[path] }

private fun cachePut(path: String, bitmap: ImageBitmap) {
    synchronized(coverCache) {
        coverCache.put(path, bitmap)?.let { coverCacheBytes -= coverBytesOf(it) }
        coverCacheBytes += coverBytesOf(bitmap)
        val it = coverCache.entries.iterator()
        while (coverCacheBytes > COVER_CACHE_MAX_BYTES && coverCache.size > 1 && it.hasNext()) {
            val e = it.next()
            coverCacheBytes -= coverBytesOf(e.value)
            it.remove()
        }
    }
}

fun clearCoverCache() {
    synchronized(coverCache) {
        coverCache.clear()
        coverCacheBytes = 0L
    }
}

/**
 * §13.3: the already-decoded cover bitmap for accent sampling — reads the cache
 * only, waiting briefly for [BookCover] to finish decoding the same path. Never
 * decodes here: a second decode path would re-open the v1.0.24 crash class.
 */
internal suspend fun awaitCoverBitmapForAccent(path: String, timeoutMs: Long = 3_000): ImageBitmap? {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        cacheGet(path)?.let { return it }
        kotlinx.coroutines.delay(100)
    }
    return cacheGet(path)
}

/**
 * An inline EPUB image: [src] is resolved against the chapter by [resolve]
 * (platform layer extracts it from the EPUB to a cache file), then decoded and
 * displayed with a fade-in. Falls back to a subtle placeholder while loading.
 */
@Composable
fun EpubImage(
    src: String,
    resolve: suspend (String) -> String?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null
) {
    var bitmap by remember(src) { mutableStateOf<ImageBitmap?>(null) }
    var resolvedPath by remember(src) { mutableStateOf<String?>(null) }

    LaunchedEffect(src) {
        val path = resolve(src)
        resolvedPath = path
        if (path != null) {
            val cached = cacheGet(path)
            if (cached != null) {
                bitmap = cached
            } else {
                val decoded = withContext(Dispatchers.IO) {
                    runCatching {
                        val file = File(path)
                if (file.exists() && file.length() > 0) decodeCoverImage(file.readBytes(), COVER_TARGET_WIDTH_PX) else null
                    }.getOrNull()
                }
                if (decoded != null) {
                    cachePut(path, decoded)
                    bitmap = decoded
                }
            }
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        val current = bitmap
        if (current != null) {
            val aspect = current.width.toFloat() / current.height.toFloat()
            val isSmallDecorative = current.width <= 600 && current.height <= 300
            
            Image(
                bitmap = current,
                // No alt text ⇒ decorative. Falling back to `src` read a raw cache
                // file path/URL aloud to TalkBack, which is noise, not a description.
                contentDescription = contentDescription,
                contentScale = if (isSmallDecorative) ContentScale.Inside else ContentScale.Fit,
                modifier = Modifier
                    .then(
                        if (isSmallDecorative) {
                            // Small decorative images: display at natural size, centered
                            Modifier.wrapContentSize()
                        } else {
                            // Larger images: constrain to width and maintain aspect ratio
                            Modifier.fillMaxWidth().aspectRatio(aspect.coerceIn(0.3f, 4f))
                        }
                    )
            )
        } else {
            // Loading / unresolved: unobtrusive placeholder strip
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                    )
                    .padding(vertical = 32.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/**
 * Displays a book cover loaded from [coverPath] (absolute file path written at
 * import time). Shows a small centred spinner while decoding, the image once
 * loaded, and a generated typographic fallback when the file is missing,
 * unreadable, or [coverPath] is null.
 *
 * [suppressFallbackText] keeps the fallback's gradient but drops its title and
 * author. Morph destinations set it: the paired title is already flying in under
 * a shared key, and drawing it inside the plate as well puts the same words on
 * screen twice. See [FolioCoverPlate].
 */
@Composable
fun BookCover(
    coverPath: String?,
    title: String,
    author: String,
    modifier: Modifier = Modifier,
    small: Boolean = false,
    suppressFallbackText: Boolean = false,
    /**
     * Desaturate the plate — the "already read" cue. A `ColorFilter` rather than a
     * `graphicsLayer { alpha }` on the cell: an alpha layer is an offscreen render pass per card,
     * and a shelf composes a dozen at once, whereas a colour matrix is applied by the draw itself
     * and costs nothing extra. Matches [com.folio.reader.ui.manga.MangaCover]'s `dimmed`.
     */
    dimmed: Boolean = false,
) {
    // A returning shelf cell already has its image. Seed synchronously so it never
    // paints a skeleton while waiting for LaunchedEffect to run.
    val initialBitmap = remember(coverPath) { coverPath?.let(::cacheGet) }
    var bitmap by remember(coverPath) { mutableStateOf(initialBitmap) }
    var failed by remember(coverPath) { mutableStateOf(false) }

    LaunchedEffect(coverPath) {
        val path = coverPath
        if (path.isNullOrBlank()) {
            failed = true
            return@LaunchedEffect
        }
        val cached = cacheGet(path)
        if (cached != null) {
            bitmap = cached
            return@LaunchedEffect
        }
        val decoded = withContext(Dispatchers.IO) {
            runCatching {
                val file = File(path)
                if (file.exists() && file.length() > 0) decodeCoverImage(file.readBytes(), COVER_TARGET_WIDTH_PX) else null
            }.getOrNull()
        }
        if (decoded != null) {
            cachePut(path, decoded)
            bitmap = decoded
        } else {
            failed = true
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        val current = bitmap
        // Covers fade in over 260ms instead of popping: a grid of eight covers
        // snapping in at different moments reads as jank, while a short fade reads
        // as paper developing. Honours reduce-motion via rememberEntryState.
        when {
            current != null -> {
                // Draw-phase, not composition-phase. `rememberEntryProgress` returns
                // the value, which makes this branch recompose on *every frame* of
                // the reveal; `rememberEntryState` is the draw-phase form (see
                // EntryMotion) so the same fade animates without recomposing. The
                // difference is not academic on a shelf: a grid entering the
                // Home→Library morph composes a dozen covers at once, and every one
                // of them used to recompose per frame for 260ms — right through the
                // part of the morph the reader is watching.
                val reveal = if (initialBitmap == null) rememberEntryState(coverPath) else null
                Image(
                    bitmap = current,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    colorFilter = if (dimmed) {
                        ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0.35f) })
                    } else {
                        null
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(8.dp))
                        .graphicsLayer { alpha = reveal?.value ?: 1f }
                )
            }
            failed -> FallbackCover(title, author, small, suppressText = suppressFallbackText)
            else -> Box(
                // A shimmering plate, not a spinner: covers land in a cell that was
                // already drawn its shape, so a grid fades in as one surface instead
                // of popping in behind individual spinners.
                modifier = Modifier
                    .fillMaxSize()
                    .folioShimmer(RoundedCornerShape(8.dp))
            )
        }
    }
}

/**
 * Generated typographic cover used when no image is available.
 *
 * [suppressText] drops the title and author but keeps the gradient, which is what
 * a morph destination wants — see [BookCover].
 */
@Composable
fun FallbackCover(
    title: String,
    author: String,
    small: Boolean = false,
    suppressText: Boolean = false,
) {
    val binding = remember(title) { bindingPalette(title) }
    val titleInk = Color(0xFFFFF2D8)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(8.dp))
            .folioBinding(binding)
            .padding(horizontal = if (small) 8.dp else 18.dp, vertical = if (small) 6.dp else 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (suppressText) return@Column
            Text(
                text = title,
                style = if (small) FolioTheme.typography.labelSmall else FolioTheme.typography.titleMedium,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                color = titleInk,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            if (!small && author.isNotBlank()) {
                Text(
                    text = author,
                    style = FolioTheme.typography.labelSmall,
                    color = titleInk.copy(alpha = 0.85f),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}
