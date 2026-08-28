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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.reader.ui.theme.FolioTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Decoded-cover LRU-ish cache so grid scrolling doesn't re-decode files. */
private val coverCache = ConcurrentHashMap<String, ImageBitmap>()
private val coverCacheOrder = java.util.concurrent.ConcurrentLinkedDeque<String>()
private const val COVER_CACHE_MAX = 120

private fun cachePut(path: String, bitmap: ImageBitmap) {
    if (coverCache.size >= COVER_CACHE_MAX) {
        coverCacheOrder.pollFirst()?.let { coverCache.remove(it) }
    }
    coverCache[path] = bitmap
    coverCacheOrder.addLast(path)
}

fun clearCoverCache() {
    coverCache.clear()
    coverCacheOrder.clear()
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
            val cached = coverCache[path]
            if (cached != null) {
                bitmap = cached
            } else {
                val decoded = withContext(Dispatchers.IO) {
                    runCatching {
                        val file = File(path)
                        if (file.exists() && file.length() > 0) decodeCoverImage(file.readBytes()) else null
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
                contentDescription = contentDescription ?: src,
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
 */
@Composable
fun BookCover(
    coverPath: String?,
    title: String,
    author: String,
    modifier: Modifier = Modifier,
    small: Boolean = false
) {
    var bitmap by remember(coverPath) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(coverPath) { mutableStateOf(false) }

    LaunchedEffect(coverPath) {
        val path = coverPath
        if (path.isNullOrBlank()) {
            failed = true
            return@LaunchedEffect
        }
        val cached = coverCache[path]
        if (cached != null) {
            bitmap = cached
            return@LaunchedEffect
        }
        val decoded = withContext(Dispatchers.IO) {
            runCatching {
                val file = File(path)
                if (file.exists() && file.length() > 0) decodeCoverImage(file.readBytes()) else null
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
        when {
            current != null -> Image(
                bitmap = current,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            failed -> FallbackCover(title, author, small)
            else -> CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/** Generated typographic cover used when no image is available. */
@Composable
fun FallbackCover(title: String, author: String, small: Boolean = false) {
    val palettes = listOf(
        Color(0xFF4F46E5) to Color(0xFF818CF8),
        Color(0xFF0F766E) to Color(0xFF2DD4BF),
        Color(0xFFB45309) to Color(0xFFF59E0B),
        Color(0xFFBE185D) to Color(0xFFF472B6),
        Color(0xFF1D4ED8) to Color(0xFF60A5FA),
        Color(0xFF6D28D9) to Color(0xFFA78BFA)
    )
    val (base, accent) = palettes[
        title.hashCode().let { if (it < 0) -it else it } % palettes.size
    ]

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = androidx.compose.ui.graphics.Brush.linearGradient(
                    colors = listOf(base, accent)
                ),
                shape = RoundedCornerShape(8.dp)
            )
            .padding(if (small) 6.dp else 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title.take(24),
                style = if (small) FolioTheme.typography.labelSmall else FolioTheme.typography.titleMedium,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            if (!small && author.isNotBlank()) {
                Text(
                    text = author.take(24),
                    style = FolioTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.85f),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}