@file:OptIn(dev.chrisbanes.haze.ExperimentalHazeApi::class)

package com.folio.reader.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.unit.Dp
import com.folio.reader.ui.theme.FolioTokens
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazePerformanceMode
import dev.chrisbanes.haze.HazeSourceRetention
import dev.chrisbanes.haze.HazeSourceSelection
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.glass.GlassStyle
import dev.chrisbanes.haze.glass.GlassReducedMotionPolicy
import dev.chrisbanes.haze.glass.hazeGlass
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.folio.reader.ui.theme.rememberMotionEnabled
import com.folio.reader.ui.theme.surfaceOpacity
import com.folio.reader.ui.theme.folioSheenGain
import com.folio.reader.ui.theme.panelKnobScale
import com.folio.reader.ui.theme.panelFill
import com.folio.reader.ui.theme.LocalFolioSurfaceDepth
import com.folio.reader.ui.theme.LocalFolioSurfaceFill
import dev.chrisbanes.haze.hazeSource

/**
 * Folio's only Haze import seam. Hosts provide capabilities and one source registry;
 * the material builders select diffusion or rounded optical glass without exposing
 * renderer APIs to screens. Unprovided preview trees retain the painted fallback.
 * Android keeps its API-32 blur floor and low-memory policy. Desktop opts in at its
 * app root; Haze owns its portable renderer and graceful optical fallback.
 */
@Immutable
data class GlassCapabilities(
    /** Backdrop blur behind the surface. Needs a [LocalGlassBackdrop] source too. */
    val blur: Boolean,
    /** Deterministic grain, to kill gradient banding on glass without blur. */
    val noise: Boolean,
    /** Daylight-directional specular and edge bevel. */
    val specular: Boolean,
    /** Device-class multiplier on Folio's surface quality budget. */
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
 * Surface budget tokens retained across the material system. Haze 2's quality
 * fractions are not pixel scales: [folioGlassQuality] maps these class/device
 * budgets to explicit profiles and reduces quality during scroll work.
 */
object FolioGlassCost {
    const val bar: Float = 0.5f
    const val pane: Float = 0.3334f
    const val control: Float = 0.5f
    const val constrainedBudget: Float = 0.5f
    const val floor: Float = 0.2f

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
    /** Surface class/device budget; mapped to Haze 2 quality at the rendering seam. */
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

/** Registered Compose content available to the material. Null uses the painted fallback. */
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

/** Keep the real room backdrop; scope only the shelf's interaction quality budget. */
@Composable
internal fun FolioGlassScene(moving: Boolean, content: @Composable () -> Unit) {
    val workload = remember { FolioGlassWorkload() }
    androidx.compose.runtime.SideEffect {
        if (moving) workload.scrolled() else workload.settled()
    }
    androidx.compose.runtime.CompositionLocalProvider(
        LocalGlassWorkload provides workload,
        content = content,
    )
}

/** A local artwork source for the reading dock, separate from the page it lives inside. */
@Composable
internal fun FolioArtworkGlassScene(moving: Boolean, content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(LocalGlassBackdrop provides rememberGlassBackdrop()) {
        FolioGlassScene(moving, content)
    }
}

/** One functional reading control over artwork; no glass on the cover plates themselves. */
@Composable
internal fun Modifier.folioReadingDockGlass(shape: RoundedCornerShape, accent: Color): Modifier {
    val caps = LocalGlassCapabilities.current
    val backdrop = LocalGlassBackdrop.current
    val colors = com.folio.reader.ui.theme.FolioTheme.colors
    val canBlur = caps.blur && LocalGlassInFlowGlass.current && backdrop != null
    val fill = com.folio.reader.ui.theme.FolioTheme.surfaceOpacity.panelKnobScale(if (canBlur) .38f else .94f)
    val sheen = com.folio.reader.ui.theme.folioSheenGain()
    val base = if (canBlur && fill < .999f) {
        clip(shape).folioGlassEffect(
            backdrop = requireNotNull(backdrop),
            backgroundColor = Color.Transparent,
            planeTint = colors.surface.copy(alpha = fill),
            shape = shape,
            optical = true,
            backdropAlpha = .84f,
            inputScale = FolioGlassCost.control,
            lightGain = .7f * sheen,
        )
    } else clip(shape).drawWithCache {
        onDrawBehind { drawRect(colors.surface.copy(alpha = fill)) }
    }
    return base.folioMaterialFinish(shape).drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val edge = Brush.linearGradient(listOf(colors.onSurface.copy(alpha = .23f),
            colors.onSurface.copy(alpha = .03f), accent.copy(alpha = .32f)),
            Offset.Zero, Offset(size.width, size.height))
        onDrawBehind { drawOutline(outline, edge, style = Stroke(.8.dp.toPx())) }
    }
}

/** A quiet data sheet: real backdrop diffusion, a defined edge, no ornamental bevel or shadow. */
@Composable
internal fun folioDataGlassPlane(fillAlpha: Float? = null, blurEnabled: Boolean = true): Color {
    val canBlur = blurEnabled && LocalGlassCapabilities.current.blur && LocalGlassInFlowGlass.current && LocalGlassBackdrop.current != null
    if (fillAlpha == null && LocalFolioSurfaceDepth.current > 0) {
        val parent = LocalFolioSurfaceFill.current
            ?: folioReferenceColor().copy(alpha = com.folio.reader.ui.theme.FolioTheme.surfaceOpacity.panelFill(canBlur))
        return parent.copy(alpha = parent.alpha * .25f)
    }
    return folioReferenceColor().copy(alpha = fillAlpha?.coerceIn(0f, 1f)
        ?: com.folio.reader.ui.theme.FolioTheme.surfaceOpacity.panelFill(canBlur))
}

@Composable
internal fun Modifier.folioDataGlass(shape: Shape = FolioMaterialShapes.reference, fillAlpha: Float? = null,
    overlay: Boolean = false, blurEnabled: Boolean = true, clipEnabled: Boolean = true, drawRim: Boolean = true): Modifier {
    val caps = LocalGlassCapabilities.current
    val backdrop = LocalGlassBackdrop.current
    val plane = folioDataGlassPlane(fillAlpha, blurEnabled)
    val ink = com.folio.reader.ui.theme.FolioTheme.colors.onSurface
    val embedded = LocalFolioSurfaceDepth.current > 0
    val clipped = if (clipEnabled) Modifier.clip(shape) else Modifier
    // A solid preference needs no capture. A scrolling data card never pays for lens refraction.
    val blurred = if (!embedded && blurEnabled && caps.blur && (overlay || LocalGlassInFlowGlass.current) && backdrop != null && plane.alpha < .999f) {
        clipped.folioGlassEffect(backdrop, backgroundColor = Color.Transparent,
            // Fade only the captured backdrop, not the content. With the tint above it,
            // uncaptured page transmission is (1 - plane.alpha)^2 rather than zero.
            alpha = plane.alpha, blurRadius = 12.dp, inputScale = FolioGlassCost.resolve(FolioGlassCost.pane, caps))
    } else clipped
    return then(blurred).drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val rim = if (embedded) .6f else 1f
        val edge = Brush.verticalGradient(listOf(ink.copy(alpha = .13f * rim), ink.copy(alpha = .035f * rim)))
        onDrawBehind {
            drawOutline(outline, plane)
            if (drawRim) drawOutline(outline, edge, style = Stroke(.75.dp.toPx()))
        }
    }.folioMaterialFinish(shape)
}

