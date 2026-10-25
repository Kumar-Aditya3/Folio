package com.folio.reader.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.folio.reader.settings.MotionPreferences
import com.folio.reader.settings.MotionPreset
import com.folio.reader.settings.ReduceMotionMode
import com.folio.reader.settings.ReaderSettings
import com.folio.reader.ui.components.FolioSegmented
import com.folio.reader.ui.components.FolioSlider
import com.folio.reader.ui.theme.FOLIO_FIELD_GLOBAL_KEY
import com.folio.reader.ui.theme.FieldLever
import com.folio.reader.ui.theme.fieldFactor
import com.folio.reader.ui.theme.LocalFolioPackId
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens
import com.folio.reader.ui.theme.MotionChannel
import com.folio.reader.ui.theme.factorFor

/**
 * The **Animations** section, inside the Transparency panel — the panel the Settings hub's
 * "Appearance" row opens.
 *
 * ## Why this shape and not a list of forty animations
 *
 * The app has a tuned table behind it: one duration, easing, spring and intensity per animation,
 * chosen for what that animation is *for*. That table is the app's opinion and Settings does not
 * touch it — a reader is never asked whether the selection toolbar is 180 ms, because that answer
 * depends on forty other answers. What a reader genuinely has an opinion about is how much motion
 * they want, and that is what lives here:
 *
 * - one **preset** (Calm / Standard / Lively) that scales every animation at once;
 * - seven **levers** for the parts that dominate how busy the app feels — the reader's chrome, its
 *   sheets, its covers, its progress feedback, the living room behind everything, and the entry
 *   stagger;
 * - one **reduce motion** choice, because the platform flag is a blunt instrument that says nothing
 *   about a reader who simply wants less;
 * - the **background animation** family, moved here from the Themes screen so every animation
 *   control in the app has one home, with a scope toggle for the reader who wants one theme to
 *   behave differently from the rest.
 *
 * A lever reads **Auto** until it is touched, which means *follow the preset* — the state every
 * fresh install is in, and the state that renders the app exactly as it was tuned.
 */

/**
 * One lever as a **slider**, with the value it currently resolves to and a way back to Auto.
 *
 * Auto is not a seventh stop on the track — it is the slider *sitting at the effective value*
 * (the lever if set, otherwise the preset's). So the control always shows what the app is doing
 * right now, and dragging it off that point is what pins it; the Auto button that appears next to
 * the value is how you hand it back. This is why a slider rather than a dropdown with fixed
 * stops: a multiplier is continuous in nature and the useful values are not round numbers.
 */
@Composable
private fun LeverSlider(
    title: String,
    subtitle: String,
    value: Float,
    auto: Boolean,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    onResetAuto: () -> Unit,
) {
    val colors = FolioTheme.colors
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = FolioTheme.typography.bodyLarge,
                    color = colors.onSurface,
                )
            }
            Text(
                text = if (auto) "Auto" else times(value),
                style = FolioTheme.typography.labelLarge,
                color = if (auto) colors.onSurfaceVariant else colors.onSurface,
            )
            if (!auto) {
                // Handing the lever back is one tap, and the hint says what it will return to.
                TextButton(onClick = onResetAuto) { Text("Auto") }
            }
        }
        FolioSlider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            accent = colors.accentProgress,
        )
        Text(
            text = subtitle,
            style = FolioTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(top = FolioTokens.space1),
        )
    }
}

/** "2×" for a multiplier, so the convention is legible in the control itself. */
private fun times(v: Float): String =
    if (v == v.toInt().toFloat()) "${v.toInt()}×" else "${(v * 100).toInt()}%"

/**
 * The Animations destination, in full.
 *
 * It is its own destination rather than a section inside Transparency, because a reader looking for
 * "the app moves too much" should not have to know that the answer lives behind the panel about how
 * solid the glass is. Everything that animates — the room behind the screens, the reader's chrome,
 * the sheets, the covers — is in one place, and the family picker that used to live in Themes is
 * here too, beside the controls that decide how that family behaves.
 */
