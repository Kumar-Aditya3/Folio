package com.folio.reader.ui.theme

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import io.androidpoet.mirage.ColorPanels
import io.androidpoet.mirage.Dithering
import io.androidpoet.mirage.DitheringShape
import io.androidpoet.mirage.DitheringType
import io.androidpoet.mirage.DotGrid
import io.androidpoet.mirage.DotOrbit
import io.androidpoet.mirage.GemSmokeShape
import io.androidpoet.mirage.GodRays
import io.androidpoet.mirage.GrainGradient
import io.androidpoet.mirage.GrainGradientShape
import io.androidpoet.mirage.HalftoneDotsGrid
import io.androidpoet.mirage.HalftoneDotsType
import io.androidpoet.mirage.LiquidMetalShape
import io.androidpoet.mirage.MeshGradient
import io.androidpoet.mirage.Metaballs
import io.androidpoet.mirage.NeuroNoise
import io.androidpoet.mirage.PerlinNoise
import io.androidpoet.mirage.PulsingBorder
import io.androidpoet.mirage.SimplexNoise
import io.androidpoet.mirage.SmokeRing
import io.androidpoet.mirage.Spiral
import io.androidpoet.mirage.StaticMeshGradient
import io.androidpoet.mirage.StaticRadialGradient
import io.androidpoet.mirage.Swirl
import io.androidpoet.mirage.Voronoi
import io.androidpoet.mirage.Warp
import io.androidpoet.mirage.WarpPattern
import io.androidpoet.mirage.Waves
import io.androidpoet.mirage.core.ShaderFit
import io.androidpoet.mirage.core.SizingParams
import io.androidpoet.mirage.gemSmoke
import io.androidpoet.mirage.halftoneDots
import io.androidpoet.mirage.heatmap
import io.androidpoet.mirage.liquidMetal
import io.androidpoet.mirage.paperTexture
import io.androidpoet.mirage.water

/**
 * Android side of the per-theme field: each [FolioFieldKind] is a different Mirage AGSL shader,
 * fed this theme's palette and tuned for its concept.
 *
 * ## Two API families, not one
 *
 * Mirage exposes its shaders in two shapes, and [FolioFieldKind] flattens both into one enum, so
 * this dispatch has to adapt them:
 *
 * - **Procedural composables** (`GrainGradient`, `Warp`, `MeshGradient`, �) are `@Composable`
 *   surfaces built on `drawWithCache { onDrawBehind { drawRect(shaderBrush) } }`. They paint their
 *   own bounds, so passing [modifier] straight through is all they need.
 * - **Image filters** (`water`, `liquidMetal`, `heatmap`, `gemSmoke`, `paperTexture`,
 *   `halftoneDots`) are `Modifier` extensions built on `graphicsLayer { renderEffect = � }`, whose
 *   AGSL samples the layer's own content through `uniform shader u_image`. They need content and
 *   a cover fit to work as a ground plane � see [filterSource] and [FILTER_SIZING].
 *
 * The whole dispatch is gated on API 33 (Mirage's shaders are `@RequiresApi(TIRAMISU)`); below
 * that it draws nothing and [folioThemeFieldSupported] is false, so [FolioThemeBackground]'s
 * fallback ramp stands alone. Reduce-motion is expressed as `speed = 0f` � the field freezes at
 * zero cost rather than disappearing � except on the two filters that have no `speed` at all,
 * where it parks the source's drift instead.
 *
 * Colour feeding is uniform: every family is handed the theme's own pools and accents and a
 * `colorBack` taken from the field ramp, so no family can import a stock palette. Where a family
 * needs more entries than the theme supplies, the list is cycled rather than padded with grey.
 */
/**
 * How far the dark field's base is pushed below the ramp it is sampled from.
 *
 * **COLOUR ONLY — P9.** `back` is the `colorBack` every Mirage family paints behind its own
 * pattern, i.e. the colour the ground plane actually *is* wherever the shader's figure is thin.
 * It used to be `bottom` on a dark face — the ramp's **brightest** stop, the one the atmosphere
 * blooms toward — so the darkest thing on screen was built from the lightest thing the page
 * has. Measured on `dawn`/Dusk that was `#201525` (a washed mid purple) against an authored
 * page of `#05030F`: the entire gap in the "dark mode isn't dark" report, in one assignment.
 *
 * The base is now the ramp's own crown-weighted point, deepened: the crown is the stop anchored
 * on the authored `background`, so weighting toward it returns the shader's floor to the page
 * the designer signed off, and the extra deepen pays for the light the pattern itself then adds
 * on top. The *hue* is still the ramp's, so the field keeps the theme's identity as a tint.
 */
