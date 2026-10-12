package com.folio.reader.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.folio.reader.settings.DocumentSettings
import com.folio.reader.settings.LayoutMode
import com.folio.reader.settings.TextAlignment
import com.folio.reader.settings.normalized
import com.folio.reader.ui.components.DropdownMenuButton
import com.folio.reader.ui.components.FolioEyebrow
import com.folio.reader.ui.components.FolioRule
import com.folio.reader.ui.components.FolioSegmented
import com.folio.reader.ui.components.FolioSlider
import com.folio.reader.ui.reader.platformBaseReaderFonts
import com.folio.reader.ui.theme.FolioTheme

/**
 * The **document** half of the reader defaults — the typography, page and page
 * theme a PDF, Word or HTML document opens with.
 *
 * Documents used to have no defaults of their own: the document reader rendered
 * through the global *book* row, so a Word file inherited whatever typography
 * and page theme a novel was last set to. This panel is the dedicated,
 * document-scoped replacement, and it states the scope out loud — these values
 * apply to documents, never to books, and (unlike a book) a document never takes
 * a private copy, so every document follows them.
 */
@Composable
fun DocumentDefaultsPanel(
    settings: DocumentSettings,
    /** The persisted `documentReaderMode` (SINGLE_PAGE / CONTINUOUS). */
    pageMode: String,
    onSettingsChange: (DocumentSettings) -> Unit,
    onPageModeChange: (String) -> Unit,
    /** Imported font names from the global row, so documents can use them too. */
    extraFonts: List<String> = emptyList(),
) {
    val colors = FolioTheme.colors
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(
            "Word files and the other reflowable documents keep their own reading settings, " +
                "separate from your books. Changing a book's typeface or page theme never moves " +
                "a document, and every reflowable document follows these values.",
            style = FolioTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
        Text(
            "A PDF is a fixed page — its text is part of the page image, so typeface, spacing, " +
                "margins and layout cannot change it. PDFs are not affected by the Text or Page " +
                "settings below; only the page mode applies to them.",
            style = FolioTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )

        // ── Page ────────────────────────────────────────────────────────────
        FolioEyebrow("Page mode (PDF and reflowable)", accent = colors.accentStreak)

        val pageModes = listOf("SINGLE_PAGE" to "Single page", "CONTINUOUS" to "Continuous")
        DropdownMenuButton(
            label = "Page mode",
            selected = pageModes.firstOrNull { it.first == pageMode }?.second ?: "Single page",
            options = pageModes.map { it.second },
            onChange = { label ->
                onPageModeChange(pageModes.first { it.second == label }.first)
            },
        )
        Text(
            "How a document turns: one screen at a time, or pages run into a single scroll. " +
                "This one applies to PDFs as well.",
            style = FolioTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )

        FolioEyebrow("Page (reflowable documents)", accent = colors.accentStreak)

        val layouts = listOf(
            LayoutMode.CONTINUOUS to "Scrolling",
            LayoutMode.PAGINATED to "Paged",
        )
        DropdownMenuButton(
            label = "Reflowable layout",
            selected = layouts.firstOrNull { it.first == settings.layoutMode.normalized }?.second
                ?: "Scrolling",
            options = layouts.map { it.second },
            onChange = { label ->
                onSettingsChange(settings.copy(layoutMode = layouts.first { it.second == label }.first))
            },
        )
        Text(
            "For Word and HTML documents, which reflow to the page. A PDF keeps its own fixed " +
                "layout and ignores this.",
            style = FolioTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )

        LabeledSlider(
            label = "Side margins",
            valueLabel = "${settings.margins.left.toInt()} px",
            value = settings.margins.left,
            valueRange = 0f..64f,
            onValueChange = {
                onSettingsChange(settings.copy(margins = settings.margins.copy(left = it, right = it)))
            },
        )

        // Page theme — a dropdown of the reader's page themes.
        val themes = com.folio.reader.settings.Theme.PICKER
        val themeNames = themes.map { it.name }
        val selectedThemeName = themes.firstOrNull { it.id == (settings.customTheme?.id ?: settings.themeId) }?.name
            ?: themes.first().name
        DropdownMenuButton(
            label = "Page theme",
            selected = selectedThemeName,
            options = themeNames,
            onChange = { name ->
                themes.firstOrNull { it.name == name }?.let {
                    onSettingsChange(settings.copy(themeId = it.id, customTheme = null))
                }
            },
        )

        FolioRule()

        // ── Text ────────────────────────────────────────────────────────────
        FolioEyebrow("Text (reflowable documents)", accent = colors.accentProgress)

        val fonts = (platformBaseReaderFonts() + extraFonts).distinct()
        DropdownMenuButton(
            label = "Typeface",
            selected = settings.fontFamily,
            options = fonts,
            onChange = { onSettingsChange(settings.copy(fontFamily = it)) },
        )

        LabeledSlider(
            label = "Text size",
            valueLabel = "${settings.fontSize.toInt()} sp",
            value = settings.fontSize,
            valueRange = 12f..24f,
            onValueChange = { onSettingsChange(settings.copy(fontSize = it)) },
        )

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "Weight",
                style = FolioTheme.typography.labelLarge,
                color = colors.onSurfaceVariant,
            )
            val weights = listOf("300" to "Light", "400" to "Regular", "500" to "Medium", "700" to "Bold")
            FolioSegmented(
                options = weights.map { it.second },
                selectedIndex = weights.indexOfFirst { it.first == settings.fontWeight.toString() }.coerceAtLeast(0),
                onSelect = { i -> onSettingsChange(settings.copy(fontWeight = weights[i].first.toInt())) },
            )
        }

        LabeledSlider(
            label = "Line spacing",
            valueLabel = "%.1f".format(settings.lineHeight),
            value = settings.lineHeight,
            valueRange = 1f..3f,
            onValueChange = { onSettingsChange(settings.copy(lineHeight = it)) },
        )

        LabeledSlider(
            label = "Paragraph spacing",
            valueLabel = "%.1f".format(settings.paragraphSpacing),
            value = settings.paragraphSpacing,
            valueRange = 0f..2.5f,
            onValueChange = { onSettingsChange(settings.copy(paragraphSpacing = it)) },
        )

        val alignments = listOf(
            TextAlignment.LEFT to "Left",
            TextAlignment.JUSTIFIED to "Justified",
            TextAlignment.CENTER to "Centred",
        )
        DropdownMenuButton(
            label = "Alignment",
            selected = alignments.firstOrNull { it.first == settings.alignment }?.second ?: "Justified",
            options = alignments.map { it.second },
            onChange = { label ->
                onSettingsChange(settings.copy(alignment = alignments.first { it.second == label }.first))
            },
        )

        ToggleRow(
            label = "Hyphenation",
            description = "Break long words across lines to keep the measure even",
            checked = settings.hyphenation,
            onChange = { onSettingsChange(settings.copy(hyphenation = it)) },
        )

        FolioRule()

        // ── Reader display ──────────────────────────────────────────────────
        FolioEyebrow("Reader display (reflowable documents)", accent = colors.accentDiscovery)
        ToggleRow(
            label = "Progress bar",
            description = "Show the page block and where you are",
            checked = settings.showProgress,
            onChange = { onSettingsChange(settings.copy(showProgress = it)) },
        )
        ToggleRow(
            label = "Clock",
            description = "Show the time in the reflowable reader's title bar",
            checked = settings.showClock,
            onChange = { onSettingsChange(settings.copy(showClock = it)) },
        )
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    valueLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = FolioTheme.typography.labelLarge, color = FolioTheme.colors.onSurfaceVariant)
            Text(valueLabel, style = FolioTheme.typography.labelLarge, color = FolioTheme.colors.onSurface)
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
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = FolioTheme.typography.labelLarge, color = FolioTheme.colors.onSurface)
            Text(description, style = FolioTheme.typography.bodySmall, color = FolioTheme.colors.onSurfaceVariant)
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(checkedTrackColor = FolioTheme.colors.primary),
        )
    }
}
