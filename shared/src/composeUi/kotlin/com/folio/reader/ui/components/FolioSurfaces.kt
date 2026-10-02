package com.folio.reader.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.folio.reader.ui.theme.DAYLIGHT_POOLS
import com.folio.reader.ui.theme.DAYLIGHT_RIM_TINT_MAX
import com.folio.reader.ui.theme.DAYLIGHT_WASH_ALPHA_MAX
import com.folio.reader.ui.theme.FolioAmbient
import com.folio.reader.ui.theme.FolioAtmosphere
import com.folio.reader.ui.theme.FolioPressFocal
import com.folio.reader.ui.theme.folioPressFocalOrigin
import com.folio.reader.ui.theme.FolioDaylight
import com.folio.reader.ui.theme.FolioShapes
import com.folio.reader.ui.theme.FolioSignature
import com.folio.reader.ui.theme.signatureHash
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens
import com.folio.reader.ui.theme.LocalFolioAmbient
import com.folio.reader.ui.theme.LocalFolioDaylight
import com.folio.reader.ui.theme.ambientPoolDrift
import com.folio.reader.ui.theme.atmosphere
import com.folio.reader.ui.theme.cosmic
import com.folio.reader.ui.theme.CosmicSkyIntensity
import com.folio.reader.ui.theme.CosmicMotion
import com.folio.reader.ui.theme.rememberCosmicIntensity
import com.folio.reader.ui.theme.sky
import com.folio.reader.ui.theme.fieldColors
import com.folio.reader.ui.theme.fieldDrift
import com.folio.reader.ui.theme.lightDirection
import com.folio.reader.ui.theme.poolAlphaAt
import com.folio.reader.ui.theme.poolCenterAt
import com.folio.reader.ui.theme.panelFill
import com.folio.reader.ui.theme.rememberGlassTick
import com.folio.reader.ui.theme.folioSkyShader
import com.folio.reader.ui.theme.rememberMotionEnabled
import com.folio.reader.ui.theme.rememberShaderSupported
import com.folio.reader.ui.theme.rememberSlowPhases
import com.folio.reader.ui.theme.surfaceOpacity
import com.folio.reader.ui.theme.swungBy
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Folio's material system.
 *
 * Five materials, each with a job. A screen reads as art-directed when surfaces
 * *disagree* — one thing floats, one thing is pressed in, one thing is only type
 * on the page. Uniform cards are what made the previous build read as scaffolding.
 *
 * | Material | Reads as | Use for |
 * |---|---|---|
 * | [folioRaised] | floating above the page | heroes, the one card that matters |
 * | [folioPanel] | resting on the page | grouped content |
 * | [folioSunken] | pressed into the page | wells: charts, heatmaps, inputs |
 * | [folioVeil] | glass over content | bars, nav, sheets |
 * | *(nothing)* | type directly on the field | most rows and lists |
 *
 * The lighting is consistent: one source, now tracked to the reader's local hour
 * by [FolioDaylight] — above and leading at the day's neutral, swinging to
 * wherever the sun actually is across the day. Raised things catch it on the lit
 * edge and occlude opposite; sunken things do the exact inverse. That single rule
 * makes depth legible without putting a shadow on everything. [FolioDaylight.Neutral]
 * (sun overhead, intensity 0) collapses every surface here back to the fixed
 * top-light they had before the room learned the time, so an un-lit tree — a test,
 * a preview — is unchanged.
 */

/** Elevation is a token, not a literal, and scales per atmosphere. */
private fun FolioAtmosphere.scaled(dp: Dp): Dp = dp * shadowScale

/**
 * The gradient axis the day's light falls along, as (start, end) offsets in the
 * surface's own pixels: start on the anti-sun edge, end on the sun-facing edge.
 * Taken from [FolioDaylight.lightDirection], whose horizontal push is scaled by
 * elevation, so after dark this is straight bottom→top — the fixed vertical
 * gradient these materials always had. The span is the box's projection onto
 * the direction (its support function), so the ramp runs corner to corner whatever
 * the angle and never foreshortens into a band.
 *
 * [ambient] is §17's living light: it swings the azimuth around wherever the
 * hour put it (see [ambientAzimuth]), which is what makes a resting surface's
 * sheen slowly travel instead of sitting still. The default is the neutral
 * identity, so callers that do not pass it — including every test and preview
 * — get today's byte-for-byte axis.
 */
internal fun daylightGradient(
    size: Size,
    daylight: FolioDaylight,
    ambient: FolioAmbient = FolioAmbient.Neutral,
): Pair<Offset, Offset> {
    val (dx, dy) = daylight.swungBy(ambient).lightDirection()
    val span = size.width * abs(dx) + size.height * abs(dy)
    val center = Offset(size.width * 0.5f, size.height * 0.5f)
    val reach = Offset(dx * span * 0.5f, dy * span * 0.5f)
    return (center - reach) to (center + reach)
}

/**
 * A rim colour warmed or cooled toward the sun, exactly as every lit edge in the
 * app does it: capped at [DAYLIGHT_RIM_TINT_MAX] and scaled by intensity, the
 * original [base] alpha preserved. At intensity 0 (Neutral, night) the tint is
 * skipped outright, so the edge is byte-for-byte the palette's own with no Oklab
 * round-trip to nudge it a sub-step off.
 *
 * The raised rim, the sunken caught-wall and the pane's new daylight hairline all
 * read it, so the one "a sun tints a rim, it never replaces it" rule lives in one
 * place (Rule 3) and the audit/guard can sample the same function the surfaces draw.
 */
internal fun daylightRimTint(base: Color, daylight: FolioDaylight): Color {
    val tint = DAYLIGHT_RIM_TINT_MAX * daylight.intensity
    return if (tint <= 0f) base else lerp(base, daylight.temperature, tint).copy(alpha = base.alpha)
}

/**
 * The surface's own outline as a [Path], so a directional sheen can be painted
 * *inside* the shape rather than over its bounding box.
 *
 * `Shape.createPath` is not public in Compose 1.7 — the interface exposes only
 * [Shape.createOutline] — and `Outline.Rounded`'s own path accessor is internal,
 * so the rounded case is rebuilt from its [RoundRect]. [CacheDrawScope] is a
 * [Density], which is what `createOutline` asks for.
 *
 * Internal rather than private because [folioThemeRim] traces the same outline and
 * must not fork a second copy of this rounded-case workaround (Rule 3).
 */
internal fun CacheDrawScope.shapePath(shape: Shape): Path =
    when (val outline = shape.createOutline(size, layoutDirection, this)) {
        is Outline.Rounded -> Path().apply { addRoundRect(outline.roundRect) }
        is Outline.Generic -> outline.path
        is Outline.Rectangle -> Path().apply { addRect(outline.rect) }
    }


/**
 * Floating surface. The highest material — at most one or two per screen.
 *
 * [accent] mixes into the light catch, so a hero lit by a book cover reports that
 * cover's colour at its edge. That is what starts making the cover behave like an
 * object in the room rather than a picture in a box. [daylight] then decides
 * *which* edge that is and how warm the catch runs: the rim gradient is laid along
 * the sun's actual direction and tinted a small fraction toward its colour, so a
 * 7am card is caught from the leading edge in amber and a 6pm card from the
 * trailing edge.
 */
