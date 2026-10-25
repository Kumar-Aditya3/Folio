package com.folio.reader.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import com.folio.reader.ui.theme.LocalFolioVibrancy
import com.folio.reader.ui.theme.enrichChroma
import com.folio.reader.settings.FOLIO_VIBRANCY_DEFAULT
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.folio.reader.ui.components.awaitCoverBitmapForAccent
import com.folio.reader.ui.components.folioRoomSource
import com.folio.reader.ui.manga.awaitMangaCoverBitmap
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.atmosphere
import androidx.compose.runtime.Immutable
import androidx.compose.foundation.layout.BoxScope
import com.folio.reader.ui.theme.LocalFolioAmbientTint

/** Describes an existing cover cache entry. It does not own a loader or network client. */
@Immutable
data class FolioArtwork(val key: String, val coverPath: String?, val sourceId: Long? = null,
    val thumbnailUrl: String? = null)

@Composable
fun FolioArtworkScene(artwork: FolioArtwork?, modifier: Modifier = Modifier,
    enabled: Boolean = LocalFolioAmbientTint.current != null,
    content: @Composable BoxScope.() -> Unit) {
    FolioArtworkGlassScene(moving = false) {
        Box(modifier) {
            if (artwork != null) FolioArtworkField(listOf(artwork), animated = false,
                enabled = enabled, strength = .4f, modifier = Modifier.matchParentSize())
            content()
        }
    }
}

/** The shipped projection stays unchanged; vividness affects its light, never the cover image. */
internal fun artworkProjectionSaturation(vibrancy: Float): Float {
    val value = vibrancy.coerceIn(0f, 1f)
    return if (value <= FOLIO_VIBRANCY_DEFAULT) .3f + .7f * value / FOLIO_VIBRANCY_DEFAULT
        else 1f + .2f * (value - FOLIO_VIBRANCY_DEFAULT) / (1f - FOLIO_VIBRANCY_DEFAULT)
}

/** Cached artwork is projected across the scene, feathered into the page at all four edges. */
@Composable
fun FolioArtworkField(
    artworks: List<FolioArtwork>,
    weight: (Int) -> Float = { 1f },
    offset: (Int) -> Float = { 0f },
    animated: Boolean,
    enabled: Boolean,
    strength: Float = 1f,
    modifier: Modifier = Modifier,
) {
    val colors = FolioTheme.colors
    val dark = FolioTheme.atmosphere.isDark
    val saturation = artworkProjectionSaturation(LocalFolioVibrancy.current.level)
    val filter = remember(saturation) {
        if (kotlin.math.abs(saturation - 1f) < .00001f) null
        else ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(saturation) })
    }
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    val accents = listOf(colors.accentProgress, colors.accentDiscovery, colors.accentStreak)
    // The source contains precisely this artwork, not the page, covers, or glass control.
    Box(modifier.clipToBounds().folioRoomSource()
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithCache {
            // Preserve strong artwork around the covers, with a quieter field behind small type.
            val vertical = Brush.verticalGradient(0f to Color.Transparent, .13f to Color.Black,
                .46f to Color.Black, .6f to Color.Black.copy(alpha = .24f),
                .76f to Color.Black.copy(alpha = .24f), .87f to Color.Black.copy(alpha = .48f),
                1f to Color.Transparent)
            val horizontal = Brush.horizontalGradient(0f to Color.Transparent, .16f to Color.Black,
                .84f to Color.Black, 1f to Color.Transparent)
            onDrawWithContent {
                drawContent()
                drawRect(vertical, blendMode = BlendMode.DstIn)
                drawRect(horizontal, blendMode = BlendMode.DstIn)
            }
        }) {
        if (enabled) artworks.forEachIndexed { index, item -> key(item.key) {
            val bitmap by produceState<ImageBitmap?>(null, item) {
                value = if (item.sourceId != null) {
                    awaitMangaCoverBitmap(item.sourceId, item.thumbnailUrl, item.coverPath)
                } else item.coverPath?.let { awaitCoverBitmapForAccent(it, 6_000) }
            }
            val accent = enrichChroma(accents[index % accents.size], saturation)
            val layer = Modifier.fillMaxSize().graphicsLayer {
                val relative = offset(index)
                alpha = weight(index).coerceIn(0f, 1f) * strength.coerceIn(0f, 1f) * if (dark) .62f else .37f
                translationX = if (animated) -relative.coerceIn(-1f, 1f) * size.width * .055f * direction else 0f
                scaleX = 1.12f
                scaleY = 1.12f
            }.blur(22.dp)
            val artwork = bitmap
            if (artwork != null) Image(artwork, contentDescription = null,
                contentScale = ContentScale.Crop, colorFilter = filter, modifier = layer)
            else Box(layer.drawWithCache {
                // Missing artwork gets an abstract jacket composition, without a loading shimmer.
                val wash = Brush.linearGradient(listOf(accent.copy(alpha = .12f), accent,
                    enrichChroma(colors.accentDiscovery, saturation).copy(alpha = .35f)), Offset.Zero, Offset(size.width, size.height))
                onDrawBehind {
                    drawRect(wash)
                    drawLine(colors.background.copy(alpha = .7f), Offset(-size.width * .2f, size.height * .2f),
                        Offset(size.width * 1.2f, size.height * .8f), strokeWidth = size.width * .36f)
                }
            })
        } }
    }
}