private const val FIELD_DARK_BACK_FOOT = 0.35f
private const val FIELD_DARK_BACK_DEEPEN = 0.22f

/**
 * How far the dark field's working palette sinks toward its own base.
 *
 * **COLOUR ONLY — P9.** The field is fed `accents + pools`, and on a dark face the accents are
 * the palette's *luminous* display colours by design (`FolioArtDirection` authors the dark
 * primaries at Oklab L 0.70-0.85 — "the same world at night" puts the light in the accents).
 * Those are the right colours for a 48dp progress ring and the wrong ones for a full-screen
 * field: every family mixes them over `colorBack` at an intensity that is within a couple of
 * points of its light-face value, so the animation rendered at roughly paper's luminance on top
 * of a page that is asking to be near-black. That is the "ESPECIALLY ON THE BACKGROUND AND ITS
 * ANIMATIONS" half of the report.
 *
 * Sinking each entry toward the field's own base in Oklab keeps its hue and the *relative*
 * spread between the entries — so a theme's field is still unmistakably its own, and the
 * families that read a single `colorFront` still read a coloured one — while bringing the
 * field's output luminance down to the room's band. It is applied once, here, so all 25
 * families inherit it rather than each branch being retuned (and so the per-family parameters
 * stay where their owner put them).
 */
private const val FIELD_DARK_PALETTE_SINK = 0.52f

@Composable
actual fun FolioThemeField(
    kind: FolioFieldKind,
    top: Color,
    bottom: Color,
    pools: List<Color>,
    accents: List<Color>,
    isDark: Boolean,
    animate: Boolean,
    modifier: Modifier,
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    // See FIELD_DARK_BACK_FOOT: a dark face's shader floor is the page, not the page's bloom.
    val back = if (isDark) {
        lerp(lerp(top, bottom, FIELD_DARK_BACK_FOOT), Color.Black, FIELD_DARK_BACK_DEEPEN)
    } else {
        top
    }
    // The field's working palette: the loud accents first (they carry the theme's identity), then
    // the softer aurora pools. Deduped and never empty. On a dark face every entry is sunk toward
    // the field's own base so the animation carries the theme's hue at the room's luminance
    // rather than at the accents' — see FIELD_DARK_PALETTE_SINK.
    val palette = remember(pools, accents, isDark, back) {
        val authored = (accents + pools).distinct().ifEmpty { listOf(top, bottom) }
        if (!isDark) authored else authored.map { lerp(it, back, FIELD_DARK_PALETTE_SINK) }
    }
    // The field's pixel budget. Every family here is a full-screen AGSL pass whose clock lives
    // inside Mirage and runs on `withFrameNanos` — i.e. at the display's full rate, which no
    // call-site parameter can slow (`startFrame` is seeded by an unkeyed `remember`, and the
    // only lever that touches the clock is `speed = 0`, which freezes the field outright). So
    // the cost is cut by area instead of by rate: the family renders into a layer a fraction of
    // the device's pixels and is scaled back up, which leaves the motion bit-for-bit as authored
    // and divides the fragment work by the square of the scale. See [FolioFieldCost].
    val scale = rememberFolioFieldScale(kind)
    MirageField(kind, back, palette, isDark, animate, modifier.folioFieldDownsample(scale))
}

/** Pulls [n] colours from [src], cycling if the theme authored fewer. */
private fun List<Color>.cycle(n: Int): List<Color> =
    if (isEmpty()) List(n) { Color.Gray } else List(n) { this[it % size] }

/**
 * The sizing every image-filter family is forced to use.
 *
 * Mirage gives each filter its own default � `SizingParams.Object.copy(scale = 0.6f..0.8f)`, i.e.
 * [ShaderFit.Contain] at sub-unity scale. That is the right default for its intended job (putting
 * an effect on a photo inside a card), but this is a full-screen ground plane: contain-fitting it
 * at 0.6 leaves the field inset in the middle of the display, ringed by flat `colorBack`, and the
 * shaders' own `getUvFrame` masks the source to that inset box as well. [ShaderFit.Cover] at the
 * default scale of 1 makes the pattern and the sampled source both cover the screen.
 */
private val FILTER_SIZING = SizingParams(fit = ShaderFit.Cover)

