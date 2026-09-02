package com.folio.reader.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.folio.reader.settings.ReaderSettings
import com.folio.reader.settings.TextAlignment
import com.folio.reader.settings.Theme
import com.folio.reader.ui.components.systemFontFamily

@Composable
fun SettingsLivePreview(settings: ReaderSettings) {
    val theme = settings.customTheme ?: Theme.getPreset(settings.themeId)
    val fontFamily = systemFontFamily(settings.fontFamily)
    val align = when (settings.alignment) {
        TextAlignment.CENTER -> TextAlign.Center
        TextAlignment.JUSTIFIED -> TextAlign.Justify
        else -> TextAlign.Start
    }
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(shape)
            .background(Color(theme.background))
            .border(1.dp, Color(theme.divider), shape)
            .padding(
                horizontal = settings.margins.left.coerceIn(0f, 28f).dp,
                vertical = 18.dp
            )
    ) {
        Text(
            text = "Chapter One",
            style = TextStyle(
                fontFamily = fontFamily,
                fontSize = (settings.fontSize * 1.3f).sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = settings.letterSpacing.sp,
                color = Color(theme.headingText),
                textAlign = align
            )
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "The reading room was quiet but for the rain, and the lamplight had " +
                "made a small country of its own on the table, where the pages waited " +
                "for someone to turn them.",
            style = TextStyle(
                fontFamily = fontFamily,
                fontSize = settings.fontSize.sp,
                lineHeight = (settings.fontSize * settings.lineHeight).sp,
                fontWeight = FontWeight(settings.fontWeight),
                letterSpacing = settings.letterSpacing.sp,
                color = Color(theme.primaryText),
                textAlign = align
            )
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "${settings.fontFamily} · ${settings.fontSize.toInt()}sp · " +
                "${"%.1f".format(settings.lineHeight)} line · ${theme.name}",
            style = TextStyle(fontSize = 11.sp, color = Color(theme.secondaryText))
        )
    }
}

/** Renders an enum as a readable word for dropdowns without touching the persisted name. */
internal fun Enum<*>.settingsLabel(): String =
    name.lowercase().replaceFirstChar { it.uppercaseChar() }
