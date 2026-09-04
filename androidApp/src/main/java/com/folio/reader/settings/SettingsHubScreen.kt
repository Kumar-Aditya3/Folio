package com.folio.reader.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.reader.ui.components.FolioEyebrow
import com.folio.reader.ui.components.FolioRule
import com.folio.reader.ui.components.FolioTopBar
import com.folio.reader.ui.components.folioPressable
import com.folio.reader.ui.components.folioSunken
import com.folio.reader.ui.components.rememberFolioInteraction
import com.folio.reader.ui.theme.FolioShapes
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens
import com.folio.reader.ui.theme.LocalFolioBarInset

/**
 * The More hub, as a **table of contents** rather than nested boxes.
 *
 * The old screen put every group inside a `FolioSectionCard`, producing a
 * list-inside-a-box-inside-a-list — three levels of container for what is really
 * one document with five headings. Now:
 *
 *  - each group is titled by an eyebrow **on the page**, tinted with its own
 *    accent role, so the five categories have distinct identities;
 *  - rows are separated by hairline rules, not by card rims;
 *  - each icon sits on a small embedded tile in its section's accent, so the icon
 *    interacts with a surface instead of floating in a gutter;
 *  - sections breathe at `spaceMovement`.
 *
 * Every destination, label and tap target is unchanged.
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
    Column(modifier = Modifier.fillMaxSize()) {
        val headerState = com.folio.reader.ui.components.rememberFolioHeaderState()
        FolioTopBar(title = "More", collapse = headerState.collapse)
        val colors = FolioTheme.colors
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(headerState.nestedScrollConnection),
            contentPadding = PaddingValues(
                start = FolioTokens.gutter,
                end = FolioTokens.gutter,
                top = FolioTokens.space2,
                bottom = FolioTokens.spaceMovement + LocalFolioBarInset.current,
            ),
        ) {
            item {
                HubSection("Reading", colors.accentProgress) {
                    HubRow(
                        icon = Icons.Filled.Equalizer,
                        title = "Reader defaults",
                        subtitle = "What a new book or manga starts with",
                        accent = colors.accentProgress,
                        onClick = { onOpenSettings(FolioSettingsCategory.DEFAULTS) }
                    )
                    HubRow(
                        icon = Icons.Filled.TextFields,
                        title = "Typography",
                        subtitle = "Typeface, size and spacing for prose",
                        accent = colors.accentProgress,
                        onClick = { onOpenSettings(FolioSettingsCategory.TYPOGRAPHY) }
                    )
                    HubRow(
                        icon = Icons.Filled.ViewColumn,
                        title = "Layout",
                        subtitle = "Text width and margins",
                        accent = colors.accentProgress,
                        onClick = { onOpenSettings(FolioSettingsCategory.LAYOUT) }
                    )
                    HubRow(
                        icon = Icons.AutoMirrored.Filled.FormatAlignLeft,
                        title = "Formatting",
                        subtitle = "Alignment, hyphenation, formatting mode",
                        accent = colors.accentProgress,
                        onClick = { onOpenSettings(FolioSettingsCategory.FORMATTING) }
                    )
                    HubRow(
                        icon = Icons.AutoMirrored.Filled.MenuBook,
                        title = "Reader behavior",
                        subtitle = "Chapter title, progress, clock, brightness",
                        accent = colors.accentProgress,
                        last = true,
                        onClick = { onOpenSettings(FolioSettingsCategory.READING) }
                    )
                }
            }
            item {
                HubSection("Appearance", colors.accentDiscovery) {
                    HubRow(
                        icon = Icons.Filled.Palette,
                        title = "Themes",
                        subtitle = "Theme packs, typeface pairing, fonts",
                        accent = colors.accentDiscovery,
                        last = true,
                        onClick = { onOpenSettings(FolioSettingsCategory.THEMES) }
                    )
                }
            }
            item {
                HubSection("Sync & backup", colors.accentStreak) {
                    HubRow(
                        icon = Icons.Filled.Sync,
                        title = "Cloud sync",
                        subtitle = "Firebase project and sync preferences",
                        accent = colors.accentStreak,
                        onClick = { onOpenSettings(FolioSettingsCategory.CLOUD_SYNC) }
                    )
                    HubRow(
                        icon = Icons.Filled.Backup,
                        title = "Backup & data",
                        subtitle = "Backups, annotation export, storage",
                        accent = colors.accentStreak,
                        last = true,
                        onClick = { onOpenSettings(FolioSettingsCategory.ADVANCED) }
                    )
                }
            }
            item {
                HubSection("Library tools", colors.accentAnnotation) {
                    HubRow(
                        icon = Icons.Filled.Label,
                        title = "Tags",
                        subtitle = "Organise books with tags",
                        accent = colors.accentAnnotation,
                        onClick = onOpenTags
                    )
                    HubRow(
                        icon = Icons.Filled.FormatQuote,
                        title = "Quotes",
                        subtitle = "Saved passages across books",
                        accent = colors.accentAnnotation,
                        onClick = onOpenQuotes
                    )
                    HubRow(
                        icon = Icons.Filled.History,
                        title = "Revisit",
                        subtitle = "Books worth returning to",
                        accent = colors.accentAnnotation,
                        onClick = onOpenRevisit
                    )
                    HubRow(
                        icon = Icons.Filled.Equalizer,
                        title = "Statistics exclusions",
                        subtitle = "Keep titles out of Stats and Home",
                        accent = colors.accentAnnotation,
                        last = true,
                        onClick = { onOpenSettings(FolioSettingsCategory.STATS) }
                    )
                }
            }
            item {
                HubSection("Manga", colors.tertiary) {
                    HubRow(
                        icon = Icons.Filled.Notifications,
                        title = "Manga updates",
                        subtitle = "Background chapter checks",
                        accent = colors.tertiary,
                        onClick = { onOpenSettings(FolioSettingsCategory.MANGA) }
                    )
                    HubRow(
                        icon = Icons.Filled.Extension,
                        title = "Extensions",
                        subtitle = "Browse and manage manga sources",
                        accent = colors.tertiary,
                        onClick = onOpenExtensions
                    )
                    HubRow(
                        icon = Icons.Filled.Download,
                        title = "Downloads",
                        subtitle = "Downloaded chapters and storage",
                        accent = colors.tertiary,
                        onClick = onOpenDownloads
                    )
                    HubRow(
                        icon = Icons.AutoMirrored.Filled.MenuBook,
                        title = "History",
                        subtitle = "Recently read chapters",
                        accent = colors.tertiary,
                        last = true,
                        onClick = onOpenHistory
                    )
                }
            }
        }
    }
}

/** A titled group: accent eyebrow on the page, rows beneath, a real gap after. */
@Composable
private fun HubSection(
    title: String,
    accent: Color,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.padding(bottom = FolioTokens.spaceMovement)) {
        FolioEyebrow(title, accent = accent)
        Spacer(Modifier.height(FolioTokens.space2))
        content()
    }
}

/**
 * One navigation row. The icon sits on an embedded tile tinted by its section's
 * accent — icon and surface belong to each other, instead of a bare glyph in a
 * gutter. A hairline closes the row unless it is the section's last.
 */
@Composable
private fun HubRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accent: Color,
    last: Boolean = false,
    onClick: () -> Unit
) {
    val colors = FolioTheme.colors
    val interaction = rememberFolioInteraction()
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .folioPressable(interaction, scaleTo = 0.99f)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                .padding(vertical = FolioTokens.space2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .folioSunken(FolioShapes.inset, accent = accent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(19.dp),
                )
            }
            Spacer(Modifier.width(FolioTokens.space3))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = FolioTheme.typography.titleSmall,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle,
                    style = FolioTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(FolioTokens.space2))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(18.dp),
            )
        }
        if (!last) FolioRule()
    }
}
