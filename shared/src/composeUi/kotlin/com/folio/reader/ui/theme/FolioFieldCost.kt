package com.folio.reader.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import com.folio.reader.ui.components.LocalGlassCapabilities
import kotlin.math.ceil

/**
 * The ground-plane field's **per-frame budget**: how many pixels its shader is allowed to
 * touch, and whether it is allowed to draw at all.
 *
 * ## Why this file exists
 *
 * Every other never-ending animation in the app is paced ([SLOW_MOTION_TICK_MS] at 10 Hz,
 * [FolioTokens.fieldAnimationFrameMs] at 20 Hz, the guilloche and silk ticks at 60) and every
 * one of them reads its phase in the *draw* phase so a tick never recomposes. The Mirage field
 * stacked over that base obeys none of it, and it cannot be made to, because its clock is
 * inside the library:
 *
 * - `io.androidpoet.mirage.core.rememberShaderTime(speed, startFrame)` runs
 *   `LaunchedEffect(speed) { while (true) withFrameNanos { time += dt * speed } }` and writes a
 *   `MutableFloatState`. `withFrameNanos`, not `delay` — so it asks the Choreographer for a
 *   frame **every vsync**, forever, and (being `withFrameNanos` rather than
 *   `withInfiniteAnimationFrameNanos`) it is not even subject to an `InfiniteAnimationPolicy`.
 * - `MirageSurface` reads that state inside `drawWithCache { onDrawBehind { … } }` and
 *   `mirageEffect` reads it inside `graphicsLayer { … }`. Draw-phase reads, so there is no
 *   recomposition — but the draw node invalidates at the display's full rate and repaints a
 *   **full-screen** procedural shader, 60–120 times a second.
 * - `startFrame` is not a throttle: `rememberShaderTime` seeds its state with an *unkeyed*
 *   `remember`, so a later `startFrame` is ignored. The only exposed lever that changes the
 *   clock at all is `speed = 0`, which freezes the field outright (that is what reduce-motion
 *   already uses). There is no supported way to ask Mirage for 30 Hz.
 *
 * So the lever that is actually available at the call site is **area**, not rate: AGSL cost is
 * linear in fragments, and a 1080×2400 field is 2.59 M fragment invocations per pass. The field
 * families are low-frequency by design ("soft, slow, atmospheric", "Slow Perlin cloud drift"),
 * and procedural noise is the one thing that survives being rendered small and scaled up — the
 * grain families are noise to begin with. Halving the linear scale is a quarter of the work;
 * 0.4 is a sixth. The motion is untouched: same shader, same clock, same speed, fewer pixels.
 *
 * The second lever is **presence**: a field that nothing can see should not be drawn at all
 * (see [FolioFieldOccluder]).
 *
 * Both are expressed here, as data, so the whole budget is one table rather than a decision
 * spread across twenty-six `when` branches.
 */
object FolioFieldCost {

    /**
     * Families whose motif is pitched in **raw device pixels**, so a coarser render moves the
     * design rather than only softening it. These stay 1:1.
     *
     * [FolioFieldKind.Dithering]'s AGSL pixelises from `fragCoord` directly
     * (`pxSize = u_pxSize * u_pixelRatio`, `pxSizeUV = fragCoord - .5 * u_resolution`) at
     * `size = 2f`: a two-device-pixel ordered Bayer cell. Halving the render does not coarsen
     * that, it smears the matrix into grey — the one thing a darkroom dither must not be.
     * [FolioFieldKind.DotGrid] pitches its dots on `gapX/gapY = 34f` through `getPatternUV`,
     * which multiplies by `u_resolution` and divides by `u_pixelRatio`; at half scale the grid
     * would read at 68px and that is a design decision, not a cost one.
     */
    const val scalePattern: Float = 1f

    /**
     * Families carrying a `getPatternUV` term, whose motif therefore grows with the inverse of
     * the scale — grain, fibre, cloud cells, filaments, strokes. A quarter of the fragment work
     * for a motif rendered at twice the size, which on noise, grain and a slow Perlin cloud is
     * the direction they were authored in anyway (softer, larger, quieter).
     */
    const val scaleGrain: Float = 0.5f