/** Reader instruments: optical diffusion, one etched edge, no grain, ribs, bevel or cast shadow.
 * Native book pages cannot be sampled; their caller suppresses the backdrop, so the same material
 * resolves to an honest translucent plane. The user's reader opacity is never silently replaced.
 */
@Composable
internal fun Modifier.folioReaderGlass(shape: Shape, fillAlpha: Float, blurEnabled: Boolean = true): Modifier {
    val backdrop = LocalGlassBackdrop.current
    val caps = LocalGlassCapabilities.current
    val plane = folioReferenceColor().copy(alpha = fillAlpha.coerceIn(0f, 1f))
    val ink = com.folio.reader.ui.theme.FolioTheme.colors.onSurface
    val sheen = folioSheenGain().coerceIn(0f, 1f)
    val base = if (blurEnabled && caps.blur && backdrop != null && plane.alpha < .999f) {
        clip(shape).folioGlassEffect(backdrop, backgroundColor = Color.Transparent,
            alpha = plane.alpha, blurRadius = 18.dp, noiseFactor = 0f, inputScale = FolioGlassCost.resolve(FolioGlassCost.pane, caps),
            readerSourcesOnly = true)
    } else clip(shape)
    return then(base).drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val edge = Brush.linearGradient(listOf(ink.copy(alpha = .10f + .08f * sheen),
            ink.copy(alpha = .035f), ink.copy(alpha = .07f)), Offset.Zero, Offset(size.width, size.height))
        val light = Brush.verticalGradient(listOf(ink.copy(alpha = .018f * sheen), Color.Transparent),
            endY = minOf(size.height, 32.dp.toPx()))
        onDrawBehind {
            drawOutline(outline, plane)
            drawOutline(outline, light)
            drawOutline(outline, edge, style = Stroke(.75.dp.toPx()))
        }
    }
}

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
        LocalGlassWorkload provides rememberGlassWorkload(),
    ) {
        content()
    }
}

/**
 * Whether the §20 in-flow upgrade is actually **live** where this is read: the user has
 * not opted into minimal glass, and the device plus this screen can carry a real blur.
 *
 * Call sites that own a painted well ([folioSunken]) or a flat panel ([folioPanel] and
 * friends) use this to choose materials: `true` -> the full [folioVeil] glass, so the
 * room frosts through the surface the way the section cards already do; `false` -> the
 * painted material exactly as shipped. Keeping the choice at the call site (rather than
 * teaching every material to promote) preserves the deliberate designs — a chart well
 * stays a well when the user asked for minimal glass, and nothing changes on a device
 * that cannot blur.
 */
