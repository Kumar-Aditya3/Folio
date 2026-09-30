package com.folio.reader.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * §13.7 / M7 — AGSL liquid glass, the design's last and least-portable effect.
 *
 * A `RuntimeShader` (Android, API 33+) paints a refractive, light-responsive
 * glass film **behind** a hero's content: a faint caustic body sheen, a specular
 * highlight tracked to the room's virtual sun, and a sun-facing edge catch that
 * approximates lensing at the rim. It is the honest Compose equivalent of the
 * refraction the rejected JS shader stacks sold (§13.0), scoped to one surface.
 *
 * **Contract (Rule 19 — every effect declares its floor and its fallback):**
 *
 * | Condition | Result |
 * |---|---|
 * | API ≥ 33, [enabled] on, surface pressed with a [focal] | AGSL film drawn under the content, its specular gathered under the finger |
 * | API ≥ 33, [enabled] on, at rest or with no [focal] | the same film, daylight-lit — byte-identical to the picture before the press focal existed |
 * | API < 33, or shader compile fails, or [enabled] off | receiver unchanged — the §13.4 mesh + sheen stands in |
 * | Reduce-motion | [FolioPressFocal.strength] never leaves 0, so the focal term contributes nothing and the film is the resting one |
 * | Desktop / previews / tests | receiver unchanged, byte-for-byte |
 *
 * It is drawn **behind** content only, never over text, so the §12.3 4.5:1
 * contrast guarantees are untouched — the film cannot make a hero unreadable.
 * Every colour comes from the caller's accent roles (Rule 2 holds inside
 * shaders too); nothing is hardcoded.
 *
 * It carries no *time* uniform, so it never asks for a frame on its own. It is no
 * longer strictly static: a press moves the specular, and that is the only input.
 * The cost is bounded by the gesture — a focal write invalidates this surface's draw
 * while the spring runs and nothing after it, because [focal] is a [State] read in
 * the draw phase rather than a value captured by the draw cache. The one path to real
 * refraction, deliberately last because API 33+ is the minority of a `minSdk` 24
 * install base, so the fallback is the majority path and must look intentional, not
 * degraded — which is why the same hot-spot is also drawn at Canvas level by
 * [com.folio.reader.ui.components.folioGlassPress] on every platform.
 *
 * @param accent the hero's own hue — usually its cover-derived tint — driving the
 *   body sheen. @param highlight the light-catch colour (the atmosphere's rim
 *   light). @param lightX/[lightY] the unit virtual-sun direction from
 *   [FolioDaylight.lightDirection], so the specular agrees with every other
 *   material's lighting. @param intensity an overall scalar (0 disables the film
 *   without a branch at the call site). @param enabled the user's liquid-glass
 *   preference, so pref-off restores the fallback exactly. @param focal where a
 *   finger is pressing, or null for a surface that does not track it.
 */
@Composable
expect fun Modifier.folioLiquidGlass(
    accent: Color,
    highlight: Color,
    lightX: Float,
    lightY: Float,
    intensity: Float = 1f,
    enabled: Boolean = true,
    focal: FolioPressFocal? = null,
): Modifier
