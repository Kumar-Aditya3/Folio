package com.folio.reader.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.Stable
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.unit.Dp
import com.folio.reader.ui.theme.FolioTokens
import dev.chrisbanes.haze.HazeArea
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
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
    /**
     * Device-class multiplier on every surface's blur-resolution budget — the one
     * *degradation* lever that is not a switch.
     *
     * The ladder above only ever said yes or no to blur, and the only "no" it had was
     * `ActivityManager.isLowRamDevice`, which is a 512MB-class flag that essentially no
     * device the app actually runs on reports. So in practice there was **no**
     * device-class degradation at all: a 2GB phone and a flagship both paid the same
     * full-resolution capture-and-blur per surface per frame, which is the whole of the
     * "extremely laggy when liquid glass is on" report on the slow half of the fleet.
     *
     * A multiplier rather than a second boolean, because the honest trade here is
     * resolution and not presence: Haze's cost falls with the *square* of the input
     * scale (it records the sampled backdrop into a scratch layer sized
     * `bounds × scale`), so 0.5 here is a further quarter of the fill cost on top of
     * whatever class budget the surface already asked for — and the blur radius is
     * scaled with it, so the *visual* radius is unchanged. The surface keeps its frost,
     * its tint, its body and its edge; it simply resolves them coarser. Turning glass
     * off outright on a mid-range device would have been the cheaper code and the worse
     * product.
     *
     * 1f (the default, and every un-provided tree) is byte-for-byte the per-class
     * budget. Applied where the capability is already read — [folioVeil], [folioRaised]
     * and the masthead — because [folioGlassEffect] is a plain `Modifier` extension and
     * cannot read a composition local.
     */
    val budget: Float = 1f,
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
 *
 * [memoryClassMb] is `ActivityManager.memoryClass` — the per-app heap ceiling the
 * platform assigns from the device's own class — and it is what finally makes the
 * degradation ladder have a middle. `lowRamDevice` is a 512MB-class flag; it is the
 * only device input the resolver had, and it is false on every device the lag was
 * reported from, so the ladder was effectively two rows (pref on / pref off) with no
 * device term at all. The memory class is the same verdict at usable granularity, and
 * it is spent on [GlassCapabilities.budget] rather than on `blur`: a constrained device
 * keeps the material and resolves it coarser. 0 means "not supplied" (desktop, tests,
 * previews) and never degrades, so every existing caller is unchanged.
 */
fun glassCapabilitiesFor(
    platformBlurSupported: Boolean,
    lowRamDevice: Boolean,
    liquidGlassEffects: Boolean,
    memoryClassMb: Int = 0,
): GlassCapabilities = GlassCapabilities(
    blur = platformBlurSupported && !lowRamDevice && liquidGlassEffects,
    noise = liquidGlassEffects,
    specular = liquidGlassEffects,
    // Only meaningful when something blurs, and pinned to 1f otherwise so the pref-off row
    // of the ladder stays exactly [GlassCapabilities.None] on every device — the invariant
    // GlassCapabilityTest asserts by equality, which a stray budget would break without
    // changing a single pixel.
    budget = when {
        !(platformBlurSupported && !lowRamDevice && liquidGlassEffects) -> 1f
        memoryClassMb in 1 until FOLIO_GLASS_MEMORY_CLASS_FLOOR -> FolioGlassCost.constrainedBudget
        else -> 1f
    },
)

/**
 * The `ActivityManager.memoryClass`, in MB, at or above which a device is treated as
 * able to carry the glass at its designed resolution.
 *
 * 192MB is the platform's own step for the 3GB+ class; 2GB-class devices are handed
 * 128MB or less, and those are the devices where a full-resolution capture-and-blur per
 * surface per frame does not fit in a 16ms budget. Deliberately a *floor on the fast
 * path* rather than a ceiling on the slow one: a device the platform has not classified
 * (0) is given the benefit of the doubt.
 */
const val FOLIO_GLASS_MEMORY_CLASS_FLOOR = 192