@Composable
fun Modifier.folioRaised(
    shape: Shape = FolioShapes.card,
    elevation: Dp = FolioTokens.elevationRaised,
    accent: Color? = null,
    fill: Color? = null,
    daylight: FolioDaylight = LocalFolioDaylight.current,
): Modifier {
    val atmos = FolioTheme.atmosphere
    val base = fill ?: atmos.raisedFill
    val accentLight = if (accent != null) {
        lerp(atmos.rimLight, accent, 0.16f).copy(alpha = atmos.rimLight.alpha)
    } else {
        atmos.rimLight
    }
    // The sun colours the catch; it never replaces it. Capped at
    // DAYLIGHT_RIM_TINT_MAX and scaled by intensity, so the rim stays the one the
    // atmosphere designed — merely warmed or cooled. At intensity 0 (Neutral,
    // night) the tint is skipped outright (see daylightRimTint).
    val light = daylightRimTint(accentLight, daylight)
    val shade = atmos.rimShade
    // §17: the living light, read in the draw phase only (FolioShimmer
    // precedent) so the travelling sheen costs one drawing pass, never a
    // recomposition. The still-room default keeps the identity byte-for-byte.
    val ambient = LocalFolioAmbient.current
    // A pane (translucent fill) shows the lit room through itself, and the room plus the
    // caller's themed rim already give it depth. The directional sheen and the 1dp
    // gradient border were drawn for an *opaque* raised slab; stacked on a pane they laid
    // a bright rim and a darker perimeter band just inside it — two nested rounded rects,
    // the "box within the card" that showed on every raised surface. So a pane keeps only
    // its fill, shadow and clip; an opaque raised surface keeps the full treatment.
    val pane = base.alpha < 1f
    return this
        .then(
            // A translucent pane lets its *own* elevation shadow show through from
            // behind — Android occludes a shadow by the caster's alpha, so a 55% pane
            // passes ~45% of the shadow, densest just inside the silhouette. That is the
            // darker rounded band hugging the edge — the "box within the card" — and it
            // is on every raised pane because every one is elevated. A pane does not need
            // a cast shadow to float: the room shows through it and the themed rim rings
            // it. Opaque raised surfaces keep their shadow.
            if (pane) Modifier
            else Modifier.shadow(
                elevation = atmos.scaled(elevation),
                shape = shape,
                ambientColor = atmos.shadowAmbient,
                spotColor = atmos.shadowSpot,
            ),
        )
        .background(base, shape)
        .drawWithCache {
            val path = shapePath(shape)
            onDrawBehind {
                // Anti-sun edge occludes, sun-facing edge catches. At neutral daylight
                // the sun is straight overhead, so this is the old top-light / bottom-
                // shade vertical gradient with the transparent stop in the same place.
                // The axis is recomputed here rather than cached so the ambient swing
                // moves it — a few float ops per frame, against a recomposition per
                // frame for anything cached in composition. Opaque surfaces only: a pane
                // gets its light from the room behind it, not a sheen painted on top.
                if (!pane) {
                    val (start, end) = daylightGradient(size, daylight, ambient.value)
                    val sheen = Brush.linearGradient(
                        0f to shade.copy(alpha = shade.alpha * 0.5f),
                        0.5f to Color.Transparent,
                        1f to light.copy(alpha = light.alpha * 0.55f),
                        start = start,
                        end = end,
                    )
                    drawPath(path, sheen)
                }
            }
        }
        .then(
            // A pane carries no cast shadow and no directional sheen (the room shows
            // through it, which is what the box fix restored). But removing them also
            // took paper's daylight *rim* with them, so a pane stopped reading the hour
            // at all. This puts a single flat, shadowless daylight-tinted hairline back
            // on the edge — the one cue a translucent pane can carry — tinted toward the
            // sun exactly like every other rim (daylightRimTint) and otherwise the
            // atmosphere's own hairline. Flat, 1dp, no gradient and no shadow, so the
            // "box within the card" cannot return.
            if (pane) Modifier.border(1.dp, daylightRimTint(atmos.hairline, daylight), shape)
            else Modifier.border(1.dp, Brush.verticalGradient(listOf(light, shade)), shape),
        )
        .clip(shape)
}

/**
 * Resting surface: grouped content that should read as *on* the page, not above
 * it. Deliberately quieter than [folioRaised] — a thin rim, a whisper of shadow,
 * no sheen. This is the material most cards should have had all along.
 */
@Composable
fun Modifier.folioPanel(
    shape: Shape = FolioShapes.card,
    accent: Color? = null,
): Modifier {
    val atmos = FolioTheme.atmosphere
    val colors = FolioTheme.colors
    val fill = if (atmos.isDark) {
        lerp(colors.surface, colors.background, 0.20f)
    } else {
        colors.surface
    }
    val rim = if (accent != null) {
        lerp(atmos.hairline, accent, 0.30f).copy(alpha = atmos.hairline.alpha + 0.14f)
    } else {
        atmos.hairline
    }
    return this
        .shadow(
            elevation = atmos.scaled(FolioTokens.elevationPanel),
            shape = shape,
            ambientColor = atmos.shadowAmbient,
            spotColor = atmos.shadowSpot,
        )
        .background(fill, shape)
        .border(1.dp, rim, shape)
        .clip(shape)
}

/**
 * Embedded surface: a well cut into the page. Inverted lighting — the lip occludes
 * on the sun-facing edge and the far wall catches, so which edge is dark follows
 * the sun ([FolioDaylight]) rather than always being the top.
 *
 * Charts, heatmaps and figure fields live here. The inversion is what stops a
 * chart from reading as one more card.
 */
@Composable
fun Modifier.folioSunken(
    shape: Shape = FolioShapes.inset,
    accent: Color? = null,
    daylight: FolioDaylight = LocalFolioDaylight.current,
): Modifier {
    val atmos = FolioTheme.atmosphere
    val tinted = if (accent != null) lerp(atmos.sunkenFill, accent, 0.07f) else atmos.sunkenFill
    // The caught wall takes the sun's temperature at the same rationed fraction as
    // the raised rim; the occluding lip stays the palette's own shade, because a
    // shadow is absence of light and the sun does not colour it. Tint 0 skips the
    // lerp so neutral daylight is byte-for-byte the palette's rim (daylightRimTint).
    val light = daylightRimTint(atmos.rimLight, daylight)
    val shade = atmos.rimShade
    // §17 living light, draw-phase read — see folioRaised.
    val ambient = LocalFolioAmbient.current
    return this
        .background(tinted, shape)
        .drawWithCache {
            val path = shapePath(shape)
            onDrawBehind {
                // Inverted along the same axis: light at the anti-sun edge, shade at
                // the sun edge. Overhead neutral sun → shade on top, light on bottom.
                val (start, end) = daylightGradient(size, daylight, ambient.value)
                val sheen = Brush.linearGradient(
                    0f to light.copy(alpha = light.alpha * 0.30f),
                    0.65f to Color.Transparent,
                    1f to shade.copy(alpha = shade.alpha * 0.9f),
                    start = start,
                    end = end,
                )
                drawPath(path, sheen)
            }
        }
        .clip(shape)
}