/**
 * Draws this theme's ground plane *inside* the filtered layer, so the filter has an image.
 *
 * Mirage's image filters are hosted by
 * `graphicsLayer { renderEffect = RenderEffect.createRuntimeShaderEffect(shader, "u_image") }`,
 * and their AGSL reads the layer back through `uniform shader u_image`. Two consequences the
 * previous code missed:
 *
 * 1. A `RenderNode` with an empty display list is not renderable, so HWUI skips it and the effect
 *    never runs. Hung on a content-less `Box`, every one of the six filters drew *nothing* �
 *    which is also why picking one looked worse than Plain: [FolioThemeBackground] parks the
 *    fallback ramp whenever a field claims it will paint, so the screen went completely static.
 * 2. Even when it does run, `u_image.eval` on an empty layer returns transparent black, and the
 *    content-driven families degenerate: `halftoneDots` reads luminance 0 and emits no dots at
 *    all, and `heatmap`'s mixer collapses so the whole field flattens to `colorBack`.
 *
 * Placing a draw modifier *after* the filter in the chain puts it inside that layer, so the ramp
 * becomes the filter's source. [folioSilkBackground] is a plain `drawWithCache` fill, which keeps
 * this to one display-list-cached full-screen draw rather than a second shader.
 *
 * [animate] is the source's own drift, which is only worth paying for on the two filters that
 * have no `u_time` of their own ([FolioFieldKind.PaperTexture], [FolioFieldKind.HalftoneDots]):
 * there it is the only motion in the field. Where the filter animates, the source is frozen.
 */
