package com.folio.reader.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.folio.reader.settings.MotionPreferences
import com.folio.reader.settings.MotionPreset
import com.folio.reader.settings.ReduceMotionMode
import com.folio.reader.settings.ReaderSettings
import com.folio.reader.ui.components.DropdownMenuButton
import com.folio.reader.ui.components.FolioSegmented
import com.folio.reader.ui.theme.FOLIO_FIELD_GLOBAL_KEY
import com.folio.reader.ui.theme.LocalFolioPackId
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens

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

/** The two places a lever can be read from. */
private enum class LeverScope { AUTO, CUSTOM }

/**
 * One lever: a label, why it exists, and a control that can return to the preset.
 *
 * The values are multipliers in the app's own convention — **higher is more motion** — and they are
 * offered as stops rather than as a continuous range because a multiplier is only meaningful
 * against the preset it multiplies, and three good stops beat sixty bad ones.
 */
@Composable
private fun LeverRow(
    title: String,
    subtitle: String,
    current: Float?,
    stops: List<Float>,
    onLeverChange: (Float?) -> Unit,
) {
    val colors = FolioTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = FolioTheme.typography.bodyLarge,
                color = colors.onSurface,
            )
            Text(
                text = subtitle,
                style = FolioTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
        val values = buildList {
            add("Auto")
            stops.forEach { add(times(it)) }
        }
        val selected = if (current == null) "Auto" else times(current)
        DropdownMenuButton(
            label = title,
            selected = selected,
            options = values,
            onChange = { chosen -> onLeverChange(if (chosen == "Auto") null else stops.firstOrNull { times(it) == chosen }) },
        )
    }
}

/** "2×" for a multiplier, so the convention is legible in the control itself. */
private fun times(v: Float): String =
    if (v == v.toInt().toFloat()) "${v.toInt()}×" else "${(v * 100).toInt()}%"

@Composable
internal fun AnimationsSettingsSection(
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
        LeverRow(
            title = "Reader chrome",
            subtitle = "The bars, the floating rail, the side panels and the selection toolbar.",
            current = motion.readerChrome,
            stops = listOf(0.5f, 0.75f, 1.25f, 1.5f, 2f),
            onLeverChange = { v -> edit { it.copy(readerChrome = v) } },
        )
        LeverRow(
            title = "Sheets and overlays",
            subtitle = "Menus, dialogs, sheets and the navigation transitions.",
            current = motion.sheetsOverlays,
            stops = listOf(0.5f, 0.75f, 1.25f, 1.5f, 2f),
            onLeverChange = { v -> edit { it.copy(sheetsOverlays = v) } },
        )
        LeverRow(
            title = "Covers and morphs",
            subtitle = "The cover morph, the tab dissolve and the bookmark swap.",
            current = motion.coverMorph,
            stops = listOf(0.75f, 0.9f, 1.25f, 1.5f),
            onLeverChange = { v -> edit { it.copy(coverMorph = v) } },
        )
        LeverRow(
            title = "Progress feedback",
            subtitle = "Rings, bars, the goal dial, shimmer and the sync pill.",
            current = motion.progressFeedback,
            stops = listOf(0.5f, 0.75f, 1.25f, 1.5f, 2f),
            onLeverChange = { v -> edit { it.copy(progressFeedback = v) } },
        )
        LeverRow(
            title = "Entry stagger",
            subtitle = "Charts and lists arriving one after another.",
            current = motion.chartEntry,
            stops = listOf(0.5f, 0.85f, 1.25f, 1.5f),
            onLeverChange = { v -> edit { it.copy(chartEntry = v) } },
        )

        // ── The living room ────────────────────────────────────────────────────────
        Text(
            text = "Background motion",
            style = FolioTheme.typography.bodyLarge,
            color = colors.onSurface,
        )
        Text(
            text = "The living room behind every screen. Its speed costs nothing — the field " +
                "redraws at the display's rate either way — and its intensity is how much the " +
                "theme's colour shows through.",
            style = FolioTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
        LeverRow(
            title = "Field motion",
            subtitle = "How fast the room drifts, and with it the light, the rim and the silk.",
            current = motion.fieldMotion,
            stops = listOf(0f, 0.25f, 0.5f, 1.5f, 2f),
            onLeverChange = { v -> edit { it.copy(fieldMotion = v) } },
        )
        LeverRow(
            title = "Field intensity",
            subtitle = "How strongly the theme's colour is carried by that motion.",
            current = motion.fieldIntensity,
            stops = listOf(0f, 0.25f, 0.5f, 0.75f, 1f),
            onLeverChange = { v -> edit { it.copy(fieldIntensity = v) } },
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
