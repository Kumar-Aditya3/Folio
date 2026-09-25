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
import com.folio.reader.security.SyncCredentials
import com.folio.reader.settings.ReaderSettings
import com.folio.reader.ui.components.folioBackdropSource
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
    onPickMangaDownloadsLocation: () -> Unit = {},
    /** Library scanning controls; null hides the section (Android hosts it as its own screen). */
    scanState: LibraryScanPanelState? = null,
    onScanScopeChange: (com.folio.reader.importer.LibraryScanScope) -> Unit = {},
    onPickScanFolder: () -> Unit = {},
    /** Null (desktop) hides the device-root picker: the device scope scans the user's common folders there. */
    onPickScanDeviceRoot: (() -> Unit)? = null,
    onScanOnStartChange: (Boolean) -> Unit = {},
    onScanNow: () -> Unit = {},
    /** Cloud-sync credentials, held in the no-backup store rather than the settings blob. */
    syncCredentials: SyncCredentials = SyncCredentials(),
    onSyncCredentialsChange: (SyncCredentials) -> Unit = {},
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
                            .nestedScroll(headerState.nestedScrollConnection)
                            .folioBackdropSource(),
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

                                    SettingsCategory.CUSTOM_THEME ->
                                        CustomThemeSettingsPanel(settings, onSettingsChange)

                                    SettingsCategory.TRANSPARENCY ->
                                        TransparencySettingsPanel(settings, onSettingsChange)

                                    SettingsCategory.DEFAULTS -> ReaderDefaultsPanel(
                                        settings, mangaDefaultMode, onSettingsChange, onMangaDefaultModeChange
                                    )

                                    SettingsCategory.TEXT_AND_PAGE -> TextAndPageSettingsPanel(
                                        settings,
                                        onSettingsChange
                                    )
                                    SettingsCategory.READING -> ReadingSettingsPanel(settings, onSettingsChange)
                                    SettingsCategory.CLOUD_SYNC -> CloudSyncSettingsPanel(
                                        settings,
                                        syncState,
                                        onSettingsChange,
                                        onSyncNow,
                                        syncCredentials,
                                        onSyncCredentialsChange
                                    )

                                    SettingsCategory.ADVANCED -> Column {
                                        AdvancedSettingsPanel(
                                            settings, onSettingsChange, onImportFont, onExportBackup, onImportBackup,
                                            onExportAnnotations
                                        )
                                        if (scanState != null) {
                                            LibraryScanSettingsPanel(
                                                state = scanState,
                                                onScopeChange = onScanScopeChange,
                                                onPickFolder = onPickScanFolder,
                                                onPickDeviceRoot = onPickScanDeviceRoot,
                                                onScanOnStartChange = onScanOnStartChange,
                                                onScanNow = onScanNow,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        if (showPreview && selectedCategory in setOf(
                                SettingsCategory.TEXT_AND_PAGE
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
                            .nestedScroll(headerState.nestedScrollConnection)
                            .folioBackdropSource(),
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

                                    SettingsCategory.CUSTOM_THEME ->
                                        CustomThemeSettingsPanel(settings, onSettingsChange)

                                    SettingsCategory.TRANSPARENCY ->
                                        TransparencySettingsPanel(settings, onSettingsChange)

                                    SettingsCategory.DEFAULTS -> ReaderDefaultsPanel(
                                        settings, mangaDefaultMode, onSettingsChange, onMangaDefaultModeChange
                                    )

                                    SettingsCategory.TEXT_AND_PAGE -> TextAndPageSettingsPanel(
                                        settings,
                                        onSettingsChange
                                    )
                                    SettingsCategory.READING -> ReadingSettingsPanel(settings, onSettingsChange)
                                    SettingsCategory.CLOUD_SYNC -> CloudSyncSettingsPanel(
                                        settings,
                                        syncState,
                                        onSettingsChange,
                                        onSyncNow,
                                        syncCredentials,
                                        onSyncCredentialsChange
                                    )

                                    SettingsCategory.ADVANCED -> Column {
                                        AdvancedSettingsPanel(
                                            settings, onSettingsChange, onImportFont, onExportBackup, onImportBackup,
                                            onExportAnnotations
                                        )
                                        if (scanState != null) {
                                            LibraryScanSettingsPanel(
                                                state = scanState,
                                                onScopeChange = onScanScopeChange,
                                                onPickFolder = onPickScanFolder,
                                                onPickDeviceRoot = onPickScanDeviceRoot,
                                                onScanOnStartChange = onScanOnStartChange,
                                                onScanNow = onScanNow,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        if (showPreview && selectedCategory in setOf(
                                SettingsCategory.TEXT_AND_PAGE
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
enum class SettingsCategory(val displayName: String, val routeName: String) {
    GENERAL("General", "general"),
    CUSTOM_THEME("Custom theme", "custom_theme"),
    TRANSPARENCY("Transparency", "transparency"),
    DEFAULTS("Reader defaults", "defaults"),
    TEXT_AND_PAGE("Text & page", "text_and_page"),
    READING("Reading", "reading"),
    CLOUD_SYNC("Cloud Sync", "cloud_sync"),
    ADVANCED("Advanced", "advanced");

    companion object {
        /**
         * Pre-merge route names and their successor. Kept so deep links and
         * bookmarks from older builds land on the merged screen instead of a
         * dead end — §14.2.
         */
        private val LEGACY_ROUTES = mapOf(
            "typography" to TEXT_AND_PAGE,
            "layout" to TEXT_AND_PAGE,
            "formatting" to TEXT_AND_PAGE,
            "text" to TEXT_AND_PAGE,
        )

        /** Route-name resolution for `settings/{category}`, shared with the Android hub. */
        fun fromRoute(route: String): SettingsCategory? =
            entries.firstOrNull { it.routeName == route } ?: LEGACY_ROUTES[route]
    }
}
