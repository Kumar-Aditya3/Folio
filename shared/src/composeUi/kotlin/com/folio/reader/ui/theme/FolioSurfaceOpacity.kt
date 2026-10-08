package com.folio.reader.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.util.lerp
import com.folio.reader.settings.ReaderSettings

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
 * The §16 glass tier follows the same split. The alphas above were calibrated
 * for the no-blur world (near-opaque, because translucency alone let text bleed
 * through); real blur fixes bleed-through, so when a paint site is actually
 * blurring it steps its knob down via [glassTierAlpha] instead of burying the
 * blurred backdrop under the un-compensated fill. The atmosphere and the §15
 * tests are untouched — the tier is a paint-site decision, keyed on the
 * platform's glass capabilities, and `1f` remains a solid lid everywhere.
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

        /** Where the designed masthead glass (`barGlass`) lands. */
        const val BAR_GLASS = 0.35f

        /** Where the designed nav capsule lands. */
        const val NAV_GLASS = 0.9f

        /**
         * Where the designed panel fill (`veilFill`) lands.
         *
         * Ramped down from 0.97. A near-lid at rest is what made an un-blurred panel read as a
         * box rather than a pane: the eye needs *some* bleed-through to accept a surface as
         * glass. 0.90 still keeps on-panel text legible and the rim reading, but leaves the
         * room faintly present through it.
         */
        const val PANEL_GLASS = 0.90f

        /**
         * The §16 glass tiers — each surface's designed point once *real* blur
         * runs under it (see [glassTierAlpha]). Reader chrome deliberately has
         * none: it sits over the page being read, where bleed-through is a legibility
         * problem blur does not fully buy back, and over the WebView it cannot blur
         * at all.
         */
        const val BAR_GLASS_BLURRED = 0.38f
        const val NAV_GLASS_BLURRED = 0.50f
        // Stepped down again from 0.70. The glass now carries its body *inside*
        // the blur pass via FolioGlassWeight.bodyTintAlpha — a surface-coloured
        // frost that rides the blur — so the external flat fill no longer has to
        // play the body of the slab. Keeping it at 0.70 on top of the new frost
        // double-lidded the pane back into an opaque square.
        //
        // Raised from 0.52 to 0.62. The two numbers are a pair: 0.52 against a
        // 0.97 rest point was a *big* step, and the step is what the eye reads as
        // a box appearing on blur-capable devices. With the rest point now 0.90
        // the tier is a gentle ramp, and 0.62 is the seal coat that keeps
        // on-panel text legible while the frosted body + accent tint under it do
        // the "opaque yet glass" work.
        const val PANEL_GLASS_BLURRED = 0.62f
    }
}

/**
 * The §16 glass tier of a fill alpha: what [knob] becomes at a paint site that
 * is actually blurring its backdrop.
 *
 * The shipped alphas were calibrated for the no-blur world — near-opaque on
 * purpose, because translucency alone let page text bleed through a bar at full
 * contrast. Blur is exactly the thing that fixes bleed-through, so when it runs
 * the fill steps down to [glassPoint]: the blurred backdrop shows through the
 * material instead of being buried under it, which is the whole difference
 * between liquid glass and the same pill with better anti-aliasing.
 *
 * The knob's contract survives unchanged: `1f` is still a lid (a reader who
 * asked for solid gets solid — blur never punches a hole in it), and at or
 * below [designPoint] the knob keeps its proportional meaning, so the floor and
 * the sliders behave identically; only the designed point moves.
 */
fun glassTierAlpha(knob: Float, designPoint: Float, glassPoint: Float): Float {
    val k = knob.coerceIn(FolioSurfaceOpacity.MIN, 1f)
    if (k >= 1f) return 1f
    val point = designPoint.coerceIn(FolioSurfaceOpacity.MIN, 1f)
    return if (k <= point) {
        (k * (glassPoint / point)).coerceIn(FolioSurfaceOpacity.MIN, glassPoint.coerceAtMost(1f))
    } else {
        lerp(glassPoint, 1f, (k - point) / (1f - point))
    }
}