@Composable
fun AnimationsSettingsPanel(
    settings: ReaderSettings,
    onSettingsChange: (ReaderSettings) -> Unit,
) {
    val colors = FolioTheme.colors
    val motion = settings.motion
    val packId = LocalFolioPackId.current

    fun edit(change: (MotionPreferences) -> MotionPreferences) {
        onSettingsChange(settings.copy(motion = change(motion)))
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(FolioTokens.space3),
    ) {
        Text(
            text = "Animations",
            style = FolioTheme.typography.titleMedium,
            color = colors.onSurface,
        )
        Text(
            text = "How much the app moves. Every animation's own timing is tuned for what it is " +
                "for; these settings scale them together.",
            style = FolioTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )

        // ── The preset ─────────────────────────────────────────────────────────────
        Text(
            text = "Motion",
            style = FolioTheme.typography.bodyLarge,
            color = colors.onSurface,
        )
        FolioSegmented(
            options = listOf("Calm", "Standard", "Lively"),
            selectedIndex = when (motion.preset) {
                MotionPreset.CALM -> 0
                MotionPreset.STANDARD -> 1
                MotionPreset.LIVELY -> 2
            },
            onSelect = { index ->
                edit {
                    it.copy(
                        preset = when (index) {
                            0 -> MotionPreset.CALM
                            2 -> MotionPreset.LIVELY
                            else -> MotionPreset.STANDARD
                        }
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        // ── Reduce motion ──────────────────────────────────────────────────────────
        Text(
            text = "Reduce motion",
            style = FolioTheme.typography.bodyLarge,
            color = colors.onSurface,
        )
        Text(
            text = "Reduced keeps every motion short and drops the decorative ones; the living " +
                "background slows to a crawl rather than stopping. Follow system uses the " +
                "platform's own animation setting.",
            style = FolioTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
        FolioSegmented(
            options = listOf("Follow system", "Reduced", "Full"),
            selectedIndex = when (motion.reduceMotion) {
                ReduceMotionMode.FOLLOW_SYSTEM -> 0
                ReduceMotionMode.REDUCED -> 1
                ReduceMotionMode.FULL -> 2
            },
            onSelect = { index ->
                edit {
                    it.copy(
                        reduceMotion = when (index) {
                            1 -> ReduceMotionMode.REDUCED
                            2 -> ReduceMotionMode.FULL
                            else -> ReduceMotionMode.FOLLOW_SYSTEM
                        }
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        // ── The levers ─────────────────────────────────────────────────────────────
        Text(
            text = "Fine-tune",
            style = FolioTheme.typography.bodyLarge,
            color = colors.onSurface,
        )
        Text(
            text = "Auto follows the preset above.",
            style = FolioTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
        LeverSlider(
            title = "Reader chrome",
            subtitle = "The bars, the floating rail, the side panels and the selection toolbar.",
            value = if (motion.readerChrome == null) motion.factorFor(MotionChannel.CHROME) else motion.readerChrome,
            auto = motion.readerChrome == null,
            range = 0.5f..2f,
            onChange = { v -> edit { it.copy(readerChrome = v) } },
            onResetAuto = { edit { it.copy(readerChrome = null) } },
        )
        LeverSlider(
            title = "Sheets and overlays",
            subtitle = "Menus, dialogs, sheets and the navigation transitions.",
            value = if (motion.sheetsOverlays == null) motion.factorFor(MotionChannel.SHEET) else motion.sheetsOverlays,
            auto = motion.sheetsOverlays == null,
            range = 0.5f..2f,
            onChange = { v -> edit { it.copy(sheetsOverlays = v) } },
            onResetAuto = { edit { it.copy(sheetsOverlays = null) } },
        )
        LeverSlider(
            title = "Covers and morphs",
            subtitle = "The cover morph, the tab dissolve and the bookmark swap.",
            value = if (motion.coverMorph == null) motion.factorFor(MotionChannel.MORPH) else motion.coverMorph,
            auto = motion.coverMorph == null,
            range = 0.5f..2f,
            onChange = { v -> edit { it.copy(coverMorph = v) } },
            onResetAuto = { edit { it.copy(coverMorph = null) } },
        )
        LeverSlider(
            title = "Progress feedback",
            subtitle = "Rings, bars, the goal dial, shimmer and the sync pill.",
            value = if (motion.progressFeedback == null) motion.factorFor(MotionChannel.FEEDBACK) else motion.progressFeedback,
            auto = motion.progressFeedback == null,
            range = 0.5f..2f,
            onChange = { v -> edit { it.copy(progressFeedback = v) } },
            onResetAuto = { edit { it.copy(progressFeedback = null) } },
        )
        LeverSlider(
            title = "Entry stagger",
            subtitle = "Charts and lists arriving one after another.",
            value = if (motion.chartEntry == null) motion.factorFor(MotionChannel.ENTRY) else motion.chartEntry,
            auto = motion.chartEntry == null,
            range = 0.5f..2f,
            onChange = { v -> edit { it.copy(chartEntry = v) } },
            onResetAuto = { edit { it.copy(chartEntry = null) } },
        )

        // ── The living room ────────────────────────────────────────────────────────
        Text(
            text = "Background motion",
            style = FolioTheme.typography.bodyLarge,
            color = colors.onSurface,
        )
        Text(
            text = "The living room behind every screen. Each field has its own motion and " +
                "rendering budget; intensity controls how much of the theme's colour shows through.",
            style = FolioTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
        LeverSlider(
            title = "Field motion",
            subtitle = "How fast the room drifts, and with it the light, the rim and the silk.",
            value = if (motion.fieldMotion == null) motion.fieldFactor(FieldLever.MOTION) else motion.fieldMotion,
            auto = motion.fieldMotion == null,
            range = 0f..2f,
            onChange = { v -> edit { it.copy(fieldMotion = v) } },
            onResetAuto = { edit { it.copy(fieldMotion = null) } },
        )
        LeverSlider(
            title = "Field intensity",
            subtitle = "How strongly the theme's colour is carried by that motion.",
            value = if (motion.fieldIntensity == null) motion.fieldFactor(FieldLever.INTENSITY) else motion.fieldIntensity,
            auto = motion.fieldIntensity == null,
            range = 0f..1f,
            onChange = { v -> edit { it.copy(fieldIntensity = v) } },
            onResetAuto = { edit { it.copy(fieldIntensity = null) } },
        )

        LeverSlider(
            title = "Pattern size",
            subtitle = "How big the room's own motif is — the blobs, cells, dots and bands. Auto is the size the theme was designed at.",
            value = if (motion.fieldScale == null) motion.fieldFactor(FieldLever.SCALE) else motion.fieldScale,
            auto = motion.fieldScale == null,
            range = 0.5f..2f,
            onChange = { v -> edit { it.copy(fieldScale = v) } },
            onResetAuto = { edit { it.copy(fieldScale = null) } },
        )
        // ── The family, moved here ─────────────────────────────────────────────────
        Text(
            text = "Background animation",
            style = FolioTheme.typography.bodyLarge,
            color = colors.onSurface,
        )
        Text(
            text = "Which animation the room wears. Plain is a still background.",
            style = FolioTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
        // The scope toggle this picker always implied: one choice for every theme, or this one.
        var perTheme by remember(packId) { mutableStateOf(settings.fieldOverrides.containsKey(packId)) }
        FolioSegmented(
            options = listOf("All themes", "This theme only"),
            selectedIndex = if (perTheme) 1 else 0,
            onSelect = { index -> perTheme = index == 1 },
            modifier = Modifier.fillMaxWidth(),
        )
        ThemeFieldOverridePicker(
            packId = packId,
            current = settings.fieldOverrides[if (perTheme) packId else FOLIO_FIELD_GLOBAL_KEY],
            onChoose = { token ->
                val key = if (perTheme) packId else FOLIO_FIELD_GLOBAL_KEY
                val next = settings.fieldOverrides.toMutableMap().apply {
                    if (token == null) remove(key) else put(key, token)
                }
                onSettingsChange(settings.copy(fieldOverrides = next))
            },
        )
    }
}
