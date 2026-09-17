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
import com.folio.reader.ui.theme.DAYLIGHT_RIM_TINT_MAX
import com.folio.reader.ui.theme.DAYLIGHT_WASH_ALPHA_MAX
import com.folio.reader.ui.theme.FolioAmbient
import com.folio.reader.ui.theme.FolioAtmosphere
import com.folio.reader.ui.theme.FolioDaylight
import com.folio.reader.ui.theme.FolioShapes
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens
import com.folio.reader.ui.theme.LocalFolioAmbient
import com.folio.reader.ui.theme.LocalFolioDaylight
import com.folio.reader.ui.theme.ambientPoolDrift
import com.folio.reader.ui.theme.atmosphere
import com.folio.reader.ui.theme.lightDirection
import com.folio.reader.ui.theme.panelFill
import com.folio.reader.ui.theme.rememberGlassTick
import com.folio.reader.ui.theme.surfaceOpacity
import com.folio.reader.ui.theme.swungBy
import kotlin.math.abs
import kotlin.math.min

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
 * The surface's own outline as a [Path], so a directional sheen can be painted
 * *inside* the shape rather than over its bounding box.
 *
 * `Shape.createPath` is not public in Compose 1.7 — the interface exposes only
 * [Shape.createOutline] — and `Outline.Rounded`'s own path accessor is internal,
 * so the rounded case is rebuilt from its [RoundRect]. [CacheDrawScope] is a
 * [Density], which is what `createOutline` asks for.
 */
private fun CacheDrawScope.shapePath(shape: Shape): Path =
    when (val outline = shape.createOutline(size, layoutDirection, this)) {
        is Outline.Rounded -> Path().apply { addRoundRect(outline.roundRect) }
        is Outline.Generic -> outline.path
        is Outline.Rectangle -> Path().apply { addRect(outline.rect) }
    }

/**
 * One ambient pool's resting geometry as fractions of the field, plus the
 * horizontal side it represents so daylight knows which pool the sun is on.
 * Centres sit outside the bounds and radii are large multiples of the width so no
 * pool ever shows an edge — unchanged from before; daylight only drifts them and
 * re-weights their alpha.
 */
private data class DaylightPool(
    val fx: Float,
    val fy: Float,
    val fr: Float,
    val index: Int,
    val sideX: Float,
)

private val DAYLIGHT_POOLS = listOf(
    DaylightPool(-0.15f, -0.10f, 1.15f, 0, -1f),
    DaylightPool(1.10f, 0.28f, 0.95f, 1, +1f),
    DaylightPool(0.35f, 1.15f, 1.30f, 2, 0f),
)

/**
 * How far the pools drift toward the anti-sun edge, as a fraction of width. Small
 * on purpose: this shifts the page's temperature, it does not slide the
 * background. Scaled by intensity in the caller, so at night there is no drift.
 */
private const val POOL_DRIFT = 0.12f

/**
 * How much the far pool recedes while the sun-side one holds. The facing term is
 * capped in the caller so no alpha ever exceeds `atmos.poolAlpha` — daylight may
 * dim a pool, never brighten it past its design.
 */
private const val POOL_FACING = 0.45f

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
        lerp(atmos.rimLight, accent, 0.35f).copy(alpha = atmos.rimLight.alpha)
    } else {
        atmos.rimLight
    }
    // The sun colours the catch; it never replaces it. Capped at
    // DAYLIGHT_RIM_TINT_MAX and scaled by intensity, so the rim stays the one the
    // atmosphere designed — merely warmed or cooled. At intensity 0 (Neutral,
    // night) the tint is skipped outright, so the rim is byte-for-byte the
    // palette's own with no Oklab round-trip to nudge it a sub-step off.
    val tint = DAYLIGHT_RIM_TINT_MAX * daylight.intensity
    val light = if (tint <= 0f) {
        accentLight
    } else {
        lerp(accentLight, daylight.temperature, tint).copy(alpha = accentLight.alpha)
    }
    val shade = atmos.rimShade
    // §17: the living light, read in the draw phase only (FolioShimmer
    // precedent) so the travelling sheen costs one drawing pass, never a
    // recomposition. The still-room default keeps the identity byte-for-byte.
    val ambient = LocalFolioAmbient.current
    return this
        .shadow(
            elevation = atmos.scaled(elevation),
            shape = shape,
            ambientColor = atmos.shadowAmbient,
            spotColor = atmos.shadowSpot,
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
                // frame for anything cached in composition.
                val (start, end) = daylightGradient(size, daylight, ambient.value)
                val sheen = Brush.linearGradient(
                    0f to shade.copy(alpha = shade.alpha * 0.5f),
                    0.55f to Color.Transparent,
                    1f to light.copy(alpha = light.alpha * 0.55f),
                    start = start,
                    end = end,
                )
                drawPath(path, sheen)
            }
        }
        .border(1.dp, Brush.verticalGradient(listOf(light, shade)), shape)
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
    // lerp so neutral daylight is byte-for-byte the palette's rim.
    val tint = DAYLIGHT_RIM_TINT_MAX * daylight.intensity
    val light = if (tint <= 0f) {
        atmos.rimLight
    } else {
        lerp(atmos.rimLight, daylight.temperature, tint).copy(alpha = atmos.rimLight.alpha)
    }
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
 * Glass over content: bars, floating navigation, sheets. Near-opaque by default on
 * purpose — Android cannot blur a separate native surface (the reader page), so
 * translucency alone would let text bleed through at full contrast. The glass is
 * carried by the rim and the sheen.
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
                backgroundColor = FolioTheme.colors.background,
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
 * low-alpha colour pools drawn from the palette's accent roles, so the background
 * has a temperature and a direction of light instead of one flat fill.
 *
 * Static by default: this is atmosphere, not motion. One drawing pass via
 * [drawWithCache]; nothing recomposes per frame. §17's living light adds one
 * exception — the pools breathe. Their centres drift a few percent of the width
 * on the ambient's slow cycles (draw-phase state reads, so still one pass),
 * which is what gives a resting page the slow shift of temperature that liquid
 * chrome needs behind it to read as material. Under reduce-motion, or in an
 * un-provided tree, the pools sit exactly where they always did.
 */
