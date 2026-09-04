package com.folio.reader.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.folio.reader.settings.ReaderSettings
import com.folio.reader.settings.TextWidth
import com.folio.reader.ui.components.DropdownMenuButton
import com.folio.reader.ui.components.FolioSliderRow
import kotlin.math.roundToInt

@Composable
fun LayoutSettingsPanel(
    settings: ReaderSettings,
    onSettingsChange: (ReaderSettings) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        // Text width dropdown
        DropdownMenuButton(
            label = "Text width",
            selected = settings.textWidth.settingsLabel(),
            options = TextWidth.entries.map { it.settingsLabel() },
            onChange = { name ->
                val width = TextWidth.entries.first { it.settingsLabel() == name }
                onSettingsChange(settings.copy(textWidth = width))
            }
        )

        // Margins slider (unified)
        FolioSliderRow(
            label = "Margins",
            valueLabel = "${settings.margins.left.roundToInt()} dp",
            value = settings.margins.left,
            onValueChange = {
                onSettingsChange(settings.copy(margins = settings.margins.copy(left = it, right = it)))
            },
            valueRange = 0f..64f
        )
    }
}
