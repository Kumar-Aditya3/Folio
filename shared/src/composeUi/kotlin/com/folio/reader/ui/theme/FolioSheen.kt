package com.folio.reader.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf

/**
 * The **metallic-sheen** lever: one scalar in `0..1` for how strongly every surface in
 * the app catches the light — the specular rims, the corner sheens, the travelling
 * glints, the mirror band on the masthead and the room's corner lamp bloom.
 *
 * Like [FolioSurfaceOpacity] and [FolioVibrancy] this deliberately lives *outside*
 * [FolioAtmosphere]: the atmosphere is a pure function of a palette and is asserted
 * directly by the design tests (`rimLight`'s derivation, the §15 contrast guards, the
 * lamp's solved peak). A user preference folded into it would make those invariants
 * describe a preference rather than the design. So the atmosphere keeps stating what
 * the design intends, and the knob is applied once, at each paint site — the one
 * place that knows which light it is catching.
 *
 * **What the knob scales, and what it must never touch:**
 *
 *  - *scaled*: every painted light catch (`rimLight`-alpha terms), the lens's specular
 *    gain (`gleam`), the AGSL films' `intensity` (body caustic, rays, travelling rim,
 *    glint, press focal), the masthead's mirror band, the capsule's selection sweep,
 *    the theme rim's comet beam, cover-seat rims, the reading page's CSS lift, and the
 *    lamp's bloom alpha at its Canvas draw site.
 *  - *never scaled*: fills, shadows, hairlines, edges, blur, refraction and dispersion —
 *    depth and material identity are the design's, not the glow's. Shadow-side terms
 *    (`rimShade`, the bevel's depth wall, the page-light shade) stay too: a shadow is
 *    the absence of light, and dimming it would trade contrast for nothing.
 *
 * `1f` is byte-for-byte the shipped look on every device, so every test that derives
 * at the default is unaffected. `0f` turns every catch off without removing a single
 * surface: the panes stay, the refraction stays, only nothing glints.
 */
@Immutable
data class FolioSheen(val level: Float = com.folio.reader.settings.FOLIO_METALLIC_SHEEN_FULL) {
    companion object {
        /** What the app ships at and the slider's top of travel. */
        val Full = FolioSheen(com.folio.reader.settings.FOLIO_METALLIC_SHEEN_FULL)
    }
}

/**
 * Provided once at the app root from `ReaderSettings.metallicSheen`. Defaults to
 * [FolioSheen.Full] so any tree that does not provide it (previews, isolated
 * activities, tests) renders the shipped metallic look byte-for-byte.
 */
val LocalFolioSheen = compositionLocalOf { FolioSheen.Full }

/**
 * The metallic-sheen knob as a multiplicative gain, clamped to its legal range — the
 * one form every paint site reads, so no site clamps for itself (that is how a knob
 * drifts). `1f` at rest; `0f` when the reader has asked for every light catch off.
 */
@Composable
@ReadOnlyComposable
fun folioSheenGain(): Float =
    LocalFolioSheen.current.level.coerceIn(0f, com.folio.reader.settings.FOLIO_METALLIC_SHEEN_FULL)
