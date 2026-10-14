package com.folio.reader.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.folio.reader.ui.theme.UiFonts
import androidx.compose.ui.util.lerp
import com.folio.reader.ui.theme.FolioGlassWeight
import com.folio.reader.ui.theme.FolioShapes
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens
import com.folio.reader.ui.theme.LocalFolioAmbient
import com.folio.reader.ui.theme.LocalFolioDaylight
import com.folio.reader.ui.theme.atmosphere
import com.folio.reader.ui.theme.rememberMotionEnabled
import com.folio.reader.ui.theme.surfaceOpacity
import com.folio.reader.ui.theme.folioLiquidGlass
import com.folio.reader.ui.theme.folioSheenGain
import com.folio.reader.ui.theme.lightDirection
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import com.folio.reader.ui.theme.MotionKey
import com.folio.reader.ui.theme.rememberMotionAnimation

/**
 * Screen chrome: the collapsing masthead and the controls that live in it.
 *
 * Home's hero already dissolved into its top bar as the page scrolled, and that
 * is the one piece of motion in the app that makes the chrome read as part of the
 * page rather than a lid on it. This file generalises the behaviour so any screen
 * can have it:
 *
 *  - [rememberFolioHeaderState] watches a scrolling child through nested scroll,
 *    so no list has to hoist its own state to get the effect;
 *  - [FolioTopBar] takes that collapse fraction and, over the first ~56dp of
 *    scroll, shrinks its title, travels its specular band and folds its rail away.
 *
 * The bar's **material** is not part of that: it is the chrome material
 * ([FolioChromeMaterial]) at the chrome opacity, which is the same substance, the same
 * alpha, the same frost, the same sheen and the same hairline the floating nav capsule is
 * made of. The two ends of the window are one piece of furniture and are now built from one
 * definition rather than kept in step by hand. An earlier design had the bar at "no fill at
 * all" until content passed underneath it; what that produced in practice was a masthead
 * with no surface on it at all — a leaf logo and a title sitting bare on the animated field
 * with a seam where the band ended — because a blur of a smooth field is the field, and the
 * only thing that made the bar a surface was an alpha that faded the whole blur layer to
 * nothing. The fill is the preference, everywhere, at rest and in flight.
 */

/**
 * One scroll container's motion, published for effects that must read it in the
 * **draw** phase rather than in composition.
 *
 * Two numbers, deliberately not one decayed value: [speed] is the level the most
 * recent scroll event reached and never falls on its own, while [stamp] says how
 * long ago that was — so the settle is a pure function of age
 * ([com.folio.reader.ui.components.rimVelocityLevel]) rather than a coroutine that
 * has to outlive the thing drawing it. That distinction is load-bearing here: the
 * consumers are featured cells of a `LazyVerticalGrid`, which *disposes* them on
 * scroll-out, and any settle scheduled inside one dies with it — leaving the effect
 * stuck at full strength. A stale [speed] self-corrects on the next read.
 *
 * Never expose this as a plain `Float` property: `collapse` below is the cautionary
 * tale, read in composition by four shelf call sites and a route, so every scroll
 * event recomposes what reads it.
 */
@Stable
class FolioScrollVelocity internal constructor(
    val speed: MutableFloatState,
    val stamp: MutableLongState,
)

/**
 * The shelf's motion as effects see it. Un-provided is the *resting* value, which is
 * why desktop — which never installs one — draws byte-for-byte today's rim, and why
 * no new `expect`/`actual` pair is needed for the same guarantee.
 */
val LocalFolioScrollVelocity = compositionLocalOf<FolioScrollVelocity?> { null }

/**
 * Scroll-linked collapse for a screen's masthead.
 *
 * [collapse] runs 0 (at rest) to 1 (fully collapsed) over the range passed to
 * [rememberFolioHeaderState], tracked 1:1 off what the scrolling child actually
 * consumed — an over-scroll pull at the top of a list moves nothing, which is
 * what made a plain `firstVisibleItemScrollOffset` reading jitter on bounce.
 *
 * Frozen at 0 under reduce-motion: this is scroll-linked animation, and §13.9
 * already holds Home's hero to the same rule. [velocity] is unreachable for the
 * same reason — the write sits behind the same `enabled` gate, so a reduce-motion
 * reader flinging the shelf cannot light a rim.
 */
