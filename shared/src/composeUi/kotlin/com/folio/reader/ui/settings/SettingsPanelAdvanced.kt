package com.folio.reader.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.folio.reader.settings.ReaderSettings

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AdvancedSettingsPanel(
    settings: ReaderSettings,
    onSettingsChange: (ReaderSettings) -> Unit,
    onImportFont: () -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    onExportAnnotations: (String) -> Unit
) {
    // Both Settings hosts already wrap this panel in a FolioSectionCard, so the panel
    // adds no card of its own — nesting one here produced a card inside a card.
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(
            "Backup/restore and annotation export", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Backup & restore
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Backup & Restore", style = MaterialTheme.typography.titleSmall)
            // FlowRow, not LazyRow: the buttons must all stay visible. A LazyRow
            // scrolled them out of sight and read as broken layout.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = onExportBackup) { Text("Export backup") }
                OutlinedButton(onClick = onImportBackup) { Text("Import backup") }
            }
            Text(
                "Backups include your library metadata, reading progress, settings and annotations.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // Annotation export
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Export Annotations", style = MaterialTheme.typography.titleSmall)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = { onExportAnnotations("md") }) { Text("Markdown") }
                OutlinedButton(onClick = { onExportAnnotations("json") }) { Text("JSON") }
                OutlinedButton(onClick = { onExportAnnotations("csv") }) { Text("CSV") }
            }
            Text(
                "Exports highlights, notes and bookmarks across every book, with the passage text and reading location.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