/**
 * §16 — **the blur cost model**: what fraction of its own resolution a class of surface
 * is allowed to blur at.
 *
 * This is the lever the material was missing, and it is worth being blunt about the
 * arithmetic because the old default looks harmless and is not. Haze records the sampled
 * backdrop into a scratch `GraphicsLayer` sized `bounds × scale` and runs the blur over
 * that, then upscales ([dev.chrisbanes.haze.HazeEffectNode] `drawEffectWithGraphicsLayer`),
 * and it caches **nothing** across frames — a fresh layer is allocated, recorded, blurred
 * and released every draw. Cost therefore falls with the *square* of the scale, and the
 * hard-coded `HazeInputScale.None` the app shipped with meant every glass surface in the
 * app paid full price:
 *
 * | scale | relative fill cost |
 * |---|---|
 * | 1.0 (`None`) | 1 |
 * | 0.5 | 1/4 |
 * | 0.3334 (Haze's own `Auto`) | 1/9 |
 *
 * **Why this is not a look lever.** Haze scales the blur radius by the same factor when
 * it builds the render effect (`resolveBlurRadius() * inputScale`), so a 22dp blur at
 * 0.3334 is a 7.3dp blur over a third-resolution capture — the *same* visual radius,
 * reconstructed from fewer samples. The comment that pinned the app to full resolution
 * cited Auto's "pixel blocks", and that is a real artefact, but it is a function of the
 * blur radius being *small* relative to the upscale: Haze's own `Auto` turns scaling off
 * entirely below 7dp for exactly that reason. At this app's 22dp radius
 * ([FolioTokens.blurRadius], which is also [FolioTokens.blurRadiusMax], so every surface
 * blurs at it) the upscale is far inside the blur kernel and there is nothing left to
 * block.
 *
 * Per class, not per call site: a call site that names a magic number is a call site that
 * drifts from the budget the next one picks.
 */
object FolioGlassCost {
    /**
     * Full-bleed bars — the masthead, the reader's chrome bands.
     *
     * The largest blurred areas in the app by a wide margin, and the ones that re-blur on
     * every frame the room animates. 0.5 is also the scale Haze itself selects for any
     * progressive gradient, and the masthead is progressive, so on that surface this is
     * not even a trade: it is the value the library would have chosen.
     */
    const val bar: Float = 0.5f

    /**
     * Panes: cards, heroes, sheets, panels — anything whose job is to be a frosted slab
     * with content on it.
     *
     * Haze's own `Auto` figure. These surfaces are large, their backdrop is the animated
     * ground plane (low-frequency by construction — a ramp, a lamp bloom, drifting silk),
     * and the thing the reader is looking at is the *content* on the glass, not the room
     * through it. A ninth of the fill cost is the single biggest frame-time win available
     * to this material.
     */
    const val pane: Float = 0.3334f

    /**
     * Small, high-attention chrome: the nav capsule, menus, the status banner, chips.
     *
     * Finer than [pane] on purpose — these sit under the reader's eye at the moment they
     * are being used, and they are small enough that their absolute cost is low even at a
     * generous scale, so there is nothing to buy by starving them. Still not full
     * resolution: the capsule is on screen on every tab, on every frame, for the life of
     * the session.
     */
    const val control: Float = 0.5f

    /**
     * What [GlassCapabilities.budget] drops to on a device the platform classes below
     * [FOLIO_GLASS_MEMORY_CLASS_FLOOR]: a further quarter of the fill cost on top of the
     * surface's own class budget.
     */
    const val constrainedBudget: Float = 0.5f

    /**
     * The smallest scale the model will ask Haze for.
     *
     * Haze's `HazeInputScale.Fixed` *throws* outside `0 < x <= 1`, so a budget
     * multiplication has to land somewhere safe rather than somewhere plausible. 0.2 is
     * also the point past which the upscale starts to show even under a 22dp kernel.
     */
    const val floor: Float = 0.2f

    /**
     * A surface's class budget, spent against the device's own
     * [GlassCapabilities.budget] and clamped into Haze's legal range.
     *
     * One function so no call site multiplies and clamps for itself — that is how the
     * floor gets forgotten on the one surface that reaches it.
     */
    fun resolve(surface: Float, caps: GlassCapabilities): Float =
        (surface * caps.budget).coerceIn(floor, 1f)
}

/**
 * Per-surface blur tuning. The radius is capped at [FolioTokens.blurRadiusMax]
 * at the effect site, so no caller can buy a 60dp mush.
 *
 * [blurEnabled] exists for surfaces that are *on screen while the backdrop they
 * would sample is not yet describing them*. The floating nav capsule is the only
 * such surface: on a destination change the incoming screen's `hazeSource`
 * registers a frame after the outgoing one's backdrop layer is dropped, so for
 * the first frames the capsule would blur the **previous** screen — the page
 * visibly lagging one frame behind the chrome. Passing `false` for that window
 * keeps the veil on its non-blur path (fill, sheen, grain, bevel — the whole
 * material minus the effect), which is a complete surface rather than a blank
 * one. It is not a capability toggle: [LocalGlassCapabilities] still decides
 * whether glass exists at all.
 */
