package com.folio.reader.ui.reader

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
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.folio.reader.settings.ReaderSettings
import com.folio.reader.settings.normalized
import com.folio.reader.ui.components.FolioChip
import com.folio.reader.ui.components.FolioEyebrow
import com.folio.reader.ui.components.FolioRule
import com.folio.reader.ui.components.FolioSegmented
import com.folio.reader.ui.components.FolioSlider
import com.folio.reader.ui.components.FolioSliderRow
import com.folio.reader.ui.components.folioVeil
import com.folio.reader.ui.components.glassPanel
import com.folio.reader.ui.components.rememberFolioSheetMorphShape
import com.folio.reader.ui.components.rememberLegibleAccent
import com.folio.reader.ui.theme.FolioShapes
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens
import com.folio.reader.ui.theme.readerVeilAlpha

/** Smallest reader text size in sp — unchanged; users liked the low end. */
private const val MIN_FONT_SIZE_SP = 12f

/**
 * Largest reader text size in sp. Lowered from 36sp: even mid-range sizes looked
 * oversized on phones, so the top of the slider was brought down to 24sp.
 */
private const val MAX_FONT_SIZE_SP = 24f

/**
 * Thorium-style quick reading settings — reorganised from one long scroll into
 * three labelled movements (Text · Page · Comfort) separated by hairline rules,
 * so an opened panel reads as grouped sections rather than a stack of controls.
 * Applied live via [onSettingsChange].
 *
 * When [scopeControlEnabled] is set (Android), the panel also carries the §4.2
 * scope control: "This book" keeps writing the per-book snapshot through
 * [onSettingsChange]; "All books" routes the write through [onWriteGlobal].
 * [overriddenFields] drives the per-row override dots and the reset button.
 * Eye protection is a global comfort preference, so it always writes the global
 * row regardless of the scope toggle.
 */
