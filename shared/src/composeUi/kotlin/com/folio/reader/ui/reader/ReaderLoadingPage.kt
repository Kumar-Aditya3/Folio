package com.folio.reader.ui.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.folio.reader.settings.ReaderSettings
import com.folio.reader.settings.Theme

/** Loading shares the destination's paper, margins and leading; there is no foreign spinner. */
@Composable
internal fun ReaderLoadingPage(settings: ReaderSettings, modifier: Modifier = Modifier) {
    val theme = settings.customTheme ?: Theme.getPreset(settings.themeId)
    val ink = Color(theme.primaryText)
    Canvas(modifier.fillMaxSize().background(Color(theme.background))
        .semantics { contentDescription = "Preparing your reading page" }
        .padding(start = settings.margins.left.dp, end = settings.margins.right.dp,
            top = settings.margins.top.dp, bottom = settings.margins.bottom.dp)) {
        val leading = (settings.fontSize * settings.lineHeight).dp.toPx().coerceAtLeast(14.dp.toPx())
        val thickness = (settings.fontSize * .3f).dp.toPx()
        val widths = listOf(.96f, 1f, .94f, .98f, .63f, 0f, 1f, .97f, .93f, 1f, .72f, 0f)
        var y = leading
        var index = 0
        while (y + thickness < size.height) {
            val width = widths[index % widths.size]
            if (width > 0) drawRoundRect(ink.copy(alpha = .07f), Offset(0f, y),
                Size(size.width * width, thickness), CornerRadius(thickness / 2))
            y += leading
            index++
        }
    }
}
