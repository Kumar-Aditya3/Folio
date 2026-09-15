package com.folio.reader.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import com.folio.reader.ui.theme.FolioTokens
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource

/**
 * §16 Liquid Glass — the capability model and the **one Haze seam**.
 *
 * Glass is the material of the overlay layer only: surfaces that sit *over*
 * content (bars, the nav capsule, sheets, the status banner) may be glass;
 * in-flow content (cards, wells, cover plates, chips, the reader page block)
 * stays paper or sunken. The material itself is upgraded once, in
 * [folioVeil] and the masthead, so every eligible surface inherits it — never
 * forked per screen (Rule 3).
 *
 * What a surface actually gets is decided by a pure degradation ladder
 * ([glassCapabilitiesFor] + the caller's backdrop), so the fallback is a
 * contract rather than an accident:
 *
 * | Condition | Result |
 * |---|---|
 * | Blur supported, pref on, low-RAM off, Compose backdrop present | Blur + tint + noise + specular |
 * | Pref on, no backdrop (any API) — e.g. reader chrome over the WebView | Specular + grain only; fill alpha still governed by the reader chrome knob |
 * | Pref off | Today's look: rim + sheen + hairline + fill |
 * | Desktop (capabilities never provided) | Today's look, byte-for-byte — Rule 1 |
 *
 * The blur floor is API 32: Haze disables API 31 by default for observed
 * RenderNode invalidation issues, and a stale blur frame is worse than the
 * clean fallback, so 31 renders the ladder's second row.
 *
 * **The seam.** This is the only file in the repository that imports
 * `dev.chrisbanes.haze` (FOLIO_DEPENDENCY_PLAN §14.3). Everything else —
 * [folioVeil], the masthead, the readers — reaches blur through
 * [LocalGlassBackdrop], [folioBackdropSource] and [folioGlassEffect], none of
 * which expose a Haze type in source. `Select-String 'dev\.chrisbanes\.haze'`
 * must return this file (plus the two Gradle files that declare the dependency)
 * and nothing else.
 */

/**
 * What the current platform and preferences allow the glass to do. Provided
 * once at the app root (Android); the default is [GlassCapabilities.None] so
 * an un-provided tree — desktop, previews, unit tests — renders exactly the
 * pre-glass material. Blur is a material, not motion: it is *not* gated on
 * reduce-motion (§14.3.2), only on platform support, memory class and the
 * user's preference.
 */
@Immutable
data class GlassCapabilities(
    /** Backdrop blur behind the surface. Needs a [LocalGlassBackdrop] source too. */
    val blur: Boolean,
    /** Deterministic grain, to kill gradient banding on glass without blur. */
    val noise: Boolean,
    /** Daylight-directional specular and edge bevel. */
    val specular: Boolean,
) {
    companion object {
        val None = GlassCapabilities(blur = false, noise = false, specular = false)
    }
}

val LocalGlassCapabilities = compositionLocalOf { GlassCapabilities.None }

/**
 * The pure resolver behind the ladder's first column. Android calls it at the
 * activity root with real inputs; tests call it with the matrix.
 *
 * `platformBlurSupported` is the platform verdict (API ≥ 32), `lowRamDevice`
 * the ActivityManager's memory class, `liquidGlassEffects` the user
 * preference. Noise and specular cost no API floor, so the preference alone
 * governs them — turning it off restores today's look on every device.
 */
fun glassCapabilitiesFor(
    platformBlurSupported: Boolean,
    lowRamDevice: Boolean,
    liquidGlassEffects: Boolean,
): GlassCapabilities = GlassCapabilities(
    blur = platformBlurSupported && !lowRamDevice && liquidGlassEffects,
    noise = liquidGlassEffects,
    specular = liquidGlassEffects,
)

/**
 * Per-surface blur tuning. The radius is capped at [FolioTokens.blurRadiusMax]
 * at the effect site, so no caller can buy a 60dp mush.
 */
@Immutable
data class GlassSpec(
    val blurRadius: Dp = FolioTokens.blurRadius,
) {
    companion object {
        val Default = GlassSpec()
    }
}