@Stable
class FolioHeaderState internal constructor(
    private val rangePx: Float,
    private val enabled: Boolean,
    private val offsetState: MutableFloatState,
    private val speedState: MutableFloatState,
    private val stampState: MutableLongState,
) {
    private var offset by offsetState

    /**
     * 0 at rest, 1 fully collapsed. Derived rather than computed per read, so a
     * reader in composition is only invalidated when the *fraction* actually moves:
     * at rest, and once the bar has saturated at the end of a long fling, nothing
     * recomposes. What this does **not** buy is silence while the bar travels its
     * range — there the value genuinely changes every frame, and only moving the
     * masthead's reads into the draw phase would fix that.
     */
    private val collapseState = derivedStateOf {
        if (!enabled || rangePx <= 0f) 0f else (offset / rangePx).coerceIn(0f, 1f)
    }

    val collapse: Float
        get() = collapseState.value

    /** Null when motion is off, so a consumer cannot accidentally opt in. */
    val velocity: FolioScrollVelocity? =
        if (enabled) FolioScrollVelocity(speedState, stampState) else null

    /**
     * Snap back to the at-rest, expanded state. The offset is otherwise a sticky
     * accumulator with no reset path, so a screen that swaps the scrollable beneath
     * the bar (Library's Books/Manga shelves) inherits the old shelf's collapse and
     * arrives with its rail still folded away.
     *
     * Motion settles with it. [LibraryScreen] calls this on entry and on a search
     * swap, which is the one moment a rim caught mid-fling would otherwise have to
     * decay on its own.
     */
    fun reset() {
        offset = 0f
        speedState.floatValue = 0f
        stampState.longValue = 0L
    }

    /**
     * Publish a progress the screen computed for itself, where §13.9's rules keep the
     * maths out of the bar: Home's hero must track its list offset 1:1, not through a
     * nested-scroll connection, so `HomeScreen` derives the fraction and hands it
     * here. Storing it in this state rather than a loose float is what lets Home's
     * masthead be restored with the page like every other one, and the same
     * reduce-motion freeze applies — with motion off the bar never collapses.
     *
     * Deliberately writes no velocity: Home's hero is driven by a list offset, not by
     * consumed scroll deltas, so a rate derived here would be a rate of something
     * else. The Home rim passes no source and rests at its own progress.
     */
    fun setProgress(fraction: Float) {
        if (enabled) offset = fraction.coerceIn(0f, 1f) * rangePx
    }

    /** Attach to the container that holds the scrolling content. */
    val nestedScrollConnection: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPostScroll(
            consumed: Offset,
            available: Offset,
            source: NestedScrollSource,
        ): Offset {
            if (enabled) {
                val dy = consumed.y
                offset = (offset - dy).coerceIn(0f, rangePx)
                val now = System.nanoTime()
                val gapNanos = if (stampState.longValue == 0L) 0L else now - stampState.longValue
                stampState.longValue = now
                speedState.floatValue = rimSpeedLevel(dy, gapNanos)
            }
            return Offset.Zero
        }
    }
}

@Composable
fun rememberFolioHeaderState(range: Dp = 56.dp): FolioHeaderState {
    val rangePx = with(LocalDensity.current) { range.toPx() }
    val motion = rememberMotionEnabled()
    // Saveable, not merely remembered. The bar's collapse belongs to its screen, but
    // switching tabs throws that screen's composition away, so a remembered offset
    // came back as expanded — over a shelf that *does* restore its scroll (the same
    // mechanism), which left the first rows of the page under the glass. The
    // bottom-bar tabs navigate with saveState/restoreState, so each destination's
    // registry survives the switch and this restores with it.
    val offset = rememberSaveable { mutableFloatStateOf(0f) }
    // A scroll *rate* from a previous session is not worth restoring, and a restored
    // stamp would be arbitrarily old — so neither is saveable. The decay envelope
    // makes any stale level read as rest on the first frame regardless.
    val speed = remember { mutableFloatStateOf(0f) }
    val stamp = remember { mutableLongStateOf(0L) }
    return remember(rangePx, motion, offset, speed, stamp) {
        FolioHeaderState(rangePx, motion, offset, speed, stamp)
    }
}

/**
 * When true (a tab↔tab swap is in flight), the masthead paints an opaque field behind itself and
 * drops its blur, so the outgoing tab — still composed for the shared-element cover flight — cannot
 * show through the bar's translucent glass. Restored to glass the instant the swap settles. This is
 * the occluder half of "keep the cover morph but never see the old tab behind the new masthead":
 * the flying cover is drawn in the shared-element overlay *above* the bar, so it still morphs.
 */
val LocalChromeOccludeSwap = androidx.compose.runtime.compositionLocalOf { false }

/**
 * The screen's masthead. Editorial, not Material: the title carries the display
 * face at `headlineMedium`, the bar's glass is the chrome material at the chrome
 * opacity so the page's field still runs behind it, and a soft scrim holds the OS
 * status icons off the page.
 *
 * ## One material, twenty-three call sites, two positions
 *
 * The masthead and the floating nav capsule are **the same surface at opposite ends of the
 * window**, and they are now built from one definition: [FolioChromeMaterial] supplies the
 * plane, the room-washed glass, the frost accent, the sheen, the hairline and the weight;
 * `FolioSurfaceOpacity.chromeFill` supplies the alpha both ends paint at. Nothing about the
 * bar's material is decided here, by a call site, or per screen — which is what makes the
 * change reach every screen that draws a masthead at once instead of being patched per
 * section.
 *
 * The construction is the capsule's, too, not merely the colour: the blur layer draws at
 * **full strength** and the plane rides over it at the preference's alpha, the body of the
 * glass comes from a surface-hued tint *inside* the blur, and the silhouette carries a
 * hairline. The bar previously faded its whole Haze layer to the knob and painted no plane
 * at all when it blurred, which composites to the field it was drawn over — a bar with no
 * material, which is exactly what it looked like.
 *
 * Its **geometry** is untouched and stays its own: the full-bleed band, the status inset,
 * the 56dp title row, the folding [rail], the fade at the foot, the status-icon scrim. The
 * decay is measured against the bar's chrome band rather than its total height — so Home,
 * Library, More and a pushed settings category wear one bar, whether or not they hang a
 * [rail] under the title and wherever their page happens to be scrolled to.
 *
 * [collapse] drives what genuinely belongs to the scroll: the title and the mark
 * step down in size, the specular band travels, and [rail] folds up under the bar.
 * Nothing changes the bar's own height, so the page never jumps — and nothing in the
 * material reads it, so a screen collapsing over 160dp (Home's hero) and one collapsing over
 * 56dp and one that never collapses at all are all the same bar.
 *
 * [titleStyle] lets the §13.9 collapsing Home hero migrate its title in at a
 * smaller size; [titleContent] replaces the title outright for callers that
 * cross-fade two of them.
 *
 * Top-level destinations pass no [navigationIcon], so they get the Folio mark in
 * that slot and the persistent screens read as one product masthead; pushed
 * screens keep their back arrow exactly where it was.
 *
 * [rail] is the one row a screen may hang under its title — filter chips, a
 * segmented switch. Deliberately *one*: a mode row over a filter row over the bar
 * is what made Library read as a control panel.
 */
