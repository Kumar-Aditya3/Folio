package com.folio.reader.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape

/**
 * §13.7 / M7 — AGSL liquid glass, the design's last and least-portable effect.
 *
 * Two layers, one surface. A `RuntimeShader` (Android, API 33+) paints a faint
 * refractive **film** *behind* a hero's content — a caustic body sheen, a specular
 * highlight tracked to the room's virtual sun, a sun-facing edge catch — and, where
 * the platform can composite and the tree can say what the room looks like
 * ([LocalFolioBackdrop]), a **gel** over it that bends that room at the rim: three
 * displaced taps at different depths per channel, which is the one cue that
 * separates a lens from a gradient.
 *
 * The gel bends the *backdrop*, not its own contents. That distinction is the
 * whole point of the effect and it is not a choice about taste: a render effect can
 * only ever see the subtree it is applied to, so anything that wants to refract what
 * is *behind* a surface has to reproduce that thing. Here that is a closed form —
 * the page's gradient ramp and its three drifting pools — sampled from
 * [FolioFieldModel], the same functions `folioField` draws with. So a pane shows the
 * room it sits in, rolled over at its edge, and the room it shows is the room.
 *
 * It paints only its rim band and fades to nothing a fifth of its short side inside,
 * so a pane keeps the fill it was given and gains an edge. Nothing is drawn over
 * text, and the surface's own [shape] clips it, so it cannot grow a rectangle past a
 * rounded silhouette.
 *
 * **Contract (Rule 19 — every effect declares its floor and its fallback):**
 *
 * | Condition | Result |
 * |---|---|
 * | API ≥ 33, [enabled], **[refractBackdrop]**, blur capability, a provided [LocalFolioBackdrop] | film + gel: the room bent at the rim, dispersion and Fresnel included |
 * | API ≥ 33, [enabled] on, surface pressed with a [focal] | the focal gathers under the finger, in both layers |
 * | API ≥ 33, [refractBackdrop] off (the default), no blur capability, or no backdrop in the tree | the film alone — the picture that shipped before the gel |
 * | API < 33, or shader compile fails, or [enabled] off | receiver unchanged — the §13.4 mesh + sheen stands in |
 * | Reduce-motion | [FolioPressFocal.strength] never leaves 0, so the focal term contributes nothing; the gel itself is a material, not motion, and keeps its (static) refraction |
 * | Desktop / previews / tests | receiver unchanged, byte-for-byte |
 *
 * The gel is **opt-in** ([refractBackdrop], default off). A rim lens pays its way only
 * over a backdrop with high-frequency detail to bend; the hero's field is a smooth ramp
 * plus a few soft pools and one lamp bloom, where the three dispersion samples land on
 * the same colour and the band degenerates into a painted rectangle inset a fifth of the
 * short side. Tuning that rectangle's brightness, Fresnel floor, baseline and chroma each
 * fixed one of its faces and surfaced the next; defaulting the gel off ends that loop and
 * keeps it available for a surface that genuinely has something behind it to refract.
 *
 * It is drawn **behind** content only, never over text, so the §12.3 4.5:1
 * contrast guarantees are untouched — the film cannot make a hero unreadable.
 * Every colour comes from the caller's accent roles (Rule 2 holds inside
 * shaders too); nothing is hardcoded.
 *
 * It carries no *time* uniform, so it never asks for a frame on its own. It is no
 * longer strictly static: a press moves the specular, and scrolling moves the card
 * across the room it refracts. The cost is bounded by those two — [focal] and the
 * card's position are [State]s read in the draw phase, so neither recomposes the
 * subtree behind them. The one path to real refraction, deliberately last because
 * API 33+ is the minority of a `minSdk` 24 install base, so the fallback is the
 * majority path and must look intentional, not degraded — which is why the same
 * hot-spot is also drawn at Canvas level by
 * [com.folio.reader.ui.components.folioGlassPress] on every platform.
 *
 * @param accent the hero's own hue — usually its cover-derived tint — driving the
 *   body sheen. @param highlight the light-catch colour (the atmosphere's rim
 *   light) — **its alpha is spent too**, so a 55% dark-theme lift is not laid down as
 *   if it were solid; handing over only the channels is what made the film
 *   polarity-blind. @param lightX/[lightY] the unit virtual-sun direction from
 *   [FolioDaylight.lightDirection], so the specular agrees with every other
 *   material's lighting. @param intensity an overall scalar (0 disables the film
 *   without a branch at the call site). @param enabled the user's liquid-glass
 *   preference, so pref-off restores the fallback exactly. @param refractBackdrop
 *   opt into the backdrop gel (default off); only worthwhile over a surface with real
 *   detail behind it to bend. @param focal where a
 *   finger is pressing, or null for a surface that does not track it. @param shape
 *   the surface's own silhouette, which the gel's rim follows and its clip obeys.
 */
@Composable
expect fun Modifier.folioLiquidGlass(
    accent: Color,
    highlight: Color,
    lightX: Float,
    lightY: Float,
    intensity: Float = 1f,
    enabled: Boolean = true,
    refractBackdrop: Boolean = false,
    focal: FolioPressFocal? = null,
    shape: Shape = FolioShapes.card,
): Modifier