@Immutable
data class GlassSpec(
    val blurRadius: Dp = FolioTokens.blurRadius,
    val blurEnabled: Boolean = true,
    /**
     * This surface's class budget from [FolioGlassCost] — how much of its own
     * resolution the blur runs at before the device's [GlassCapabilities.budget] is
     * applied on top.
     *
     * Lives here rather than at the effect site because the *caller* is the only thing
     * that knows which class it is: [folioVeil] paints the nav capsule, a reader sheet,
     * a search strip and a modal from one body of code, and those are not the same
     * surface class. [FolioGlassCost.pane] is the default because the overwhelming
     * majority of veils are panes; the handful that are small chrome say so.
     */
    val inputScale: Float = FolioGlassCost.pane,
) {
    companion object {
        val Default = GlassSpec()

        /**
         * The small, high-attention chrome spec — the nav capsule, menus, the status
         * banner. [Default] with the finer budget, so a call site names the class rather
         * than a number.
         */
        val Control = GlassSpec(inputScale = FolioGlassCost.control)
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

/**
 * §20 reach: whether in-flow surfaces (section cards, stats blocks, shelf items, panels)
 * upgrade to the full overlay glass treatment — blur, lens, sheen, bevel — or stay on the
 * lighter painted-glass tier.
 *
 * This is the "minimal liquid glass" opt-out. `true` (the default) lets the glass reach
 * every panel so an in-flow pane frosts the room like a real slab; `false` keeps the effect
 * on true overlays only (bars, the capsule, sheets, modals, heroes floating over content)
 * so glass still shows but stays off the many in-flow panels and chips. It is only ever a
 * *decline*: a `false` here never adds glass and never matters on a device that cannot blur,
 * it only chooses not to upgrade an in-flow surface that would otherwise go full glass.
 */
val LocalGlassInFlowGlass = compositionLocalOf { true }

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
    inFlowGlass: Boolean = true,
    content: @Composable () -> Unit,
) {
    androidx.compose.runtime.CompositionLocalProvider(
        LocalGlassCapabilities provides capabilities,
        LocalGlassBackdrop provides rememberGlassBackdrop(),
        LocalGlassInFlowGlass provides inFlowGlass,
    ) {
        content()
    }
}

/**
 * The two rungs of the backdrop ladder, as Haze z-indices on **one** registry.
 *
 * This is the fix for the defect that made every in-flow glass surface a flat tint, and
 * it is worth stating plainly because the symptom pointed nowhere near the cause.
 *
 * `Modifier.hazeSource` publishes its own z-index to its subtree as a modifier local, and
 * `hazeEffect` resolves its backdrop to `areas.filter { it.zIndex < thatLocal }`. A glass
 * surface drawn *inside* the source it would sample therefore resolves to **zero areas**,
 * and a Haze effect with zero areas is not a weak blur — it is skipped entirely
 * (`HazeEffectNode.isValid`). Every card on Home is inside the `LazyColumn` that carries
 * [folioBackdropSource], so every card's blur was inert: what remained was the translucent
 * fill, the hairline and the painted sheen. A tint. Exactly what it looked like.
 *
 * Haze's own mechanism already has the answer, so no new registry is needed — only a second
 * rung *below* the content:
 *
 * | Surface | Local z-index | Sees | Result |
 * |---|---|---|---|
 * | Card inside the page | [FOLIO_BACKDROP_Z_CONTENT] | the room only | frosts the field it stands on |
 * | Masthead / nav capsule (outside both) | none | room **and** page | frosts the real room *and* the page |
 *
 * The bars gain from it too: before, a bar over a transparent `LazyColumn` blurred mostly
 * nothing and fell back to a flat `backgroundColor`, which is the second place the tint
 * came from. Now the room is genuinely under them.
 *
 * Recursion stays impossible by construction rather than by a guard: nothing glassy is
 * ever placed inside [folioRoomSource]'s subtree — it wraps the ground plane only.
 *
 * **The values are -1 and 0 rather than 0 and 1, and that is load-bearing.** `HazeSourceNode`
 * does not publish its own `zIndex` to its subtree: it publishes a *compound*
 * (`upstream + zIndex`) and stores that same compound on the area. A screen that registers a
 * source on a wrapper **and** again on its own scrolling child therefore nests two sources —
 * which is the common case here, not an edge case (Home, Stats, the settings hubs and the
 * quotes/revisit screens all do it). With a non-zero content rung the inner area compounds to
 * 2 while the outer stays 1, so an effect inside has a local of 2, `area.zIndex < 2` keeps the
 * *outer* source — the one that contains it — and Haze aborts the draw:
 *
 * ```
 * java.lang.IllegalArgumentException: Modifier.haze nodes can not draw Modifier.hazeChild nodes.
 * ```
 *
 * That `require(!area.contentDrawing)` fires because the effect is being drawn from inside the
 * very source it is sampling. With the content rung at **0**, every nested content source
 * compounds to 0 however deep it sits (0 + 0 each time), so the effect's local is 0,
 * `area.zIndex < 0` keeps only the room, and the cycle cannot form. The room rung is -1 purely
 * to stay strictly below the content rung.
 */
private const val FOLIO_BACKDROP_Z_ROOM = -1f
private const val FOLIO_BACKDROP_Z_CONTENT = 0f

/** Identifies the room rung in [HazeState.areas] without exposing a Haze type. */
private const val FOLIO_BACKDROP_KEY_ROOM = "folio.room"

/**
 * Marks the app's **ground plane** — the theme field, its animated shader and the lamp —
 * as the lowest backdrop rung. Attach it once, at the root, around the background layers
 * and *nothing else*: it must not contain the page or any glass surface.
 *
 * This is what in-flow glass refracts and frosts. It is also the layer that makes the
 * refraction worth drawing at all: the field carries an animated shader, a lamp bloom and
 * three drifting silk blooms, which is real high-frequency structure to bend. The
 * closed-form reproduction the old gel bent instead had none, and its own source notes
 * record the consequence — "the band only ever reads as a painted rectangle".
 */
@Composable
fun Modifier.folioRoomSource(): Modifier {
    val backdrop = LocalGlassBackdrop.current ?: return this
    if (!LocalGlassCapabilities.current.blur) return this
    return hazeSource(backdrop, zIndex = FOLIO_BACKDROP_Z_ROOM, key = FOLIO_BACKDROP_KEY_ROOM)
}

/**
 * Holds a destination's content out of the glass backdrop while that destination is
 * *leaving*, without the caller naming a Haze type or owning a shared-element scope.
 *
 * The app shares one backdrop registry across every destination (see [FolioGlassRoot]), and
 * Haze's effect node composites **every** registered area that overlaps the effect — it loops
 * the areas rather than picking one. So for the length of a push both the outgoing and the
 * incoming page are registered, and the masthead blurs the two of them stacked over its
 * opaque base. That is the "masthead jumps to opaque, then settles to its real transparency
 * a moment later" report: the flash is two pages in one blur layer, and it clears when the
 * outgoing entry is finally disposed.
 *
 * [folioBackdropSource] already drops a leaving screen — but only when a shared-element
 * scope happens to be in composition, which is true for Home/Library/Stats and the detail
 * and reader routes and false for More, every `settings/{category}`, Tags, Quotes and
 * Revisit. Those are exactly the screens the flash was reported on. Wrapping a destination
 * in this gives the same protection without pulling the shared-element machinery (and its
 * cover morphs) onto a route that does not want it.
 */
@Composable
fun FolioBackdropScope(
    animatedVisibilityScope: AnimatedVisibilityScope,
    content: @Composable () -> Unit,
) {
    val exiting = animatedVisibilityScope.transition.targetState == EnterExitState.PostExit
    androidx.compose.runtime.CompositionLocalProvider(
        LocalBackdropExiting provides exiting,
        content = content,
    )
}

/** True while the destination around this point in the tree is leaving. */
internal val LocalBackdropExiting = compositionLocalOf { false }

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
    // While a screen is *exiting* — the outgoing page of a tab morph or a push — stop feeding its
    // content to the shared blur backdrop. The floating masthead and the nav capsule both blur that
    // backdrop, and the effect composites every overlapping area, so an exiting Home's warm hero
    // colours were being blurred in under the incoming Library's Books/Manga/Documents bar until the
    // morph settled (the "out-of-place colours in the masthead"), and a push into a settings
    // category stacked two whole pages into the bar's pane (the "masthead flashes opaque on open").
    // The incoming and at-rest screens still register, so the glass always has the *current* page to
    // refract; only the leaving screen drops out, and its own alpha-0 exit hides it everywhere else.
    //
    // Two sources for "am I leaving", because neither covers the app alone: the shared-element scope
    // is present only on morph routes, and [LocalBackdropExiting] is published by
    // [FolioBackdropScope] for the routes that carry no morph.
    if (LocalBackdropExiting.current) return this
    val avScope = LocalSharedElementScopes.current?.animatedVisibilityScope
    if (avScope != null && avScope.transition.targetState == EnterExitState.PostExit) return this
    // Above the room, so a glass surface *inside* this page still resolves the room beneath
    // it instead of resolving nothing at all — see FOLIO_BACKDROP_Z_ROOM.
    return hazeSource(backdrop, zIndex = FOLIO_BACKDROP_Z_CONTENT)
}