/**
 * The backdrop a screen offers its overlays. Null (the default) means no
 * Compose content is being sampled — desktop never provides one, and screens
 * over the reader's native WebView surface deliberately do not either, which
 * is exactly the ladder's "specular + grain only" row.
 *
 * The value is a Haze state, but callers never name that type: they call
 * [rememberGlassBackdrop], provide it, and attach [folioBackdropSource] to
 * their scrolling child. Pattern precedent: [com.folio.reader.ui.theme.LocalFolioBarInset].
 */
val LocalGlassBackdrop = compositionLocalOf<HazeState?> { null }

/**
 * Whether this point in the composition will actually blur: the capability is
 * on *and* a backdrop is present. The condition the §16 glass-tier fill steps
 * are keyed on (see FolioSurfaceOpacity) — a helper rather than a bare local
 * read so app modules never name the backdrop's type just to test it for null,
 * which would drag Haze across the seam.
 */
@Composable
fun glassBlurred(): Boolean =
    LocalGlassCapabilities.current.blur && LocalGlassBackdrop.current != null

/** A screen-sized backdrop registry; provide it around both the content and the chrome. */
@Composable
fun rememberGlassBackdrop(): HazeState = remember { HazeState() }

/**
 * The glass environment for an app root: the platform's capability verdict plus
 * the one backdrop registry everything blurs against. Hosts call this instead of
 * providing the locals themselves, so no Haze type ever crosses the seam.
 */
@Composable
fun FolioGlassRoot(
    capabilities: GlassCapabilities,
    content: @Composable () -> Unit,
) {
    androidx.compose.runtime.CompositionLocalProvider(
        LocalGlassCapabilities provides capabilities,
        LocalGlassBackdrop provides rememberGlassBackdrop(),
    ) {
        content()
    }
}

/**
 * Marks a scrolling child as the glass backdrop for everything floating over
 * it (the masthead above, the capsule below, sheets in between). No-op unless
 * blur is possible, so desktop and pref-off builds pay nothing — the source
 * layer is never recorded.
 *
 * Attach to the *scrollable* (the LazyColumn/grid), not an ancestor of the
 * chrome: the masthead must sample the page, not itself.
 */
@Composable
fun Modifier.folioBackdropSource(): Modifier {
    val backdrop = LocalGlassBackdrop.current ?: return this
    if (!LocalGlassCapabilities.current.blur) return this
    return hazeSource(backdrop)
}

/**
 * The single `hazeEffect` call site. Draws the blurred backdrop *under* the
 * caller's own fill, so the surface's fill alpha still governs — at 100% the
 * surface is a lid and the blur is invisible; at the designed glass points the
 * blur reads. Never a tint pass-through: [folioVeil] and the masthead paint
 * their own fills over this.
 *
 * [alpha] fades the whole effect (the masthead gates it on its presence);
 * [progressive] requests the crown-heavy gradient blur, which suits a bar.
 */
fun Modifier.folioGlassEffect(
    backdrop: HazeState,
    backgroundColor: Color,
    blurRadius: Dp = FolioTokens.blurRadius,
    noiseFactor: Float = FolioTokens.glassNoise,
    alpha: Float = 1f,
    progressive: Boolean = false,
): Modifier = hazeEffect(
    state = backdrop,
    style = HazeStyle(
        backgroundColor = backgroundColor,
        tints = emptyList(),
        blurRadius = blurRadius.coerceAtMost(FolioTokens.blurRadiusMax),
        noiseFactor = noiseFactor,
    ),
) {
    // The capabilities resolver already excluded platforms where blur is
    // unreliable; Haze's own default would additionally exclude API 31 and
    // substitute a scrim, which is not this design's fallback.
    blurEnabled = true
    this.alpha = alpha
    if (progressive) {
        this.progressive = HazeProgressive.verticalGradient(
            startY = 0f,
            startIntensity = 1f,
            endY = Float.POSITIVE_INFINITY,
            endIntensity = 0.20f,
            preferPerformance = true,
        )
    }
}
