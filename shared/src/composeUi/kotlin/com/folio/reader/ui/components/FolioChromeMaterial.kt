package com.folio.reader.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.folio.reader.ui.theme.FolioAtmosphere
import com.folio.reader.ui.theme.FolioBarFill
import com.folio.reader.ui.theme.FolioGlassWeight
import com.folio.reader.ui.theme.FolioSurfaceOpacity
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.atmosphere
import com.folio.reader.ui.theme.navCapsuleFill
import com.folio.reader.ui.theme.elements

/** One quiet floating-control material for both ends of the window.
 * Its authored plane is independent of section accents and cover artwork.
 * Geometry, blur capability, opacity preferences, and interaction remain at the consumers.
 */
@Immutable
data class FolioChromeMaterial(
    /** The theme's authored chrome plane. */
    val plane: Color,
    /** The same plane, unaffected by artwork or route changes. */
    val glass: Color,
    /** Restrained frost tint; active controls carry the stronger accent. */
    val accent: Color,
    /** The light catch along the silhouette — the atmosphere's own rim light. */
    val sheen: Color,
    /**
     * The edge the chrome meets the field along. One colour for both ends: the capsule
     * strokes it all the way round its pill, the masthead along the single edge it has that
     * the field is on (its foot — the crown runs off under the status bar and the sides run
     * off the window). A surface with a fill and no edge is what the masthead was, and it is
     * why the screenshot shows a seam where a bar should be.
     */
    val hairline: Color,
    /**
     * How much material the chrome carries. One weight for both ends: airy enough that
     * chrome does not fight content, bodied enough that the page passing behind it does not
     * read through as mud. See [CHROME_BODY_TINT].
     */
    val weight: FolioGlassWeight,
)

/**
 * The chrome's own glass weight.
 *
 * [FolioGlassWeight.Bar]'s blur and frost, with the **body** raised from 0.16 to
 * [CHROME_BODY_TINT]. That is the one lever the material model sanctions for the reported
 * "page content shows through the capsule and reads muddy": `folioGlassEffect`'s `bodyTint`
 * dyes the *blurred* backdrop with the surface's own plane from inside the Haze pass, so the
 * pane gains the body of a real frosted material while light and colour still bleed through
 * it. The alternative — raising the external flat fill — is the thing Glass.kt's material
 * model explicitly tells callers not to do: it buries the blur and reads as a dead square.
 *
 * Declared here rather than as a fifth [FolioGlassWeight] constant because it belongs to
 * *this* material: both ends of the window read it through [FolioChromeMaterial.weight], so
 * the masthead and the capsule cannot gain body at different rates.
 */
private const val CHROME_BODY_TINT = 0.30f

private val chromeGlassWeight = FolioGlassWeight(
    blurScale = FolioGlassWeight.Bar.blurScale,
    frostTintAlpha = FolioGlassWeight.Bar.frostTintAlpha,
    bodyTintAlpha = CHROME_BODY_TINT,
)

/**
 * The designed chrome glass point — **one** number for both ends of the window, and the
 * capsule's, because the capsule is the end that already read as a defined surface.
 *
 * The masthead used to be designed at [FolioSurfaceOpacity.BAR_GLASS] (0.35, stepping to
 * 0.38 under blur) while the capsule sat at [FolioSurfaceOpacity.NAV_GLASS] (0.9, stepping
 * to 0.50). Two designed points is two materials however identical their colour, which is
 * most of why the top of the screen had no surface on it and the bottom had a pill.
 */
const val FOLIO_CHROME_GLASS: Float = FolioSurfaceOpacity.NAV_GLASS

/** The same point once real blur runs under the chrome. See [FOLIO_CHROME_GLASS]. */
const val FOLIO_CHROME_GLASS_BLURRED: Float = FolioSurfaceOpacity.NAV_GLASS_BLURRED

/**
 * The chrome opacity: one knob for both ends of the window.
 *
 * It resolves to [FolioSurfaceOpacity.navBar] because the two ends are one surface and a
 * surface has one opacity. The preference model keeps both fields — old settings blobs still
 * load, and `SettingsMergeTest` still describes a four-field record — and the appearance
 * panel writes them in lockstep from a single "App bars" slider, so the pair can never
 * disagree again.
 */
