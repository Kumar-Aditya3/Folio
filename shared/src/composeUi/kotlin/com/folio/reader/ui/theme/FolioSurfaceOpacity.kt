package com.folio.reader.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.util.lerp
import com.folio.reader.settings.ReaderSettings
import kotlin.math.max

/**
 * User control over how solid the app's glass is.
 *
 * This deliberately lives *outside* [FolioAtmosphere]. The atmosphere is a pure
 * function of a palette and is asserted directly by the §15 design tests ("glass
 * not lid": `barGlass` alpha stays in a narrow band, `veilFill` stays effectively
 * opaque). If a user preference were folded into it those invariants would
 * describe a preference rather than the design, and the tests would have to be
 * loosened until they stopped meaning anything.
 *
 * So the atmosphere keeps stating what the design intends, and each knob is
 * applied at the paint site — the one place that knows *which* surface it is.
 *
 * Every knob is a **fill alpha, not a factor**: `1f` means the surface is solid
 * and nothing bleeds through it. The shipped glass look therefore sits partway
 * along each slider ([BAR_GLASS], [NAV_GLASS], [PANEL_GLASS]) rather than at its
 * end. Factors were the first design and they were wrong: a factor on a `barGlass`
 * alpha of 0.36 can only ever scale 0.36 *down*, so the top-bar slider spanned
 * "invisible" to "nearly invisible" and its two extremes looked identical.
 */
@Immutable
data class FolioSurfaceOpacity(
    /** App top bars and the search masthead — the crown's alpha; see [topBarFill]. */
    val topBar: Float = BAR_GLASS,
    /** The floating nav capsule. */
    val navBar: Float = NAV_GLASS,
    /** Cards, sheets and menus drawn with `Modifier.folioVeil`. */
    val panel: Float = PANEL_GLASS,
    /** Reader bars, side rail, TOC/notes drawers and the reader settings sheet. */
    val readerChrome: Float = 1f
) {
    companion object {
        val Default = FolioSurfaceOpacity()

        /**
         * The floor. `folioVeil` keeps a 1dp hairline and a sheen at any fill, so
         * a surface stays findable even when its tint has all but vanished.
         */
        const val MIN = 0.1f

        /** Where the designed masthead glass (`barGlass` at full collapse) lands. */
        const val BAR_GLASS = 0.35f

        /** Where the designed nav capsule lands. */
        const val NAV_GLASS = 0.9f

        /** Where the designed panel fill (`veilFill`) lands. */
        const val PANEL_GLASS = 0.97f
    }
}

/**
 * The masthead's fill, as the alphas of the stops its gradient is built from.
 *
 * The *shape* of the decay is part of the answer, not a detail: shipped glass
 * thins toward the bar's foot so the collapsed masthead reads as the page's own
 * field thickening under the status icons, but a bar the user has asked to be
 * solid must not thin at all or the page shows through its bottom edge.
 */
@Immutable
data class FolioBarFill(
    /** Alpha at the crown, behind the status icons. */
    val crown: Float,
    /** Alpha at ~60% of the bar's height. */
    val waist: Float,
    /** Alpha where the bar meets the page. */
    val foot: Float,
    /** Factor on the designed status-icon scrim. */
    val scrim: Float,
    /** How present the bar is at all — drives the sheen, the rule and its fade. */
    val presence: Float,
)

/**
 * The top bar's fill at [collapse] (0 at rest, 1 fully collapsed).
 *
 * At and below [FolioSurfaceOpacity.BAR_GLASS] this is exactly the shipped
 * behaviour: no fill at rest, glass fading in as content passes underneath.
 * Above it the bar progressively stops waiting for the scroll and stops decaying,
 * because a "100%" that still vanishes at rest is not an opacity setting.
 */
fun FolioSurfaceOpacity.topBarFill(collapse: Float): FolioBarFill {
    val knob = topBar.coerceIn(FolioSurfaceOpacity.MIN, 1f)
    val f = collapse.coerceIn(0f, 1f)
    // How far past the designed glass point the user has pushed: 0 = glass, 1 = lid.
    val solid = ((knob - FolioSurfaceOpacity.BAR_GLASS) /
        (1f - FolioSurfaceOpacity.BAR_GLASS)).coerceIn(0f, 1f)
    val presence = max(f, solid)
    val crown = knob * presence
    return FolioBarFill(
        crown = crown,
        // 0.52 and 0.19 are the shipped 0.44/0.85 and 0.16/0.85 decay, re-based on
        // the crown now that the crown is the alpha rather than 0.85 of it.
        waist = crown * lerp(0.52f, 1f, solid),
        foot = crown * lerp(0.19f, 1f, solid),
        // The scrim is only ever the ground for the OS icons, so it tops out at
        // the designed strength and merely fades away below the glass point.
        scrim = (knob / FolioSurfaceOpacity.BAR_GLASS).coerceAtMost(1f),
        presence = presence,
    )
}

/** Clamps a persisted value into the usable range; tolerates absent/old data. */
private fun sane(value: Float): Float =
    value.coerceIn(FolioSurfaceOpacity.MIN, 1f)

/** Reads the four opacity preferences off the settings blob. */
fun ReaderSettings.surfaceOpacity(): FolioSurfaceOpacity = FolioSurfaceOpacity(
    topBar = sane(topBarOpacity),
    navBar = sane(navBarOpacity),
    panel = sane(panelOpacity),
    readerChrome = sane(readerChromeOpacity)
)

val LocalFolioSurfaceOpacity = staticCompositionLocalOf { FolioSurfaceOpacity.Default }

/** The active opacity preferences. */
val FolioTheme.surfaceOpacity: FolioSurfaceOpacity
    @Composable
    @ReadOnlyComposable
    get() = LocalFolioSurfaceOpacity.current

/**
 * Fill alpha for a reader glass surface.
 *
 * Reader controls sit over a page being read rather than over the app's own
 * field, so at 100% they are fully solid and the page cannot bleed through the
 * text; at the floor they are a barely-there tint over it.
 */
val FolioTheme.readerVeilAlpha: Float
    @Composable
    get() = LocalFolioSurfaceOpacity.current.readerChrome.coerceIn(FolioSurfaceOpacity.MIN, 1f)
