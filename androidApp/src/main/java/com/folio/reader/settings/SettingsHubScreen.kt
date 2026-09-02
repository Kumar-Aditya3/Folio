package com.folio.reader.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.folio.reader.ui.components.FolioSectionCard
import com.folio.reader.ui.components.FolioTopBar
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens

/**
 * The More destination: grouped rows into every settings category and library
 * tool (§3.5 FOLIO_IMPLEMENTATION_SPEC). Replaces the old SettingsCategory chip
 * row — chips filter, rows navigate.
 */
@Composable
fun SettingsHubScreen(
    onOpenSettings: (String) -> Unit,
    onOpenTags: () -> Unit,
    onOpenQuotes: () -> Unit,
    onOpenRevisit: () -> Unit,
    onOpenExtensions: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenHistory: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FolioTheme.colors.background)
    ) {
        FolioTopBar(title = "More")
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(FolioTokens.space3),
            verticalArrangement = Arrangement.spacedBy(FolioTokens.space3)
        ) {
            item {
                FolioSectionCard(title = "Reading") {
                    HubRow(
                        icon = Icons.Filled.TextFields,
                        title = "Typography",
                        subtitle = "Font, size, spacing",
                        onClick = { onOpenSettings(FolioSettingsCategory.TYPOGRAPHY) }
                    )
                    HubRow(
                        icon = Icons.Filled.ViewColumn,
                        title = "Layout",
                        subtitle = "Text width and margins",
                        onClick = { onOpenSettings(FolioSettingsCategory.LAYOUT) }
                    )
                    HubRow(
                        icon = Icons.AutoMirrored.Filled.FormatAlignLeft,
                        title = "Formatting",
                        subtitle = "Alignment, hyphenation, formatting mode",
                        onClick = { onOpenSettings(FolioSettingsCategory.FORMATTING) }
                    )
                    HubRow(
                        icon = Icons.AutoMirrored.Filled.MenuBook,
                        title = "Reader behavior",
                        subtitle = "Chapter title, progress, clock, brightness",
                        onClick = { onOpenSettings(FolioSettingsCategory.READING) }
                    )
                }
            }
            item {
                FolioSectionCard(title = "Appearance") {
                    HubRow(
                        icon = Icons.Filled.Palette,
                        title = "Themes",
                        subtitle = "Theme packs, typeface pairing, fonts",
                        onClick = { onOpenSettings(FolioSettingsCategory.THEMES) }
                    )
                }
            }
            item {
                FolioSectionCard(title = "Sync & backup") {
                    HubRow(
                        icon = Icons.Filled.Sync,
                        title = "Cloud sync",
                        subtitle = "Firebase project and sync preferences",
                        onClick = { onOpenSettings(FolioSettingsCategory.CLOUD_SYNC) }
                    )
                    HubRow(
                        icon = Icons.Filled.Backup,
                        title = "Backup & data",
                        subtitle = "Backups, annotation export, storage",
                        onClick = { onOpenSettings(FolioSettingsCategory.ADVANCED) }
                    )
                }
            }
            item {
                FolioSectionCard(title = "Library tools") {
                    HubRow(
                        icon = Icons.Filled.Label,
                        title = "Tags",
                        subtitle = "Organise books with tags",
                        onClick = onOpenTags
                    )
                    HubRow(
                        icon = Icons.Filled.FormatQuote,
                        title = "Quotes",
                        subtitle = "Saved passages across books",
                        onClick = onOpenQuotes
                    )
                    HubRow(
                        icon = Icons.Filled.History,
                        title = "Revisit",
                        subtitle = "Books worth returning to",
                        onClick = onOpenRevisit
                    )
                }
            }
            item {
                FolioSectionCard(title = "Manga") {
                    HubRow(
                        icon = Icons.Filled.Extension,
                        title = "Extensions",
                        subtitle = "Browse and manage manga sources",
                        onClick = onOpenExtensions
                    )
                    HubRow(
                        icon = Icons.Filled.Download,
                        title = "Downloads",
                        subtitle = "Downloaded chapters and storage",
                        onClick = onOpenDownloads
                    )
                    HubRow(
                        icon = Icons.AutoMirrored.Filled.MenuBook,
                        title = "History",
                        subtitle = "Recently read chapters",
                        onClick = onOpenHistory
                    )
                }
            }
        }
    }
}

@Composable
private fun HubRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = {
            Text(title, style = FolioTheme.typography.bodyLarge, color = FolioTheme.colors.onSurface)
        },
        supportingContent = {
            Text(subtitle, style = FolioTheme.typography.bodySmall, color = FolioTheme.colors.onSurfaceVariant)
        },
        leadingContent = {
            Icon(icon, contentDescription = null, tint = FolioTheme.colors.primary)
        },
        trailingContent = {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = FolioTheme.colors.onSurfaceVariant
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick)
    )
}