/**
 * A **cosmic content card**: real **liquid glass** (via [folioVeil]) that blurs the
 * cosmic field behind it, with a cosmic accent personality on top. Now that the field
 * carries actual nebula/stars (and is registered as a glass backdrop source), the glass
 * has something to refract — so this reads as frosted glass over space rather than the
 * flat "dull shade" a plain translucent pane gave.
 *
 * It delegates the glass to [folioVeil]: when the device allows blur and the screen
 * provides a backdrop, the fill steps down to its translucent glass tier and the field
 * blurs through; otherwise it falls back to a near-opaque pane so text stays legible.
 * On top of that it lays a diagonal accent wash and a characterful 1.5dp accent rim —
 * the "personality" — using the project's own tokens, no new hex. Rounded
 * [FolioShapes.card] by default (never an edge-bleed silhouette), which reads as smooth.
 */
@Composable
fun Modifier.folioCosmicCard(
    accent: Color? = null,
    shape: Shape = FolioShapes.card,
): Modifier {
    val atmos = FolioTheme.atmosphere
    val cosmic = FolioTheme.cosmic
    val acc = accent ?: cosmic.accent.primary
    // A diagonal accent wash from the top-start — personality with contrast, kept light
    // enough that it tints the glass rather than hiding what it refracts.
    val tint = acc.copy(alpha = if (atmos.isDark) 0.16f else 0.10f)
    // A characterful accent edge, pulled well toward the accent and lifted, 1.5dp.
    val rim = lerp(atmos.hairline, acc, 0.55f)
        .copy(alpha = (atmos.hairline.alpha + 0.38f).coerceAtMost(1f))
    return this
        .folioVeil(shape = shape)
        .background(Brush.linearGradient(listOf(tint, Color.Transparent)), shape)
        .border(1.5.dp, rim, shape)
        .clip(shape)
}

/**
 *
 * [fillAlpha] overrides that for glass that sits over *Compose* content only, where
 * a little transparency is what makes the surface read as glass rather than as a
 * panel: the floating nav capsule uses it so the page is visible through it.
 *
 * §16 liquid glass, when [LocalGlassCapabilities] allows and the screen provides a
 * [LocalGlassBackdrop]: the blur layer is **additive under** the existing fill —
 * the opacity knobs above still govern the fill alpha on top, so at 100% the
 * surface is a lid and the blur is invisible — but when the veil actually blurs,
 * the panel fill steps down to its glass tier
 * (see [com.folio.reader.ui.theme.glassTierAlpha]), because blur is what fixes
 * the text bleed-through the near-opaque default was calibrated against. Turning
 * the preference off restores the pre-glass modifier chain exactly. The specular
 * sheen follows [FolioDaylight] like every other material (a fixed vertical band
 * otherwise), a deterministic grain kills gradient banding when blur is
 * unavailable, and the hairline gains a light-facing inner rim — the edge bevel
 * that approximates lensing without a shader.
 */
@Composable
fun Modifier.folioVeil(
    shape: Shape = FolioShapes.card,
    elevation: Dp = FolioTokens.elevationVeil,
    fillAlpha: Float? = null,
    glass: GlassSpec = GlassSpec.Default,
): Modifier {
    val atmos = FolioTheme.atmosphere
    val caps = LocalGlassCapabilities.current
    val backdrop = LocalGlassBackdrop.current
    // glass.blurEnabled lets a caller hold the blur off for the frames where the
    // registered backdrop still describes the screen we just left — see GlassSpec.
    // Everything downstream reads this one boolean, so the blur layer and the fill
    // tier can never disagree about whether the surface is currently glassy.
    val blurred = caps.blur && backdrop != null && glass.blurEnabled
    // A caller that names its own alpha has already accounted for its context
    // (the nav capsule, the reader's sheets); everything else is an app panel and
    // takes the panel preference, which is the alpha itself rather than a factor
    // on it — see FolioSurfaceOpacity. When this veil actually blurs, that fill
    // steps down to its glass tier: the blur is what fixes the bleed-through the
    // near-opaque default was calibrated against, so leaving the fill unchanged
    // buries the effect under a 97% lid and only the sheen and grain remain
    // visible — the "invisible glass" failure mode.
    val fill = atmos.veilFill.copy(alpha = fillAlpha ?: FolioTheme.surfaceOpacity.panelFill(blurred))
    val daylight = LocalFolioDaylight.current
    // §17 living light, draw-phase read — see folioRaised. This is the layer
    // that makes liquid glass read as liquid at rest: the specular highlight
    // travelling across the material is the one thing a still page cannot
    // give the blur, so the veil supplies it itself.
    val ambient = LocalFolioAmbient.current

    // The blur layer sits under the fill: rounded first (the effect draws over
    // its whole node bounds), then the blurred backdrop, then everything the
    // surface itself paints.
    val blurLayer = if (blurred) {
        Modifier
            .clip(shape)
            .folioGlassEffect(
                backdrop = backdrop,
                // What to show where the registered source has no content. This was
                // the palette's *unlit* `background`, but the room a veil floats in is
                // the field *after* the lamp and the cover's tint — so a surface over a
                // page that stops short of it (Home's list ends above the capsule)
                // painted a disc of a colour the surroundings disagree with, and read as
                // an opaque pill rather than as glass. The nav shell already dissolves
                // into `folioFieldBottom`; the blur has to fall back to the same ground.
                backgroundColor = folioFieldBottom(atmos),
                blurRadius = glass.blurRadius,
            )
    } else {
        Modifier
    }

    // Specular 2.0: the highlight tracks the virtual sun along the same axis
    // every other material uses, laid inside the shape rather than over its
    // bounding box. At neutral daylight the axis is bottom→top, which is the
    // fixed vertical sheen this material always had. §17: the axis recomputes
    // per draw against the living light, so the glass catches a highlight that
    // is always a little in transit.
    val sheenLayer = if (caps.specular) {
        Modifier.drawWithCache {
            val path = shapePath(shape)
            onDrawBehind {
                val (start, end) = daylightGradient(size, daylight, ambient.value)
                val light = atmos.rimLight
                val sheen = Brush.linearGradient(
                    0f to Color.Transparent,
                    0.62f to Color.Transparent,
                    1f to light.copy(alpha = light.alpha * 0.35f),
                    start = start,
                    end = end,
                )
                drawPath(path, sheen)
            }
        }
    } else {
        Modifier.background(
            brush = Brush.verticalGradient(
                listOf(atmos.rimLight.copy(alpha = atmos.rimLight.alpha * 0.35f), Color.Transparent),
            ),
            shape = shape,
        )
    }

    // Edge bevel: a light-facing inner rim — brighter on the sun side, nearly
    // absent on the anti-sun side — stroked inside the outline and clipped by
    // the shape, so it reads as the glass's own thickness. Same §17 draw-phase
    // ambient read as the sheen above it.
    val bevelLayer = if (caps.specular) {
        Modifier.drawWithCache {
            val path = shapePath(shape)
            onDrawBehind {
                val (start, end) = daylightGradient(size, daylight, ambient.value)
                val rim = Brush.linearGradient(
                    0f to Color.Transparent,
                    0.55f to atmos.rimLight.copy(alpha = atmos.rimLight.alpha * 0.18f),
                    1f to atmos.rimLight.copy(alpha = atmos.rimLight.alpha * 0.45f),
                    start = start,
                    end = end,
                )
                drawPath(path, rim, style = Stroke(width = 2.dp.toPx()))
            }
        }
    } else {
        Modifier
    }

    // Grain for glass that cannot blur (API floor, pref on, or the reader's
    // chrome over the WebView): a deterministic speckle that breaks up the
    // banding the tint gradients otherwise produce. Blurred glass gets Haze's
    // own noise instead.
    val grainLayer = if (caps.noise && !blurred) folioGlassGrain() else Modifier

    return this
        .shadow(
            elevation = atmos.scaled(elevation),
            shape = shape,
            ambientColor = atmos.shadowAmbient,
            spotColor = atmos.shadowSpot,
        )
        .then(blurLayer)
        .background(fill, shape)
        .then(sheenLayer)
        .then(bevelLayer)
        .then(grainLayer)
        .border(1.dp, atmos.hairline, shape)
        .clip(shape)
}

