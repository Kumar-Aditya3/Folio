package com.folio.reader.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * "Living Paper" atmosphere — an *animated* ambient light film for the app's
 * ground plane, drawn over [Modifier.folioField]'s static wash and pools but
 * behind all screen content.
 *
 * Where [folioLiquidGlass] is a single static pass on one hero, this is the whole
 * field breathing: two large, soft accent blooms whose centres orbit on a very
 * slow clock, so a resting Home/Library page has a sub-perceptible drift of
 * light instead of a fixed temperature. It is the shader counterpart to the
 * pool-drift that [folioField] already does with radial gradients — deliberately
 * kept below notice, never a visible pattern.
 *
 * **Contract (Rule 19 — every effect declares its floor and its fallback):**
 *
 * | Condition | Result |
 * |---|---|
 * | API ≥ 33, [enabled] on, motion on | animated AGSL bloom film drawn over the field |
 * | API < 33, shader compile fails, [enabled] off, or reduce-motion | receiver unchanged — the static [folioField] stands alone |
 * | Desktop / previews / tests | receiver unchanged, byte-for-byte |
 *
 * Drawn behind content only, at very low alpha, so §12.3 text-contrast
 * guarantees are untouched. The animation is sampled on the house slow clock
 * ([rememberSlowPhases], ~10 Hz) and read in the draw phase, so it costs no
 * per-frame recomposition and stops the frame loop between ticks.
 *
 * @param colorA the warm bloom hue (usually the theme's primary accent).
 * @param colorB the cool bloom hue (usually the theme's tertiary counter-accent).
 * @param intensity overall ceiling on the film's alpha; keep it small.
 * @param enabled the liquid-glass capability gate — pass
 *   `LocalGlassCapabilities.current.specular` so the user's preference and the
 *   device verdict both apply, exactly like [folioLiquidGlass].
 */
@Composable
expect fun Modifier.folioAmbientShader(
    colorA: Color,
    colorB: Color,
    intensity: Float = 0.10f,
    enabled: Boolean = true,
): Modifier