@Composable
fun FolioTopBar(
    title: String,
    modifier: Modifier = Modifier,
    titleStyle: TextStyle? = null,
    navigationIcon: (@Composable () -> Unit)? = null,
    collapse: Float = 0f,
    titleContent: (@Composable () -> Unit)? = null,
    rail: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    /**
     * **Retained for source compatibility and otherwise inert.**
     *
     * It used to gate the hairline rule at the bar's foot, "so screens whose rail hangs
     * outside the bar can turn it off". In practice it defaulted off, no call site ever
     * passed `true`, and seven passed `false` — so the masthead's one edge was never drawn
     * anywhere in the app while the nav capsule stroked its hairline all the way round its
     * pill. That is a per-call-site material override, which is the thing the two ends of the
     * window are being unified to remove: the rule now belongs to the chrome material and is
     * always painted (see [FolioChromeMaterial.hairline]). The parameter stays so the twenty-three
     * call sites keep compiling; new ones should not pass it.
     */
    @Suppress("UNUSED_PARAMETER")
    bottomRule: Boolean = false,
) {
    val colors = FolioTheme.colors
    val atmos = FolioTheme.atmosphere
    val f = collapse.coerceIn(0f, 1f)
    val statusPx = WindowInsets.statusBars.getTop(LocalDensity.current).toFloat()
    // The bar's chrome band: its status inset plus the title row, in px. Every
    // decay the masthead paints is measured against this and not against the
    // Column, so a screen that hangs a rail under its title paints the same glass
    // behind its logo as one that does not.
    val chromeBandPx = statusPx + with(LocalDensity.current) { FolioTokens.barHeight.toPx() }
    // §16 liquid glass: the masthead's blur and fill are computed together, so
    // the tier is applied inside chromeBarFill — when the bar can actually blur,
    // the chrome knob steps down to its glass tier, because blur fixes the
    // bleed-through the near-opaque default was calibrated against.
    val glassCaps = LocalGlassCapabilities.current
    val glassBackdrop = LocalGlassBackdrop.current
    val blurred = glassCaps.blur && glassBackdrop != null
    // Occlude behind the bar during a tab↔tab swap so the outgoing tab cannot bleed through the
    // glass while its cover still flies (see [LocalChromeOccludeSwap]).
    val occlude = LocalChromeOccludeSwap.current
    // The chrome opacity *is* the crown's alpha, not a factor on a designed one: scaling a
    // 0.36 `barGlass` could only ever go down, so the slider ran from invisible to nearly
    // invisible and its ends looked the same. It is also the *whole* answer — the fill does
    // not track this screen's collapse, because each screen collapses over a range of its
    // own and the same preference then painted a different bar on Home, Library, More and a
    // settings category. The scroll still moves the type, the mark, the rail and the
    // specular band; the glass is the preference, everywhere.
    //
    // Read from the **chrome** knob and the chrome's designed point (FolioChromeMaterial),
    // not from `topBarFill`'s `topBar`/`BAR_GLASS` pair. Two designed points — 0.35/0.38 for
    // the bar against 0.9/0.50 for the capsule — is two materials however identical their
    // colour, and it is most of why the top of the window had no surface on it while the
    // bottom had a defined pill.
    val fill = FolioTheme.surfaceOpacity.chromeBarFill(blurred = blurred)
    // Status-icon ground: per palette and per theme, not the featured cover. The masthead no
    // longer borrows the hero's hue (see FolioChromeMaterial) — a null tint returns the authored
    // scrim byte-for-byte so the top of the screen wears the theme's own colour.
    val scrimBase = com.folio.reader.ui.theme.scrimFor(atmos, null, atmos.fieldTintStrength)
    val statusColor = scrimBase.copy(alpha = scrimBase.alpha * fill.scrim)
    // Glass for *this* room, not the palette's resting one: the chrome plane is derived per
    // palette and the book's colour is runtime, so the bar asks the chrome material to bend its
    // hue. Hue only — alpha and lightness are pinned — so §15's glass window holds.
    //
    // This is now the **same material the nav capsule is made of** (see FolioChromeMaterial):
    // the two ends of the window are one substance, read from one place. The masthead keeps
    // every part of its own geometry — the band, the decay, the scrim, the rail — and changes
    // only what it is made of.
    val chrome = rememberFolioChromeMaterial()
    val veil = chrome.glass
    // The ground the blur falls back to where the registered source has no content — the
    // *lit* field's lower endpoint, which is what the capsule already uses (`folioVeil`).
    // Read unconditionally, outside the modifier's `if`, so the composable call is not
    // conditional and the lookup happens once per recomposition rather than per frame.
    val chromeGround = folioFieldBottom(atmos)
    // Specular catch for the masthead's mirror finish — the atmosphere's own rim
    // light, so a dark field emits at the crown and paper catches a white sheen.
    // Purely additive over the veil. Read from the chrome material so the catch on
    // the bar and the catch on the capsule are one colour.
    val sheen = chrome.sheen
    // The metallic-sheen knob: the bar's own light answer — the film's glints and the
    // mirror band below — dims with every other catch in the app. The fill, the
    // scrim, the hairline and the blur are the bar's material, not its light, and
    // stay untouched.
    val sheenGain = folioSheenGain()
    // Blur only while something is passing underneath — the same condition as the
    // fill — fading in with the bar's presence. A bar at rest over the page's own
    // field has nothing to refract.
    val canBlur = blurred && fill.presence > 0.01f && !occlude
    val daylight = LocalFolioDaylight.current
    // §17 living light, draw-phase read: the crown's specular catch drifts with
    // the room's slow light, so even a bar over an entirely still page has one
    // thing on it that is genuinely in transit.
    val ambient = LocalFolioAmbient.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (canBlur) {
                    Modifier.folioGlassEffect(
                        backdrop = glassBackdrop,
                        // The ground the blur falls back to where the registered source has
                        // no content — the *lit* field's lower endpoint, which is what the
                        // capsule already uses (`folioVeil`). It was the palette's unlit
                        // `background`, a colour the surroundings disagree with, so a bar
                        // over a page that stops short of it painted a band of a shade the
                        // room is not. One ground for both ends of the window.
                        backgroundColor = chromeGround,
                        // The chrome weight's own blur, like the capsule's. The bar used to
                        // take the raw token and so was the one piece of chrome whose glass
                        // was a different thickness from the other piece.
                        blurRadius = FolioTokens.blurRadius * chrome.weight.blurScale,
                        // **No alpha.** This is the defect the screenshot shows. Haze records
                        // an opaque `backgroundColor`, draws the sampled backdrop over it and
                        // composites both frost tints inside the layer — so an `alpha` here
                        // fades the *entire material*, base and blur and body together, and
                        // what survives over a smooth animated field is arithmetically the
                        // field itself. At the shipped 0.38 the masthead was 38% of a blur of
                        // the thing it was drawn over: no fill, no surface, just a seam where
                        // the band ended. The capsule has never passed an alpha either (see
                        // `folioVeil`): its layer draws at full strength and its plane rides
                        // *over* the blur, which is exactly what makes it read as a pane. The
                        // opacity preference lives in that plane — see the `drawBehind` below.
                        progressive = true,
                        // Near-uniform across the band, matching the capsule's own 6% plane
                        // ramp. The shipped 0.19/0.38 ≈ 0.50 halved the blur by the band's
                        // foot, which is the other half of "the bar dissolves into nothing".
                        progressiveEndIntensity = (fill.foot / fill.crown).coerceIn(0f, 1f),
                        // …and over the bar's chrome band, not the Column. Left at the
                        // layer's bottom the decay stretched with whatever a screen hung
                        // under its title, so Library's railed masthead was still blurring at
                        // full strength where Home's and a settings category's had already
                        // thinned. This is why `progressiveEndY` exists and it stays.
                        progressiveEndY = chromeBandPx,
                        // Chrome-weight frost, from the one material both ends read: the
                        // chrome accent dyes the frost (personality) and the surface-hued
                        // body tint frosts the backdrop into something you cannot see sharply
                        // through while the field still bleeds — opaque and glass at once —
                        // without a heavier flat fill, which is the one thing the material
                        // model forbids. Both tints are the capsule's, at the capsule's
                        // alphas, because there is one chrome weight now.
                        tint = chrome.accent.copy(
                            alpha = chrome.weight.frostTintAlpha * fill.presence,
                        ),
                        bodyTint = veil.copy(
                            alpha = chrome.weight.bodyTintAlpha * fill.presence,
                        ),
                        // The masthead is the largest blurred area in the app — full width,
                        // full bleed, and it re-blurs on every frame the room animates. The
                        // bar class budget is 0.5, which is the scale Haze itself selects for
                        // any progressive gradient (see HazeInputScale.Auto), so this is not a
                        // compromise on the bar's look; it is a quarter of the fill cost on the
                        // one surface where the area makes it matter. Spent against the
                        // device's own budget, so a constrained class resolves coarser still.
                        // See FolioGlassCost.
                        inputScale = FolioGlassCost.resolve(FolioGlassCost.bar, glassCaps),
                    )
                } else {
                    Modifier
                }
            )
            .then(if (glassCaps.noise && !canBlur) Modifier.folioGlassGrain() else Modifier)
            // R1: the AGSL film on the masthead. Under the veil and the sheen below, so
            // the bar keeps the fill it was given and gains an edge — the same ordering as
            // [folioRaised]'s card. Gated on the bar's own presence, because a bar at rest
            // over the page's field has no edge to catch and the film would be painting a
            // rim around nothing. `refractBackdrop` stays off here: the bar's backdrop is
            // the page scrolling beneath it, which the Haze pass already reproduces, and
            // the film's job at the crown is the specular, not a second lens.
            .then(
                if (fill.presence > 0.01f) {
                    val (lx, ly) = daylight.lightDirection()
                    Modifier.folioLiquidGlass(
                        // The surface's own plane, at the alpha it is actually painted at —
                        // the film's body sheen should be made of the material rather than of
                        // a second, fully-opaque colour laid over it. This is byte-for-byte
                        // what the capsule hands the same film (`folioVeil` passes its `fill`),
                        // and it was the last place the two ends disagreed about what they are
                        // made of.
                        accent = veil.copy(alpha = fill.crown),
                        highlight = sheen,
                        lightX = lx,
                        lightY = ly,
                        // The bar's presence *and* the metallic-sheen knob: the film
                        // is the bar's light answer (the specular at the crown), so it
                        // fades with the material and dims with every other catch.
                        intensity = fill.presence * sheenGain,
                        enabled = glassCaps.specular,
                        // The masthead is an unclipped, full-bleed band — it runs under the
                        // status bar and squares off at the crown — so its true silhouette is
                        // a rectangle, and the rim must follow that rather than the default
                        // card radius. Giving it a rounded shape here would round the bar's
                        // own hairline.
                        shape = RectangleShape,
                    )
                } else {
                    Modifier
                }
            )
            .drawBehind {
                // One glass profile for every masthead, whatever hangs under its title.
                // The decay is measured against the bar's own chrome band — the status
                // inset plus the title row — and never against the Column's height: a
                // screen that hangs a rail under its title (Library's filters) is half
                // again as tall as one that does not (Home, More, a settings category),
                // so a fill normalised to the whole masthead painted a different alpha
                // behind the logo on every section of the app, and moved again as the
                // rail folded. Measured against the band, the logo row sits on identical
                // glass everywhere and the rail below it rides the bar's foot.
                val bandEnd = (chromeBandPx / size.height).coerceIn(0.2f, 1f)
                // Tab↔tab swap: lay down an opaque field first so nothing behind the bar (the
                // outgoing tab, still composed for the cover flight) can bleed through the glass.
                // Uses the field's own top colour so it reads as the page thickening, not a slab.
                if (occlude) {
                    drawRect(atmos.fieldTop.copy(alpha = 1f))
                }
                // The OS icons need their own ground on every theme; the page does
                // not need a band. The scrim decays fast — full strength only in the
                // few pixels the icons actually occupy, and effectively gone by the
                // end of the chrome band — so there is no cut line across the screen
                // and no painted strip along the top of it.
                if (statusPx > 0f) {
                    val mid = (statusPx / size.height).coerceIn(0.05f, bandEnd * 0.75f)
                    val a = statusColor.alpha
                    drawRect(
                        Brush.verticalGradient(
                            0f to statusColor.copy(alpha = a),
                            mid to statusColor.copy(alpha = a * 0.70f),
                            bandEnd to statusColor.copy(alpha = a * 0.32f),
                        )
                    )
                }
                // Glass, and the same glass at rest as in flight. The plane carries the
                // capsule's own 6% crown→foot ramp across the chrome band — enough that the
                // silhouette stops being a measurable line where it meets the page, far short
                // of the 0.52/0.19 fade it used to carry, which took the bar to a fifth of its
                // crown alpha by the band's end and is why the bottom of the masthead had no
                // material on it at all.
                if (fill.presence > 0.01f) {
                    // The chrome's plane, on both paths. It used to be painted *only* off
                    // the blur path, on the theory that a blurring bar already carries its
                    // plane inside the Haze pass — but what the pass carries is an opaque
                    // base faded by the layer's own alpha, which is not a plane, and with
                    // the alpha gone (see the glass effect above) there was nothing left on
                    // the bar at all. The capsule has always worked the other way round and
                    // is the end that reads as a surface: `folioVeil` draws its blur layer
                    // at full strength and then paints the chrome plane over it at the
                    // preference's alpha. Same construction here, same plane, same alpha —
                    // one material, two positions.
                    //
                    // This is not the "raise the external fill" move Glass.kt's material
                    // model forbids: the fill stays at the chrome knob's tiered alpha
                    // (0.50 under blur), and the extra body comes from the chrome weight's
                    // `bodyTintAlpha` *inside* the blur. The plane is the surface; the body
                    // tint is the frost.
                    drawRect(
                        Brush.verticalGradient(
                            0f to veil.copy(alpha = fill.crown),
                            bandEnd * 0.6f to veil.copy(alpha = fill.waist),
                            bandEnd to veil.copy(alpha = fill.foot),
                        )
                    )
                    // The mirror finish: a thin specular band along the crown that fades
                    // by ~38% of the bar's height, so the collapsed masthead reads as
                    // polished glass reflecting the light above rather than a flat tint.
                    // Tied to the bar's presence exactly like the veil — it appears only
                    // once the bar is a surface at all, which is the only time it needs
                    // to read as one. §16: the band runs along the daylight axis like
                    // every other material's sheen; at neutral daylight (and on
                    // platforms without the glass upgrade) it is the fixed top-light
                    // band this bar has always had.
                    if (glassCaps.specular) {
                        val (sheenStart, sheenEnd) = daylightGradient(size, daylight, ambient.value)
                        drawRect(
                            Brush.linearGradient(
                                0f to Color.Transparent,
                                mastheadSheenStop(f) to Color.Transparent,
                                1f to sheen.copy(alpha = sheen.alpha * 0.30f * fill.presence * sheenGain),
                                start = sheenStart,
                                end = sheenEnd,
                            )
                        )
                    } else {
                        drawRect(
                            Brush.verticalGradient(
                                0f to sheen.copy(alpha = sheen.alpha * 0.30f * fill.presence * sheenGain),
                                0.38f to Color.Transparent,
                            )
                        )
                    }
                    // The bar's lower boundary — the glass fade thickening down into the
                    // chrome rim — painted inside the masthead's own footprint. As Column
                    // children they grew the bar 11dp past folioBarTopInset and overhung
                    // what was pinned there; and both bands are clamped to the fade's own
                    // 10dp so neither reaches up into the row's content (a 40dp button
                    // centred in the 56dp row dips to 48dp), where only transparent
                    // padding is below.
                    val fadePx = FolioTokens.barGlassFade.toPx()
                    drawRect(
                        Brush.verticalGradient(
                            0f to veil.copy(alpha = fill.crown * 0.45f),
                            1f to Color.Transparent,
                        ),
                        topLeft = Offset(0f, size.height - fadePx),
                        size = Size(size.width, fadePx),
                    )
                    // The chrome's edge, and now unconditional and §18-shaped.
                    //
                    // Unconditional: the capsule carries a rim all the way round its pill and
                    // that edge is half of why it reads as a defined object, while the masthead
                    // has exactly one edge the field is on — its foot — and drew nothing there,
                    // because `bottomRule` defaulted off and not one of the twenty-three call
                    // sites ever passed `true`. Seven of them passed `false` explicitly, which is
                    // how a per-call-site material override survives as dead ceremony.
                    //
                    // §18-shaped: the strength comes from `FolioEdgeRole.Chrome.profile`, the same
                    // rung the capsule's rim now resolves from (FolioSurfaceEdge), instead of a
                    // local 0.55/0.10 pair that would have made one material's two ends disagree
                    // about their own edge again. A straight edge cannot take concentric inset
                    // rings, so the role's `feather` becomes the band's depth and its `rimScale`
                    // its peak, run as one soft ramp *inside* the foot with **zero ink on the
                    // silhouette itself** — the ladder's rule, and the thing that keeps this from
                    // reading as a divider ruled across the screen.
                    val edgeProfile = FolioEdgeRole.Chrome.profile
                    val rimPx = edgeProfile.feather.toPx().coerceAtMost(fadePx)
                    val rimPeak =
                        (chrome.hairline.alpha * edgeProfile.rimScale * fill.presence)
                            .coerceIn(0f, 1f)
                    if (!FOLIO_EDGE_FEATHER) {
                        // §18's one-line revert must revert this edge too, or flipping the flag
                        // leaves the masthead on the new ramp while every other surface is back
                        // on its step. The pre-§18 rule, kept only for that path.
                        drawRect(
                            Brush.horizontalGradient(
                                listOf(
                                    chrome.hairline.copy(alpha = 0.55f * fill.presence),
                                    chrome.hairline.copy(alpha = 0.10f * fill.presence),
                                )
                            ),
                            topLeft = Offset(0f, size.height - 1.dp.toPx()),
                            size = Size(size.width, 1.dp.toPx()),
                        )
                    } else if (rimPx >= 1f && rimPeak > 0.001f) {
                        drawRect(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                0.6f to chrome.hairline.copy(alpha = rimPeak),
                                1f to Color.Transparent,
                            ),
                            topLeft = Offset(0f, size.height - rimPx),
                            size = Size(size.width, rimPx),
                        )
                    }
                }
            }
    ) {
        Box(modifier = Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(FolioTokens.barHeight)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (navigationIcon != null) {
                navigationIcon()
            } else {
                FolioMark(
                    modifier = Modifier.graphicsLayer {
                        val scale = lerp(1f, 0.82f, f)
                        scaleX = scale
                        scaleY = scale
                    }
                )
            }
            Box(
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (titleContent != null) {
                    titleContent()
                } else {
                    FolioBarTitle(
                        title = title,
                        preferred = titleStyle ?: FolioTheme.typography.headlineSmall,
                        collapse = f,
                        color = colors.onSurface,
                    )
                }
            }
            actions()
        }
        if (rail != null) {
            // Folds up *under* the bar: the height shrinks while the content stays
            // anchored to the fold's bottom edge, so the chips slide behind the
            // masthead instead of squashing.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clipToBounds()
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        val height = (placeable.height * (1f - f)).roundToInt()
                        layout(placeable.width, height) {
                            placeable.place(0, height - placeable.height)
                        }
                    }
                    .graphicsLayer { alpha = (1f - f * 1.5f).coerceIn(0f, 1f) }
                    .padding(bottom = 6.dp),
            ) {
                rail()
            }
        }
    }
}

