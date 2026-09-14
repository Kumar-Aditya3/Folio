package com.folio.reader.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.reader.model.FormattingMode
import com.folio.reader.settings.ReaderSettings
import com.folio.reader.settings.TextAlignment
import com.folio.reader.settings.TextWidth
import com.folio.reader.ui.components.DropdownMenuButton
import com.folio.reader.ui.components.FolioSliderRow
import com.folio.reader.ui.components.rememberLegibleAccent
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens
import kotlin.math.roundToInt

/**
 * Text & page (§14.2): the three prose-formatting screens — Typography,
 * Layout and Formatting — as one panel in three sections. The reader
 * experience is "how the page looks", and the controls were split across
 * destinations that all answered to that single question; adjusting type,
 * measure and alignment here moves the one live preview above in one motion.
 *
 * Importing fonts keeps its single entry point in Settings → General, next to
 * the typeface pairing that also governs the app chrome; this panel does not
 * duplicate it.
 */
@Composable
fun TextAndPageSettingsPanel(
    settings: ReaderSettings,
    onSettingsChange: (ReaderSettings) -> Unit,
    /** Books keeping their own type or measure values from an earlier open (§ ReaderDefaultsPanel). */
    overrideCount: Int = 0,
    /** Drops those, so the values below take effect in books already opened. */
    onApplyToOpenedBooks: (() -> Unit)? = null,
    /** Books still carrying pre-global formatting snapshots; null hides the review row. */
    legacyFormattingCount: Int? = null,
    onReviewLegacyFormatting: (() -> Unit)? = null,
) {
    val colors = FolioTheme.colors
    val accent = rememberLegibleAccent(colors.primary)
    val fonts = readerFontOptions(settings)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        // Without this line the panel reads as "type for my reading", and a book
        // already open ignoring it reads as a bug rather than as the per-book
        // ownership it is. The preview above shows the effect immediately.
        Text(
            "How prose pages are set: the typeface, spacing, measure and alignment " +
                "a book is given the first time you open it. Manga is images, so none " +
                "of this applies there.",
            style = FolioTheme.typography.bodySmall,
            color = colors.onSurfaceVariant
        )

        // ── Text ────────────────────────────────────────────────
        PanelSectionTitle("Text")

        DropdownMenuButton(
            label = "Font family",
            selected = settings.fontFamily,
            options = fonts,
            onChange = { onSettingsChange(settings.copy(fontFamily = it)) },
            optionContent = { option ->
                Text(
                    text = readerFontLabel(settings, option),
                    fontFamily = readerFontFamily(settings, option),
                    fontWeight = if (option == settings.fontFamily) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (option == settings.fontFamily) accent else colors.onSurface
                )
            },
            selectedContent = {
                Text(
                    text = readerFontLabel(settings, settings.fontFamily),
                    style = FolioTheme.typography.bodyMedium,
                    fontFamily = readerFontFamily(settings, settings.fontFamily),
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        )

        val fontSize = settings.fontSize.coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP)
        FolioSliderRow(
            label = "Font size",
            valueLabel = "${fontSize.roundToInt()} sp",
            value = fontSize,
            onValueChange = { onSettingsChange(settings.copy(fontSize = it)) },
            valueRange = MIN_FONT_SIZE_SP..MAX_FONT_SIZE_SP
        )

        val fontWeight = settings.fontWeight.coerceIn(100, 900)
        FolioSliderRow(
            label = "Font weight",
            valueLabel = "$fontWeight",
            value = fontWeight.toFloat(),
            onValueChange = { onSettingsChange(settings.copy(fontWeight = it.roundToInt())) },
            valueRange = 100f..900f,
            steps = 7 // 100, 200, ..., 900
        )

        FolioSliderRow(
            label = "Line height",
            valueLabel = "%.1f".format(settings.lineHeight),
            value = settings.lineHeight,
            onValueChange = { onSettingsChange(settings.copy(lineHeight = it)) },
            valueRange = 1.0f..3.0f
        )

        FolioSliderRow(
            label = "Letter spacing",
            valueLabel = "%.2f".format(settings.letterSpacing),
            value = settings.letterSpacing,
            onValueChange = { onSettingsChange(settings.copy(letterSpacing = it)) },
            valueRange = -0.1f..0.3f
        )

        FolioSliderRow(
            label = "Word spacing",
            valueLabel = "%.2f".format(settings.wordSpacing),
            value = settings.wordSpacing,
            onValueChange = { onSettingsChange(settings.copy(wordSpacing = it)) },
            valueRange = -0.2f..0.5f
        )

        FolioSliderRow(
            label = "Paragraph spacing",
            valueLabel = "%.1fx".format(settings.paragraphSpacing),
            value = settings.paragraphSpacing,
            onValueChange = { onSettingsChange(settings.copy(paragraphSpacing = it)) },
            valueRange = 0.5f..3.0f
        )

        // Moved from General: this is a property of prose typesetting, not of the
        // app chrome, and it changes what the preview above renders.
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

        // ── Measure ─────────────────────────────────────────────
        PanelSectionTitle("Measure")

        DropdownMenuButton(
            label = "Text width",
            selected = settings.textWidth.settingsLabel(),
            options = TextWidth.entries.map { it.settingsLabel() },
            onChange = { name ->
                val width = TextWidth.entries.first { it.settingsLabel() == name }
                onSettingsChange(settings.copy(textWidth = width))
            }
        )

        FolioSliderRow(
            label = "Margins",
            valueLabel = "${settings.margins.left.roundToInt()} dp",
            value = settings.margins.left,
            onValueChange = {
                onSettingsChange(settings.copy(margins = settings.margins.copy(left = it, right = it)))
            },
            valueRange = 0f..64f
        )

        // ── Formatting ──────────────────────────────────────────
        PanelSectionTitle("Formatting")

        DropdownMenuButton(
            label = "Formatting mode",
            selected = settings.formattingMode.settingsLabel(),
            options = FormattingMode.entries.map { it.settingsLabel() },
            onChange = { name ->
                val mode = FormattingMode.entries.first { it.settingsLabel() == name }
                onSettingsChange(settings.copy(formattingMode = mode))
            }
        )

        DropdownMenuButton(
            label = "Default text alignment",
            selected = settings.alignment.settingsLabel(),
            options = TextAlignment.entries.map { it.settingsLabel() },
            onChange = { name ->
                val alignment = TextAlignment.entries.first { it.settingsLabel() == name }
                onSettingsChange(settings.copy(alignment = alignment))
            }
        )

        Text(
            "Hybrid keeps explicit EPUB alignment for title pages and special paragraphs. " +
                "Normalized uses this default everywhere. Changes here apply to every book, " +
                "including ones you have already opened.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (legacyFormattingCount != null && legacyFormattingCount > 0 && onReviewLegacyFormatting != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(FolioTokens.radiusChip))
                    .clickable { onReviewLegacyFormatting() }
                    .padding(vertical = FolioTokens.space1),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (legacyFormattingCount == 1) "1 book has its own override — review"
                    else "$legacyFormattingCount books have their own overrides — review",
                    style = FolioTheme.typography.bodySmall,
                    color = colors.primary
                )
            }
        }

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

        // Resets the per-book snapshot fields this panel governs (text and
        // measure). The formatting fields below are global-only and stay as set.
        OutlinedButton(
            onClick = {
                val d = ReaderSettings()
                onSettingsChange(
                    settings.copy(
                        fontFamily = d.fontFamily,
                        fontSize = d.fontSize,
                        fontWeight = d.fontWeight,
                        lineHeight = d.lineHeight,
                        letterSpacing = d.letterSpacing,
                        wordSpacing = d.wordSpacing,
                        paragraphSpacing = d.paragraphSpacing,
                        useEmbeddedFonts = d.useEmbeddedFonts,
                        textWidth = d.textWidth,
                        margins = d.margins
                    )
                )
            }
        ) {
            Text("Reset text and measure to defaults")
        }

        InheritanceEscapeHatch(
            count = overrideCount,
            noun = "book",
            confirmBody = "Their own typeface, size, weight, spacing, text width and margins " +
                "are dropped, so the values above apply there too. Page theme, positions, " +
                "highlights and notes are untouched.",
            onApply = onApplyToOpenedBooks,
        )
    }
}

/** A quiet sub-heading so one panel can carry the three former screens' worth of controls. */
@Composable
private fun PanelSectionTitle(title: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            title,
            style = FolioTheme.typography.titleSmall,
            color = FolioTheme.colors.primary
        )
    }
}

/** Smallest reader text size in sp — matches the reader quick-settings range. */
private const val MIN_FONT_SIZE_SP = 12f

/** Largest reader text size in sp — lowered from 36sp to keep sizes sane. */
private const val MAX_FONT_SIZE_SP = 24f