@Composable
fun ReaderSettingsPanel(
    settings: ReaderSettings,
    onSettingsChange: (ReaderSettings) -> Unit,
    onDismiss: () -> Unit,
    onOpenFullSettings: () -> Unit,
    scopeControlEnabled: Boolean = false,
    overriddenFields: Set<String> = emptySet(),
    onWriteGlobal: ((ReaderSettings) -> Unit)? = null,
    onResetBook: (() -> Unit)? = null
) {
    var fontMenuOpen by remember { mutableStateOf(false) }
    var applyToAll by remember { mutableStateOf(false) }
    val apply: (ReaderSettings) -> Unit = { updated ->
        if (scopeControlEnabled && applyToAll && onWriteGlobal != null) onWriteGlobal(updated)
        else onSettingsChange(updated)
    }
    // Eye protection lives only in the global row (never a per-book override), so
    // it writes the global defaults whichever scope the chips are on.
    val applyGlobal: (ReaderSettings) -> Unit = { updated -> onWriteGlobal?.invoke(updated) ?: onSettingsChange(updated) }
    // Platform-resolvable faces first (the Android WebView can only render the
    // generics and the imported faces — no @font-face exists for the bundled
    // names, where they read as placeholders that did nothing when tapped).
    val fonts = platformBaseReaderFonts()
    val availableFonts = (fonts + settings.customFonts.map { it.name }).distinct()
    fun actualFontName(font: String): String =
        settings.customFonts.firstOrNull { it.name == font }?.familyName ?: font
    val quickThemes = com.folio.reader.settings.Theme.PICKER.map { it.id }
    // §16: the sweep enters reading as a capsule and settles to 26dp.
    val panelShape = rememberFolioSheetMorphShape(RoundedCornerShape(topStart = 26.dp, bottomStart = 26.dp))
    val accentText = rememberLegibleAccent(FolioTheme.colors.primary)

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(280.dp)
            .folioVeil(panelShape, fillAlpha = FolioTheme.readerVeilAlpha)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = FolioTokens.space3, vertical = FolioTokens.spaceBeat),
        verticalArrangement = Arrangement.spacedBy(FolioTokens.space3)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Reading settings",
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

        // §4.2: every write states which setting it is — this book's snapshot or
        // the defaults every book follows.
        if (scopeControlEnabled) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FolioTokens.space1)
            ) {
                Text(
                    "Applies to:",
                    style = FolioTheme.typography.labelLarge,
                    color = FolioTheme.colors.onSurfaceVariant
                )
                FolioChip(
                    selected = !applyToAll,
                    onClick = { applyToAll = false },
                    label = "This book",
                    modifier = Modifier.semantics { selected = !applyToAll }
                )
                FolioChip(
                    selected = applyToAll,
                    onClick = { applyToAll = true },
                    label = "All books",
                    modifier = Modifier.semantics { selected = applyToAll }
                )
            }
        }

        // ── TEXT ────────────────────────────────────────────────────────────
        PanelSection("Text") {
            // Font size: A− / slider / A+
            Column(verticalArrangement = Arrangement.spacedBy(FolioTokens.space1)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(FolioTokens.space1)
                ) {
                    Text("Text size", style = FolioTheme.typography.labelLarge, color = FolioTheme.colors.onSurfaceVariant)
                    OverrideDot("fontSize" in overriddenFields)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FolioTokens.space2)) {
                    IconButton(
                        onClick = { apply(settings.copy(fontSize = (settings.fontSize - 1f).coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP))) },
                        modifier = Modifier.background(FolioTheme.colors.surfaceVariant, FolioShapes.chip)
                    ) {
                        Text("A−", style = FolioTheme.typography.titleSmall, color = FolioTheme.colors.onSurface)
                    }
                    FolioSlider(
                        value = settings.fontSize,
                        onValueChange = { apply(settings.copy(fontSize = it)) },
                        valueRange = MIN_FONT_SIZE_SP..MAX_FONT_SIZE_SP,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { apply(settings.copy(fontSize = (settings.fontSize + 1f).coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP))) },
                        modifier = Modifier.background(FolioTheme.colors.surfaceVariant, FolioShapes.chip)
                    ) {
                        Text("A+", style = FolioTheme.typography.titleMedium, color = FolioTheme.colors.onSurface)
                    }
                }
            }

            // Font family dropdown
            Column(verticalArrangement = Arrangement.spacedBy(FolioTokens.space1)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(FolioTokens.space1)
                ) {
                    Text("Typeface", style = FolioTheme.typography.labelLarge, color = FolioTheme.colors.onSurfaceVariant)
                    OverrideDot("fontFamily" in overriddenFields)
                }
                Box(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { fontMenuOpen = true }
                            .background(FolioTheme.colors.surfaceVariant, FolioShapes.inset)
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            actualFontName(settings.fontFamily),
                            style = FolioTheme.typography.bodyMedium,
                            color = FolioTheme.colors.onSurface,
                            fontFamily = com.folio.reader.ui.components.systemFontFamily(
                                actualFontName(settings.fontFamily)
                            )
                        )
                        Icon(
                            Icons.Filled.KeyboardArrowDown,
                            contentDescription = null,
                            tint = FolioTheme.colors.onSurfaceVariant
                        )
                    }
                    DropdownMenu(
                        expanded = fontMenuOpen,
                        onDismissRequest = { fontMenuOpen = false },
                        modifier = Modifier
                            .width(248.dp)
                            .background(Color.Transparent)
                            .glassPanel(FolioShapes.inset)
                    ) {
                        availableFonts.forEach { font ->
                            // The label is the app UI font (uniform size, aligned
                            // rows); the specimen keeps each option's own face at a
                            // fixed size so the font's character stays visible
                            // without its optical size dictating the row.
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(FolioTokens.space2)
                                    ) {
                                        Text(
                                            actualFontName(font),
                                            style = FolioTheme.typography.bodyMedium,
                                            fontWeight = if (font == settings.fontFamily) FontWeight.SemiBold else FontWeight.Normal,
                                            color = if (font == settings.fontFamily) accentText
                                            else FolioTheme.colors.onSurface,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(
                                            "Ag",
                                            fontFamily = com.folio.reader.ui.components.systemFontFamily(
                                                actualFontName(font)
                                            ),
                                            fontSize = 18.sp,
                                            lineHeight = 22.sp,
                                            color = FolioTheme.colors.onSurfaceVariant,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.width(34.dp)
                                        )
                                    }
                                },
                                onClick = {
                                    apply(settings.copy(fontFamily = font))
                                    fontMenuOpen = false
                                }
                            )
                        }
                    }
                }
            }

            // Font weight — a first-class in-reader lever now the bundled faces
            // are variable (BundledFonts.isVariable), so every step from Light to
            // Bold renders from the one file instead of snapping to a pinned face.
            SegmentedRow(
                label = "Weight",
                overridden = "fontWeight" in overriddenFields,
                options = listOf(
                    "300" to "Light",
                    "400" to "Regular",
                    "500" to "Medium",
                    "700" to "Bold",
                ),
                selectedValue = settings.fontWeight.toString(),
                onSelect = { value -> value.toIntOrNull()?.let { apply(settings.copy(fontWeight = it)) } },
            )

            // Line spacing
            LabeledSlider(
                label = "Line spacing",
                valueLabel = "%.1f".format(settings.lineHeight),
                overridden = "lineHeight" in overriddenFields,
                value = settings.lineHeight,
                valueRange = 1f..3f,
                accent = accentText,
                onValueChange = { apply(settings.copy(lineHeight = it)) },
            )

            // Paragraph spacing — the gap between paragraphs, authored in em so
            // it tracks text size. Pairs with line spacing above.
            LabeledSlider(
                label = "Paragraph spacing",
                valueLabel = "%.1f".format(settings.paragraphSpacing),
                overridden = "paragraphSpacing" in overriddenFields,
                value = settings.paragraphSpacing,
                valueRange = 0f..2.5f,
                accent = accentText,
                onValueChange = { apply(settings.copy(paragraphSpacing = it)) },
            )
        }

        FolioRule()

        // ── PAGE ────────────────────────────────────────────────────────────
        PanelSection("Page") {
            SegmentedRow(
                label = "Layout",
                overridden = "layoutMode" in overriddenFields,
                options = buildList {
                    add("CONTINUOUS" to "Scroll")
                    add("PAGINATED" to "Page")
                    if (com.folio.reader.ui.render.htmlSurfaceOccludesOverlays()) add("SPREAD" to "Double Page")
                },
                selectedValue = settings.layoutMode.normalized.name,
                onSelect = { name ->
                    val mode = com.folio.reader.settings.LayoutMode.entries.firstOrNull { it.name == name }
                    if (mode != null) apply(settings.copy(layoutMode = mode))
                },
            )

            // Margins
            LabeledSlider(
                label = "Margins",
                valueLabel = "${settings.margins.left.toInt()} px",
                overridden = "margins" in overriddenFields,
                value = settings.margins.left,
                valueRange = 0f..64f,
                accent = accentText,
                onValueChange = { apply(settings.copy(margins = settings.margins.copy(left = it, right = it))) },
            )

            // Theme: scrollable/draggable vertical slider of live mini previews.
            // Opens scrolled to the active theme; tap a preview to apply it.
            Column(verticalArrangement = Arrangement.spacedBy(FolioTokens.space1)) {
                val selectedThemeId = settings.customTheme?.id ?: settings.themeId
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(FolioTokens.space1)
                ) {
                    Text("Theme", style = FolioTheme.typography.labelLarge, color = FolioTheme.colors.onSurfaceVariant)
                    OverrideDot("themeId" in overriddenFields || "customTheme" in overriddenFields)
                }
                val listState = rememberLazyListState()
                val selectedIndex = quickThemes.indexOf(selectedThemeId).coerceAtLeast(0)
                LaunchedEffect(selectedThemeId) {
                    listState.scrollToItem(index = selectedIndex)
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.heightIn(max = 236.dp),
                    verticalArrangement = Arrangement.spacedBy(FolioTokens.space1),
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 1.dp)
                ) {
                    items(
                        count = quickThemes.size,
                        key = { quickThemes[it] }
                    ) { index ->
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
                                        onClick = { apply(settings.copy(themeId = id, customTheme = null)) }
                                    )
                            )
                            if (selected) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = "Selected",
                                    tint = accentText,
                                    modifier = Modifier.size(16.dp)
                                )
                            } else {
                                Spacer(Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }

            // Highlight colour: picked from the active theme's own palette, so the
            // wash always stays inside the theme's contrast budget.
            Column(verticalArrangement = Arrangement.spacedBy(FolioTokens.space1)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(FolioTokens.space1)
                ) {
                    Text("Highlight", style = FolioTheme.typography.labelLarge, color = FolioTheme.colors.onSurfaceVariant)
                    OverrideDot("highlightColorIndex" in overriddenFields)
                }
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
                                    .clickable {
                                        apply(settings.copy(highlightColorIndex = index))
                                    }
                            )
                        }
                }
            }
        }

        FolioRule()

        // ── COMFORT ─────────────────────────────────────────────────────────
        PanelSection("Comfort") {
            // Light — the device's own screen brightness. Dimming the page instead
            // washed the ink, and brightness is a property of the hardware, not a
            // reading preference worth storing or syncing.
            val brightness = com.folio.reader.ui.components.rememberScreenBrightness()
            if (brightness.supported) {
                FolioSliderRow(
                    label = "Light",
                    valueLabel = "${(brightness.value * 100).toInt()}%",
                    value = brightness.value,
                    onValueChange = { brightness.set(it) },
                    valueRange = 0.05f..1f,
                    accent = accentText,
                )
            }

            // Eye protection — a warm, blue-light-reducing wash over the page.
            // Global comfort preference: always writes the defaults row.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = settings.eyeProtection,
                        role = Role.Switch,
                        onValueChange = { applyGlobal(settings.copy(eyeProtection = it)) },
                    ),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Eye protection", style = FolioTheme.typography.labelLarge, color = FolioTheme.colors.onSurface)
                    Text(
                        "Warm filter that eases blue light",
                        style = FolioTheme.typography.bodySmall,
                        color = FolioTheme.colors.onSurfaceVariant
                    )
                }
                Switch(
                    checked = settings.eyeProtection,
                    onCheckedChange = null,
                    colors = SwitchDefaults.colors(checkedTrackColor = FolioTheme.colors.primary)
                )
            }
            if (settings.eyeProtection) {
                FolioSliderRow(
                    label = "Warmth",
                    valueLabel = "${(settings.eyeProtectionIntensity * 100).toInt()}%",
                    value = settings.eyeProtectionIntensity,
                    onValueChange = { applyGlobal(settings.copy(eyeProtectionIntensity = it.coerceIn(0f, 1f))) },
                    valueRange = 0f..1f,
                    accent = accentText,
                )
            }
        }

        if (scopeControlEnabled) {
            TextButton(
                onClick = { onResetBook?.invoke() },
                enabled = overriddenFields.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Reset this book to defaults", style = FolioTheme.typography.labelLarge)
            }
        }

        // All settings — frosted glass pill
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .glassPanel(FolioShapes.inset)
                .clickable { onOpenFullSettings() }
                .padding(vertical = FolioTokens.space1),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "All settings",
                style = FolioTheme.typography.labelLarge,
                color = accentText
            )
        }
    }
}