    /**
     * Families whose sizing is **resolution-invariant**: their AGSL derives every coordinate
     * from `getObjectUV`, `getResponsiveUV` or `getImageUV`, all of which normalise against
     * `u_resolution` (`objectWorldScale = u_resolution / objectBoxSize`, and `objectBoxSize` is
     * itself derived from `u_resolution` whenever `u_worldWidth == 0`, which is every call here).
     *
     * For these the picture is *mathematically the same picture* at any render scale — same
     * lobes, same positions, same sizes — and the only thing that changes is how finely it is
     * sampled before being magnified. Since they are also the softest families (large lobes,
     * god rays, smoke, metaballs, caustics) and several are the dearest per pixel, they take the
     * most aggressive scale: a sixth of the fragment work.
     */
    const val scaleSoft: Float = 0.4f

    /**
     * What a device the platform classes as constrained multiplies its field scale by.
     *
     * Read off [com.folio.reader.ui.components.GlassCapabilities.budget] rather than probing
     * `ActivityManager` again: that value is already derived from `ActivityManager.memoryClass`
     * at the app root, is already the app's one device-class verdict, and is 1f on every
     * un-provided tree (desktop, previews, tests), so nothing changes where it is absent. Only
     * the *fact* of a reduced budget is consumed here, never its magnitude — the blur budget and
     * the field budget are different currencies and must not be multiplied together.
     */
    const val constrainedScale: Float = 0.75f

    /**
     * The floor. Below roughly a third, bilinear upscaling starts to show as banding on the
     * soft ramps rather than as a softer field, and the two or three pixels of rounding
     * overdraw stop being free.
     */
    const val minScale: Float = 0.3f
}

/**
 * How large a fraction of the device's pixels this family is rendered at before being scaled
 * back up to fill the ground plane. 1 means 1:1 — see [FolioFieldCost.scalePattern].
 *
 * The split is not taste, it is each family's own AGSL: which of the four sizing helpers its
 * `main` calls decides whether a coarser render changes the *picture* or only its *sharpness*.
 *
 * Exhaustive `when` on purpose: a family added to [FolioFieldKind] must be given a budget
 * rather than silently inheriting one.
 */
fun FolioFieldKind.fieldRenderScale(): Float = when (this) {
    // Pitched in device pixels — the motif moves if the render does.
    FolioFieldKind.Dithering,
    FolioFieldKind.DotGrid -> FolioFieldCost.scalePattern

    // Broad gradients and cloud density tolerate the softest sampling. The custom programs use
    // normalised field coordinates, so downsampling never grows their motif.
    FolioFieldKind.MeshGradient,
    FolioFieldKind.PerlinNoise,
    FolioFieldKind.Swirl,
    FolioFieldKind.GodRays,
    FolioFieldKind.GemSmoke,
    FolioFieldKind.StaticMeshGradient,
    FolioFieldKind.StaticRadialGradient -> FolioFieldCost.scaleSoft

    // The one family that renders **once**, so resolution is free and quality is all that is
    // left to optimise for.
    //
    // It declares no `u_time` and is hosted with the library's clock switched off and a
    // frozen source (see FolioStaticField.android.kt), so it pays exactly one shader pass
    // per theme change rather than one per frame. A per-frame budget is therefore the wrong
    // instrument. (PaperTexture used to share this tier; the family is removed outright —
    // see MeshField.android.kt's dispatch note for the record.)
    //
    // Full scale. If it ever gains a clock, move it back to [FolioFieldCost.scaleGrain].
    FolioFieldKind.HalftoneDots -> FolioFieldCost.scalePattern

    // These Mirage families carry a getPatternUV term, so their grain / stroke pitch grows
    // with 1/scale. The custom fields below have scale-invariant geometry instead.
    //
    // [FolioFieldKind.GrainGradient] is the mixed case and the reason the tiers are drawn here:
    // its shape comes from `getObjectUV` (invariant) and only its `noise = 0.28` canvas grain
    // from `getPatternUV`, so the lit wall is unchanged and the grain is twice the size.
    FolioFieldKind.GrainGradient,
    FolioFieldKind.SimplexNoise,
    FolioFieldKind.NeuroNoise,
    FolioFieldKind.DotOrbit,
    FolioFieldKind.PulsingBorder -> FolioFieldCost.scaleGrain

    // These independent fields have fine features worth resolving: smoke filaments, merging
    // membranes, thermal isolines, caustics, glass rims and chrome reflections. Still quarter-area.
    FolioFieldKind.Warp,
    FolioFieldKind.Voronoi,
    FolioFieldKind.Spiral,
    FolioFieldKind.Waves,
    FolioFieldKind.SmokeRing,
    FolioFieldKind.Metaballs,
    FolioFieldKind.Heatmap,
    FolioFieldKind.Water,
    FolioFieldKind.ColorPanels,
    FolioFieldKind.LiquidMetal -> FolioFieldCost.scaleGrain
}