/**
 * The grain tile: 128×128 pixels of deterministic speckle, four gray levels
 * drawn as four grouped point calls so the tile is built exactly once per
 * process and shared by every glass surface. Mid-gray deviations composited
 * with [BlendMode.Overlay] lighten or darken the fill beneath by a couple of
 * percent — enough to break banding, invisible as texture.
 */
private val GLASS_GRAIN_TILE: ImageBitmap by lazy {
    val n = 128
    val bitmap = ImageBitmap(n, n)
    val canvas = androidx.compose.ui.graphics.Canvas(bitmap)
    // Seeded LCG (precedent: CoreRandom in StatsCoreSample): the grain must be
    // identical every frame or it becomes motion, which Rule 19 forbids.
    var seed = 0x5F3759DF
    fun next(): Int {
        seed = seed * 1_103_515_245 + 12_345
        return (seed ushr 16) and 0xFF
    }
    val groups = Array(4) { mutableListOf<Offset>() }
    for (y in 0 until n) {
        for (x in 0 until n) {
            groups[next() and 3].add(Offset(x.toFloat(), y.toFloat()))
        }
    }
    listOf(64f, 96f, 160f, 192f).forEachIndexed { level, gray ->
        val paint = androidx.compose.ui.graphics.Paint().apply {
            color = Color(gray / 255f, gray / 255f, gray / 255f)
            strokeWidth = 1f
        }
        canvas.drawPoints(PointMode.Points, groups[level], paint)
    }
    bitmap
}

/** The grain pass for un-blurred glass. Draw-phase only; nothing recomposes. */
internal fun Modifier.folioGlassGrain(alpha: Float = FolioTokens.glassGrainAlpha): Modifier =
    drawWithCache {
        val brush = ShaderBrush(
            ImageShader(GLASS_GRAIN_TILE, TileMode.Repeated, TileMode.Repeated),
        )
        onDrawBehind {
            drawRect(brush, alpha = alpha, blendMode = BlendMode.Overlay)
        }
    }

/**
 * The page itself. A vertical field wash plus up to three enormous, very
 * low-alpha colour pools, so the background has a temperature and a direction of
 * light instead of one flat fill.
 *
 * The room is lit by what is being read. [ambient] is the colour of the featured
 * cover, hoisted to the app root because this field is an *ancestor* of every
 * screen — a screen cannot provide a CompositionLocal upward to the ground it sits
 * on, so it writes into this state instead. It moves the whole field's hue and
 * re-weights the pools toward it at [FolioAtmosphere.fieldTintStrength], at pinned
 * luminance: the room may become any colour a cover is, and may only ever deepen
 * downward, so ink keeps every contrast ratio it had. See
 * [fieldColors] for the two invariants that make that true.
 *
 * Geometry comes from [FolioFieldModel], the same closed form the refraction gel
 * samples, so a card bending the page and the page itself cannot drift apart.
 *
 * Static by default: this is atmosphere, not motion. One drawing pass via
 * [drawWithCache]; nothing recomposes per frame. The ambient colour is read inside
 * the cache block, so a tab switch rebuilds the wash once instead of recomposing
 * the whole tree under it. §17's living light keeps its exception — the pools
 * breathe, their centres drifting a few percent of the width on the ambient's slow
 * cycles, read in the draw phase so even that costs no recomposition. Under
 * reduce-motion, or in an un-provided tree, the pools sit exactly where they always
 * did and the field is the palette's own.
 */
@Composable
fun Modifier.folioField(
    /** The colour of what is being read, hoisted from the screens above. */
    ambient: State<Color?>? = null,
    daylight: FolioDaylight = LocalFolioDaylight.current,
    strength: Float = FolioTheme.atmosphere.fieldTintStrength,
): Modifier {
    val atmos = FolioTheme.atmosphere
    // The sun's horizontal pull, scaled by intensity: the whole page's temperature
    // follows the day. At night (intensity ~0) this is 0, so the field is the
    // palette's own with its pools exactly where they always were.
    val shift = daylight.azimuth * daylight.intensity
    // The day's own colour and how much of it the room may wear, read once in
    // composition (daylight steps a minute at a time, so this rebuilds then, not per
    // frame). Bounded by DAYLIGHT_WASH_ALPHA_MAX x intensity — the envelope DaylightTest
    // proves cannot move a WCAG ratio off its floor — so at night it is 0 and at noon it
    // is a whisper of white, warm amber at golden hour, never a filter.
    val dayTemp = daylight.temperature
    val dayAlpha = (DAYLIGHT_WASH_ALPHA_MAX * daylight.intensity).coerceIn(0f, DAYLIGHT_WASH_ALPHA_MAX)
    // §17: the living light, read in the draw phase only.
    val ambientLight = LocalFolioAmbient.current
    // The per-theme guilloché signature and its slow breathing clock. The clock is
    // only attached when motion is enabled; under reduce-motion the figure is still
    // drawn, just parked (no phase travel) — see the draw block below.
    val sig = atmos.signature
    // On API 33+ the skyscape is rendered by the AGSL folioSkyShader (chained onto the
    // return below); on desktop/older devices that is a no-op and the portable Canvas
    // skyscape (drawSkyscape) stands in. Gated on the one capability flag so exactly one
    // of the two ever draws.
    val shaderSky = rememberShaderSupported()
    val signatureMotion = rememberMotionEnabled()
    // Two phases on the one house clock: a slow 30s drift (nebula + orbit) and a
    // few-second twinkle, so stars shimmer at a lively rate while the clouds still drift
    // imperceptibly. Same 10 Hz cost as the single clock it replaced.
    val sigPhases: State<List<Float>>? = if (signatureMotion) {
        rememberSlowPhases(listOf(FOLIO_SIGNATURE_PERIOD_MS, CosmicMotion.starTwinkleMs))
    } else {
        null
    }
    // The per-screen cosmic intensity, hoisted from the screens above (last-writer-wins,
    // default Atmospheric). Read in composition so a tab switch rebuilds the wash once;
    // the solved scalars scale every atmosphere term below. contentScale is capped at 1,
    // so no level can raise the content-band ceiling the contrast guards measure.
    val skyIntensity = rememberCosmicIntensity().sky()
    return this.drawWithCache {
        // Read here, not in composition: a new featured cover rebuilds the wash and
        // the pool colours once, and nothing under the root invalidates.
        val colors = fieldColors(atmos, ambient?.value, strength)
        val w = size.width
        val h = size.height
        val field = Brush.verticalGradient(listOf(colors.top, colors.bottom))
        val drift = fieldDrift(shift)
        // The per-theme skyscape, built once per size (and seed) in the cache block so
        // the stars/arcs/mist never reallocate per frame; only the twinkle/drift phase
        // is read per draw.
        val sky = buildSkyscape(size, sig)
        onDrawBehind {
            drawRect(field)
            // The ambient displacement is bounded by AMBIENT_DRIFT_MAX of the
            // width, on top of the sun's own drift, so the two never conspire to
            // walk a pool's centre somewhere the geometry above did not design for.
            val (driftX, driftY) = ambientPoolDrift(ambientLight.value)
            DAYLIGHT_POOLS.forEach { pool ->
                val color = colors.poolColors.getOrNull(pool.index) ?: return@forEach
                // The pool on the sun's side holds at poolAlpha; the far side
                // recedes. Capped, so daylight may dim a pool but never lift it
                // past the field's own alpha ceiling. The cosmic intensity dims it
                // further on Quiet screens (contentScale <= 1), never brighter.
                val alpha = poolAlphaAt(colors.poolAlpha, pool.sideX, shift) * skyIntensity.contentScale
                val (cx, cy) = poolCenterAt(pool, drift, driftX, driftY)
                val center = Offset(cx * w, cy * h)
                val radius = pool.fr * w
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(color.copy(alpha = alpha), Color.Transparent),
                        center = center,
                        radius = radius,
                    ),
                    radius = radius,
                    center = center,
                )
            }
            // The per-theme skyscape, laid over the pools and *under* the day's wash so
            // the hour still passes over everything. Vivid at the top/bottom edges,
            // falling to the alpha ceiling through the content band (see drawSkyscape),
            // so cardless text stays legible and folioClearing can finish the job. The
            // slow clock drives star twinkle and a few percent of drift; parked under
            // reduce-motion.
            val phase = sigPhases?.value?.getOrNull(0) ?: 0f
            val twinkle = sigPhases?.value?.getOrNull(1) ?: 0f
            if (!shaderSky) drawSkyscape(sky, sig, phase, skyIntensity, twinkle)
            // The day's own light, laid over the whole room as its last pass — the one
            // wire that lets every screen read as a time of day instead of a constant.
            // `fieldColorAtPoint` applies the identical mix, so a sampled tap agrees
            // with the pixel; at intensity 0 (night) this is skipped entirely.
            if (dayAlpha > 0f) drawRect(color = dayTemp.copy(alpha = dayAlpha))
        }
    }.folioSkyShader(enabled = shaderSky)
}

