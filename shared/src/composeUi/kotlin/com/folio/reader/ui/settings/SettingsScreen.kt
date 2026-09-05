package com.folio.reader.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.folio.reader.settings.ReaderSettings
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.folioBarTopInset

/**
 * Full settings surface used by the desktop app and as a fallback host. The
 * Android app presents these categories as individual nav destinations (§3.5);
 * desktop keeps this single-surface layout (Rule 1).
 */
@Composable
fun SettingsScreen(
    settings: ReaderSettings,
    onSettingsChange: (ReaderSettings) -> Unit,
    onBackPress: () -> Unit,
    syncState: com.folio.reader.sync.SyncState = com.folio.reader.sync.SyncState(),
    onSyncNow: () -> Unit = {},
    onImportFont: () -> Unit = {},
    onExportBackup: () -> Unit = {},
    onImportBackup: () -> Unit = {},
    onExportAnnotations: (String) -> Unit = {},
    mangaDefaultMode: com.folio.reader.ui.manga.MangaReaderMode = com.folio.reader.ui.manga.MangaReaderMode.WEBTOON,
    onMangaDefaultModeChange: (com.folio.reader.ui.manga.MangaReaderMode) -> Unit = {},
    mangaDownloadsLocation: String? = null,
    onPickMangaDownloadsLocation: () -> Unit = {}
) {
    var selectedCategory by remember { mutableStateOf(SettingsCategory.GENERAL) }
    var showPreview by remember { mutableStateOf(true) }

    // The masthead floats *over* the page instead of sitting above it as a sibling:
    // a bar with nothing behind it but the page's own flat field has nothing to
    // refract, which is why the glass read as invisible. Overlaying it and paying
    // the inset as the scroller's `contentPadding` puts real settings under it.
    val headerState = com.folio.reader.ui.components.rememberFolioHeaderState()
    val topInset = folioBarTopInset()

    Box(modifier = Modifier.fillMaxSize()) {
        // Responsive: phones get a horizontal category chip bar + full-width
        // content; tablets/desktop keep the two-panel layout.
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isCompact = maxWidth < 640.dp
            if (isCompact) {
                // The chip rail never scrolls vertically, so it clears the bar with
                // outer padding; only the pane below it pays contentPadding.
                Column(modifier = Modifier.fillMaxSize().padding(top = topInset)) {
                    // Category chips — horizontally scrollable
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp)
                    ) {
                        items(SettingsCategory.entries) { category ->
                            com.folio.reader.ui.components.FolioChip(
                                selected = selectedCategory == category,
                                onClick = { selectedCategory = category },
                                label = category.displayName
                            )
                        }
                    }
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .nestedScroll(headerState.nestedScrollConnection),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        item(key = selectedCategory.name) {
                            com.folio.reader.ui.components.FolioSectionCard {
                                when (selectedCategory) {
                                    SettingsCategory.GENERAL -> GeneralSettingsPanel(
                                        settings,
                                        onSettingsChange,
                                        onImportFont,
                                        mangaDownloadsLocation,
                                        onPickMangaDownloadsLocation
                                    )

                                    SettingsCategory.DEFAULTS -> ReaderDefaultsPanel(
                                        settings, mangaDefaultMode, onSettingsChange, onMangaDefaultModeChange
                                    )

                                    SettingsCategory.TYPOGRAPHY -> TypographySettingsPanel(settings, onSettingsChange)
                                    SettingsCategory.LAYOUT -> LayoutSettingsPanel(settings, onSettingsChange)
                                    SettingsCategory.FORMATTING -> FormattingSettingsPanel(settings, onSettingsChange)
                                    SettingsCategory.READING -> ReadingSettingsPanel(settings, onSettingsChange)
                                    SettingsCategory.CLOUD_SYNC -> CloudSyncSettingsPanel(
                                        settings,
                                        syncState,
                                        onSettingsChange,
                                        onSyncNow
                                    )

                                    SettingsCategory.ADVANCED -> AdvancedSettingsPanel(
                                        settings, onSettingsChange, onImportFont, onExportBackup, onImportBackup,
                                        onExportAnnotations
                                    )
                                }
                            }
                        }
                        if (showPreview && selectedCategory in setOf(
                                SettingsCategory.TYPOGRAPHY,
                                SettingsCategory.LAYOUT,
                                SettingsCategory.FORMATTING
                            )
                        ) {
                            item { SettingsLivePreview(settings) }
                        }
                    }
                }
            } else {
                Row(modifier = Modifier.fillMaxSize()) {
                    // Categories sidebar. Not a scroller, so it pays the inset as
                    // padding on its items rather than as contentPadding — placed
                    // *after* its background so the panel's tint still runs to the
                    // top edge and gives the glass something real to refract
                    // instead of a seam across the rail.
                    Column(
                        modifier = Modifier
                            .width(200.dp)
                            .fillMaxHeight()
                            .background(FolioTheme.colors.surfaceContainerHighest)
                            .padding(top = topInset)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SettingsCategory.entries.forEach { category ->
                            val isSelected = selectedCategory == category
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) FolioTheme.colors.primaryContainer else Color.Transparent)
                                    .clickable { selectedCategory = category }
                                    .padding(horizontal = 16.dp, vertical = 10.dp)
                            ) {
                                Text(
                                    text = category.displayName,
                                    style = FolioTheme.typography.bodyLarge,
                                    color = if (isSelected) FolioTheme.colors.primary else FolioTheme.colors.onSurfaceVariant,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }

                    // Settings content — LazyColumn keyed on category so switching tabs
                    // always rebuilds a fresh, scrollable list (weight fills remaining width).
                    // The one scroller here, so it is the one that pays the masthead's
                    // inset as contentPadding: rows travel under the glass.
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .nestedScroll(headerState.nestedScrollConnection),
                        contentPadding = PaddingValues(
                            start = 24.dp,
                            top = topInset + 24.dp,
                            end = 24.dp,
                            bottom = 24.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        item(key = selectedCategory.name) {
                            com.folio.reader.ui.components.FolioSectionCard {
                                when (selectedCategory) {
                                    SettingsCategory.GENERAL -> GeneralSettingsPanel(
                                        settings,
                                        onSettingsChange,
                                        onImportFont,
                                        mangaDownloadsLocation,
                                        onPickMangaDownloadsLocation
                                    )

                                    SettingsCategory.DEFAULTS -> ReaderDefaultsPanel(
                                        settings, mangaDefaultMode, onSettingsChange, onMangaDefaultModeChange
                                    )

                                    SettingsCategory.TYPOGRAPHY -> TypographySettingsPanel(settings, onSettingsChange)
                                    SettingsCategory.LAYOUT -> LayoutSettingsPanel(settings, onSettingsChange)
                                    SettingsCategory.FORMATTING -> FormattingSettingsPanel(settings, onSettingsChange)
                                    SettingsCategory.READING -> ReadingSettingsPanel(settings, onSettingsChange)
                                    SettingsCategory.CLOUD_SYNC -> CloudSyncSettingsPanel(
                                        settings,
                                        syncState,
                                        onSettingsChange,
                                        onSyncNow
                                    )

                                    SettingsCategory.ADVANCED -> AdvancedSettingsPanel(
                                        settings, onSettingsChange, onImportFont, onExportBackup, onImportBackup,
                                        onExportAnnotations
                                    )
                                }
                            }
                        }
                        if (showPreview && selectedCategory in setOf(
                                SettingsCategory.TYPOGRAPHY,
                                SettingsCategory.LAYOUT,
                                SettingsCategory.FORMATTING
                            )
                        ) {
                            item { SettingsLivePreview(settings) }
                        }
                    }
                }
            }
        }

        // Drawn last so it sits *over* the page: the detail pane and the sidebar's
        // tint both run beneath it, which is the only thing glass can refract.
        com.folio.reader.ui.components.FolioTopBar(
            title = "Settings",
            collapse = headerState.collapse,
            modifier = Modifier.align(Alignment.TopCenter),
            navigationIcon = {
                IconButton(onClick = onBackPress) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        )
    }
}

// Settings categories enum
enum class SettingsCategory(val displayName: String) {
    GENERAL("General"),
    DEFAULTS("Reader defaults"),
    TYPOGRAPHY("Typography"),
    LAYOUT("Layout"),
    FORMATTING("Formatting"),
    READING("Reading"),
    CLOUD_SYNC("Cloud Sync"),
    ADVANCED("Advanced")
}
