package com.folio.reader.ui.document

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.folio.reader.settings.DocumentSettings
import com.folio.reader.settings.LayoutMode
import com.folio.reader.settings.normalized
import com.folio.reader.ui.components.FolioEyebrow
import com.folio.reader.ui.components.FolioRule
import com.folio.reader.ui.components.FolioSegmented
import com.folio.reader.ui.components.FolioSlider
import com.folio.reader.ui.reader.ReaderTypefacePicker
import com.folio.reader.ui.reader.readerSettingsSheet
import com.folio.reader.ui.components.folioSettingsChoice
import com.folio.reader.ui.components.rememberFolioSheetMorphShape
import com.folio.reader.ui.components.rememberLegibleAccent
import com.folio.reader.ui.reader.ThemePreviewCard
import com.folio.reader.ui.reader.platformBaseReaderFonts
import com.folio.reader.ui.theme.FolioShapes
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens

/** Smallest document text size in sp, mirroring the reader panel. */
private const val MIN_FONT_SIZE_SP = 12f

/** Largest document text size in sp, mirroring the reader panel. */
private const val MAX_FONT_SIZE_SP = 24f

/**
 * The document reader's own quick settings — the counterpart of
 * [com.folio.reader.ui.reader.ReaderSettingsPanel], bound to [DocumentSettings]
 * instead of the book row.
 *
 * It mirrors the book reader's movements (Text · Page) so the two panels are the
 * same object in two places, and deliberately omits the per-item scope control:
 * a document does not take its own copy, so every write lands on the document
 * defaults and there is no "this document / all documents" distinction to make.
 * The app-level comfort toggles (eye protection, editorial styling, brightness)
 * live on the book row and are reached from "All settings".
 */
