package com.folio.reader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.folio.reader.settings.ReaderSettings
import com.folio.reader.settings.normalized
import com.folio.reader.ui.components.FolioChip
import com.folio.reader.ui.components.folioVeil
import com.folio.reader.ui.components.glassPanel
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens

/** Smallest reader text size in sp — unchanged; users liked the low end. */
private const val MIN_FONT_SIZE_SP = 12f

/**
 * Largest reader text size in sp. Lowered from 36sp: even mid-range sizes looked
 * oversized on phones, so the top of the slider was brought down to 24sp.
 */
private const val MAX_FONT_SIZE_SP = 24f

/**
 * Thorium-style quick reading settings: font size stepper, typeface dropdown,
 * a scrollable theme slider with live mini previews, line spacing and margins
 * — applied live via [onSettingsChange].
 *
 * When [scopeControlEnabled] is set (Android), the panel also carries the §4.2
 * scope control: "This book" keeps writing the per-book snapshot through
 * [onSettingsChange]; "All books" routes the write through [onWriteGlobal].
 * [overriddenFields] drives the per-row override dots and the reset button.
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
    // Calluna and Comfortaa ship with the app (see BundledFonts) and appear both
    // here and via the customFonts entries; the rest resolve to system fonts.
    val fonts = listOf(
        "Calluna", "Comfortaa", "Literata", "Merriweather", "Georgia", "EB Garamond", "Lora",
        "Open Sans", "Inter", "Noto Serif", "Serif", "Sans Serif", "Monospace"
    )
    val availableFonts = (fonts + settings.customFonts.map { it.name }).distinct()
    fun actualFontName(font: String): String =
        settings.customFonts.firstOrNull { it.name == font }?.familyName ?: font
    val quickThemes = com.folio.reader.settings.Theme.PICKER.map { it.id }
    val panelShape = RoundedCornerShape(topStart = 26.dp, bottomStart = 26.dp)

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(280.dp)
            .folioVeil(panelShape)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Reading settings", style = FolioTheme.typography.titleMedium)
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = "Close")
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

        // Font size: A− / slider / A+
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FolioTokens.space1)
            ) {
                Text("Text size", style = FolioTheme.typography.labelLarge, color = FolioTheme.colors.onSurfaceVariant)
                OverrideDot("fontSize" in overriddenFields)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconButton(
                    onClick = { apply(settings.copy(fontSize = (settings.fontSize - 1f).coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP))) },
                    modifier = Modifier.background(FolioTheme.colors.surfaceVariant, RoundedCornerShape(8.dp))
                ) { Text("A−", style = FolioTheme.typography.titleSmall) }
                androidx.compose.material3.Slider(
                    value = settings.fontSize,
                    onValueChange = { apply(settings.copy(fontSize = it)) },
                    valueRange = MIN_FONT_SIZE_SP..MAX_FONT_SIZE_SP,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = { apply(settings.copy(fontSize = (settings.fontSize + 1f).coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP))) },
                    modifier = Modifier.background(FolioTheme.colors.surfaceVariant, RoundedCornerShape(8.dp))
                ) { Text("A+", style = FolioTheme.typography.titleMedium) }
            }
        }

        QuickChoiceRow(
            label = "Layout",
            options = buildList {
                add("CONTINUOUS" to "Scroll")
                add("PAGINATED" to "Page")
                if (com.folio.reader.ui.render.htmlSurfaceOccludesOverlays()) add("SPREAD" to "Double Page")
            },
            selected = settings.layoutMode.normalized.name,
            onSelect = { name ->
                val mode = com.folio.reader.settings.LayoutMode.entries.firstOrNull { it.name == name }
                if (mode != null) apply(settings.copy(layoutMode = mode))
            },
            overrideDot = "layoutMode" in overriddenFields
        )

        // Font family dropdown
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                        .background(FolioTheme.colors.surfaceVariant, RoundedCornerShape(10.dp))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        actualFontName(settings.fontFamily),
                        style = FolioTheme.typography.bodyMedium,
                        fontFamily = com.folio.reader.ui.components.systemFontFamily(
                            actualFontName(settings.fontFamily)
                        )
                    )
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null)
                }
                DropdownMenu(
                    expanded = fontMenuOpen,
                    onDismissRequest = { fontMenuOpen = false },
                    modifier = Modifier
                        .width(248.dp)
                        .background(Color.Transparent)
                        .glassPanel(RoundedCornerShape(8.dp))
                ) {
                    availableFonts.forEach { font ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    actualFontName(font),
                                    fontFamily = com.folio.reader.ui.components.systemFontFamily(actualFontName(font)),
                                    fontWeight = if (font == settings.fontFamily) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (font == settings.fontFamily) FolioTheme.colors.primary
                                    else FolioTheme.colors.onSurface
                                )
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

            // Theme: scrollable/draggable vertical slider of live mini previews.
            // Opens scrolled to the active theme; tap a preview to apply it.
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                    verticalArrangement = Arrangement.spacedBy(6.dp),
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
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
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
                                    tint = FolioTheme.colors.primary,
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
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(FolioTokens.space1)
                ) {
                    Text("Highlight", style = FolioTheme.typography.labelLarge, color = FolioTheme.colors.onSurfaceVariant)
                    OverrideDot("highlightColorIndex" in overriddenFields)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (settings.customTheme ?: com.folio.reader.settings.Theme.getPreset(settings.themeId))
                        .highlightColors.forEachIndexed { index, argb ->
                            val selected = index == settings.highlightColorIndex
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(RoundedCornerShape(7.dp))
                                    .background(Color(argb))
                                    .border(
                                        width = if (selected) 2.dp else 1.dp,
                                        color = if (selected) FolioTheme.colors.primary else FolioTheme.colors.outline,
                                        shape = RoundedCornerShape(7.dp)
                                    )
                                    .clickable {
                                        apply(settings.copy(highlightColorIndex = index))
                                    }
                            )
                        }
                }
            }

            // Light — the device's own screen brightness. Dimming the page instead
            // washed the ink, and brightness is a property of the hardware, not a
            // reading preference worth storing or syncing.
            val brightness = com.folio.reader.ui.components.rememberScreenBrightness()
            if (brightness.supported) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Light", style = FolioTheme.typography.labelLarge, color = FolioTheme.colors.onSurfaceVariant)
                        Text("${(brightness.value * 100).toInt()}%", style = FolioTheme.typography.labelLarge, color = FolioTheme.colors.primary)
                    }
                    androidx.compose.material3.Slider(
                        value = brightness.value,
                        onValueChange = { brightness.set(it) },
                        valueRange = 0.05f..1f
                    )
                }
            }

            // Line spacing
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(FolioTokens.space1)
                    ) {
                        Text("Line spacing", style = FolioTheme.typography.labelLarge, color = FolioTheme.colors.onSurfaceVariant)
                        OverrideDot("lineHeight" in overriddenFields)
                    }
                    Text("%.1f".format(settings.lineHeight), style = FolioTheme.typography.labelLarge, color = FolioTheme.colors.primary)
                }
                androidx.compose.material3.Slider(
                    value = settings.lineHeight,
                    onValueChange = { apply(settings.copy(lineHeight = it)) },
                    valueRange = 1f..3f
                )
            }

            // Margins
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(FolioTokens.space1)
                    ) {
                        Text("Margins", style = FolioTheme.typography.labelLarge, color = FolioTheme.colors.onSurfaceVariant)
                        OverrideDot("margins" in overriddenFields)
                    }
                    Text("${settings.margins.left.toInt()} dp", style = FolioTheme.typography.labelLarge, color = FolioTheme.colors.primary)
                }
                androidx.compose.material3.Slider(
                    value = settings.margins.left,
                    onValueChange = {
                        apply(settings.copy(margins = settings.margins.copy(left = it, right = it)))
                    },
                    valueRange = 0f..64f
                )
            }

            if (scopeControlEnabled) {
                androidx.compose.material3.TextButton(
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
                    .glassPanel(RoundedCornerShape(12.dp))
                    .clickable { onOpenFullSettings() }
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "All settings",
                    style = FolioTheme.typography.labelLarge,
                    color = FolioTheme.colors.primary
                )
            }
        }
    }