@Composable
fun inFlowGlassActive(): Boolean = LocalGlassInFlowGlass.current && glassBlurred()

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
    return this.folioGlassScrollObserver().hazeSource(backdrop, zIndex = FOLIO_BACKDROP_Z_CONTENT)
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
 * The single Haze 2 seam. Rounded floating surfaces get one optical material;
 * full-width bars and in-flow panes use diffusion. Haze owns capture and refraction,
 * so there is no second room replay or platform-specific lens pass.
 */
@Composable
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
    inputScale: Float = FolioGlassCost.pane,
    shape: Shape? = null,
    optical: Boolean = false,
    planeTint: Color = Color.Unspecified,
    lightGain: Float = 1f,
    /** The artwork dock blends its refracted input; existing surfaces retain full diffusion. */
    backdropAlpha: Float = 1f,
    /** Reader glass may sample page content, never the app's unrelated room plane. */
    readerSourcesOnly: Boolean = false,
): Modifier {
    val moving = LocalGlassWorkload.current.moving
    val quality = folioGlassQuality(inputScale, moving)
    val performance = remember(quality) { HazePerformanceMode.Fixed(quality) }
    // Drop stale retained pixels when navigation removes a source. Folio's source
    // selection already excludes outgoing screens; retaining them defeats that rule.
    val input = remember(backdrop, readerSourcesOnly) {
        if (readerSourcesOnly) HazeInput.Sources(backdrop,
            selection = HazeSourceSelection.All.where { it.key != FOLIO_BACKDROP_KEY_ROOM && it.zIndex >= FOLIO_BACKDROP_Z_CONTENT },
            retention = HazeSourceRetention.ClearWhenUnavailable)
        else HazeInput.Sources(backdrop, retention = HazeSourceRetention.ClearWhenUnavailable)
    }
    val motion = rememberMotionEnabled()
    val rounded = shape as? RoundedCornerShape
    if (optical && rounded != null) {
        val material = remember(rounded, backgroundColor, tint, bodyTint, planeTint, alpha, lightGain, inputScale, backdropAlpha) {
            GlassStyle.regular.then {
                shape(rounded)
                backgroundColor(backgroundColor)
                val frost = if (tint.isSpecified) tint else Color.Transparent
                val body = if (bodyTint.isSpecified) bodyTint else Color.Transparent
                tint(if (planeTint.isSpecified) planeTint else frost.compositeOver(body))
                // Surfaces retain full diffusion. Clear decorative glass explicitly
                // blends a lighter refraction with the room, without an opaque floor.
                alpha(backdropAlpha.coerceIn(0f, 1f))
                specularIntensity((0.48f * lightGain).coerceIn(0f, 1f))
                ambientResponse(0.12f)
                lightPosition(Alignment.TopStart)
                // Small controls retain the recognizable bent edge. Larger panels
                // keep Regular's size-responsive diffusion behind their labels.
                if (backdropAlpha < 1f) {
                    optics(refractionStrength = .25f, refractionDisplacement = 3.dp,
                        blurRadius = 4.dp, depth = .35f, refractionDetailIntensity = 0f)
                } else if (inputScale > FolioGlassCost.pane) {
                    optics(refractionStrength = 0.65f, refractionDisplacement = 8.dp,
                        blurRadius = 12.dp, depth = 0.65f, refractionDetailIntensity = 0f)
                }
                interactionLightRadiusFraction(0.85f)
                hovered { lightingIntensity(0.72f) }
                pressed { lightingIntensity(0.95f); refractionMultiplier(1.12f) }
            }
        }
        return hazeGlass(
            input = input,
            style = material,
            performanceMode = performance,
            interactionReducedMotionPolicy = if (motion) GlassReducedMotionPolicy.System else GlassReducedMotionPolicy.Reduced,
        )
    }
    val style = remember(backgroundColor, blurRadius, noiseFactor, alpha, progressive,
        progressiveEndIntensity, progressiveEndY, tint, bodyTint) {
        HazeBlurStyle {
            backgroundColor(backgroundColor)
            blurEnabled(true)
            blurRadius(blurRadius.coerceAtMost(FolioTokens.blurRadiusMax))
            noiseFactor(noiseFactor)
            alpha(alpha.coerceIn(0f, 1f))
            colorEffects(buildList {
                if (bodyTint.isSpecified) add(HazeColorEffect.tint(bodyTint))
                if (tint.isSpecified) add(HazeColorEffect.tint(tint))
            })
            progressive(if (progressive) HazeProgressive.verticalGradient(
                startY = 0f, startIntensity = 1f,
                endY = progressiveEndY.takeIf { it.isFinite() && it > 0f } ?: Float.POSITIVE_INFINITY,
                endIntensity = progressiveEndIntensity.coerceIn(0f, 1f),
            ) else null)
        }
    }
    return hazeBlur(input = input, style = style, performanceMode = performance)
}
