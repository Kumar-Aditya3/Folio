package com.folio.reader.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * The app's living-background **field**: a different [Mirage](https://github.com/AndroidPoet/mirage)
 * AGSL shader per theme, chosen for that theme's concept by [folioFieldKindFor] and fed that
 * theme's own palette.
 *
 * ## Why a composable and not a `Modifier`
 *
 * The field used to be a `Modifier` that compiled a hand-rolled AGSL string in a draw cache. The
 * Mirage shaders are *composables* ([io.androidpoet.mirage] exposes `Metaballs`, `Warp`, … as
 * `@Composable` surfaces), so the field is now a composable layer placed behind the content rather
 * than a modifier in the chain. The caller stacks it over the fallback ramp (see
 * [FolioThemeBackground]); where the shader runs it is opaque and covers that fallback edge to
 * edge, and where it cannot run it draws nothing and the fallback shows through.
 *
 * ## Richness, not brightness
 *
 * Every family is fed the theme's authored, full-chroma pools and accents and composites by
 * blending (never additive `Plus`), so the field keeps the palette's chroma instead of climbing
 * toward white — the "rich, not merely bright" discipline the previous field kept failing.
 *
 * ## Motion and the capability gate
 *
 * Mirage is AGSL, so it is Android 33+ only; the desktop/Skiko face and pre-33 Android draw
 * nothing ([folioThemeFieldSupported] is false there) and the fallback stands alone. When
 * [animate] is false the shader is handed `speed = 0`, which freezes it at zero per-frame cost —
 * the same picture, still — rather than being removed, so reduce-motion still shows the theme's
 * field.
 *
 * @param kind which Mirage family to paint (resolved from the pack id).
 * @param top the field ramp's top colour ([FolioAtmosphere.fieldTop]).
 * @param bottom the field ramp's bottom colour ([FolioAtmosphere.fieldBottom]).
 * @param pools the theme's authored aurora hues, at **full chroma** (do not pre-mute).
 * @param accents the theme's primary/secondary/tertiary accents, for families that read best
 *   from the loud end of the palette.
 * @param isDark which face we are on.
 * @param animate false parks the drift (reduce-motion).
 */
@Composable
expect fun FolioThemeField(
    kind: FolioFieldKind,
    top: Color,
    bottom: Color,
    pools: List<Color>,
    accents: List<Color>,
    isDark: Boolean,
    animate: Boolean,
    modifier: Modifier = Modifier,
)

/** Whether the Mirage field can draw on this platform: Android API 33+ only. */
expect fun folioThemeFieldSupported(): Boolean

/**
 * The whole ground plane, stacked. The fallback ramp-plus-bloom field is painted first as the
 * universal base; the per-theme Mirage shader is layered opaquely over it when the platform can
 * run it, keyed on the live [LocalFolioPackId] so each theme paints its own animation. One call,
 * placed once behind every screen (see `MainActivity` / desktop `Main`).
 */
@Composable
fun FolioThemeBackground(modifier: Modifier = Modifier) {
    val atmo = FolioTheme.atmosphere
    val colors = FolioTheme.colors
    val packId = LocalFolioPackId.current
    val overrides = LocalFolioFieldOverrides.current
    val animate = rememberMotionEnabled()
    val choice = folioFieldChoiceFor(packId, overrides)
    // The Mirage field paints only when the user has not chosen Plain and the platform can run
    // the shader. When it will paint it is opaque and covers the base edge to edge, so the base's
    // own per-vsync bloom animation is unseen — park it ([forceStatic]) to drop a full-screen
    // redraw per frame. On Plain, or where the shader is a no-op (desktop / pre-33), the base is
    // the whole ground plane and keeps its drift.
    val fieldWillPaint = choice is FolioFieldChoice.Animated && folioThemeFieldSupported()
    // Whether anything can actually see the ground plane right now. False only while a route has
    // claimed the whole window with [FolioFieldOccluder]; true in every un-provided tree, so this
    // is the pre-existing behaviour unless a route opts in. Gating the *composition* of the field
    // (not just its draw) is what stops Mirage's own per-vsync `withFrameNanos` clock, which only
    // ends when its `LaunchedEffect` leaves the tree — see [FolioFieldCost].
    val fieldVisible = rememberFolioFieldVisible()
    // Freezing the base was only half the saving. Where the Mirage field paints it is opaque edge
    // to edge, so the base underneath is not merely *still* — it is **unseen**, and it is still
    // four full-screen draws: one vertical ramp plus three radial blooms, each over every pixel of
    // the display. They are re-recorded whenever the room layer is (which, with the field
    // invalidating per vsync inside the §16 room rung, is every frame), so three invisible
    // full-screen gradient passes were being paid per frame on top of the shader's own.
    //
    // Only the blooms are dropped, never the ramp: the ramp is the guarantee that the window is
    // covered edge to edge before the shader's first frame lands, and it is one cheap pass. The
    // opacity claim is checked against the atmosphere's own alpha rather than assumed, so a
    // translucent field ramp (a palette change is not this file's business) keeps its blooms
    // instead of silently losing them behind a field you can see through.
    val fieldOpaque = fieldWillPaint &&
        (choice as? FolioFieldChoice.Animated)?.kind?.coversGroundPlane() == true &&
        atmo.fieldTop.alpha >= 1f &&
        atmo.fieldBottom.alpha >= 1f
    Box(modifier) {
        // Base layer: the ramp + drifting blooms. Always painted — it is the floor on desktop and
        // pre-33 Android, the complete background for Plain, and a harmless (frozen, bloomless)
        // under-paint where the opaque shader covers it.
        Box(
            Modifier.fillMaxSize().folioSilkBackground(
                forceStatic = fieldWillPaint,
                blooms = !fieldOpaque,
            )
        )
        // Top layer: this theme's Mirage field. Skipped entirely on Plain, while the ground plane
        // is fully occluded, and a no-op where the shader cannot run.
        if (choice is FolioFieldChoice.Animated && fieldVisible) {
            FolioThemeField(
                kind = choice.kind,
                top = atmo.fieldTop,
                bottom = atmo.fieldBottom,
                pools = atmo.pools,
                accents = listOf(colors.primary, colors.secondary, colors.tertiary),
                isDark = atmo.isDark,
                animate = animate,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