/**
 * The slow period of the skyscape's drift + twinkle. Slower than the hero mesh's
 * 18–30s band — a field this faint should read as a place, not a moving thing.
 */
internal const val FOLIO_SIGNATURE_PERIOD_MS = 30_000L
private const val SKY_TWO_PI = 6.2831855f
private const val SKY_STAR_COUNT = 90

/** One drawn star: position (px), radius, base alpha, twinkle phase, and whether it
 *  carries a sparkle cross at the edges. */
private class SkyStar(
    val x: Float,
    val y: Float,
    val r: Float,
    val alpha: Float,
    val phase: Float,
    val sparkle: Boolean,
)

/** One theme's sky geometry at a given pixel size — built once, drawn per frame. */
private class Skyscape(
    val w: Float,
    val h: Float,
    val stars: List<SkyStar>,
    val constellation: Path,
    val bodyCenter: Offset,
    val bodyRadius: Float,
    val orbits: List<Triple<Offset, Size, Float>>,
    val mountains: Path?,
)

/** 0..1 from the signature hash stream, salted so one seed yields many values. */
private fun skyRnd(seed: Int, salt: Int): Float =
    (signatureHash(seed * 31 + salt) ushr 8 and 0xFFFF) / 65535f

/**
 * Builds a theme's [Skyscape] from its [FolioSignature] seed at the field's size.
 * Portable `Path`/point geometry only (no AGSL), so Desktop/Skiko draws the same sky;
 * run inside `folioField`'s `drawWithCache`, so the scatter happens once per size.
 */
private fun buildSkyscape(size: Size, sig: FolioSignature): Skyscape {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) {
        return Skyscape(w, h, emptyList(), Path(), Offset.Zero, 0f, emptyList(), null)
    }
    val seed = sig.seed
    val minWH = min(w, h)
    val stars = ArrayList<SkyStar>(SKY_STAR_COUNT)
    for (i in 0 until SKY_STAR_COUNT) {
        val x = skyRnd(seed, i * 7 + 1) * w
        val y = skyRnd(seed, i * 7 + 2) * h
        val r = 0.6f + skyRnd(seed, i * 7 + 3) * 1.6f
        val alpha = 0.35f + skyRnd(seed, i * 7 + 4) * 0.65f
        val sparkle = skyRnd(seed, i * 7 + 5) > 0.90f
        stars.add(SkyStar(x, y, r, alpha, skyRnd(seed, i * 7 + 6) * SKY_TWO_PI, sparkle))
    }
    // A constellation: a hairline polyline through a handful of the upper stars.
    val constellation = Path()
    val topStars = stars.filter { it.y < h * 0.32f }.sortedBy { it.x }.take(7)
    topStars.forEachIndexed { i, s -> if (i == 0) constellation.moveTo(s.x, s.y) else constellation.lineTo(s.x, s.y) }
    // A far planet near a seeded top corner.
    val leftSide = skyRnd(seed, 101) > 0.5f
    val bodyCenter = Offset(
        (if (leftSide) 0.17f else 0.83f) * w,
        (0.16f + skyRnd(seed, 102) * 0.12f) * h,
    )
    val bodyRadius = minWH * (0.045f + skyRnd(seed, 103) * 0.02f)
    // One or two thin orbital ellipses.
    val orbits = ArrayList<Triple<Offset, Size, Float>>()
    orbits.add(Triple(bodyCenter, Size(bodyRadius * 3.4f, bodyRadius * 1.35f), -20f + skyRnd(seed, 104) * 40f))
    if (skyRnd(seed, 105) > 0.4f) {
        orbits.add(Triple(Offset(w * 0.5f, h * 0.11f), Size(w * 1.15f, h * 0.3f), -8f + skyRnd(seed, 106) * 16f))
    }
    // Light faces get a low mist/mountain horizon near the bottom.
    val mountains = if (!sig.isDark) {
        Path().apply {
            val baseY = h * 0.90f
            moveTo(0f, h)
            lineTo(0f, baseY)
            val peaks = 7
            for (p in 0..peaks) {
                val px = p.toFloat() / peaks * w
                val py = baseY - (0.02f + skyRnd(seed, 200 + p) * 0.07f) * h
                lineTo(px, py)
            }
            lineTo(w, h)
            close()
        }
    } else {
        null
    }
    return Skyscape(w, h, stars, constellation, bodyCenter, bodyRadius, orbits, mountains)
}

/**
 * Draws a built [Skyscape], intensity-scaled. The whole figure is **vivid at the top and
 * bottom edges and falls to the alpha ceiling through the content band** — a reader's
 * cardless text lives in the middle, so the sky gets out of its way there (and
 * `folioClearing` finishes the job), while the margins carry the weather. [phase] is the
 * slow clock (0 under reduce-motion → a static sky).
 *
 * [intensity] scales every term: `contentScale` (≤1) sets the content-band ceiling —
 * capped at 1 so no level ever renders brighter under text than the field the contrast
 * guards prove legible — while `edgeStrength` runs the margins bolder on Expressive and
 * fainter on Quiet. Stars thin by `starDensity`; the planet and horizon haze gate on
 * their flags. Draw-phase only; nothing recomposes.
 */