val FolioSurfaceOpacity.chrome: Float
    get() = navBar

/**
 * The chrome's fill alpha at a paint site, tiered when that site can blur.
 *
 * Identical by construction to [navCapsuleFill] — it *is* that call — so the masthead cannot
 * drift from the capsule by arithmetic. Named for the material rather than for one end of it
 * so both ends read the same seam.
 */
fun FolioSurfaceOpacity.chromeFill(blurred: Boolean): Float = navCapsuleFill(canBlur = blurred)

/**
 * How the chrome's plane ramps from its crown to its foot.
 *
 * The capsule's plane carries a 6% top-to-bottom decay (`folioVeil` paints
 * `fill → fill * 0.94`), for the stated reason that one flat alpha gives the plane a single
 * hard edge wherever it meets the page and the eye finds that step. The masthead now carries
 * the *same* ramp across its chrome band — not the 0.52/0.19 profile it shipped with, which
 * took the bar from its crown alpha to a fifth of it and is exactly what made the top of the
 * screen a fading wash where the bottom was a defined pane.
 */
private const val CHROME_BAR_WAIST = 0.97f
private const val CHROME_BAR_FOOT = 0.94f

/**
 * The masthead's fill profile, built from the **chrome** knob.
 *
 * The counterpart of [navCapsuleFill] for the end of the window that paints a gradient
 * rather than a flat pill: same knob, same tier, same designed point, and a ramp matched to
 * the capsule's own. [com.folio.reader.ui.theme.topBarFill] is what this replaces — it keyed
 * the bar to [FolioSurfaceOpacity.topBar] and to [FolioSurfaceOpacity.BAR_GLASS], which is a
 * second designed point for one material. It is left in place for the tests that pin its
 * arithmetic and for nothing else.
 *
 * [FolioBarFill.material] is no longer read by the masthead: the bar's Haze layer now draws
 * at full strength and the plane rides *over* it, exactly as the capsule's does. Fading the
 * whole blur layer to the knob was the defect — it attenuated the opaque base, the blurred
 * backdrop and both frost tints together, leaving a composite indistinguishable from the
 * field it was drawn over. The field is kept populated so the type stays honest.
 */
fun FolioSurfaceOpacity.chromeBarFill(blurred: Boolean = false): FolioBarFill {
    val knob = chromeFill(blurred).coerceIn(FolioSurfaceOpacity.MIN, 1f)
    // Presence is measured against the designed point *for this path*, so the frost, the
    // scrim, the specular and the rim all reach full strength at the designed glass and only
    // thin below it. Measured against the un-tiered point instead, a blurring bar at its own
    // design value would read as 55% of a bar.
    val point = if (blurred) FOLIO_CHROME_GLASS_BLURRED else FOLIO_CHROME_GLASS
    val presence = (knob / point).coerceIn(0f, 1f)
    return FolioBarFill(
        crown = knob,
        waist = knob * CHROME_BAR_WAIST,
        foot = knob * CHROME_BAR_FOOT,
        scrim = presence,
        presence = presence,
        material = knob,
    )
}

/** Shared element-role colors, with the user's existing optical/opacity controls. */
@Composable
fun rememberFolioChromeMaterial(): FolioChromeMaterial {
    val atmos = FolioTheme.atmosphere
    val elements = FolioTheme.elements
    return remember(atmos, elements) {
        FolioChromeMaterial(
            plane = elements.chrome,
            glass = elements.chrome,
            accent = lerp(elements.chrome, elements.action, .10f),
            sheen = lerp(elements.chrome, elements.ink, .22f).copy(alpha = .25f),
            hairline = elements.ink.copy(alpha = .12f),
            weight = chromeGlassWeight,
        )
    }
}

/**
 * The chrome material as a property, the way the rest of the theme reads.
 *
 * Plain `@Composable`, not `@ReadOnlyComposable`: it caches through `remember`, and a
 * read-only composable may not call `remember`. Precedent is [FolioTheme.atmosphere], the
 * other remembered theme property.
 */
val FolioTheme.chromeMaterial: FolioChromeMaterial
    @Composable
    get() = rememberFolioChromeMaterial()
