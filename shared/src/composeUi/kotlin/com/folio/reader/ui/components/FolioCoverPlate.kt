package com.folio.reader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.folio.reader.ui.theme.FolioShapes
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens
import com.folio.reader.ui.theme.atmosphere

/**
 * A cover as a physical object rather than an image in a box.
 *
 * Three things make the difference, and all three are cheap:
 *  1. **Plate shape** — 2–5dp asymmetric corners. A 12dp radius reads as a
 *     widget; a printed trim is nearly square.
 *  2. **A spine** — a narrow dark gradient down the leading edge, which is what
 *     the eye uses to read "book" instead of "thumbnail".
 *  3. **A contact shadow** — tight, offset down, in the palette's own darkness.
 *     Covers lie *on* the page, so their shadow is short, not a 14dp bloom.
 *
 * [halo] additionally spills the cover's own dominant colour onto the surface
 * behind it, which is how the artwork starts lighting its neighbourhood. Off by
 * default: only the anchored and featured covers earn it.
 */
@Composable
fun FolioCoverPlate(
    coverPath: String?,
    title: String,
    author: String,
    modifier: Modifier = Modifier,
    /**
     * Fixed plate width; height follows at [FolioTokens.coverAspect]. Pass `null`
     * to fill the parent's width instead and derive the height from the same
     * aspect — which is what a grid cell needs, since a fixed width plus
     * `fillMaxWidth()` from the caller would stretch the width while the height
     * stayed at the token's, squashing the trim to a square.
     */
    width: Dp? = FolioTokens.coverShelf,
    shape: Shape = FolioShapes.plate,
    halo: Color? = null,
    elevation: Dp = 8.dp,
    small: Boolean = false,
    overlay: (@Composable BoxScope.() -> Unit)? = null,
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
        BookCover(coverPath = coverPath, title = title, author = author, small = small)
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
