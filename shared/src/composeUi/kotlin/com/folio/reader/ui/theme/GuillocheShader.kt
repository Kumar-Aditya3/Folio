package com.folio.reader.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * §17 / M7 — the theme's guilloche plate: a faint, flowing engraving laid over the field
 * and **under** everything else, unique to every theme.
 *
 * The plate is a weave of long crossing sine waves (see [GuillocheSpec]), drawn in the
 * theme's own colour, animated by walking each wave's phase on a slow clock so the
 * interference pattern drifts through itself — ribbons widen, slide and re-form, rather
 * than the image translating as a rigid sheet.
 *
 * **Why it exists.** Two jobs, and the second is the interesting one:
 *
 *  1. It gives the page a material. The field is a smooth vertical ramp plus three soft
 *     pools, which is an environment but not a *surface*.
 *  2. It gives the **glass something to refract**. `GlassShader.android.kt` documents at
 *     length why its refraction gel degenerates on this app's backdrop — *"a lens rim has
 *     almost nothing to bend: the dispersion that is meant to separate a lens from a
 *     gradient lands three samples on the same colour, so the band only ever reads as a
 *     painted rectangle."* A high-frequency plate under the glass is the detail that
 *     refraction needs. This layer is therefore the prerequisite for the glass work, not
 *     an unrelated decoration.
 *
 * **Rule 19.** The plate is a bonus laid over a field that already reads complete, so the
 * fallback is the pre-existing look — byte-for-byte, with no Canvas reimplementation to
 * disagree with the shader. Where it draws:
 *
 * | | draws |
 * |---|---|
 * | Android API 33+, preference on, motion on | animated AGSL weave |
 * | Android API 33+ under reduce-motion | the same plate, phases parked (still) |
 * | Android < 33, preference off, zero intensity, AGSL rejected | receiver unchanged |
 * | Desktop, previews, unit tests | receiver unchanged |
 *
 * **Cost.** Reads [rememberSlowPhases] in the draw phase only — `SLOW_MOTION_TICK_MS`
 * (~10 Hz), so there is no per-frame recomposition and the frame loop idles between ticks,
 * exactly like [folioAmbientShader].
 *
 * @param packId the theme pack's identity. The plate's *figure* is derived from it, so
 *   every theme draws a different interference pattern.
 * @param fieldTop the field's own ramp, which the plate's colour is cut out of.
 * @param fieldBottom the field's ramp at the bottom.
 * @param isDark which face we are on — decides whether the plate is a shadow (dark) or a
 *   chroma-matched line (paper). See [guillocheLineColor].
 * @param intensity the plate's weight, defaulting to [GUILLOCHE_ALPHA_MAX].
 * @param enabled the capability gate; false returns the receiver untouched.
 */
@Composable
expect fun Modifier.folioGuilloche(
    packId: String,
    fieldTop: Color,
    fieldBottom: Color,
    fieldSourceX: Float,
    fieldSourceY: Float,
    isDark: Boolean,
    intensity: Float = GUILLOCHE_ALPHA_MAX,
    enabled: Boolean = true,
): Modifier