private fun DrawScope.drawSkyscape(
    sky: Skyscape,
    sig: FolioSignature,
    phase: Float,
    intensity: CosmicSkyIntensity = CosmicSkyIntensity.Atmospheric,
    twinkle: Float = phase,
) {
    val w = sky.w
    val h = sky.h
    if (w <= 0f || h <= 0f) return
    val c = intensity.contentScale
    val e = intensity.edgeStrength
    val ceil = sig.alphaCeiling * c
    // Present across the WHOLE field — the content band holds at 0.5*contentScale (so a
    // fainter level is uniformly fainter and the strongest is capped at the proven
    // ceiling) and the margins lift to +0.5*edge*edgeStrength, which is where Expressive
    // reads bolder without touching the content band. At Atmospheric (c=e=1) this is
    // exactly 0.5 .. 1.0, the falloff the field always had.
    fun falloff(yf: Float): Float {
        val top = ((0.50f - yf) / 0.50f).coerceIn(0f, 1f)
        val bot = ((yf - 0.50f) / 0.50f).coerceIn(0f, 1f)
        val edge = maxOf(top, bot)
        return 0.5f * c + 0.5f * edge * e
    }
    clipRect(0f, 0f, w, h) {
        // 1) Sky wash — a tinted sky from the top edge, and (both polarities) a second
        // wash lifting from the bottom, so the lower field carries the sky too. These are
        // pure top/bottom-edge effects (~0 in the content band); scaled by contentScale so
        // a Quiet screen's wash is fainter and no level exceeds the shipped look.
        drawRect(
            brush = Brush.verticalGradient(
                0f to sig.skyTop.copy(alpha = 0.90f * c),
                0.50f to sig.skyTop.copy(alpha = 0f),
                startY = 0f,
                endY = h,
            ),
        )
        drawRect(
            brush = Brush.verticalGradient(
                0.64f to sig.skyTop.copy(alpha = 0f),
                1f to sig.skyTop.copy(alpha = 0.45f * c),
                startY = 0f,
                endY = h,
            ),
        )
        if (!sig.isDark && intensity.drawsHaze) {
            // paper: a warm sunset haze lifting from the horizon, over the cool wash.
            drawRect(
                brush = Brush.verticalGradient(
                    0.55f to sig.glow.copy(alpha = 0f),
                    1f to sig.glow.copy(alpha = 0.26f * c),
                    startY = 0f,
                    endY = h,
                ),
            )
        }
        // 2) Drifting nebula/cloud pools spread top → middle → lower, so the clouds show
        // wherever the field is open, not only behind the (usually covered) top.
        val driftX = sin(phase) * w * 0.03f
        val pools = listOf(
            Triple(sig.nebulaWarm, Offset(w * 0.26f + driftX, h * 0.15f), minOf(w, h) * 0.95f),
            Triple(sig.nebulaCool, Offset(w * 0.76f - driftX, h * 0.44f), minOf(w, h) * 0.98f),
            Triple(sig.nebulaWarm, Offset(w * 0.30f + driftX, h * 0.80f), minOf(w, h) * 0.90f),
        )
        pools.forEach { (col, center, radius) ->
            val a = 0.22f * falloff(center.y / h)
            if (a > 0f) {
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(col.copy(alpha = a), Color.Transparent),
                        center = center,
                        radius = radius,
                    ),
                    radius = radius,
                    center = center,
                )
            }
        }
        // 3) The far planet (small, subtle — a distant body, not a sun) + its orbits.
        if (intensity.drawsPlanet) {
            val bf = falloff(sky.bodyCenter.y / h)
            if (bf > 0f && sky.bodyRadius > 0f) {
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(
                            sig.glow.copy(alpha = 0.34f * bf),
                            sig.glow.copy(alpha = 0.10f * bf),
                            Color.Transparent,
                        ),
                        center = sky.bodyCenter,
                        radius = sky.bodyRadius * 2.1f,
                    ),
                    radius = sky.bodyRadius * 2.1f,
                    center = sky.bodyCenter,
                )
            }
        }
        sky.orbits.forEach { (center, ovalSize, rot) ->
            val a = 0.22f * falloff(center.y / h) * intensity.orbitLineAlpha
            if (a > 0f) {
                rotate(rot + sin(phase) * 2f, center) {
                    drawOval(
                        color = sig.glow.copy(alpha = a),
                        topLeft = Offset(center.x - ovalSize.width / 2f, center.y - ovalSize.height / 2f),
                        size = ovalSize,
                        style = Stroke(width = maxOf(1f, 0.8.dp.toPx())),
                    )
                }
            }
        }
        // 4) Stars (ceiling through the band, brighter at the edges) + edge glints.
        // Thinned by starDensity on Quiet. The twinkle is staggered: a per-star phase AND
        // a per-star speed, swung at CosmicMotion's raised amplitude, so stars shimmer out
        // of sync (a few at a time) rather than pulsing as one. [twinkle] is the fast
        // few-second phase; 0 under reduce-motion → a static sky.
        val starCount = (sky.stars.size * intensity.starDensity).toInt().coerceIn(0, sky.stars.size)
        val twBase = CosmicMotion.twinkleBase
        val twAmp = CosmicMotion.twinkleAmplitude
        for (i in 0 until starCount) {
            val s = sky.stars[i]
            // Per-star speed 0.7..1.5 derived from its stable phase: different stars cycle
            // at different rates, which is what keeps the shimmer from reading as a pulse.
            val speed = 0.7f + (s.phase / SKY_TWO_PI) * 0.8f
            val tw = (twBase + twAmp * sin(twinkle * speed + s.phase)).coerceAtLeast(0f)
            val f = falloff(s.y / h)
            val a = ((ceil + (s.alpha - ceil).coerceAtLeast(0f) * f).coerceAtMost(s.alpha) * tw)
                .coerceIn(0f, 1f)
            if (a <= 0f) continue
            drawCircle(color = sig.star.copy(alpha = a), radius = s.r, center = Offset(s.x, s.y))
            // A soft gold/white glint on the brighter (sparkle) stars that swells to a
            // cross at the twinkle crest and vanishes in the trough.
            if (s.sparkle && f > 0f) {
                val glint = ((tw - twBase) / twAmp).coerceIn(0f, 1f)
                val sa = 0.55f * f * glint
                if (sa > 0.01f) {
                    val len = s.r * (3.5f + 2.5f * glint)
                    drawLine(sig.glow.copy(alpha = sa), Offset(s.x - len, s.y), Offset(s.x + len, s.y), strokeWidth = 1f)
                    drawLine(sig.glow.copy(alpha = sa), Offset(s.x, s.y - len), Offset(s.x, s.y + len), strokeWidth = 1f)
                }
            }
        }
        // 5) The constellation hairline.
        drawPath(sky.constellation, color = sig.star.copy(alpha = 0.22f * c), style = Stroke(width = 1f))
        // 6) The mist horizon (paper faces only).
        if (intensity.drawsHaze) {
            sky.mountains?.let { drawPath(it, color = (sig.mist ?: sig.star).copy(alpha = 0.38f * c)) }
        }
    }
}