/**
 * The screen's name, sized to the slot the actions left behind.
 *
 * A masthead that renders "Lib…" has failed at the one job it has. The type steps
 * down the display ladder until the whole name fits, so a crowded bar costs a few
 * points of size instead of the screen's identity; ellipsis remains only as the
 * last resort for a genuinely long title.
 */
@Composable
private fun FolioBarTitle(
    title: String,
    preferred: TextStyle,
    collapse: Float,
    color: Color,
) {
    val typography = FolioTheme.typography
    BoxWithConstraints {
        // Keyed on the font theme: the typeface preset changes families without
        // changing the title, and a measurer/cache artifact left the masthead
        // painting the previous face until something else recomposed it (tab
        // switches, relaunch). A key boundary forces a clean re-measure.
        key(typography.fontTheme) {
            val measurer = rememberTextMeasurer()
            val slot = constraints.maxWidth
            // Build the ladder and run the fit measurement only when an input that affects it
            // changes (title, available width, preferred style, font theme) — not on every
            // recomposition of the always-present masthead. The collapse scroll is applied via
            // graphicsLayer below, so it never re-runs this.
            val style = remember(title, slot, preferred, typography.fontTheme) {
                if (slot <= 0) {
                    preferred
                } else {
                    // The ladder shrinks the DISPLAY face rather than stepping down to the
                    // text face: dropping families made the masthead stop matching the app
                    // theme the moment a wide preset (Soft/Comfortaa) stopped fitting. The
                    // text-face tier stays only as the last resort.
                    val shrink1 = preferred.fontSize * 0.82f
                    val shrink2 = preferred.fontSize * 0.68f
                    val ladder = listOf(
                        preferred,
                        preferred.copy(
                            fontSize = shrink1,
                            fontFamily = UiFonts.display(typography.fontTheme, weight = 600, opticalSize = shrink1.value),
                        ),
                        preferred.copy(
                            fontSize = shrink2,
                            fontFamily = UiFonts.display(typography.fontTheme, weight = 600, opticalSize = shrink2.value),
                        ),
                        typography.titleMedium,
                    )
                    ladder.firstOrNull { candidate ->
                        measurer.measure(text = title, style = candidate, maxLines = 1).size.width <= slot
                    } ?: ladder.last()
                }
            }
            Text(
                text = title,
                style = style,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // Scaled, not re-styled, for the collapse: a font-size change here
                // relayouts the whole bar on every scroll frame.
                modifier = Modifier.graphicsLayer {
                    val scale = lerp(1f, 0.84f, collapse)
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0f, 0.5f)
                },
            )
        }
    }
}

