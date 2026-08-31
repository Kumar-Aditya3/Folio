package com.folio.reader.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.folio.reader.model.Book
import com.folio.reader.model.BookStatus
import com.folio.reader.model.Collection
import com.folio.reader.model.Series
import com.folio.reader.ui.components.BookCover
import com.folio.reader.model.FormattingMode
import com.folio.reader.settings.Theme
import com.folio.reader.settings.LayoutMode
import com.folio.reader.settings.ReaderSettings
import com.folio.reader.settings.TextAlignment
import com.folio.reader.settings.TextWidth
import com.folio.reader.ui.components.DropdownMenuButton
import com.folio.reader.ui.theme.AppPalette
import com.folio.reader.ui.components.systemFontFamily
import com.folio.reader.ui.theme.FolioTheme
import androidx.compose.ui.text.input.PasswordVisualTransformation

@OptIn(ExperimentalMaterial3Api::class)
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
    onExportAnnotations: (String) -> Unit = {}
) {
    var selectedCategory by remember { mutableStateOf(SettingsCategory.GENERAL) }
    var showPreview by remember { mutableStateOf(true) }

    Column(modifier = Modifier.fillMaxSize()) {
        com.folio.reader.ui.components.FolioTopBar(
            title = "Settings",
            navigationIcon = {
                IconButton(onClick = onBackPress) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        )

        // Responsive: phones get a horizontal category chip bar + full-width
        // content; tablets/desktop keep the two-panel layout.
        androidx.compose.foundation.layout.BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isCompact = maxWidth < 640.dp
            if (isCompact) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Category chips — horizontally scrollable
                    androidx.compose.foundation.lazy.LazyRow(
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
                    androidx.compose.foundation.lazy.LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(FolioTheme.colors.background),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        item(key = selectedCategory.name) {
                            com.folio.reader.ui.components.FolioSectionCard {
                                when (selectedCategory) {
                                    SettingsCategory.GENERAL -> GeneralSettingsPanel(
                                        settings,
                                        onSettingsChange,
                                        onImportFont
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
                    // Categories sidebar
                    Column(
                        modifier = Modifier
                            .width(200.dp)
                            .fillMaxHeight()
                            .background(FolioTheme.colors.surfaceContainerHighest)
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
                    androidx.compose.foundation.lazy.LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(FolioTheme.colors.background),
                        contentPadding = PaddingValues(24.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        item(key = selectedCategory.name) {
                            com.folio.reader.ui.components.FolioSectionCard {
                                when (selectedCategory) {
                                    SettingsCategory.GENERAL -> GeneralSettingsPanel(
                                        settings,
                                        onSettingsChange,
                                        onImportFont
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
    }
}

// Settings categories enum
enum class SettingsCategory(val displayName: String) {
    GENERAL("General"),
    TYPOGRAPHY("Typography"),
    LAYOUT("Layout"),
    FORMATTING("Formatting"),
    READING("Reading"),
    CLOUD_SYNC("Cloud Sync"),
    ADVANCED("Advanced")
}

// Placeholder composables for settings panels
// These will render basic settings until proper implementation

@Composable
fun GeneralSettingsPanel(
    settings: ReaderSettings,
    onSettingsChange: (ReaderSettings) -> Unit,
    onImportFont: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("General Settings", style = MaterialTheme.typography.titleLarge)

        // Theme packs: a curated chrome + page pair, applied together.
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text("Theme packs", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Matched pairs of app colours and page colours. Applying a pack " +
                        "changes both; either side can still be adjusted on its own.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(com.folio.reader.ui.theme.ThemePack.ALL) { pack ->
                    ThemePackCard(
                        pack = pack,
                        selected = settings.appThemeId == pack.appPaletteId &&
                            settings.themeId == pack.readerThemeId &&
                            settings.customTheme == null,
                        onClick = {
                            onSettingsChange(
                                settings.copy(
                                    appThemeId = pack.appPaletteId,
                                    themeId = pack.readerThemeId,
                                    customTheme = null,
                                )
                            )
                        },
                    )
                }
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text("Typeface", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Display and UI font pairing for the app chrome.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(com.folio.reader.ui.theme.FontTheme.entries) { ft ->
                    FontThemeCard(
                        fontTheme = ft,
                        selected = settings.fontThemeId == ft.id,
                        onClick = { onSettingsChange(settings.copy(fontThemeId = ft.id)) },
                    )
                }
            }
        }

        // Use embedded fonts toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Use publisher fonts", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Allow EPUBs to load embedded fonts",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = settings.useEmbeddedFonts,
                onCheckedChange = { onSettingsChange(settings.copy(useEmbeddedFonts = it)) }
            )
        }

        // Import custom font button
        OutlinedButton(
            onClick = onImportFont,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Import custom font")
        }
    }
}

@Composable
private fun ThemePackCard(
    pack: com.folio.reader.ui.theme.ThemePack,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = com.folio.reader.ui.theme.FolioTheme.colors
    val chrome = com.folio.reader.ui.theme.AppPalette.byId(pack.appPaletteId).colors
    val page = com.folio.reader.settings.Theme.getPreset(pack.readerThemeId)
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(
        com.folio.reader.ui.theme.FolioTokens.radiusControl
    )
    androidx.compose.foundation.layout.Column(
        modifier = Modifier
            .width(132.dp)
            .background(colors.surface.copy(alpha = 0.55f), shape)
            .border(
                1.dp,
                if (selected) colors.primary else colors.outline.copy(alpha = 0.45f),
                shape
            )
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(7.dp))
                    .background(chrome.background)
                    .border(1.dp, colors.outline.copy(alpha = 0.5f), androidx.compose.foundation.shape.RoundedCornerShape(7.dp))
            )
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(7.dp))
                    .background(androidx.compose.ui.graphics.Color(page.background))
                    .border(1.dp, colors.outline.copy(alpha = 0.5f), androidx.compose.foundation.shape.RoundedCornerShape(7.dp))
            )
        }
        Text(
            pack.name,
            style = MaterialTheme.typography.titleSmall,
            color = if (selected) colors.primary else colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun FontThemeCard(
    fontTheme: com.folio.reader.ui.theme.FontTheme,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = com.folio.reader.ui.theme.FolioTheme.colors
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(
        com.folio.reader.ui.theme.FolioTokens.radiusControl
    )
    androidx.compose.foundation.layout.Column(
        modifier = Modifier
            .width(132.dp)
            .background(colors.surface.copy(alpha = 0.55f), shape)
            .border(
                1.dp,
                if (selected) colors.primary else colors.outline.copy(alpha = 0.45f),
                shape
            )
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Ag",
            fontFamily = com.folio.reader.ui.theme.UiFonts.display(fontTheme, weight = 600, opticalSize = 28f),
            fontSize = 28.sp,
            color = colors.onSurface,
        )
        Text(
            fontTheme.label,
            style = MaterialTheme.typography.titleSmall,
            color = if (selected) colors.primary else colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun TypographySettingsPanel(
    settings: ReaderSettings,
    onSettingsChange: (ReaderSettings) -> Unit
) {
    val fonts = listOf(
        "Calluna", "Comfortaa", "Literata", "Merriweather", "Georgia", "EB Garamond", "Lora",
        "Open Sans", "Inter", "Noto Serif", "Serif", "Sans Serif", "Monospace"
    )

    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Typography", style = MaterialTheme.typography.titleLarge)

        // Font family dropdown
        DropdownMenuButton(
            label = "Font family",
            selected = settings.fontFamily,
            options = (fonts + settings.customFonts.map { it.name }).distinct(),
            onChange = { onSettingsChange(settings.copy(fontFamily = it)) },
            optionContent = { option ->
                val actualFamily = settings.customFonts
                    .firstOrNull { it.name == option }
                    ?.familyName
                    ?: option
                Text(
                    text = actualFamily,
                    fontFamily = systemFontFamily(actualFamily),
                    fontWeight = if (option == settings.fontFamily) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (option == settings.fontFamily) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
            },
            selectedContent = {
                val actualFamily = settings.customFonts
                    .firstOrNull { it.name == settings.fontFamily }
                    ?.familyName
                    ?: settings.fontFamily
                Text(
                    text = actualFamily,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = systemFontFamily(actualFamily),
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        )

        // Font size slider
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Font size", style = MaterialTheme.typography.labelLarge)
                Text(
                    "${settings.fontSize.toInt()} sp", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Slider(
                value = settings.fontSize,
                onValueChange = { onSettingsChange(settings.copy(fontSize = it)) },
                valueRange = MIN_FONT_SIZE_SP..MAX_FONT_SIZE_SP
            )
        }

        // Font weight slider
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Font weight", style = MaterialTheme.typography.labelLarge)
                Text(
                    "${settings.fontWeight}", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Slider(
                value = settings.fontWeight.toFloat(),
                onValueChange = { onSettingsChange(settings.copy(fontWeight = it.toInt())) },
                valueRange = 100f..900f,
                steps = 7 // 100, 200, ..., 900
            )
        }

        // Line height slider
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Line height", style = MaterialTheme.typography.labelLarge)
                Text(
                    "%.1f".format(settings.lineHeight), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Slider(
                value = settings.lineHeight,
                onValueChange = { onSettingsChange(settings.copy(lineHeight = it)) },
                valueRange = 1.0f..3.0f
            )
        }

        // Letter spacing slider
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Letter spacing", style = MaterialTheme.typography.labelLarge)
                Text(
                    "%.2f".format(settings.letterSpacing), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Slider(
                value = settings.letterSpacing,
                onValueChange = { onSettingsChange(settings.copy(letterSpacing = it)) },
                valueRange = -0.1f..0.3f
            )
        }

        // Word spacing slider
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Word spacing", style = MaterialTheme.typography.labelLarge)
                Text(
                    "%.2f".format(settings.wordSpacing), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Slider(
                value = settings.wordSpacing,
                onValueChange = { onSettingsChange(settings.copy(wordSpacing = it)) },
                valueRange = -0.2f..0.5f
            )
        }

        // Paragraph spacing slider
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Paragraph spacing", style = MaterialTheme.typography.labelLarge)
                Text(
                    "%.1fx".format(settings.paragraphSpacing), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Slider(
                value = settings.paragraphSpacing,
                onValueChange = { onSettingsChange(settings.copy(paragraphSpacing = it)) },
                valueRange = 0.5f..3.0f
            )
        }
    }
}

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

        // Layout mode dropdown
        DropdownMenuButton(
            label = "Layout mode",
            selected = settings.layoutMode.name,
            options = LayoutMode.entries.map { it.name },
            onChange = { name ->
                val mode = LayoutMode.entries.first { it.name == name }
                onSettingsChange(settings.copy(layoutMode = mode))
            }
        )

        // Text width dropdown
        DropdownMenuButton(
            label = "Text width",
            selected = settings.textWidth.name,
            options = TextWidth.entries.map { it.name },
            onChange = { name ->
                val width = TextWidth.entries.first { it.name == name }
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

@Composable
fun FormattingSettingsPanel(
    settings: ReaderSettings,
    onSettingsChange: (ReaderSettings) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Formatting", style = MaterialTheme.typography.titleLarge)

        // Formatting mode dropdown
        DropdownMenuButton(
            label = "Formatting mode",
            selected = settings.formattingMode.name,
            options = FormattingMode.entries.map { it.name },
            onChange = { name ->
                val mode = FormattingMode.entries.first { it.name == name }
                onSettingsChange(settings.copy(formattingMode = mode))
            }
        )

        DropdownMenuButton(
            label = "Default text alignment",
            selected = settings.alignment.name,
            options = TextAlignment.entries.map { it.name },
            onChange = { name ->
                val alignment = TextAlignment.entries.first { it.name == name }
                onSettingsChange(settings.copy(alignment = alignment))
            }
        )

        Text(
            "Hybrid keeps explicit EPUB alignment for title pages and special paragraphs. " +
                "Normalized uses this default everywhere.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

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

/** Smallest reader text size in sp — matches the reader quick-settings range. */
private const val MIN_FONT_SIZE_SP = 12f

/** Largest reader text size in sp — lowered from 36sp to keep sizes sane. */
private const val MAX_FONT_SIZE_SP = 24f

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
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
        Text("Advanced Settings", style = MaterialTheme.typography.titleLarge)
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

@Composable
fun CloudSyncSettingsPanel(
    settings: ReaderSettings,
    syncState: com.folio.reader.sync.SyncState,
    onSettingsChange: (ReaderSettings) -> Unit,
    onSyncNow: () -> Unit
) {
    var apiKey by remember(settings.firebaseApiKey) { mutableStateOf(settings.firebaseApiKey) }
    var projectId by remember(settings.firebaseProjectId) { mutableStateOf(settings.firebaseProjectId) }
    var accountEmail by remember(settings.syncAccountEmail) { mutableStateOf(settings.syncAccountEmail) }
    var accountPassword by remember(settings.syncAccountPassword) { mutableStateOf(settings.syncAccountPassword) }
    val isConnected = settings.firebaseApiKey.isNotBlank() && settings.firebaseProjectId.isNotBlank()

    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Cloud Sync", style = MaterialTheme.typography.titleLarge)

        // Real-time sync status card
        com.folio.reader.ui.components.SyncStatusCard(
            syncState = syncState,
            isConnected = isConnected,
            onSyncNow = onSyncNow
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Icon(
                        imageVector = if (isConnected) androidx.compose.material.icons.Icons.Filled.CheckCircle
                        else androidx.compose.material.icons.Icons.Filled.Info,
                        contentDescription = null,
                        tint = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            if (isConnected) "Cloud sync is active" else "Cloud sync is off",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            if (isConnected)
                                "Syncing via project \"${settings.firebaseProjectId}\""
                            else
                                "Connect your Firebase account to sync books, progress and annotations across devices.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                OutlinedTextField(
                    value = projectId,
                    onValueChange = { projectId = it },
                    label = { Text("Firebase Project ID") },
                    placeholder = { Text("my-folio-project") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("Firebase Web API Key") },
                    placeholder = { Text("AIza...") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = accountEmail,
                    onValueChange = { accountEmail = it },
                    label = { Text("Sync Account Email (Optional)") },
                    placeholder = { Text("Same email on all devices to link sync") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = accountPassword,
                    onValueChange = { accountPassword = it },
                    label = { Text("Sync Account Password (Optional)") },
                    placeholder = { Text("Same password across devices") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            onSettingsChange(
                                settings.copy(
                                    firebaseApiKey = apiKey.trim(),
                                    firebaseProjectId = projectId.trim(),
                                    syncAccountEmail = accountEmail.trim(),
                                    syncAccountPassword = accountPassword.trim(),
                                    cloudSyncEnabled = true // Enable cloud sync when credentials saved
                                )
                            )
                        },
                        enabled = apiKey.isNotBlank() && projectId.isNotBlank() &&
                                (apiKey != settings.firebaseApiKey || projectId != settings.firebaseProjectId ||
                                        accountEmail != settings.syncAccountEmail || accountPassword != settings.syncAccountPassword)
                    ) {
                        Text("Save")
                    }
                    if (isConnected) {
                        OutlinedButton(onClick = {
                            apiKey = ""
                            projectId = ""
                            accountEmail = ""
                            accountPassword = ""
                            onSettingsChange(
                                settings.copy(
                                    firebaseApiKey = "",
                                    firebaseProjectId = "",
                                    syncAccountEmail = "",
                                    syncAccountPassword = "",
                                    cloudSyncEnabled = false
                                )
                            )
                        }) {
                            Text("Disconnect")
                        }
                    }
                }

                // Cloud sync opt-in toggle and granular controls
                if (isConnected) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text("Sync Preferences", style = MaterialTheme.typography.titleSmall)

                    // Master cloud sync toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Enable cloud sync", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Sync your data across devices",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = settings.cloudSyncEnabled,
                            onCheckedChange = { onSettingsChange(settings.copy(cloudSyncEnabled = it)) }
                        )
                    }

                    // Granular sync options (only shown when cloud sync enabled)
                    if (settings.cloudSyncEnabled) {
                        Spacer(Modifier.height(8.dp))

                        SyncToggleRow(
                            label = "Reading progress",
                            description = "Sync position across sessions & devices",
                            checked = settings.syncPositions,
                            onCheckedChange = { onSettingsChange(settings.copy(syncPositions = it)) }
                        )
                        SyncToggleRow(
                            label = "Annotations",
                            description = "Highlights, notes & bookmarks",
                            checked = settings.syncAnnotations,
                            onCheckedChange = { onSettingsChange(settings.copy(syncAnnotations = it)) }
                        )
                        SyncToggleRow(
                            label = "Reading settings",
                            description = "Typography & appearance preferences",
                            checked = settings.syncSettings,
                            onCheckedChange = { onSettingsChange(settings.copy(syncSettings = it)) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SyncToggleRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun SettingsLivePreview(settings: ReaderSettings) {
    val theme = settings.customTheme ?: Theme.getPreset(settings.themeId)
    val fontFamily = systemFontFamily(settings.fontFamily)
    val align = when (settings.alignment) {
        TextAlignment.CENTER -> TextAlign.Center
        TextAlignment.JUSTIFIED -> TextAlign.Justify
        else -> TextAlign.Start
    }
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(shape)
            .background(Color(theme.background))
            .border(1.dp, Color(theme.divider), shape)
            .padding(
                horizontal = settings.margins.left.coerceIn(0f, 28f).dp,
                vertical = 18.dp
            )
    ) {
        Text(
            text = "Chapter One",
            style = TextStyle(
                fontFamily = fontFamily,
                fontSize = (settings.fontSize * 1.3f).sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = settings.letterSpacing.sp,
                color = Color(theme.headingText),
                textAlign = align
            )
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "The reading room was quiet but for the rain, and the lamplight had " +
                "made a small country of its own on the table, where the pages waited " +
                "for someone to turn them.",
            style = TextStyle(
                fontFamily = fontFamily,
                fontSize = settings.fontSize.sp,
                lineHeight = (settings.fontSize * settings.lineHeight).sp,
                fontWeight = FontWeight(settings.fontWeight),
                letterSpacing = settings.letterSpacing.sp,
                color = Color(theme.primaryText),
                textAlign = align
            )
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "${settings.fontFamily} · ${settings.fontSize.toInt()}sp · " +
                "${"%.1f".format(settings.lineHeight)} line · ${theme.name}",
            style = TextStyle(fontSize = 11.sp, color = Color(theme.secondaryText))
        )
    }
}