/** Peak alpha of the paper radial a clearing paints over the motif. */
internal const val FOLIO_CLEARING_PEAK = 0.6f

/**
 * Soft paper clearing for cardless type-on-field.
 *
 * The per-theme signature ([folioField]) is faint by construction, but editorial
 * screens put real type *directly on the field* with no surface under it. A clearing
 * paints a soft paper-tinted radial — the field's own [FolioAtmosphere.fieldTop],
 * no border, no box — behind such a node, locally fading the motif to near-nothing
 * and giving the content a whisper of lift. Because a content node draws *after* the
 * ancestor field, this is purely local and needs no upward registry (same model as
 * [coverHalo]).
 *
 * Apply it to cardless content only (section heads, eyebrows, figures, empty states,
 * settings rows, ledger strips, detail metadata). Content already sitting on a
 * `folioPanel`/`folioRaised`/`folioSunken` surface must NOT use it — the paper radial
 * would read as a lighter blotch on the surface.
 */
@Composable
fun Modifier.folioClearing(strength: Float = 1f): Modifier {
    val atmos = FolioTheme.atmosphere
    val paper = atmos.fieldTop
    val peak = (FOLIO_CLEARING_PEAK * strength).coerceIn(0f, 1f)
    if (peak <= 0f) return this
    return this.drawBehind {
        val radius = maxOf(size.width, size.height) * 0.75f
        if (radius <= 0f) return@drawBehind
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(paper.copy(alpha = peak), paper.copy(alpha = 0f)),
                center = Offset(size.width * 0.5f, size.height * 0.5f),
                radius = radius,
            ),
        )
    }
}

/**
 * Opt-in full-surface daylight wash: one very-low-alpha sweep of the sun's colour
 * from its own edge to transparent, for a screen that wants the room's light laid
 * over the whole surface rather than only caught at the rims.
 *
 * Nothing calls it by default — it is a wash, so a surface asks for it or it does
 * not happen, and no existing call site shifts. The ceiling
 * ([DAYLIGHT_WASH_ALPHA_MAX], 0.06) times intensity keeps it in the same register
 * as `folioField`'s "~4%, only as air" gradient: low enough that it cannot move a
 * WCAG ratio off its floor, which `DaylightTest` pins. Under neutral daylight
 * (intensity 0) it is a no-op.
 */
@Composable
fun Modifier.daylightWash(
    daylight: FolioDaylight = LocalFolioDaylight.current,
    strength: Float = DAYLIGHT_WASH_ALPHA_MAX,
): Modifier {
    if (daylight.intensity <= 0f) return this
    val alpha = (strength * daylight.intensity).coerceIn(0f, DAYLIGHT_WASH_ALPHA_MAX)
    return this.drawWithCache {
        val (start, end) = daylightGradient(size, daylight)
        val brush = Brush.linearGradient(
            colors = listOf(daylight.temperature.copy(alpha = alpha), Color.Transparent),
            start = start,
            end = end,
        )
        onDrawBehind { drawRect(brush) }
    }
}

/**
 * Localized ambient light: the glow a bright cover throws onto the surface behind
 * it. Drawn *behind* the content and scaled to the composable's own bounds, so a
 * cover lights its neighbourhood and nothing else — one cover must never recolour
 * the app.
 */
fun Modifier.coverHalo(color: Color, strength: Float = 0.34f): Modifier = drawBehind {
    val radius = min(size.width, size.height) * 1.35f
    val center = Offset(size.width * 0.5f, size.height * 0.42f)
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = strength), Color.Transparent),
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )
}

/**
 * Press feedback with weight: the surface sinks toward the page rather than
 * flashing a ripple. 0.976 is deliberately small — enough to feel physical at card
 * scale, not enough to read as a bounce. Honours reduce-motion.
 */
@Composable
fun Modifier.folioPressable(
    interactionSource: MutableInteractionSource,
    scaleTo: Float = 0.976f,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val motion = com.folio.reader.ui.theme.rememberMotionEnabled()
    val scale by animateFloatAsState(
        targetValue = if (pressed && motion) scaleTo else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 900f),
        label = "pressScale",
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * §16 liquid press, for glass surfaces only — plain cards keep
 * [folioPressable]'s scale-only response. Three things happen while pressed:
 *
 *  - the rim **flashes**: the outline brightens with the surface's own rim light
 *    toward ~1.5× the resting bevel and decays on release — the specular a
 *    finger pressing near a pane edge would catch;
 *  - the surface **lights where the finger is** ([focal]): the same rim colour,
 *    gathered into a bloom centred on the press rather than spread evenly round the
 *    outline. This is the term that makes a glass surface answer *where* it was
 *    touched, and it needs no shader, so it runs from API 24 up;
 *  - a **tick** lands on settle ([rememberGlassTick]; nav capsule and sheet
 *    handles only — nowhere else, or every press in the app becomes noise).
 *
 * What is still missing is the fourth term the design asked for — corner softening
 * while pressed — which needs `GraphicsLayer.shape`, and the pinned Compose (1.7.3)
 * does not expose it. It is deliberately not approximated with a recomposing clip.
 *
 * Honours reduce-motion: every term collapses to identity, and so does the focal,
 * whose strength never leaves 0 when motion is off.
 */
@Composable
fun Modifier.folioGlassPress(
    interactionSource: MutableInteractionSource,
    shape: Shape,
    focal: FolioPressFocal? = null,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val motion = com.folio.reader.ui.theme.rememberMotionEnabled()
    val caps = LocalGlassCapabilities.current
    // A slight overshoot on release — liquid, not sloppy (damping ≈ 0.75).
    val press by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 700f),
        label = "glassPress",
    )
    val atmos = FolioTheme.atmosphere
    return this
        .then(
            if (caps.specular && focal != null) {
                Modifier.folioPressFocalOrigin(focal)
            } else {
                Modifier
            }
        )
        .drawWithCache {
            if (!caps.specular) {
                onDrawBehind { /* un-capable platforms keep today's press */ }
            } else {
                val path = shapePath(shape)
                val rim = atmos.rimLight
                onDrawBehind {
                    val fraction = if (motion) press else 0f
                    // The bloom is clipped for the same reason the rim's bloom is in
                    // FolioThemeRim: this surface is not necessarily clipped itself,
                    // and a radial fill would leak onto its neighbours.
                    val at = focal?.pixelPoint()
                    if (at != null && fraction > 0.001f) {
                        val radius = maxOf(size.width, size.height) * 0.62f
                        clipPath(path) {
                            drawCircle(
                                Brush.radialGradient(
                                    0f to rim.copy(alpha = rim.alpha * 0.42f * fraction),
                                    1f to rim.copy(alpha = 0f),
                                    center = at,
                                    radius = radius,
                                ),
                                radius = radius,
                                center = at,
                            )
                        }
                    }
                    if (fraction > 0.001f) {
                        // 0.45 → ~0.68 alpha at full press: the resting bevel's
                        // sun-side rim, 1.5× brighter.
                        drawPath(
                            path,
                            rim.copy(alpha = rim.alpha * 0.45f * fraction),
                            style = Stroke(width = 2.dp.toPx()),
                        )
                    }
                }
            }
        }
        .then(
            if (motion) {
                Modifier.pressTick(interactionSource)
            } else {
                Modifier
            }
        )
}