/**
 * The Folio mark: the app's own logo — the open book whose right page grows into a
 * leaf — drawn as vector geometry by [FolioLogoMark]. No plate behind it: the
 * artwork carries its own blue→green gradient, so a container would only add a
 * second shape.
 */
@Composable
private fun FolioMark(modifier: Modifier = Modifier) {
    FolioLogoMark(modifier = modifier.size(28.dp))
}

/**
 * A segmented switch: one track, one lit segment — and the segment *travels*.
 *
 * This replaces a pair of loose [FolioChip]s wherever the choice is *exclusive*.
 * Two chips side by side say "two independent filters"; one track with a lit
 * segment says "one of these" — which is what Books/Manga actually is — and it
 * costs a third of the width, so it fits on the same rail as the filters instead
 * of demanding a row of its own.
 *
 * §17 liquid selection: the lit segment is a single indicator that glides from
 * the old slot to the new one, stretching and squeezing through the gap, instead
 * of two fills cross-fading in place. The eye follows the motion, so selection
 * is legible by *where the light went* — the same argument that made the nav
 * capsule's pill breathe. Selection is never carried by weight: a heavier label
 * would re-measure the track and nudge everything beside it.
 *
 * The indicator is drawn behind the labels in one `drawWithCache`, and the
 * travel is animated by `animateDpAsState` read in the draw phase — the
 * geometry recomputes per frame but nothing recomposes and no layout pass runs
 * for the glide. Under reduce-motion the indicator snaps between slots.
 */
