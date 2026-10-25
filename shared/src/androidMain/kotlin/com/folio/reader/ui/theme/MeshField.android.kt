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
import io.androidpoet.mirage.NeuroNoise
import io.androidpoet.mirage.PulsingBorder
import io.androidpoet.mirage.SimplexNoise
import io.androidpoet.mirage.StaticMeshGradient
import io.androidpoet.mirage.StaticRadialGradient
import io.androidpoet.mirage.Swirl
import io.androidpoet.mirage.core.ShaderFit
import io.androidpoet.mirage.core.SizingParams
import io.androidpoet.mirage.gemSmoke
import io.androidpoet.mirage.halftoneDots
import com.folio.reader.ui.theme.FieldMotion
import com.folio.reader.ui.theme.rememberSlowPhases
import kotlin.math.sin

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
// A tau, for the slow-phase modulation below; kept local so the two clockless families
// read their own phase without importing anything.
private const val TAU = 6.2831853f

private const val FIELD_DARK_BACK_FOOT = 0.35f

/**
 * The knobs nobody set. Every value below was previously the Mirage library's own default — a
 * design decision being made by someone who never saw this app. Each is now named, here, with its
 * reason, so a retune is one line rather than a hunt through the dispatch.
 */
// PulsingBorder is *named* for its pulse, and its breathing rate was the library default — a
// notification's urgency in a reading app's background. A read-scale pulse instead.
private const val PULSING_BORDER_PULSE = 0.15f
private const val PULSING_BORDER_ROUNDNESS = 0.65f
private const val PULSING_BORDER_SPOT_SIZE = 0.12f
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

/**
 * §Vibrancy change B: how much of the dark-palette sink the Muted ↔ Vivid lever may lift at its
 * vivid end. The sink exists to pull the luminous dark accents down to the room's luminance band;
 * a more vivid room keeps more of the authored colour by sinking it less (never past this
 * fraction, so the room never climbs back to paper's luminance). Zero effect at VIBRANCY_NEUTRAL.
 */
private const val FIELD_DARK_SINK_RELAX = 0.5f

/**
 * Extra sink toward the field base for the palette-dominated families on a dark face.
 *
 * The shared [FIELD_DARK_PALETTE_SINK] is tuned for families that render their palette over
 * `back` at a reduced per-family intensity. A subset does not: [FolioFieldKind.MeshGradient] and
 * [FolioFieldKind.Warp] have no `colorBack` at all (the whole field is a blend of the palette),
 * and Voronoi / Swirl / Heatmap / HalftoneDots / StaticMeshGradient / StaticRadialGradient read
 * the palette straight as their dominant colour. On a near-black page those carry the theme's
 * accents at close to paper's luminance — the "mirage is too bright in dark mode" report — so
 * their palette is sunk this much further toward the already-dark `back` before use. Dark only.
 */
private const val FIELD_DARK_PALETTE_SINK_EXTRA = 0.18f