/**
 * One-call liquid press for glass surfaces: wires [rememberFolioPressFocal] into
 * [folioGlassPress] so a primary tile can opt into the §16 press-where-the-finger-is
 * bloom with a single modifier. The specular/no-op fallback already lives in
 * [folioGlassPress] — on a platform without the specular capability this is the
 * resting (scale-only) press — and reduce-motion collapses the focal to nothing, so
 * callers need not remember the focal or branch on capability themselves.
 *
 * Scope it to **primary** affordances (a hero's Continue, a feature tile), not every
 * row, so the focal stays a signature rather than noise.
 */
@Composable
fun Modifier.folioPressFocal(
    interactionSource: MutableInteractionSource,
    shape: Shape,
): Modifier {
    val focal = com.folio.reader.ui.theme.rememberFolioPressFocal(interactionSource)
    return this.folioGlassPress(interactionSource, shape, focal)
}

/** The settle tick, attached once per press. */
@Composable
private fun Modifier.pressTick(interactionSource: MutableInteractionSource): Modifier {
    val tick = rememberGlassTick()
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            if (interaction is PressInteraction.Release) {
                tick()
            }
        }
    }
    return this
}

/**
 * §16 shape morph: a glass sheet that enters reading as a capsule — its leading
 * sweep blown out toward [capsuleSweep] — and settles into its resting corners
 * over ~320ms. The construction cost is one modifier-chain rebuild per frame of
 * a one-shot enter transition (never scroll-linked, §13.3); reduce-motion jumps
 * straight to the resting shape.
 */
@Composable
fun rememberFolioSheetMorphShape(resting: RoundedCornerShape): Shape {
    val motion = com.folio.reader.ui.theme.rememberMotionEnabled()
    var value by remember { mutableFloatStateOf(if (motion) 1f else 0f) }
    LaunchedEffect(motion) {
        if (motion) {
            androidx.compose.animation.core.animate(
                initialValue = 1f,
                targetValue = 0f,
                animationSpec = androidx.compose.animation.core.tween(
                    320,
                    easing = androidx.compose.animation.core.FastOutSlowInEasing,
                ),
            ) { v, _ -> value = v }
        } else {
            value = 0f
        }
    }
    val sweep = androidx.compose.ui.unit.lerp(
        FolioTokens.radiusSheetSweep, FolioTokens.radiusSheetCapsule, value,
    )
    return RoundedCornerShape(
        topStart = if (resting.topStart != androidx.compose.foundation.shape.CornerSize(0.dp)) sweep else 0.dp,
        bottomStart = if (resting.bottomStart != androidx.compose.foundation.shape.CornerSize(0.dp)) sweep else 0.dp,
        topEnd = if (resting.topEnd != androidx.compose.foundation.shape.CornerSize(0.dp)) sweep else 0.dp,
        bottomEnd = if (resting.bottomEnd != androidx.compose.foundation.shape.CornerSize(0.dp)) sweep else 0.dp,
    )
}

/**
 * §16 elastic drag-to-dismiss for end-edge glass sheets: dragging toward the
 * screen edge follows the finger 1:1 up to the commit threshold, past it with
 * growing rubber-band resistance; release either commits the dismissal (a tick
 * lands, the sheet slides out, [onDismiss] fires) or springs home with a slight
 * overshoot — liquid, not sloppy (damping ≈ 0.75). Horizontal only, so the
 * sheets' vertical scrollers are untouched. Honours reduce-motion: settles snap.
 */
@Composable
fun Modifier.folioSheetDragToDismiss(onDismiss: () -> Unit): Modifier {
    val motion = com.folio.reader.ui.theme.rememberMotionEnabled()
    val tick = rememberGlassTick()
    val scope = rememberCoroutineScope()
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val offset = remember { mutableFloatStateOf(0f) }
    return this
        .graphicsLayer { translationX = offset.floatValue }
        .pointerInput(currentOnDismiss) {
            val maxPx = size.width.toFloat().coerceAtLeast(1f)
            val threshold = maxPx * 0.38f
            detectHorizontalDragGestures(
                onDragEnd = {
                    if (offset.floatValue >= threshold) {
                        tick()
                        scope.launch {
                            androidx.compose.animation.core.animate(
                                initialValue = offset.floatValue,
                                targetValue = maxPx,
                                animationSpec = if (motion) {
                                    androidx.compose.animation.core.tween(160)
                                } else {
                                    androidx.compose.animation.core.snap()
                                },
                            ) { v, _ -> offset.floatValue = v }
                            currentOnDismiss()
                            offset.floatValue = 0f
                        }
                    } else {
                        scope.launch {
                            androidx.compose.animation.core.animate(
                                initialValue = offset.floatValue,
                                targetValue = 0f,
                                animationSpec = androidx.compose.animation.core.spring(
                                    dampingRatio = 0.75f,
                                    stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow,
                                ),
                            ) { v, _ -> offset.floatValue = v }
                        }
                    }
                },
            ) { _, dragAmount ->
                val target = offset.floatValue + dragAmount
                offset.floatValue = when {
                    target <= threshold -> target.coerceAtLeast(0f)
                    // Rubber band: past the threshold each extra dp of drag buys
                    // less travel, asymptotically capped at the sheet's width.
                    else -> threshold +
                        (maxPx - threshold) *
                        (1f - 1f / (1f + (target - threshold) / (maxPx - threshold)))
                }
            }
        }
}

@Composable
fun rememberFolioInteraction(): MutableInteractionSource =
    remember { MutableInteractionSource() }

/**
 * Secondary-button (right-click) trigger for items whose actions live behind the
 * long-press context menu. A mouse cannot hold comfortably, so the platform's own
 * context gesture is the menu's other door; touch never reports the secondary
 * button, so this stays silent on phones and tablets.
 */
@Composable
fun Modifier.folioRightClick(onRightClick: () -> Unit): Modifier {
    val current by rememberUpdatedState(onRightClick)
    return pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                if (event.type == PointerEventType.Release && event.buttons.isSecondaryPressed) {
                    current()
                }
            }
        }
    }
}

/**
 * A hairline structural rule. Editorial layouts get their order from rules and
 * alignment, not from putting every group in a box.
 */
@Composable
fun FolioRule(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    thickness: Dp = 1.dp,
) {
    val atmos = FolioTheme.atmosphere
    Box(
        modifier
            .fillMaxWidth()
            .height(thickness)
            .background(
                Brush.horizontalGradient(
                    listOf(
                        (accent ?: atmos.hairline).copy(alpha = 0.55f),
                        atmos.hairline.copy(alpha = 0.10f),
                    ),
                ),
            ),
    )
}

/**
 * The one container that admits it is a container. Panel material, optional accent
 * rim — but no forced title bar, because a section usually reads better with its
 * heading *outside* the surface (see [FolioEyebrow]).
 */
@Composable
fun FolioPanel(
    modifier: Modifier = Modifier,
    shape: Shape = FolioShapes.card,
    accent: Color? = null,
    padding: Dp = FolioTokens.space3,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .folioPanel(shape, accent)
            .padding(padding),
        content = content,
    )
}
