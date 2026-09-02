package com.folio.reader.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.folio.reader.settings.ReaderSettings
import com.folio.reader.settings.TextWidth
import com.folio.reader.ui.components.DropdownMenuButton

@Composable
fun LayoutSettingsPanel(
    settings: ReaderSettings,
    onSettingsChange: (ReaderSettings) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Layout Settings", style = MaterialTheme.typography.titleLarge)

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
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Margins", style = MaterialTheme.typography.labelLarge)
                Text(
                    "${settings.margins.left.toInt()} dp", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Slider(
                value = settings.margins.left,
                onValueChange = {
                    onSettingsChange(settings.copy(margins = settings.margins.copy(left = it, right = it)))
                },
                valueRange = 0f..64f
            )
        }
    }
}
