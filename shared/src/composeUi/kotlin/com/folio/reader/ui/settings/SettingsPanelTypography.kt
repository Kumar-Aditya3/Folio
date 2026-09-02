package com.folio.reader.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.reader.settings.ReaderSettings
import com.folio.reader.ui.components.DropdownMenuButton
import com.folio.reader.ui.components.systemFontFamily

@Composable
fun TypographySettingsPanel(
    settings: ReaderSettings,
    onSettingsChange: (ReaderSettings) -> Unit
) {
    val fonts = listOf(
        "Calluna", "Comfortaa", "Literata", "Merriweather", "Georgia", "EB Garamond", "Lora",
        "Open Sans", "Inter", "Noto Serif", "Serif", "Sans Serif", "Monospace"
    )

    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Typography", style = MaterialTheme.typography.titleLarge)

        // Font family dropdown
        DropdownMenuButton(
            label = "Font family",
            selected = settings.fontFamily,
            options = (fonts + settings.customFonts.map { it.name }).distinct(),
            onChange = { onSettingsChange(settings.copy(fontFamily = it)) },
            optionContent = { option ->
                val actualFamily = settings.customFonts
                    .firstOrNull { it.name == option }
                    ?.familyName
                    ?: option
                Text(
                    text = actualFamily,
                    fontFamily = systemFontFamily(actualFamily),
                    fontWeight = if (option == settings.fontFamily) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (option == settings.fontFamily) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
            },
            selectedContent = {
                val actualFamily = settings.customFonts
                    .firstOrNull { it.name == settings.fontFamily }
                    ?.familyName
                    ?: settings.fontFamily
                Text(
                    text = actualFamily,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = systemFontFamily(actualFamily),
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        )

        // Font size slider
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Font size", style = MaterialTheme.typography.labelLarge)
                Text(
                    "${settings.fontSize.toInt()} sp", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Slider(
                value = settings.fontSize,
                onValueChange = { onSettingsChange(settings.copy(fontSize = it)) },
                valueRange = MIN_FONT_SIZE_SP..MAX_FONT_SIZE_SP
            )
        }

        // Font weight slider
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Font weight", style = MaterialTheme.typography.labelLarge)
                Text(
                    "${settings.fontWeight}", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Slider(
                value = settings.fontWeight.toFloat(),
                onValueChange = { onSettingsChange(settings.copy(fontWeight = it.toInt())) },
                valueRange = 100f..900f,
                steps = 7 // 100, 200, ..., 900
            )
        }

        // Line height slider
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Line height", style = MaterialTheme.typography.labelLarge)
                Text(
                    "%.1f".format(settings.lineHeight), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Slider(
                value = settings.lineHeight,
                onValueChange = { onSettingsChange(settings.copy(lineHeight = it)) },
                valueRange = 1.0f..3.0f
            )
        }

        // Letter spacing slider
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Letter spacing", style = MaterialTheme.typography.labelLarge)
                Text(
                    "%.2f".format(settings.letterSpacing), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Slider(
                value = settings.letterSpacing,
                onValueChange = { onSettingsChange(settings.copy(letterSpacing = it)) },
                valueRange = -0.1f..0.3f
            )
        }

        // Word spacing slider
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Word spacing", style = MaterialTheme.typography.labelLarge)
                Text(
                    "%.2f".format(settings.wordSpacing), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Slider(
                value = settings.wordSpacing,
                onValueChange = { onSettingsChange(settings.copy(wordSpacing = it)) },
                valueRange = -0.2f..0.5f
            )
        }

        // Paragraph spacing slider
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Paragraph spacing", style = MaterialTheme.typography.labelLarge)
                Text(
                    "%.1fx".format(settings.paragraphSpacing), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Slider(
                value = settings.paragraphSpacing,
                onValueChange = { onSettingsChange(settings.copy(paragraphSpacing = it)) },
                valueRange = 0.5f..3.0f
            )
        }

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
                        paragraphSpacing = d.paragraphSpacing,
                        margins = d.margins,
                        textWidth = d.textWidth,
                        alignment = d.alignment,
                        hyphenation = d.hyphenation
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