/**
 * A captured backdrop rung, addressable from the draw phase.
 *
 * This is the piece the previous attempt at refraction concluded was impossible. Its note
 * in `GlassShader.android.kt` reads: *"A render effect can only ever see the subtree it is
 * applied to … refraction of what is behind a surface has to reproduce that thing."* The
 * first half is true and the second does not follow. A render effect sees the subtree it is
 * applied to — so you do not apply it to the glass surface. You apply it to a layer into
 * which the *already captured backdrop* has been replayed, and `uniform shader content`
 * then hands the shader the real pixels of the room.
 *
 * Haze captures exactly that layer for its own blur and exposes it: [HazeArea.contentLayer]
 * and [HazeArea.positionOnScreen] are public reads (only their setters are internal). So the
 * refraction costs no second capture of the screen and no new dependency — it re-uses the
 * capture the blur is already paying for.
 *
 * Both reads are deliberately **unobserved** ([Snapshot.withoutReadObservation]): they are
 * written during layout and draw, and subscribing to them in composition would turn a scroll
 * into a recomposition. Callers read them inside `onDrawBehind`, where they belong.
 *
 * **The rung itself is resolved here too, and that is the second half of the same rule.**
 * This class used to be handed an already-resolved [HazeArea], which meant its factory
 * ([glassRoomPlate]) had to scan `HazeState.areas` *in composition* — and `areas` is a
 * `mutableStateListOf` whose getter returns `_areas.toList()`, so every glass surface in the
 * tree was subscribed to the registry's structure. Nothing mutates that list per frame, but
 * a `hazeSource` node attaching or detaching does: a push, a pop, a tab morph, a screen
 * dropping out of the backdrop while it exits ([folioBackdropSource]'s exiting branch), the
 * reader's PDF source flipping. Each of those recomposed *every* glass surface on screen —
 * a recomposition storm landing exactly on the frames of a transition, which is when the
 * budget is already spent. Holding the state and the key instead, and resolving under
 * [Snapshot.withoutReadObservation] in the draw phase, removes the subscription entirely.
 *
 * The resolution is cached across frames and re-taken only when the cached rung has stopped
 * describing anything, which is also how it self-heals: [dev.chrisbanes.haze.HazeSourceNode]
 * owns exactly one [HazeArea] for its node's lifetime and `reset()`s it on detach, so a dead
 * rung reports an unspecified position, fails [ready], and is replaced by the next scan.
 */