@Composable
actual fun FolioThemeField(
    kind: FolioFieldKind,
    top: Color,
    bottom: Color,
    pools: List<Color>,
    accents: List<Color>,
    isDark: Boolean,
    animate: Boolean,
    vibrancy: Float,
    modifier: Modifier,
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    // See FIELD_DARK_BACK_FOOT: a dark face's shader floor is the page, not the page's bloom.
    val back = if (isDark) {
        lerp(lerp(top, bottom, FIELD_DARK_BACK_FOOT), Color.Black, FIELD_DARK_BACK_DEEPEN)
    } else {
        top
    }
    // §Vibrancy: the pools arrive already enriched from `atmosphereFor` (shared with the Canvas
    // base), and the dark-palette sink is eased as the room grows more vivid so the shader keeps
    // more of that colour. `up` is 0 at VIBRANCY_NEUTRAL, so the sink stays at its measured 0.52.
    val up = ((vibrancy - VIBRANCY_NEUTRAL) / (1f - VIBRANCY_NEUTRAL)).coerceIn(0f, 1f)
    val sink = FIELD_DARK_PALETTE_SINK * (1f - up * FIELD_DARK_SINK_RELAX)
    // The resolved motion: the field's speed factor (from the preset, the field-motion lever and
    // reduce-motion, which *crawls* the field rather than freezing it) and its amplitude factor.
    // One entry point instead of a bare `animate` boolean whose `speed = 0` meaning was previously
    // the app's only way to say less motion.
    val motion = LocalMotion.current
    val speedFactor = motion.fieldSpeedFactor
    val amplitudeFactor = motion.fieldIntensityFactor

    // The family's own pattern size, through the library's sizing rather than through 25 separate
    // per-family multipliers. `SizingParams(scale = 1f)` is exactly what the library uses when the
    // argument is omitted, so an untouched install renders byte-for-byte as before; only a reader
    // who moves the size lever changes anything.
    val sizing = SizingParams(scale = motion.fieldScaleFactor)
    val authored = remember(pools, accents, top, bottom) {
        (accents + pools).distinct().ifEmpty { listOf(top, bottom) }
    }
    val designedSource = remember(kind) { kind.designedFieldShader() }
    val scale = rememberFolioFieldScale(kind)
    if (designedSource != null) {
        val colors = remember(back, authored, isDark, vibrancy) {
            folioDesignedFieldColors(back, authored, isDark, vibrancy)
        }
        FolioDesignedField(
            kind = kind,
            source = designedSource,
            colors = colors,
            speedFactor = speedFactor,
            intensity = amplitudeFactor,
            patternScale = motion.fieldScaleFactor,
            modifier = modifier.folioFieldDownsample(scale),
        )
        return
    }
    // Keep Dot Orbit and the remaining Mirage families on their original dark-face treatment.
    val palette = remember(authored, isDark, back, sink) {
        if (!isDark) authored else authored.map { lerp(it, back, sink) }
    }
    // The field's pixel budget. Every family here is a full-screen AGSL pass whose clock lives
    // inside Mirage and runs on `withFrameNanos` — i.e. at the display's full rate, which no
    // call-site parameter can slow (`startFrame` is seeded by an unkeyed `remember`, and the
    // only lever that touches the clock is `speed = 0`, which freezes the field outright). So
    // the cost is cut by area instead of by rate: the family renders into a layer a fraction of
    // the device's pixels and is scaled back up, which leaves the motion bit-for-bit as authored
    // and divides the fragment work by the square of the scale. See [FolioFieldCost].
    MirageField(kind, back, palette, isDark, amplitudeFactor, speedFactor, vibrancy, sizing, modifier.folioFieldDownsample(scale))
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
 * [animate] is the source's own drift, only worth paying for on a filter that has no `u_time`
 * of its own ([FolioFieldKind.HalftoneDots]) — but the source is frozen here regardless, so
 * the dearest pass runs once per theme rather than once per frame of silk.
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
    amplitudeFactor: Float,
    speedFactor: Float,
    vibrancy: Float,
    sizing: SizingParams,
    modifier: Modifier,
) {
    // Each family runs slow - a reading-app background breathes, it does not perform. `s()` scales
    // a family's *authored* rate ([FieldMotion.fieldSpeed]) by the reader's factor.
    //
    // What `speed` does *not* do: change the redraw rate. The shader repaints every vsync
    // whatever the speed, because the clock lives inside Mirage as `withFrameNanos` - so
    // changing it costs no frames, which is what makes it free to expose. `startFrame`, the
    // library's other knob, is seeded by an unkeyed `remember` and is dead at every call site;
    // `speed = 0` is the only value that stops the clock (a frozen frame - which reduce-motion
    // no longer asks for: it asks for a crawl). Cost is governed separately, by
    // [FolioFieldCost] (render scale) and [FolioFieldOccluder] (presence).
    fun s(v: Float): Float = v * speedFactor
    // The family's amplitude at this room's gain: a dark face sits at the dark end and a light
    // face at the light end, with the vibrancy lever lifting the dark end continuously - the
    // binary `if (isDark)` this replaces had no room for "a little brighter than dark", which is
    // why every dark-mode brightness fix had to edit one family's literal.
    fun amp(kind: FolioFieldKind): Float =
        kind.fieldAmplitude().at(fieldGain(isDark, vibrancy)) * amplitudeFactor

    // Only DotGrid needs parameter animation. Truly static families must not start this clock.
    val tickPhase = if (kind == FolioFieldKind.DotGrid) {
        rememberSlowPhases(listOf(FieldMotion.clocklessPeriodMs), FieldMotion.clocklessTickMs)
            .value.firstOrNull() ?: 0f
    } else {
        0f
    }    // §Mirage dark brightness: the palette-dominated families (see FIELD_DARK_PALETTE_SINK_EXTRA)
    // take an extra sink of their palette toward the field base so a night field reads as night
    // rather than at paper's luminance. The already-sunk `palette` is unchanged for every other
    // family, and light faces are untouched (`deep` == `palette`). Facets and panels use their
    // already-toned `palette` directly rather than sinking it twice.
    val deep = remember(palette, back, isDark) {
        if (isDark) palette.map { lerp(it, back, FIELD_DARK_PALETTE_SINK_EXTRA) } else palette
    }
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
            intensity = amp(kind),
            noise = 0.28f,
            shape = GrainGradientShape.Corners,
            speed = s(0.3f),
            sizing = sizing,
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
            sizing = sizing,
        )
        // ARCTIC � aurora curtains twisting over ice strata. Low twist, high softness, gentle noise.
        FolioFieldKind.Swirl -> Swirl(
            modifier = modifier,
            colorBack = back,
            colors = deep.cycle(3),
            bandCount = 5f,
            twist = 0.14f,
            center = 0.25f,
            proportion = 0.5f,
            softness = 0.6f,
            noiseFrequency = 0.4f,
            noise = 0.22f,
            speed = s(0.3f),
            sizing = sizing,
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
            sizing = sizing,
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
            intensity = amp(kind),
            bloom = amp(kind),
            speed = s(0.5f),
            sizing = sizing,
        )

        // --- Opt-in families (per-theme override picker). Each still eats this theme's palette. ---
        FolioFieldKind.DotGrid -> DotGrid(
            modifier = modifier,
            colorBack = back,
            colorFill = palette.first(),
            colorStroke = palette.cycle(2).last(),
            size = 2.4f,
            gapX = 34f,
            gapY = 34f,
            // The grid "breathes": the depth of the dots' fade rides the slow phase, which is what
            // the picker's description always claimed of this family.
            sizeRange = 0.5f,
            opacityRange = 0.5f * (0.5f + 0.5f * sin(tickPhase * TAU).toFloat()),
            sizing = sizing,
        )
        FolioFieldKind.NeuroNoise -> NeuroNoise(
            modifier = modifier,
            colorBack = back,
            colorMid = palette.cycle(2).last(),
            colorFront = palette.first(),
            brightness = amp(kind),
            contrast = 0.35f,
            speed = s(0.5f),
            sizing = sizing,
        )
        FolioFieldKind.SimplexNoise -> SimplexNoise(
            modifier = modifier,
            colors = (listOf(back) + palette).cycle(4),
            stepsPerColor = 2,
            softness = 0.4f,
            speed = s(0.3f),
            sizing = sizing,
        )
        // --- Image-filter families ------------------------------------------------------------
        // See [FILTER_SIZING] and [filterSource] for why these six are shaped differently from
        // every branch above: they are not procedural composables, they are *image filters*, and
        // a filter with nothing to filter draws nothing at all.
        // Generative (`isImage` defaults to false): the shape is built from `getObjectUV`, not
        // from `u_image`, so the content is only there to make the layer renderable � a flat fill
        // is enough and a ramp would be thrown away.
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
        // The one static filter. The shader declares no `u_time`, so it cannot move, and the
        // public wrapper forwards no `speed` — which meant it ran the library's vsync clock
        // forever to re-render an image that cannot change. Hosted with the clock off and its
        // source frozen, so it resolves to one shader pass per theme rather than one per
        // frame. See FolioStaticField.android.kt for the mechanism and the fallback.
        //
        // The source is frozen deliberately: handing the filter the live silk drift was the
        // only way to make it appear to move, but it also forced the filter to re-run on
        // every frame of that drift — full-screen, at refresh rate.
        //
        // PaperTexture is not here at all, and that is the user's verdict rather than a
        // tuning call: the paper field was reported as making the app unusable across five
        // rounds of fixes, and unlike halftone its cost was structural — per fragment a
        // 3-tap roughness fbm, two 9-cell crumple lattices, a 4-octave fibre gradient
        // sampled four times, two 15-iteration fold searches and a 9-cell drop lattice, by a
        // wide margin the dearest shader in the set. Halftone stays: it was never the report.
        FolioFieldKind.HalftoneDots -> Box(
            modifier
                .folioStaticHalftoneDots(
                    colorBack = back,
                    colorFront = deep.first(),
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
            roundness = PULSING_BORDER_ROUNDNESS,
            spotSize = PULSING_BORDER_SPOT_SIZE,
            pulse = PULSING_BORDER_PULSE,
            speed = s(0.7f),
            sizing = sizing,
        )
        FolioFieldKind.StaticMeshGradient -> StaticMeshGradient(
            modifier = modifier,
            colors = (listOf(back) + deep).cycle(5),
            mixing = 0.93f,
            sizing = sizing,
        )
        FolioFieldKind.StaticRadialGradient -> StaticRadialGradient(
            modifier = modifier,
            colorBack = back,
            colors = deep.cycle(3),
            radius = 0.85f,
            falloff = 0.3f,
            mixing = 0.5f,
            sizing = sizing,
        )
        FolioFieldKind.Warp, FolioFieldKind.Voronoi, FolioFieldKind.MeshGradient,
        FolioFieldKind.ColorPanels, FolioFieldKind.Heatmap, FolioFieldKind.LiquidMetal,
        FolioFieldKind.Metaballs, FolioFieldKind.Waves, FolioFieldKind.Water,
        FolioFieldKind.Spiral, FolioFieldKind.PerlinNoise, FolioFieldKind.SmokeRing ->
            error("$kind must use FolioDesignedField")
    }
}

actual fun folioThemeFieldSupported(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