@Composable
fun Modifier.folioField(
    /** Optional cover-derived hue that tints the top of the page. */
    tint: Color? = null,
    daylight: FolioDaylight = LocalFolioDaylight.current,
): Modifier {
    val atmos = FolioTheme.atmosphere
    val pools = atmos.pools
    val poolAlpha = atmos.poolAlpha
    val top = if (tint != null) lerp(atmos.fieldTop, tint, 0.16f) else atmos.fieldTop
    val bottom = atmos.fieldBottom
    // The sun's horizontal pull, scaled by intensity: the whole page's temperature
    // follows the day. At night (intensity ~0) this is 0, so the field is the
    // palette's own with its pools exactly where they always were.
    val shift = daylight.azimuth * daylight.intensity
    // §17: the living light, read in the draw phase only.
    val ambient = LocalFolioAmbient.current
    return this.drawWithCache {
        val w = size.width
        val h = size.height
        val field = Brush.verticalGradient(listOf(top, bottom))
        // Drift toward the anti-sun edge: that swings the sun-side pool into view
        // while the far pool recedes off the other side. Centres still rest outside
        // the bounds and radii are still large multiples of the width, so no pool
        // ever shows an edge.
        val drift = -shift * POOL_DRIFT
        onDrawBehind {
            drawRect(field)
            // The ambient displacement is bounded by AMBIENT_DRIFT_MAX of the
            // width, on top of the sun's own drift, so the two never conspire to
            // walk a pool's centre somewhere the geometry above did not design for.
            val (driftX, driftY) = ambientPoolDrift(ambient.value)
            DAYLIGHT_POOLS.forEach { pool ->
                val color = pools.getOrNull(pool.index) ?: return@forEach
                // The pool on the sun's side holds at poolAlpha; the far side
                // recedes. Capped, so daylight may dim a pool but never lift it
                // past the atmosphere's own alpha ceiling.
                val facing = pool.sideX * shift
                val alpha = (poolAlpha * (1f + POOL_FACING * facing)).coerceIn(0f, poolAlpha)
                val center = Offset((pool.fx + drift + driftX) * w, (pool.fy + driftY) * h)
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
        }
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
 * [folioPressable]'s scale-only response. Two things happen while pressed:
 *
 *  - the rim **flashes**: the outline brightens with the surface's own rim light
 *    toward ~1.5× the resting bevel and decays on release — the specular a
 *    finger pressing near a pane edge would catch;
 *  - a **tick** lands on settle ([rememberGlassTick]; nav capsule and sheet
 *    handles only — nowhere else, or every press in the app becomes noise).
 *
 * The third term the design asked for — corner softening while pressed — needs
 * `GraphicsLayer.shape`, which the pinned Compose (1.7.3) does not expose; it is
 * deliberately not approximated with a recomposing clip. Honours reduce-motion:
 * every term collapses to identity.
 */
@Composable
fun Modifier.folioGlassPress(
    interactionSource: MutableInteractionSource,
    shape: Shape,
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
        .drawWithCache {
            if (!caps.specular) {
                onDrawBehind { /* un-capable platforms keep today's press */ }
            } else {
                val path = shapePath(shape)
                val rim = atmos.rimLight
                onDrawBehind {
                    val fraction = if (motion) press else 0f
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