/** The nav capsule's fill alpha at its paint site, tiered when that site can blur. */
fun FolioSurfaceOpacity.navCapsuleFill(canBlur: Boolean): Float =
    if (canBlur) {
        glassTierAlpha(navBar, FolioSurfaceOpacity.NAV_GLASS, FolioSurfaceOpacity.NAV_GLASS_BLURRED)
    } else {
        navBar
    }

/** A panel/sheet fill alpha at its paint site, tiered when that site can blur. */
fun FolioSurfaceOpacity.panelFill(canBlur: Boolean): Float =
    if (canBlur) {
        glassTierAlpha(panel, FolioSurfaceOpacity.PANEL_GLASS, FolioSurfaceOpacity.PANEL_GLASS_BLURRED)
    } else {
        panel
    }

/**
 * The masthead's fill, as the alphas of the stops its gradient is built from.
 *
 * The stops are measured against the bar's own chrome band — its status inset plus
 * the title row — never against the whole masthead, so a screen that hangs a rail
 * under its title paints the same glass behind its logo as one that does not.
 *
 * The *shape* of the decay is part of the answer, not a detail: shipped glass
 * thins toward the bar's foot so the masthead reads as the page's own field
 * thickening under the status icons, but a bar the user has asked to be solid
 * must not thin at all or the page shows through its bottom edge.
 */
@Immutable
data class FolioBarFill(
    /** Alpha at the crown, behind the status icons. */
    val crown: Float,
    /** Alpha at ~60% of the bar's chrome band. */
    val waist: Float,
    /** Alpha where the chrome band ends, and below it. */
    val foot: Float,
    /** Factor on the designed status-icon scrim. */
    val scrim: Float,
    /** How present the bar's material is — drives the blur, sheen, rule and fade. */
    val presence: Float,
    /**
     * Alpha for the blurred pane itself, on a platform that actually blurs.
     *
     * Separate from [presence] because the blur layer is not a tint that rides over
     * the page: Haze records an **opaque** `backgroundColor` beneath the sampled
     * backdrop and draws that whole layer at this alpha, so this value — not the
     * fill painted on top of it — is what decides how solid a blurring masthead is.
     *
     * It used to be [presence], which tops out at 1 at and above the designed glass
     * point. Every setting from [FolioSurfaceOpacity.BAR_GLASS] to 100% therefore
     * painted the same fully opaque lid and the top-bar slider did nothing across
     * two thirds of its travel — the "transparency doesn't apply evenly" report.
     * Keyed to the knob instead, the pane is never more solid than the bar the
     * reader asked for.
     */
    val material: Float,
)

/**
 * The top bar's fill: one profile for every masthead in the app.
 *
 * The knob *is* the crown's alpha — at the top of a page and halfway down it, on
 * Home, on Library, on More, on every pushed settings category — so "top bar
 * opacity" means one thing wherever the bar is drawn.
 *
 * It used to be scroll-linked, and that is what made the preference impossible to
 * keep rather than subtle. Every screen collapses over a range of its own (Home's
 * hero is 160dp, every other masthead 56dp) and four screens report no collapse at
 * all, so one slider value painted a visibly different bar in each section of the
 * app; the resting floor added to stop the fill vanishing at the top of a page then
 * ignored the slider on the way *down*, pinning every bar at >= 0.30 however
 * transparent the reader asked for. Scroll still drives the bar's typography, its
 * mark, its rail and the travel of its specular band — it no longer argues with a
 * preference.
 *
 * The *shape* of the decay stays part of the answer: shipped glass thins toward the
 * bar's foot so the masthead reads as the page's own field thickening under the
 * status icons, but a bar the reader asked to be solid must not thin at all or the
 * page shows through its bottom edge. So the decay is keyed to the knob as well —
 * the full shipped fade at the designed glass point, none of it at a lid.
 *
 * [blurred] steps the knob down to its glass tier
 * ([glassTierAlpha] at [FolioSurfaceOpacity.BAR_GLASS_BLURRED]) first — the
 * masthead is the one surface whose blur and fill are computed together, so
 * its tier lives here rather than at a separate paint site.
 */
