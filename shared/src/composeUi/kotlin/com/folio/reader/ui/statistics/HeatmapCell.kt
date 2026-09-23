package com.folio.reader.ui.statistics

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.folio.reader.ui.theme.FolioTheme

/**
 * §12.5: the Stats heatmap's cell — tints with the caller's accent role, scaled
 * by intensity. [accent] null falls back to the theme's accentProgress on the same
 * alpha ladder.
 */
@Composable
internal fun HeatmapCell(
    intensity: Int,
    size: Dp = 12.dp,
    onClick: (() -> Unit)? = null,
    accent: Color? = null
) {
    val base = MaterialTheme.colorScheme.surfaceVariant
    val colors = if (accent != null) {
        listOf(
            base,
            accent.copy(alpha = 0.4f),
            accent.copy(alpha = 0.6f),
            accent.copy(alpha = 0.8f),
            accent
        )
    } else {
        val default = FolioTheme.colors.accentProgress
        listOf(
            base,
            default.copy(alpha = 0.4f),
            default.copy(alpha = 0.6f),
            default.copy(alpha = 0.8f),
            default
        )
    }

    Box(
        modifier = Modifier
            .size(size)
            .then(
                if (onClick != null) {
                    Modifier.pointerInput(intensity) {
                        detectTapGestures(onTap = { onClick() })
                    }
                } else {
                    Modifier
                }
            )
            .background(colors[intensity.coerceIn(0, 4)], RoundedCornerShape(3.dp))
    )
}