/** A titled group: an editorial eyebrow over its rows, so the panel reads in movements. */
@Composable
private fun PanelSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(FolioTokens.space2)) {
        FolioEyebrow(title)
        content()
    }
}

/** A labelled row with an override dot whose control is the animated segmented control. */
@Composable
private fun SegmentedRow(
    label: String,
    overridden: Boolean,
    options: List<Pair<String, String>>,
    selectedValue: String,
    onSelect: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(FolioTokens.space1)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FolioTokens.space1)
        ) {
            Text(label, style = FolioTheme.typography.labelLarge, color = FolioTheme.colors.onSurfaceVariant)
            OverrideDot(overridden)
        }
        val index = options.indexOfFirst { it.first == selectedValue }.coerceAtLeast(0)
        FolioSegmented(
            options = options.map { it.second },
            selectedIndex = index,
            onSelect = { i -> onSelect(options[i].first) },
        )
    }
}

/** A slider row carrying an override dot beside its label. */
@Composable
private fun LabeledSlider(
    label: String,
    valueLabel: String,
    overridden: Boolean,
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FolioTokens.space1)
            ) {
                Text(label, style = FolioTheme.typography.labelLarge, color = FolioTheme.colors.onSurfaceVariant)
                OverrideDot(overridden)
            }
            Text(valueLabel, style = FolioTheme.typography.labelLarge, color = accent)
        }
        FolioSlider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange
        )
    }
}
