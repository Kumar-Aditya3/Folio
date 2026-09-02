package com.folio.reader.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.folio.reader.settings.ReaderSettings

@Composable
fun ReadingSettingsPanel(
    settings: ReaderSettings,
    onSettingsChange: (ReaderSettings) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Reading Settings", style = MaterialTheme.typography.titleLarge)

        // Show chapter title toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Show chapter title", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Display chapter heading at top of page",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = settings.showChapterTitle,
                onCheckedChange = { onSettingsChange(settings.copy(showChapterTitle = it)) }
            )
        }

        // Show progress toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Show progress", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Display reading progress bar",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = settings.showProgress,
                onCheckedChange = { onSettingsChange(settings.copy(showProgress = it)) }
            )
        }

        // Show clock toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Show clock", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Display current time while reading",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = settings.showClock,
                onCheckedChange = { onSettingsChange(settings.copy(showClock = it)) }
            )
        }

        // The device's own screen brightness. Hidden where unsupported: dimming the
        // page to imitate a brightness control washed the paper, and brightness is a
        // property of the hardware rather than a reading preference to store or sync.
        val brightness = com.folio.reader.ui.components.rememberScreenBrightness()
        if (brightness.supported) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Light", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "${(brightness.value * 100).toInt()}%",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    "Sets the screen brightness for this app",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = brightness.value,
                    onValueChange = { brightness.set(it) },
                    valueRange = 0.05f..1f
                )
            }
        }
    }
}