@Composable
fun DocumentSettingsPanel(
    settings: DocumentSettings,
    onSettingsChange: (DocumentSettings) -> Unit,
    onDismiss: () -> Unit,
    onOpenFullSettings: () -> Unit,
    /** Imported font names from the global row, so documents can use them too. */
    extraFonts: List<String> = emptyList(),
    customFonts: List<com.folio.reader.settings.CustomFont> = emptyList(),
) {
    val fonts = (platformBaseReaderFonts() + extraFonts + customFonts.map { it.name }).distinct()
    val quickThemes = com.folio.reader.settings.Theme.PICKER.map { it.id }
    val panelShape = rememberFolioSheetMorphShape(
        RoundedCornerShape(topStart = FolioTokens.radiusSheetSweep, bottomStart = FolioTokens.radiusSheetSweep)
    )
    val accentText = rememberLegibleAccent(FolioTheme.colors.primary)
    val embedded = com.folio.reader.ui.reader.LocalReaderWorkspace.current

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .then(if (embedded) Modifier.fillMaxWidth() else Modifier.width(280.dp).readerSettingsSheet(panelShape))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = FolioTokens.space3, vertical = FolioTokens.spaceBeat)
            .padding(bottom = if (embedded) com.folio.reader.ui.reader.readerImeBottomPadding() else 0.dp),
        verticalArrangement = Arrangement.spacedBy(FolioTokens.space3)
    ) {
        if (!embedded) Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Document settings",
                style = FolioTheme.typography.titleMedium,
                color = FolioTheme.colors.onSurface
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Close",
                    tint = FolioTheme.colors.onSurfaceVariant
                )
            }
        }
        Text(
            "These apply to every document, not to your books.",
            style = FolioTheme.typography.bodySmall,
            color = FolioTheme.colors.onSurfaceVariant
        )

        // ── TEXT ────────────────────────────────────────────────────────────
        PanelSection("Text") {
            Column(verticalArrangement = Arrangement.spacedBy(FolioTokens.space1)) {
                Text("Text size", style = FolioTheme.typography.labelLarge, color = FolioTheme.colors.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioTokens.space2)) {
                    IconButton(
                        onClick = { onSettingsChange(settings.copy(fontSize = (settings.fontSize - 1f).coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP))) },
                        modifier = Modifier.folioSettingsChoice(false, FolioShapes.chip)
                    ) {
                        Text("A−", style = FolioTheme.typography.titleSmall, color = FolioTheme.colors.onSurface)
                    }
                    FolioSlider(
                        value = settings.fontSize,
                        onValueChange = { onSettingsChange(settings.copy(fontSize = it)) },
                        valueRange = MIN_FONT_SIZE_SP..MAX_FONT_SIZE_SP,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { onSettingsChange(settings.copy(fontSize = (settings.fontSize + 1f).coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP))) },
                        modifier = Modifier.folioSettingsChoice(false, FolioShapes.chip)
                    ) {
                        Text("A+", style = FolioTheme.typography.titleMedium, color = FolioTheme.colors.onSurface)
                    }
                }
            }

            // Typeface dropdown — same set the reader offers (platform-resolvable faces).
            Column(verticalArrangement = Arrangement.spacedBy(FolioTokens.space1)) {
                Text("Typeface", style = FolioTheme.typography.labelLarge, color = FolioTheme.colors.onSurfaceVariant)
                ReaderTypefacePicker(
                    selectedFont = settings.fontFamily,
                    fonts = fonts,
                    customFonts = customFonts,
                    displayName = { key -> customFonts.firstOrNull { it.name == key }?.familyName ?: key },
                    onSelect = { onSettingsChange(settings.copy(fontFamily = it)) },
                )
            }

            SegmentedRow(
                label = "Weight",
                options = listOf("300" to "Light", "400" to "Regular", "500" to "Medium", "700" to "Bold"),
                selectedValue = settings.fontWeight.toString(),
                onSelect = { value -> value.toIntOrNull()?.let { onSettingsChange(settings.copy(fontWeight = it)) } },
            )

            LabeledSlider(
                label = "Line spacing",
                valueLabel = "%.1f".format(settings.lineHeight),
                value = settings.lineHeight,
                valueRange = 1f..3f,
                accent = accentText,
                onValueChange = { onSettingsChange(settings.copy(lineHeight = it)) },
            )

            LabeledSlider(
                label = "Paragraph spacing",
                valueLabel = "%.1f".format(settings.paragraphSpacing),
                value = settings.paragraphSpacing,
                valueRange = 0f..2.5f,
                accent = accentText,
                onValueChange = { onSettingsChange(settings.copy(paragraphSpacing = it)) },
            )
        }

        FolioRule()

        // ── PAGE ────────────────────────────────────────────────────────────
        PanelSection("Page") {
            SegmentedRow(
                label = "Layout",
                options = listOf("CONTINUOUS" to "Scroll", "PAGINATED" to "Page"),
                selectedValue = settings.layoutMode.normalized.name,
                onSelect = { name ->
                    LayoutMode.entries.firstOrNull { it.name == name }
                        ?.let { onSettingsChange(settings.copy(layoutMode = it)) }
                },
            )

            LabeledSlider(
                label = "Side margins",
                valueLabel = "${settings.margins.left.toInt()} px",
                value = settings.margins.left,
                valueRange = 0f..64f,
                accent = accentText,
                onValueChange = { onSettingsChange(settings.copy(margins = settings.margins.copy(left = it, right = it))) },
            )

            // Page theme — the same live mini previews the reader offers.
            Column(verticalArrangement = Arrangement.spacedBy(FolioTokens.space1)) {
                val selectedThemeId = settings.customTheme?.id ?: settings.themeId
                Text("Page theme", style = FolioTheme.typography.labelLarge, color = FolioTheme.colors.onSurfaceVariant)
                val listState = rememberLazyListState()
                val selectedIndex = quickThemes.indexOf(selectedThemeId).coerceAtLeast(0)
                LaunchedEffect(selectedThemeId) {
                    val offScreen = listState.layoutInfo.visibleItemsInfo.none { it.index == selectedIndex }
                    if (offScreen) listState.scrollToItem(index = selectedIndex)
                }
                val themeScrollTrap = remember {
                    object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
                        override fun onPostScroll(
                            consumed: androidx.compose.ui.geometry.Offset,
                            available: androidx.compose.ui.geometry.Offset,
                            source: androidx.compose.ui.input.nestedscroll.NestedScrollSource
                        ): androidx.compose.ui.geometry.Offset = available
                        override suspend fun onPostFling(
                            consumed: androidx.compose.ui.unit.Velocity,
                            available: androidx.compose.ui.unit.Velocity
                        ): androidx.compose.ui.unit.Velocity = available
                    }
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.heightIn(max = 236.dp).nestedScroll(themeScrollTrap),
                    verticalArrangement = Arrangement.spacedBy(FolioTokens.space1),
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 1.dp)
                ) {
                    items(count = quickThemes.size, key = { quickThemes[it] }) { index ->
                        val id = quickThemes[index]
                        val theme = com.folio.reader.settings.Theme.getPreset(id)
                        val selected = id == selectedThemeId
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(FolioTokens.space1)
                        ) {
                            ThemePreviewCard(
                                theme = theme,
                                selected = selected,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = { onSettingsChange(settings.copy(themeId = id, customTheme = null)) }
                                    )
                            )
                            if (selected) {
                                Icon(Icons.Filled.Check, contentDescription = "Selected", tint = accentText, modifier = Modifier.size(16.dp))
                            } else {
                                Spacer(Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }

            // Highlight colour from the active theme's own palette.
            Column(verticalArrangement = Arrangement.spacedBy(FolioTokens.space1)) {
                Text("Highlight", style = FolioTheme.typography.labelLarge, color = FolioTheme.colors.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(FolioTokens.space1)) {
                    (settings.customTheme ?: com.folio.reader.settings.Theme.getPreset(settings.themeId))
                        .highlightColors.forEachIndexed { index, argb ->
                            val selected = index == settings.highlightColorIndex
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(FolioShapes.chip)
                                    .background(Color(argb))
                                    .border(
                                        width = if (selected) 2.dp else 1.dp,
                                        color = if (selected) accentText else FolioTheme.colors.outline,
                                        shape = FolioShapes.chip
                                    )
                                    .clickable { onSettingsChange(settings.copy(highlightColorIndex = index)) }
                            )
                        }
                }
            }

            // Reader display: progress block + clock, mirroring the reader's own toggles.
            ToggleRow(
                label = "Progress bar",
                description = "Show the page block and where you are",
                checked = settings.showProgress,
                onChange = { onSettingsChange(settings.copy(showProgress = it)) },
            )
            ToggleRow(
                label = "Clock",
                description = "Show the time in the title bar",
                checked = settings.showClock,
                onChange = { onSettingsChange(settings.copy(showClock = it)) },
            )
        }

        // Same embedded choice treatment as the other settings actions.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .folioSettingsChoice(false, FolioShapes.inset)
                .clickable { onOpenFullSettings() }
                .padding(vertical = FolioTokens.space1),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("All settings", style = FolioTheme.typography.labelLarge, color = accentText)
        }
    }
}