private val SEGMENT_H_PAD = 14.dp
private val SEGMENT_V_PAD = 7.dp
private val SEGMENT_GAP = 2.dp

/**
 * The lit segment's slot geometry, in the caller's units: (left, width) of
 * segment [index] given every segment's [widths] and the [gap] between them.
 * Pure so the travel is pinned by test rather than by eye — the indicator must
 * land exactly on the slot the labels laid out, or the two disagree by a pixel
 * and the control reads broken.
 */
internal fun segmentedSlotBounds(
    widths: List<Float>,
    index: Int,
    gap: Float,
): Pair<Float, Float> {
    val i = index.coerceIn(0, widths.lastIndex)
    val left = widths.take(i).sum() + gap * i
    return left to widths[i]
}

@Composable
fun FolioSegmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (options.isEmpty()) return
    val colors = FolioTheme.colors
    val atmos = FolioTheme.atmosphere
    val motion = rememberMotionEnabled()
    val style = FolioTheme.typography.labelMedium
    // Measured with the same measurer and style that render the labels, so the
    // slot widths are the pills' actual widths — a hand-rolled width estimate
    // is what would make the indicator land half a millimetre off its label.
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val slotWidths: List<Dp> = remember(options, style, density) {
        with(density) {
            options.map { label ->
                measurer.measure(text = label, style = style, maxLines = 1).size.width.toDp() +
                    SEGMENT_H_PAD * 2
            }
        }
    }
    val clamped = selectedIndex.coerceIn(0, options.lastIndex)
    // The lit segment wears *its* option's destination colour, not the theme's one
    // signature; labels the table does not know fall back to `primary`, so every other
    // segmented control is unchanged. The ink takes the capsule's contrast guard.
    val accent = folioDestinationAccent(options[clamped])
    val accentInk = rememberLegibleAccent(accent)
    val targetLeft = remember(slotWidths, clamped) {
        val (left, _) = segmentedSlotBounds(slotWidths.map { it.value }, clamped, SEGMENT_GAP.value)
        left.dp
    }
    val targetWidth = slotWidths[clamped]
    // Liquid, not sloppy: a touch of overshoot on the glide (damping ≈ 0.78),
    // matching the press springs' own calibration.
    // Liquid, not sloppy: the table's own glide spring (damping 0.78), which resolves to an
    // instant under reduce-motion — so the gate this used to spell out is no longer needed here.
    val glideSpec: AnimationSpec<Dp> = rememberMotionAnimation(MotionKey.SegmentedIndicator)
    val indicatorLeft by animateDpAsState(targetLeft, glideSpec, label = "segmentLeft")
    val indicatorWidth by animateDpAsState(targetWidth, glideSpec, label = "segmentWidth")
    Box(
        modifier = modifier
            .clip(FolioShapes.pill)
            .background(atmos.sunkenFill.copy(alpha = 0.5f), FolioShapes.pill)
            // §18 — the last hard outline in the chrome. A 1dp `border` is a step function
            // on the silhouette, which is exactly the "you can make out boxes" edge the
            // ladder replaces; the track is a small control, so it takes the `Tile` rung's
            // short ramp rather than chrome's long one. See FolioSurfaceEdge.
            .folioSurfaceRim(FolioShapes.pill, atmos.hairline, FolioEdgeRole.Tile.profile)
            .padding(3.dp)
            .drawWithCache {
                // Behind the labels (drawBehind runs before the content), inside
                // the track's own padding, so the coordinate space *is* the row's.
                val radius = CornerRadius(size.height / 2f)
                onDrawBehind {
                    drawRoundRect(
                        color = accent.copy(alpha = 0.20f),
                        topLeft = Offset(indicatorLeft.toPx(), 0f),
                        size = Size(indicatorWidth.toPx(), size.height),
                        cornerRadius = radius,
                    )
                }
            },
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(SEGMENT_GAP),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            options.forEachIndexed { index, label ->
                val selected = index == clamped
                // Ink still cross-fades per segment: the label tells you where
                // the light landed, the indicator shows it travelling.
                val ink by animateColorAsState(
                    targetValue = if (selected) accentInk else colors.onSurfaceVariant,
                    label = "segmentInk",
                )
                Text(
                    text = label,
                    style = style,
                    color = ink,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(FolioShapes.pill)
                        .clickable(enabled = !selected) { onSelect(index) }
                        .padding(horizontal = SEGMENT_H_PAD, vertical = SEGMENT_V_PAD),
                )
            }
        }
    }
}

