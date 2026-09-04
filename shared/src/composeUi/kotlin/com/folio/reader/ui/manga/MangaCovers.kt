package com.folio.reader.ui.manga

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.folio.reader.manga.MangaBackend
import com.folio.reader.ui.components.decodeCoverImage
import com.folio.reader.ui.components.folioShimmer
import com.folio.reader.ui.theme.FolioTheme
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val mangaCoverCache = ConcurrentHashMap<String, ImageBitmap>()
private val mangaCoverOrder = ConcurrentLinkedDeque<String>()
private const val MANGA_COVER_CACHE_MAX = 150

private suspend fun loadMangaCover(
    backend: MangaBackend,
    sourceId: Long,
    thumbnailUrl: String?,
    coverPath: String?,
): ImageBitmap? {
    coverPath?.let { path ->
        val file = java.io.File(path)
        if (file.isFile) {
            return withContext(Dispatchers.IO) { decodeCoverImage(file.readBytes()) }
        }
    }
    val key = "$sourceId:$thumbnailUrl"
    mangaCoverCache[key]?.let { return it }
    if (thumbnailUrl.isNullOrBlank()) return null
    val bytes = try {
        backend.fetchCover(sourceId, thumbnailUrl)
    } catch (_: Throwable) {
        null
    } ?: return null
    val bitmap = withContext(Dispatchers.IO) { decodeCoverImage(bytes) } ?: return null
    if (mangaCoverCache.size >= MANGA_COVER_CACHE_MAX) {
        mangaCoverOrder.pollFirst()?.let { mangaCoverCache.remove(it) }
    }
    mangaCoverCache[key] = bitmap
    mangaCoverOrder.addLast(key)
    return bitmap
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
                imageVector = Icons.Filled.MenuBook,
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
