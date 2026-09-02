package com.folio.reader.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.folio.reader.model.FormattingMode
import com.folio.reader.settings.ReaderSettings
import com.folio.reader.settings.TextAlignment
import com.folio.reader.ui.components.DropdownMenuButton
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens

@Composable
fun FormattingSettingsPanel(
    settings: ReaderSettings,
    onSettingsChange: (ReaderSettings) -> Unit,
    overrideCount: Int = 0,
    onReviewOverrides: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Formatting", style = MaterialTheme.typography.titleLarge)

        // Formatting mode dropdown
        DropdownMenuButton(
            label = "Formatting mode",
            selected = settings.formattingMode.settingsLabel(),
            options = FormattingMode.entries.map { it.settingsLabel() },
            onChange = { name ->
                val mode = FormattingMode.entries.first { it.settingsLabel() == name }
                onSettingsChange(settings.copy(formattingMode = mode))
            }
        )

        DropdownMenuButton(
            label = "Default text alignment",
            selected = settings.alignment.settingsLabel(),
            options = TextAlignment.entries.map { it.settingsLabel() },
            onChange = { name ->
                val alignment = TextAlignment.entries.first { it.settingsLabel() == name }
                onSettingsChange(settings.copy(alignment = alignment))
            }
        )

        Text(
            "Hybrid keeps explicit EPUB alignment for title pages and special paragraphs. " +
                "Normalized uses this default everywhere. Changes here apply to every book, " +
                "including ones you have already opened.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (overrideCount > 0 && onReviewOverrides != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(FolioTokens.radiusChip))
                    .clickable { onReviewOverrides() }
                    .padding(vertical = FolioTokens.space1),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (overrideCount == 1) "1 book has its own override — review"
                    else "$overrideCount books have their own overrides — review",
                    style = FolioTheme.typography.bodySmall,
                    color = FolioTheme.colors.primary
                )
            }
        }

        // Hyphenation toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Hyphenation", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Automatically hyphenate long words",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = settings.hyphenation,
                onCheckedChange = { onSettingsChange(settings.copy(hyphenation = it)) }
            )
        }
    }
}