/**
 * Where the masthead's specular band begins, along the daylight axis, for a bar
 * collapsed by [collapse] 0..1.
 *
 * §13.9's rule for anything the finger drives: 1:1, no spring, no settle. The bar has
 * always moved its fill, its title and its rail off this one number, but its light was
 * a constant — so a collapsing masthead read as a tint getting more opaque rather than
 * as an object turning under the lamp. Pulling the highlight's start back as the bar
 * collapses means the light travels while the finger travels and stops the instant the
 * finger stops, which costs no animation, no ticks and no frames.
 *
 * At rest this returns exactly the stop the bar has always used, and the term stays
 * purely additive over the veil: `barGlass` alpha is untouched, so the §15 glass
 * window pinned by `DesignSystemTest.appBarsAreGlassNotLids` still holds.
 */
internal fun mastheadSheenStop(collapse: Float): Float =
    0.62f - 0.44f * collapse.coerceIn(0f, 1f)

/**
 * §17 liquid selection, geometry half: where the capsule's specular sweep band
 * sits for [progress] 0..1 across a capsule [width] px wide with a [band] px
 * band, travelling toward [direction] (+1 left→right, -1 the reverse). At 0 the
 * band spans entirely off the leading edge and at 1 entirely past the trailing
 * edge — the same off-edge contract as [shimmerSweep], tightened by one band so
 * no travel is spent invisible. Pure so it is pinned by test. Public because
 * the capsule itself lives in the app module, which cannot see this module's
 * internal members.
 */
fun navSweepBand(progress: Float, width: Float, band: Float, direction: Float): Float {
    val x = -band + progress.coerceIn(0f, 1f) * (width + band)
    // Mirror the forward position about the capsule: -band ↔ width.
    return if (direction >= 0f) x else width - band - x
}

/**
 * The sweep's alpha envelope: 0 at rest at both ends, one soft peak mid-travel.
 * Sine rather than a triangle so the band eases in and out instead of switching
 * on. Pure so it is pinned by test; public for [navSweepBand]'s reason.
 */
fun navSweepAlpha(progress: Float): Float =
    sin(PI * progress.coerceIn(0f, 1f).toDouble()).toFloat().coerceIn(0f, 1f)
