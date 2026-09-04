package com.folio.reader.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.reader.settings.ReaderSettings
import com.folio.reader.ui.components.DropdownMenuButton
import com.folio.reader.ui.components.FolioSliderRow
import com.folio.reader.ui.components.rememberLegibleAccent
import com.folio.reader.ui.theme.FolioTheme
import kotlin.math.roundToInt

@Composable
fun TypographySettingsPanel(
    settings: ReaderSettings,
    onSettingsChange: (ReaderSettings) -> Unit
) {
    val colors = FolioTheme.colors
    val accent = rememberLegibleAccent(colors.primary)
    val fonts = readerFontOptions(settings)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        // Font family dropdown
        DropdownMenuButton(
            label = "Font family",
            selected = settings.fontFamily,
            options = fonts,
            onChange = { onSettingsChange(settings.copy(fontFamily = it)) },
            optionContent = { option ->
                Text(
                    text = readerFontLabel(settings, option),
                    fontFamily = readerFontFamily(settings, option),
                    fontWeight = if (option == settings.fontFamily) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (option == settings.fontFamily) accent else colors.onSurface
                )
            },
            selectedContent = {
                Text(
                    text = readerFontLabel(settings, settings.fontFamily),
                    style = FolioTheme.typography.bodyMedium,
                    fontFamily = readerFontFamily(settings, settings.fontFamily),
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        )

        val fontSize = settings.fontSize.coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP)
        FolioSliderRow(
            label = "Font size",
            valueLabel = "${fontSize.roundToInt()} sp",
            value = fontSize,
            onValueChange = { onSettingsChange(settings.copy(fontSize = it)) },
            valueRange = MIN_FONT_SIZE_SP..MAX_FONT_SIZE_SP
        )

        val fontWeight = settings.fontWeight.coerceIn(100, 900)
        FolioSliderRow(
            label = "Font weight",
            valueLabel = "$fontWeight",
            value = fontWeight.toFloat(),
            onValueChange = { onSettingsChange(settings.copy(fontWeight = it.roundToInt())) },
            valueRange = 100f..900f,
            steps = 7 // 100, 200, ..., 900
        )

        FolioSliderRow(
            label = "Line height",
            valueLabel = "%.1f".format(settings.lineHeight),
            value = settings.lineHeight,
            onValueChange = { onSettingsChange(settings.copy(lineHeight = it)) },
            valueRange = 1.0f..3.0f
        )

        FolioSliderRow(
            label = "Letter spacing",
            valueLabel = "%.2f".format(settings.letterSpacing),
            value = settings.letterSpacing,
            onValueChange = { onSettingsChange(settings.copy(letterSpacing = it)) },
            valueRange = -0.1f..0.3f
        )

        FolioSliderRow(
            label = "Word spacing",
            valueLabel = "%.2f".format(settings.wordSpacing),
            value = settings.wordSpacing,
            onValueChange = { onSettingsChange(settings.copy(wordSpacing = it)) },
            valueRange = -0.2f..0.5f
        )

        FolioSliderRow(
            label = "Paragraph spacing",
            valueLabel = "%.1fx".format(settings.paragraphSpacing),
            value = settings.paragraphSpacing,
            onValueChange = { onSettingsChange(settings.copy(paragraphSpacing = it)) },
            valueRange = 0.5f..3.0f
        )

        OutlinedButton(
            onClick = {
                val d = ReaderSettings()
                onSettingsChange(
                    settings.copy(
                        fontFamily = d.fontFamily,
                        fontSize = d.fontSize,
                        fontWeight = d.fontWeight,
                        lineHeight = d.lineHeight,
                        letterSpacing = d.letterSpacing,
                        wordSpacing = d.wordSpacing,
                        paragraphSpacing = d.paragraphSpacing
                    )
                )
            }
        ) {
            Text("Reset to defaults")
        }
    }
}

/** Smallest reader text size in sp — matches the reader quick-settings range. */
private const val MIN_FONT_SIZE_SP = 12f

/** Largest reader text size in sp — lowered from 36sp to keep sizes sane. */
private const val MAX_FONT_SIZE_SP = 24f