@Composable
private fun PanelSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(FolioTokens.space2)) {
        FolioEyebrow(title)
        content()
    }
}

@Composable
private fun SegmentedRow(
    label: String,
    options: List<Pair<String, String>>,
    selectedValue: String,
    onSelect: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(FolioTokens.space1)) {
        Text(label, style = FolioTheme.typography.labelLarge, color = FolioTheme.colors.onSurfaceVariant)
        val index = options.indexOfFirst { it.first == selectedValue }.coerceAtLeast(0)
        FolioSegmented(
            options = options.map { it.second },
            selectedIndex = index,
            onSelect = { i -> onSelect(options[i].first) },
        )
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    valueLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    accent: Color,
    onValueChange: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(FolioTokens.space1)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = FolioTheme.typography.labelLarge, color = FolioTheme.colors.onSurfaceVariant)
            Text(valueLabel, style = FolioTheme.typography.labelLarge, color = accent)
        }
        FolioSlider(value = value, onValueChange = onValueChange, valueRange = valueRange)
    }
}

@Composable
private fun ToggleRow(
    label: String,
    description: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = FolioTheme.typography.labelLarge, color = FolioTheme.colors.onSurface)
            Text(description, style = FolioTheme.typography.bodySmall, color = FolioTheme.colors.onSurfaceVariant)
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(checkedTrackColor = FolioTheme.colors.primary)
        )
    }
}