fun FolioSurfaceOpacity.topBarFill(blurred: Boolean = false): FolioBarFill {
    val knob = (
        if (blurred) {
            glassTierAlpha(topBar, FolioSurfaceOpacity.BAR_GLASS, FolioSurfaceOpacity.BAR_GLASS_BLURRED)
        } else {
            topBar
        }
        ).coerceIn(FolioSurfaceOpacity.MIN, 1f)
    // How solid the reader asked for, as the thing that holds the decay flat: at a
    // lid the glass must not thin anywhere, at the designed point it keeps the
    // shipped fade toward the foot.
    val solid = knob
    // How present the bar's *material* is — the blur, the frost, the specular catch
    // and the rule. It reaches full strength at the designed glass point, so every
    // masthead wears the same pane rather than one that thickens with the scroll,
    // and only thins below that point, where a reader dragging the slider down is
    // asking to see through the bar rather than to frost it.
    val presence = (knob / FolioSurfaceOpacity.BAR_GLASS).coerceAtMost(1f)
    return FolioBarFill(
        crown = knob,
        // 0.52 and 0.19 are the shipped 0.44/0.85 and 0.16/0.85 decay, re-based on
        // the crown now that the crown is the alpha rather than 0.85 of it.
        waist = knob * lerp(0.52f, 1f, solid),
        foot = knob * lerp(0.19f, 1f, solid),
        // The scrim is only ever the ground for the OS icons, so it tops out at
        // the designed strength and merely fades away below the glass point.
        scrim = presence,
        presence = presence,
        // The blurred pane carries an opaque base, so its alpha is the bar's opacity
        // rather than its "how much material is there" strength. See [FolioBarFill.material].
        material = knob,
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

/**
 * How much *weight* a glass surface carries — the perceptual depth plane it sits
 * on, expressed as the two levers that do not re-introduce the "box within the
 * card" artifact (so: no cast shadow on panes).
 *
 *  - [blurScale] multiplies [FolioTokens.blurRadius]. Deeper blur reads as a
 *    thicker slab of glass further off the background, which is the single
 *    strongest "this is a separate object, not a tint" cue available without a
 *    drop shadow.
 *  - [frostTintAlpha] is how strongly the blurred backdrop is dyed with the
 *    theme's own accent *inside* the Haze pass. 0 keeps the pre-weight neutral
 *    blur byte-for-byte; a positive value is what gives each theme's glass its
 *    own personality and makes the frost absorb the room's hue instead of
 *    passing it through as a flat neutral pane.
 *  - [bodyTintAlpha] is how strongly the blurred backdrop is dyed with the
 *    *surface's own colour*, laid under the accent frost and still inside the
 *    Haze pass. This is the lever that makes the glass read as **opaque and
 *    glass at once**: a surface-coloured tint composited over the (spatially
 *    varying) blur frosts the backdrop into a substantial pane you cannot see
 *    sharply through, exactly like a real frosted material, while light and
 *    colour still bleed through — the body comes from a tint *over the blur*,
 *    never from raising the flat external fill, which only buries the blur and
 *    reads as a dead square. 0 keeps the pre-body single-tint look.
 *
 * Weight scales with the surface's role: a nav/top bar stays airy so chrome does
 * not fight content; a card/sheet gets a medium slab; a raised hero or modal gets
 * the full thickness so it clearly floats above everything. The body/accent
 * frost rises with the tier so the heavier the surface, the more opaque-yet-glass
 * it reads.
 */
@Immutable
data class FolioGlassWeight(
    val blurScale: Float,
    val frostTintAlpha: Float,
    val bodyTintAlpha: Float = 0f,
) {
    companion object {
        /** Chrome: bars, rails. Light and transparent — barely a slab. */
        val Bar = FolioGlassWeight(blurScale = 0.95f, frostTintAlpha = 0.08f, bodyTintAlpha = 0.16f)
        /** The app's workhorse card/sheet material (`folioVeil`). A real slab. */
        val Veil = FolioGlassWeight(blurScale = 1.1f, frostTintAlpha = 0.13f, bodyTintAlpha = 0.26f)
        /** Lifted cards and heroes (`folioRaised`). Thicker, more presence. */
        val Raised = FolioGlassWeight(blurScale = 1.35f, frostTintAlpha = 0.17f, bodyTintAlpha = 0.32f)
        /** Modals and the frontmost sheets. Full thickness, floats clearly. */
        val Modal = FolioGlassWeight(blurScale = 1.6f, frostTintAlpha = 0.19f, bodyTintAlpha = 0.36f)

        /**
         * The neutral weight: current blur, no frost tint. Existing call sites
         * that pass nothing render exactly as before (Rule 19 byte-for-byte on
         * the pref-off path is unaffected since the frost only rides the blur).
         */
        val None = FolioGlassWeight(blurScale = 1.0f, frostTintAlpha = 0f, bodyTintAlpha = 0f)
    }
}

/**
 * Which layer of the app a glass surface belongs to — and therefore how much of the
 * material it is allowed to pay for.
 *
 * This is [Glass.kt's][com.folio.reader.ui.components] own stated rule ("glass is the
 * material of the overlay layer only: surfaces that sit *over* content may be glass;
 * in-flow content stays paper or sunken") finally expressed as a type, because the rule
 * was being broken by roughly forty call sites and the cost is not a detail.
 *
 * ## Why the tier is a performance decision, not a style one
 *
 * Haze caches **nothing** across frames: every draw allocates a fresh scratch
 * `GraphicsLayer`, records the sampled backdrop into it, and re-runs the blur. And because
 * the backdrop is the app's animated ground plane, the room's source layer changes every
 * frame — so every glass surface in the app re-records and re-blurs its **entire area**,
 * every frame, at 60–120 Hz. That is the whole of the "extremely laggy with liquid glass on"
 * report: it is `N_surfaces × full-area blur`, and nothing about the per-surface tuning
 * changes it.
 *
 * So the question is not "how big should the blur be" but "does this surface have anything
 * behind it worth blurring". An in-flow card is an *item* in a scrolling list: nothing ever
 * passes behind it except the ground plane, which is a smooth ramp and three soft blooms —
 * blurring it is arithmetically a no-op and visually indistinguishable, at full price. Only
 * overlays (bars, the nav capsule, sheets, menus, modals, the hero) have real content
 * passing underneath, and only they keep the blur.
 *
 * The two AGSL passes go with it, for the same reason. The lens replays the captured room
 * into a scratch layer and evaluates the shader three times per pixel (RGB dispersion); the
 * film runs a full-surface rim/glint pass. Both exist to bend and light the *room* showing
 * through a pane, which is exactly what an in-flow card does not have. Their painted
 * stand-ins — the sheen and the edge bevel — stay, so an in-flow card is still a glass slab
 * with a lit edge; it simply stops paying for a refraction of nothing.
 */
enum class FolioGlassTier {
    /** Over content: bars, the nav capsule, sheets, menus, modals, heroes. Full glass. */
    Overlay,

    /**
     * In the page's own flow: section cards, group panels, shelves. Painted glass — fill, rim,
     * grain — and no blur, lens, film, **bevel or sheen**.
     *
     * P7 removed the last two. They are both *second* edges: the bevel strokes a lit rim and a
     * shadowed wall along a silhouette that already has a rim, and the sheen lays another light on
     * it. One edge per surface is the rule — an in-flow card draws its rim, and the plane's own
     * top-to-bottom ramp supplies the "this has a top and a bottom" reading the sheen used to.
     */
    InFlow,
}