/**
 * Whether this family covers the ground plane edge to edge and opaquely, so the base
 * ramp-plus-bloom layer underneath it is *invisible* and its blooms need not be drawn.
 *
 * Every Mirage family the dispatch uses is fed an opaque `colorBack` (or, for the two
 * generative filters, an explicit `Modifier.background(back)`), so this is true across the
 * board — it is a named predicate rather than a literal `true` so that a future family which
 * is genuinely translucent has somewhere to say so, and so the call site reads as the claim it
 * is making. The *colour* it is opaque with belongs to the atmosphere, so the caller must also
 * check the ramp's own alpha; see [FolioThemeBackground].
 */
fun FolioFieldKind.coversGroundPlane(): Boolean = true

/**
 * This family's field scale after the device class has had its say.
 *
 * Not a `remember`: it is two multiplications on values that are already stable, and keying a
 * `remember` on them would cost more than the arithmetic.
 */
@Composable
fun rememberFolioFieldScale(kind: FolioFieldKind): Float {
    val constrained = LocalGlassCapabilities.current.budget < 1f
    val base = kind.fieldRenderScale()
    val scaled = if (constrained) base * FolioFieldCost.constrainedScale else base
    return scaled.coerceIn(FolioFieldCost.minScale, 1f)
}

/**
 * Renders this subtree into a layer [scale] of the node's own size and scales that layer back
 * up to fill it — the field's one cost lever that does not touch the animation.
 *
 * ## How it actually saves fragments
 *
 * The `layout` node measures its child with `Constraints.fixed` at the reduced pixel size, so
 * the child's `DrawScope.size` — which is what Mirage feeds to `u_resolution` via
 * `setSizingUniforms(shader, sizing, size.width, size.height, …)` — *is* the small size. The
 * shader therefore rasterises a small picture, and the `graphicsLayer` transform magnifies it.
 *
 * The magnification has to happen on a rasterised buffer rather than on the matrix, or HWUI
 * would simply fold the scale into the replay transform and run the shader at device
 * resolution after all. Two things force the buffer, and the field needs only one of them:
 *
 * - `compositingStrategy = CompositingStrategy.Offscreen` maps to
 *   `RenderNode.setUseCompositingLayer(true, null)` on API 29+, which is documented to render
 *   the node into an offscreen buffer sized from the node's own bounds.
 * - Mirage's six **image-filter** families already set a `renderEffect` on their own
 *   `graphicsLayer`, and a `RenderNode` with a render effect is drawn into a separate layer by
 *   definition. For those the saving does not depend on the compositing strategy at all.
 *
 * ## The edge
 *
 * The child is measured at `ceil(size * scale)` and magnified by exactly `1 / scale`, so the
 * magnified layer always covers the full node and a rounding remainder can never leave a
 * hairline of unpainted ground. The overdraw is at most `1 / scale` pixels past the right and
 * bottom edges — two or three pixels off the edge of a layer that is already the full window,
 * and `clip` is left off so nothing is paid to trim it.
 *
 * At `scale >= 1` this is the identity: no layout node, no layer, no behaviour change at all.
 */
fun Modifier.folioFieldDownsample(scale: Float): Modifier {
    if (scale >= 0.999f) return this
    val s = scale.coerceIn(FolioFieldCost.minScale, 1f)
    val magnify = 1f / s
    return this
        .layout { measurable, constraints ->
            val bounded = constraints.hasBoundedWidth && constraints.hasBoundedHeight
            if (!bounded) {
                // Unbounded on an axis: there is no pixel count to reduce, and inventing one
                // would change the layout. Pass through untouched.
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            } else {
                val w = constraints.maxWidth
                val h = constraints.maxHeight
                val lw = ceil(w * s).toInt().coerceIn(1, w.coerceAtLeast(1))
                val lh = ceil(h * s).toInt().coerceIn(1, h.coerceAtLeast(1))
                val placeable = measurable.measure(Constraints.fixed(lw, lh))
                layout(w, h) { placeable.place(0, 0) }
            }
        }
        .graphicsLayer {
            transformOrigin = TransformOrigin(0f, 0f)
            scaleX = magnify
            scaleY = magnify
            // Forces the small render into its own buffer, which is the whole point; see above.
            compositingStrategy = CompositingStrategy.Offscreen
            clip = false
        }
}
