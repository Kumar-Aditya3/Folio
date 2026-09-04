package com.folio.reader.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.folio.reader.settings.LayoutMode
import com.folio.reader.settings.ReaderSettings
import com.folio.reader.ui.components.DropdownMenuButton
import com.folio.reader.ui.manga.MangaReaderMode
import com.folio.reader.ui.theme.FolioTheme

/** Defaults applied to newly opened prose books and manga without an override. */
@Composable
fun ReaderDefaultsPanel(
    settings: ReaderSettings,
    mangaDefaultMode: MangaReaderMode,
    onSettingsChange: (ReaderSettings) -> Unit,
    onMangaDefaultModeChange: (MangaReaderMode) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(
            "These choices are used when an item first opens. Existing book and manga reader " +
                "settings remain unchanged; change an item from its reader controls.",
            style = FolioTheme.typography.bodySmall,
            color = FolioTheme.colors.onSurfaceVariant,
        )
        DropdownMenuButton(
            label = "Prose reading mode",
            selected = settings.layoutMode.settingsLabel(),
            options = LayoutMode.entries.map { it.settingsLabel() },
            onChange = { label ->
                onSettingsChange(settings.copy(layoutMode = LayoutMode.entries.first { it.settingsLabel() == label }))
            },
        )
        DropdownMenuButton(
            label = "Manga reading mode",
            selected = mangaDefaultMode.settingsLabel(),
            options = MangaReaderMode.entries.map { it.settingsLabel() },
            onChange = { label ->
                onMangaDefaultModeChange(MangaReaderMode.entries.first { it.settingsLabel() == label })
            },
        )
    }
}