@Stable
class FolioGlassPlate internal constructor(
    private val state: HazeState,
    private val key: Any,
) {
    /** The rung, once found. Draw-phase state; never read in composition. */
    private var resolved: HazeArea? = null

    private fun area(): HazeArea? = Snapshot.withoutReadObservation {
        val cached = resolved
        if (cached != null && cached.positionOnScreen.isSpecified) return@withoutReadObservation cached
        state.areas.firstOrNull { it.key == key }?.also { resolved = it } ?: cached
    }

    /** The live capture, or null before the first record / after release. */
    val layer: GraphicsLayer?
        get() = Snapshot.withoutReadObservation {
            area()?.contentLayer?.takeUnless { it.isReleased }
        }

    /** Where the capture's own origin sits, in the same space as `positionOnScreen`. */
    val originOnScreen: Offset
        get() = Snapshot.withoutReadObservation {
            area()?.positionOnScreen?.takeIf { it.isSpecified } ?: Offset.Zero
        }

    /** Whether there is anything to bend this frame. */
    val ready: Boolean
        get() = Snapshot.withoutReadObservation {
            val a = area() ?: return@withoutReadObservation false
            a.positionOnScreen.isSpecified && a.contentLayer?.isReleased == false
        }
}

/**
 * The room rung as a draw-phase handle, or null when there is nothing to refract —
 * desktop, previews, pref-off, low-RAM, or before the ground plane's first draw.
 *
 * Always the room, never the page: the room is the one rung that is guaranteed not to
 * contain the caller, so a surface anywhere in the tree can bend it without the
 * self-reference that made the blur inert in the first place.
 *
 * Reads **no** Haze state: it hands the registry and the key to [FolioGlassPlate], which
 * resolves the rung in the draw phase. Calling this is therefore free of the
 * register/unregister subscription it used to carry — see [FolioGlassPlate].
 */