@Composable
private fun Modifier.filterSource(animate: Boolean): Modifier =
    folioSilkBackground(forceStatic = !animate)

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun MirageField(
    kind: FolioFieldKind,
    back: Color,
    palette: List<Color>,
    isDark: Boolean,
    animate: Boolean,
    modifier: Modifier,
) {
    // Each family runs slow � a reading-app background breathes, it does not perform. `s()` scales
    // a family's natural speed and collapses to a frozen frame under reduce-motion.
    fun s(v: Float): Float = if (animate) v else 0f
    when (kind) {
        // GALLERY � a vast plaster wall whose light drifts under canvas grain. Corners shape keeps
        // the gradient pooled in the corners like a lit room; high softness, low intensity, grain on.
        FolioFieldKind.GrainGradient -> GrainGradient(
            modifier = modifier,
            colorBack = back,
            colors = palette.cycle(4),
            softness = 0.85f,
            // COLOUR ONLY — P9: dark 0.36 -> 0.26. The dark branch was within two points of
            // paper's, so the wall was lit as brightly at night as by day.
            intensity = if (isDark) 0.26f else 0.38f,
            noise = 0.28f,
            shape = GrainGradientShape.Corners,
            speed = s(0.3f),
        )
        // MANUSCRIPT � ink bleeding through laid paper; Stripes are the scribe's ruled lines pulled
        // out of true by a moderate warp and swirl.
        FolioFieldKind.Warp -> Warp(
            modifier = modifier,
            colors = palette.cycle(4),
            proportion = 0.5f,
            softness = 1f,
            shape = WarpPattern.Stripes,
            shapeScale = 0.12f,
            distortion = 0.22f,
            swirl = 0.7f,
            swirlIterations = 10,
            speed = s(0.35f),
        )
        // SILVER � the silver-halide grain of a darkroom print: a Bayer dither over a slow swirl.
        FolioFieldKind.Dithering -> Dithering(
            modifier = modifier,
            colorBack = back,
            colorFront = palette.first(),
            shape = DitheringShape.Swirl,
            type = DitheringType.Bayer4x4,
            size = 2f,
            speed = s(0.5f),
        )
        // ARCTIC � aurora curtains twisting over ice strata. Low twist, high softness, gentle noise.
        FolioFieldKind.Swirl -> Swirl(
            modifier = modifier,
            colorBack = back,
            colors = palette.cycle(3),
            bandCount = 5f,
            twist = 0.14f,
            center = 0.25f,
            proportion = 0.5f,
            softness = 0.6f,
            noiseFrequency = 0.4f,
            noise = 0.22f,
            speed = s(0.3f),
        )
        // SAKURA � petals orbiting on the wind: discrete soft bodies drifting across the field.
        FolioFieldKind.DotOrbit -> DotOrbit(
            modifier = modifier,
            colorBack = back,
            colors = palette.cycle(5),
            size = 1.1f,
            sizeRange = 0.5f,
            spreading = 1f,
            stepsPerColor = 4,
            speed = s(0.9f),
        )
        // HONEY � viscous drops coalescing. Few, large, slow balls.
        FolioFieldKind.Metaballs -> Metaballs(
            modifier = modifier,
            colorBack = back,
            colors = palette.cycle(5),
            count = 8f,
            size = 0.9f,
            speed = s(0.6f),
        )
        // MOSS � a cellular colony spreading over stone: Voronoi cells with a gap and a faint glow.
        FolioFieldKind.Voronoi -> Voronoi(
            modifier = modifier,
            colors = palette.cycle(2),
            stepsPerColor = 3,
            colorGlow = palette.first(),
            colorGap = back,
            distortion = 0.4f,
            gap = 0.05f,
            glow = 0.15f,
            speed = s(0.35f),
        )
        // DAWN � volumetric sun-shafts from a low, off-screen source, with bloom.
        FolioFieldKind.GodRays -> GodRays(
            modifier = modifier,
            colorBack = back,
            colorBloom = palette.first(),
            colors = palette.cycle(4),
            density = 0.32f,
            spotty = 0.28f,
            midSize = 0.22f,
            midIntensity = 0.42f,
            // COLOUR ONLY — P9: dark intensity 0.60 -> 0.42 and dark bloom 0.45 -> 0.28. God
            // rays are the one family whose figure is *volumetric light*, so it is the loudest
            // of the twenty-five on a near-black page — and `dawn`/Dusk, the face the dark-mode
            // report was filed against, is the pack that draws it.
            intensity = if (isDark) 0.42f else 0.65f,
            bloom = if (isDark) 0.28f else 0.45f,
            speed = s(0.5f),
        )
        // LAGOON � turquoise, sand and coral drifting through moving water; blends, never whitens.
        FolioFieldKind.MeshGradient -> MeshGradient(
            modifier = modifier,
            colors = palette.cycle(4),
            distortion = 0.8f,
            swirl = 0.12f,
            grainMixer = 0f,
            grainOverlay = 0f,
            speed = s(0.7f),
        )
        // ROUGE � a turbulent ring of smoke in a single spotlight: the house before curtain.
        FolioFieldKind.SmokeRing -> SmokeRing(
            modifier = modifier,
            colorBack = back,
            colors = palette.cycle(2),
            noiseScale = 3f,
            noiseIterations = 8,
            radius = 0.28f,
            thickness = 0.7f,
            innerShape = 0.7f,
            speed = s(0.4f),
        )

        // --- Opt-in families (per-theme override picker). Each still eats this theme's palette. ---
        FolioFieldKind.ColorPanels -> ColorPanels(
            modifier = modifier,
            colorBack = back,
            colors = palette.cycle(4),
            angle1 = 20f,
            angle2 = -15f,
            gradient = 0.5f,
            blur = 0.3f,
            speed = s(0.4f),
        )
        FolioFieldKind.DotGrid -> DotGrid(
            modifier = modifier,
            colorBack = back,
            colorFill = palette.first(),
            colorStroke = palette.cycle(2).last(),
            size = 2.4f,
            gapX = 34f,
            gapY = 34f,
            sizeRange = 0.5f,
            opacityRange = 0.5f,
        )
        FolioFieldKind.NeuroNoise -> NeuroNoise(
            modifier = modifier,
            colorBack = back,
            colorMid = palette.cycle(2).last(),
            colorFront = palette.first(),
            brightness = if (isDark) 0.05f else 0.04f,
            contrast = 0.35f,
            speed = s(0.5f),
        )
        FolioFieldKind.PerlinNoise -> PerlinNoise(
            modifier = modifier,
            colorBack = back,
            colorFront = palette.first(),
            proportion = 0.4f,
            softness = 0.25f,
            octaveCount = 3,
            speed = s(0.3f),
        )
        FolioFieldKind.SimplexNoise -> SimplexNoise(
            modifier = modifier,
            colors = (listOf(back) + palette).cycle(4),
            stepsPerColor = 2,
            softness = 0.4f,
            speed = s(0.3f),
        )
        FolioFieldKind.Spiral -> Spiral(
            modifier = modifier,
            colorBack = back,
            colorFront = palette.first(),
            density = 1.1f,
            distortion = 0.2f,
            strokeWidth = 0.5f,
            softness = 0.3f,
            speed = s(0.5f),
        )
        FolioFieldKind.Waves -> Waves(
            modifier = modifier,
            colorBack = back,
            colorFront = palette.first(),
            frequency = 0.5f,
            amplitude = 0.6f,
            spacing = 1.3f,
            proportion = 0.14f,
            softness = 0.2f,
        )
        // --- Image-filter families ------------------------------------------------------------
        // See [FILTER_SIZING] and [filterSource] for why these six are shaped differently from
        // every branch above: they are not procedural composables, they are *image filters*, and
        // a filter with nothing to filter draws nothing at all.
        FolioFieldKind.Water -> Box(
            modifier
                .water(
                    colorBack = back,
                    colorHighlight = palette.first(),
                    // The caustic is a 5% colour mix at `highlights = 1`, so the old 0.1f was
                    // three orders below visible even once there was an image to light.
                    highlights = 0.75f,
                    layering = 0.6f,
                    waves = 0.45f,
                    caustic = 0.4f,
                    speed = s(0.8f),
                    sizing = FILTER_SIZING,
                )
                // Animated filter: the shader supplies the motion, so the ramp it refracts is a
                // frozen frame and costs one cached full-screen fill, not one per vsync.
                .filterSource(animate = false),
        )
        // Generative (`isImage` defaults to false): the shape is built from `getObjectUV`, not
        // from `u_image`, so the content is only there to make the layer renderable � a flat fill
        // is enough and a ramp would be thrown away.
        FolioFieldKind.LiquidMetal -> Box(
            modifier
                .liquidMetal(
                    colorBack = back,
                    colorTint = palette.first(),
                    repetition = 2f,
                    softness = 0.12f,
                    distortion = 0.08f,
                    contour = 0.45f,
                    shape = LiquidMetalShape.Diamond,
                    speed = s(0.6f),
                    sizing = FILTER_SIZING,
                )
                .background(back),
        )
        FolioFieldKind.Heatmap -> Box(
            modifier
                .heatmap(
                    colors = palette.cycle(4),
                    colorBack = back,
                    contour = 0.5f,
                    innerGlow = 0.5f,
                    outerGlow = 0.5f,
                    speed = s(0.6f),
                    sizing = FILTER_SIZING,
                )
                // Isotherms are read off the blurred green channel of the content: with an empty
                // layer the mixer was 0 everywhere and the field collapsed to flat `colorBack`.
                // Over the ramp the drifting blooms become the hot spots.
                .filterSource(animate = false),
        )
        FolioFieldKind.GemSmoke -> Box(
            modifier
                .gemSmoke(
                    colors = palette.cycle(3),
                    colorBack = back,
                    colorInner = palette.first(),
                    innerGlow = 1f,
                    outerGlow = 0.55f,
                    shape = GemSmokeShape.Diamond,
                    speed = s(0.5f),
                    sizing = FILTER_SIZING,
                )
                .background(back),
        )
        // The two static filters. Neither shader declares `u_time`, so neither can move, and
        // neither public wrapper forwards a `speed` — which meant both ran the library's vsync
        // clock forever to re-render an image that cannot change. Hosted here with the clock off
        // and their source frozen, so each resolves to one shader pass per theme rather than one
        // per frame. See FolioStaticField.android.kt for the mechanism and the fallback.
        //
        // The source is frozen deliberately. Handing these two the live silk drift was the only
        // way to make them appear to move, but it also forced the filter to re-run on every frame
        // of that drift — the dearest shader in the set, full-screen, at refresh rate. Paper does
        // not move; a paper field is a paper field. Ambient motion on these themes comes from the
        // lamp and the room scrim, which cost a gradient rather than a noise lattice.
        FolioFieldKind.PaperTexture -> Box(
            modifier
                .folioStaticPaperTexture(
                    colorFront = palette.first(),
                    colorBack = back,
                    contrast = 0.3f,
                    roughness = 0.4f,
                    fiber = 0.3f,
                    folds = 0.6f,
                    sizing = FILTER_SIZING,
                )
                .filterSource(animate = false),
        )
        FolioFieldKind.HalftoneDots -> Box(
            modifier
                .folioStaticHalftoneDots(
                    colorBack = back,
                    colorFront = palette.first(),
                    size = 0.5f,
                    radius = 1.25f,
                    contrast = 0.45f,
                    grid = HalftoneDotsGrid.Hex,
                    type = HalftoneDotsType.Gooey,
                    sizing = FILTER_SIZING,
                )
                .filterSource(animate = false),
        )
        FolioFieldKind.PulsingBorder -> PulsingBorder(
            modifier = modifier,
            colorBack = back,
            colors = palette.cycle(4),
            thickness = 0.14f,
            softness = 0.75f,
            intensity = 0.25f,
            bloom = 0.3f,
            spots = 5,
            smoke = 0.3f,
            speed = s(0.7f),
        )
        FolioFieldKind.StaticMeshGradient -> StaticMeshGradient(
            modifier = modifier,
            colors = (listOf(back) + palette).cycle(5),
            mixing = 0.93f,
        )
        FolioFieldKind.StaticRadialGradient -> StaticRadialGradient(
            modifier = modifier,
            colorBack = back,
            colors = palette.cycle(3),
            radius = 0.85f,
            falloff = 0.3f,
            mixing = 0.5f,
        )
    }
}

actual fun folioThemeFieldSupported(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