@Composable
fun glassRoomPlate(): FolioGlassPlate? {
    if (!LocalGlassCapabilities.current.blur) return null
    val backdrop = LocalGlassBackdrop.current ?: return null
    return remember(backdrop) { FolioGlassPlate(backdrop, FOLIO_BACKDROP_KEY_ROOM) }
}

/**
 * Holds the glass backdrop off its subtree while [suppress] is true, then restores it — without
 * ever surfacing the Haze type to the caller (the seam rule: only this file names `HazeState`).
 *
 * The one use is the tab↔tab swap: the app shares a single backdrop registry across the four tabs,
 * so during a swap it briefly captures BOTH the outgoing and incoming pages and the incoming
 * masthead's glass would blur the *previous* tab showing behind it. Wrapping the page content in
 * this with `suppress = true` for the morph window makes the mastheads fall back to their solid
 * glass fill (no backdrop to sample) instead of blurring the old tab; the nav capsule sits outside
 * this and keeps its backdrop.
 */
@Composable
fun FolioSuppressibleBackdrop(suppress: Boolean, content: @Composable () -> Unit) {
    val backdrop = if (suppress) null else LocalGlassBackdrop.current
    androidx.compose.runtime.CompositionLocalProvider(
        LocalGlassBackdrop provides backdrop,
        content = content,
    )
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
 *
 * [tint] is the **chromatic frost**: a colour the blurred backdrop is tinted
 * *inside* the Haze pass, before the caller's own fill lands on top. Empty
 * ([Color.Unspecified], the default) keeps the pre-weight look byte-for-byte —
 * `tints = emptyList()`, a neutral blur. A themed low-alpha tint is what gives
 * the glass weight and per-theme personality: the frost absorbs the room's own
 * hue instead of passing it through as a flat neutral pane.
 *
 * [bodyTint] is the **frosted body** — the single lever that lets the glass be
 * *opaque and glass at the same time*. It is a surface-coloured tint laid under
 * [tint], still inside the Haze pass, so it rides the blurred (spatially
 * varying) backdrop rather than sitting on it as a flat lid. This is exactly how
 * a real frosted material (iOS's `UIBlurEffect`, Windows Acrylic/Mica) reads as
 * a substantial pane you cannot see sharply through while light and colour still
 * bleed through it: the body comes from a *tint over the blur*, not from raising
 * the external fill — which only ever buries the blur and reads as a dead
 * square. Stacking the two tints inside one pass means the body and the hue both
 * composite over the frost, so the slab gains weight and personality together
 * while staying unmistakably glass. Empty keeps the single-tint look unchanged.
 *
 * Tints paint in list order (earlier under later), so [bodyTint] goes first and
 * the chromatic [tint] rides on top of it.
 *
 * [progressiveEndIntensity] is how much of the pane survives at the far end of
 * that gradient. It is a parameter rather than a constant because the masthead's
 * plane now lives inside this pass: a bar the reader has set to 100% must not
 * thin at its foot, or the page shows through the bottom edge of a surface that
 * was asked to be solid. Callers that do not blur progressively never read it.
 *
 * [progressiveEndY] is *where* that far end is, in layer pixels. It defaults to
 * the bottom of the layer, which is right for a surface whose whole height is the
 * surface — and wrong for the masthead, whose height depends on whether the screen
 * hangs a rail under its title. Decaying over the whole Column, Library's railed
 * bar reached the logo row at a different intensity than Home's or a settings
 * category's, which is the same preference painting a different bar per section.
 * Passing the chrome band's bottom pins the decay to the band; beyond it the
 * gradient holds [progressiveEndIntensity].
 */
fun Modifier.folioGlassEffect(
    backdrop: HazeState,
    backgroundColor: Color,
    blurRadius: Dp = FolioTokens.blurRadius,
    noiseFactor: Float = FolioTokens.glassNoise,
    alpha: Float = 1f,
    progressive: Boolean = false,
    progressiveEndIntensity: Float = 0.20f,
    progressiveEndY: Float = Float.POSITIVE_INFINITY,
    tint: Color = Color.Unspecified,
    bodyTint: Color = Color.Unspecified,
    /**
     * How much of the backdrop's resolution the blur runs at — a **cost** lever, not a look
     * lever, and the only one Haze exposes that is worth a factor of nine.
     *
     * Haze records the sampled backdrop into a scratch layer sized `bounds × scale` and runs
     * the blur over that, then upscales. Cost falls with the square, so `1f` is full price,
     * `0.5f` is a quarter, and Haze's own `Auto` picks `0.3334f` — a ninth — for a plain
     * surface. Kept as a `Float` rather than Haze's own type so the library stays behind this
     * one seam (the file's whole contract).
     *
     * **The default is [FolioGlassCost.pane], and it used to be `1f`.** The old value was
     * inherited from a hard-coded `HazeInputScale.None` whose comment cited Auto's "pixel
     * blocks", and that reasoning does not survive contact with this app's numbers: Haze
     * scales the blur radius by the same factor when it builds the render effect, and at the
     * app's 22dp radius ([FolioTokens.blurRadiusMax], which every surface is coerced to) the
     * upscale sits far inside the blur kernel. Full resolution was buying nothing visible and
     * costing nine times the fill on every glass surface, every frame — see [FolioGlassCost].
     *
     * Values are coerced into Haze's legal `0 < x <= 1` window ([FolioGlassCost.floor]
     * upward): `HazeInputScale.Fixed` *throws* outside it, and a device-budget multiplication
     * can otherwise land there.
     */
    inputScale: Float = FolioGlassCost.pane,
): Modifier = hazeEffect(
    state = backdrop,
    style = HazeStyle(
        backgroundColor = backgroundColor,
        tints = buildList {
            if (bodyTint.isSpecified) add(HazeTint(bodyTint))
            if (tint.isSpecified) add(HazeTint(tint))
        },
        blurRadius = blurRadius.coerceAtMost(FolioTokens.blurRadiusMax),
        noiseFactor = noiseFactor,
    ),
) {
    // The capabilities resolver already excluded platforms where blur is
    // unreliable; Haze's own default would additionally exclude API 31 and
    // substitute a scrim, which is not this design's fallback.
    blurEnabled = true
    this.alpha = alpha
    // Smoothness over thrift was the old policy on every surface, and it was the whole
    // cost of the material: `None` blurs the full area at full resolution, and Haze
    // re-records and re-blurs it on every frame the room animates. The budget now comes
    // from [FolioGlassCost] per surface class — Haze scales the blur radius by the same
    // factor, so the visual radius is unchanged and only the sample count falls.
    //
    // Coerced into Haze's legal window: `HazeInputScale.Fixed` throws outside
    // `0 < x <= 1`, which a device-budget multiplication can otherwise reach — and a
    // non-finite value would sail past a bare `>=` comparison straight into that throw,
    // so it is normalised before the comparison rather than inside the branch.
    //
    // `this.` is load-bearing: the parameter shares the scope property's name, and the bare
    // form would resolve to the parameter (a Float) rather than to the effect scope.
    val scale = if (inputScale.isFinite()) inputScale else 1f
    this.inputScale = if (scale >= 1f) {
        HazeInputScale.None
    } else {
        HazeInputScale.Fixed(scale.coerceIn(FolioGlassCost.floor, 1f))
    }
    if (progressive) {
        this.progressive = HazeProgressive.verticalGradient(
            startY = 0f,
            startIntensity = 1f,
            endY = if (progressiveEndY.isFinite() && progressiveEndY > 0f) {
                progressiveEndY
            } else {
                Float.POSITIVE_INFINITY
            },
            endIntensity = progressiveEndIntensity.coerceIn(0f, 1f),
            preferPerformance = true,
        )
    }
}
