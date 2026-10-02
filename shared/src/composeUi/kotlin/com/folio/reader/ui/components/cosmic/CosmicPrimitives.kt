package com.folio.reader.ui.components.cosmic

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import com.folio.reader.ui.theme.CosmicIntensity
import com.folio.reader.ui.theme.CosmicMotion
import com.folio.reader.ui.theme.CosmicScheme
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.cosmic
import com.folio.reader.ui.theme.rememberCosmicIntensity
import com.folio.reader.ui.theme.rememberMotionEnabled
import com.folio.reader.ui.theme.rememberSlowPhases
import com.folio.reader.ui.theme.signatureHash
import kotlin.math.cos
import kotlin.math.sin

/**
 * The reusable **foreground** cosmic primitives.
 *
 * The base atmosphere (nebula / star field / glow) is the root field's job
 * ([com.folio.reader.ui.components.folioField]); these are the celestial *accents* an
 * Expressive screen composes on top, in safe zones, where book/content stays dominant.
 * Every one is:
 *
 *  - **portable Canvas** — no AGSL, so Android and Desktop/Skiko draw the same thing;
 *  - **colour-tokenised** — defaults come from [CosmicScheme], so a primitive always
 *    matches the active theme and polarity and no raw hex reaches a call site;
 *  - **motion- and reduce-motion-aware** — phases ride the house idle slow clock
 *    ([rememberSlowPhases]); under reduce-motion every phase parks at 0 (a static final
 *    frame), read in the *draw* phase so animation costs no recomposition;
 *  - **safe-zone aware** — each takes a position/alignment, so a screen keeps it off the
 *    column where body text lives.
 *
 * Compose them inside a [CosmicScene], which gates the whole group on the screen's
 * intensity, or place them directly in a `Box` behind content.
 */

// ── Shared phase plumbing ─────────────────────────────────────────────────────────────

/**
 * Slow phases for a primitive, read in the draw phase. Frozen (all 0) under reduce-motion
 * so the primitive renders its static final frame; otherwise each entry advances 0..2π
 * once per its period on the shared 10 Hz clock.
 */
@Composable
private fun rememberCosmicPhases(periodsMs: List<Long>): State<List<Float>> {
    val motion = rememberMotionEnabled()
    return if (motion) {
        rememberSlowPhases(periodsMs)
    } else {
        remember(periodsMs) { mutableStateOf(periodsMs.map { 0f }) }
    }
}

/** 0..1 from the signature hash stream, salted so one seed yields many stable values. */
private fun rnd(seed: Int, salt: Int): Float =
    (signatureHash(seed * 31 + salt) ushr 8 and 0xFFFF) / 65535f

private const val TWO_PI = 6.2831855f

// ── Scene composer ───────────────────────────────────────────────────────────────────

/**
 * Groups foreground cosmic primitives and gates them on the screen's intensity: nothing
 * draws below [minIntensity] (default [CosmicIntensity.Expressive]), so the same screen
 * code quietly drops its celestial art when a user is on a calmer intensity. Lay content
 * over it in the same `Box`.
 */
@Composable
fun CosmicScene(
    modifier: Modifier = Modifier,
    minIntensity: CosmicIntensity = CosmicIntensity.Expressive,
    content: @Composable BoxScope.() -> Unit,
) {
    val intensity = rememberCosmicIntensity()
    if (intensity.ordinal < minIntensity.ordinal) return
    Box(modifier = modifier, content = content)
}

// ── Celestial glow ─────────────────────────────────────────────────────────────────────

/**
 * A soft radial glow that slowly **breathes** — a swell in and back out, never a flash.
 * The quietest primitive; good as a localized light behind a hero anchor or a focal.
 */
@Composable
fun CelestialGlow(
    modifier: Modifier = Modifier,
    color: Color = FolioTheme.cosmic.glow.primary,
    center: Offset = Offset(0.5f, 0.5f),
    radiusFraction: Float = 0.6f,
    peakAlpha: Float = 0.5f,
    breathing: Boolean = true,
) {
    val phases = rememberCosmicPhases(listOf(CosmicMotion.glowBreathMs))
    Canvas(modifier) {
        val p = phases.value.getOrElse(0) { 0f }
        val breath = if (breathing) 1f + CosmicMotion.glowBreathAmplitude * sin(p) else 1f
        val c = Offset(size.width * center.x, size.height * center.y)
        val radius = minOf(size.width, size.height) * radiusFraction * breath
        if (radius <= 0f) return@Canvas
        drawCircle(
            brush = Brush.radialGradient(
                0f to color.copy(alpha = (peakAlpha * breath).coerceIn(0f, 1f)),
                0.6f to color.copy(alpha = (peakAlpha * 0.3f * breath).coerceIn(0f, 1f)),
                1f to Color.Transparent,
                center = c,
                radius = radius,
            ),
            radius = radius,
            center = c,
        )
    }
}

// ── Star field + cluster ───────────────────────────────────────────────────────────────

/**
 * A scattered field of **staggered-twinkle** stars with a soft glint on the brighter
 * ones. Deterministic from [seed]; [density] scales the count, [twinkle] turns the
 * shimmer off for a still starfield. The twinkle is per-star phase + per-star speed, so
 * stars shimmer out of sync at [CosmicMotion.twinkleAmplitude] depth.
 */
@Composable
fun CosmicStarField(
    modifier: Modifier = Modifier,
    color: Color = FolioTheme.cosmic.artwork.star,
    glintColor: Color = FolioTheme.cosmic.glow.primary,
    count: Int = 48,
    density: Float = 1f,
    maxAlpha: Float = 0.9f,
    seed: Int = FolioTheme.cosmic.artwork.star.value.toInt(),
    twinkle: Boolean = true,
) {
    val phases = rememberCosmicPhases(listOf(CosmicMotion.starTwinkleMs))
    val n = (count * density).toInt().coerceAtLeast(0)
    Canvas(modifier) {
        val tw = if (twinkle) phases.value.getOrElse(0) { 0f } else Float.NaN
        for (i in 0 until n) {
            val x = rnd(seed, i * 7 + 1) * size.width
            val y = rnd(seed, i * 7 + 2) * size.height
            val r = 0.6f + rnd(seed, i * 7 + 3) * 1.8f
            val base = (0.3f + rnd(seed, i * 7 + 4) * 0.7f) * maxAlpha
            val sparkle = rnd(seed, i * 7 + 5) > 0.86f
            val ph = rnd(seed, i * 7 + 6) * TWO_PI
            val mult = if (tw.isNaN()) {
                1f
            } else {
                val speed = 0.7f + rnd(seed, i * 7 + 6) * 0.8f
                (CosmicMotion.twinkleBase + CosmicMotion.twinkleAmplitude * sin(tw * speed + ph))
                    .coerceAtLeast(0f)
            }
            val a = (base * mult).coerceIn(0f, 1f)
            if (a <= 0f) continue
            drawCircle(color = color.copy(alpha = a), radius = r, center = Offset(x, y))
            if (sparkle && !tw.isNaN()) {
                val glint = (((mult - CosmicMotion.twinkleBase) / CosmicMotion.twinkleAmplitude))
                    .coerceIn(0f, 1f)
                val sa = 0.5f * glint
                if (sa > 0.01f) {
                    val len = r * (3.5f + 2.5f * glint)
                    drawLine(glintColor.copy(alpha = sa), Offset(x - len, y), Offset(x + len, y), strokeWidth = 1f)
                    drawLine(glintColor.copy(alpha = sa), Offset(x, y - len), Offset(x, y + len), strokeWidth = 1f)
                }
            }
        }
    }
}

/** A tight knot of stars — a star field confined to a small area, for a focal accent. */
@Composable
fun CosmicStarCluster(
    modifier: Modifier = Modifier,
    color: Color = FolioTheme.cosmic.artwork.star,
    count: Int = 14,
    seed: Int = FolioTheme.cosmic.artwork.star.value.toInt() * 3 + 7,
) = CosmicStarField(modifier = modifier, color = color, count = count, maxAlpha = 0.8f, seed = seed)

// ── Planet / moon ───────────────────────────────────────────────────────────────────────

/**
 * A soft lit disc with a glow, an optional thin ring, and a gentle terminator (the lit
 * limb opposite a subtle shadow). Parked by [center] (fractions). Static — a planet that
 * visibly moves is wrong; its only life is the ring, if present.
 */
@Composable
fun CosmicPlanet(
    modifier: Modifier = Modifier,
    color: Color = FolioTheme.cosmic.glow.primary,
    ringColor: Color = FolioTheme.cosmic.accent.secondary,
    center: Offset = Offset(0.82f, 0.18f),
    radiusFraction: Float = 0.06f,
    hasRing: Boolean = true,
    alpha: Float = 0.5f,
) {
    val phases = rememberCosmicPhases(listOf(CosmicMotion.orbitDurationMs))
    Canvas(modifier) {
        val c = Offset(size.width * center.x, size.height * center.y)
        val pr = minOf(size.width, size.height) * radiusFraction
        if (pr <= 0f) return@Canvas
        // Soft glow halo.
        drawCircle(
            brush = Brush.radialGradient(
                0f to color.copy(alpha = (alpha * 0.5f).coerceIn(0f, 1f)),
                1f to Color.Transparent,
                center = c,
                radius = pr * 2.2f,
            ),
            radius = pr * 2.2f,
            center = c,
        )
        // Lit disc with a subtle terminator toward bottom-right.
        drawCircle(
            brush = Brush.radialGradient(
                0f to color.copy(alpha = alpha.coerceIn(0f, 1f)),
                1f to color.copy(alpha = (alpha * 0.35f).coerceIn(0f, 1f)),
                center = Offset(c.x - pr * 0.3f, c.y - pr * 0.3f),
                radius = pr * 1.4f,
            ),
            radius = pr,
            center = c,
        )
        if (hasRing) {
            val rot = -22f + sin(phases.value.getOrElse(0) { 0f }) * CosmicMotion.orbitWobbleDegrees
            rotate(rot, c) {
                drawOval(
                    color = ringColor.copy(alpha = (alpha * 0.5f).coerceIn(0f, 1f)),
                    topLeft = Offset(c.x - pr * 1.9f, c.y - pr * 0.7f),
                    size = Size(pr * 3.8f, pr * 1.4f),
                    style = Stroke(width = 1.5f),
                )
            }
        }
    }
}

/** A smaller, cooler satellite disc — a moon. No ring, fainter glow. */
@Composable
fun CosmicMoon(
    modifier: Modifier = Modifier,
    color: Color = FolioTheme.cosmic.artwork.star,
    center: Offset = Offset(0.3f, 0.3f),
    radiusFraction: Float = 0.04f,
    alpha: Float = 0.5f,
) = CosmicPlanet(
    modifier = modifier,
    color = color,
    center = center,
    radiusFraction = radiusFraction,
    hasRing = false,
    alpha = alpha,
)

// ── Orbital path / ring ─────────────────────────────────────────────────────────────────

/**
 * A thin orbital ellipse that rotates **very** slowly (one turn per
 * [CosmicMotion.orbitDurationMs]). A ring that reads as moving is wrong — this is the
 * slowest "rotation" in the system, meant to be felt rather than seen.
 */
@Composable
fun CosmicOrbitalRing(
    modifier: Modifier = Modifier,
    color: Color = FolioTheme.cosmic.glow.primary,
    center: Offset = Offset(0.5f, 0.5f),
    widthFraction: Float = 0.9f,
    heightFraction: Float = 0.4f,
    alpha: Float = 0.3f,
    rotating: Boolean = true,
    strokeWidth: Float = 1.4f,
) {
    val phases = rememberCosmicPhases(listOf(CosmicMotion.orbitDurationMs))
    Canvas(modifier) {
        val c = Offset(size.width * center.x, size.height * center.y)
        val w = size.width * widthFraction
        val h = size.height * heightFraction
        val rot = if (rotating) (phases.value.getOrElse(0) { 0f } / TWO_PI) * 360f else 0f
        rotate(rot, c) {
            drawOval(
                color = color.copy(alpha = alpha.coerceIn(0f, 1f)),
                topLeft = Offset(c.x - w / 2f, c.y - h / 2f),
                size = Size(w, h),
                style = Stroke(width = strokeWidth),
            )
        }
    }
}

/** Alias with the brief's other name; identical to [CosmicOrbitalRing]. */
@Composable
fun CosmicOrbitalPath(
    modifier: Modifier = Modifier,
    color: Color = FolioTheme.cosmic.glow.primary,
    alpha: Float = 0.3f,
) = CosmicOrbitalRing(modifier = modifier, color = color, alpha = alpha)

// ── Constellation ──────────────────────────────────────────────────────────────────────

/** A hairline polyline through [points] (fractions of the bounds) with a node at each. */
@Composable
fun Constellation(
    modifier: Modifier = Modifier,
    color: Color = FolioTheme.cosmic.artwork.star,
    points: List<Offset>,
    alpha: Float = 0.3f,
) {
    Canvas(modifier) {
        if (points.size < 2) return@Canvas
        val path = Path()
        points.forEachIndexed { i, pt ->
            val x = pt.x * size.width
            val y = pt.y * size.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            drawCircle(color = color.copy(alpha = (alpha + 0.25f).coerceIn(0f, 1f)), radius = 1.6f, center = Offset(x, y))
        }
        drawPath(path, color = color.copy(alpha = alpha.coerceIn(0f, 1f)), style = Stroke(width = 1f))
    }
}

// ── Light rays / aurora (light-face flavour) ─────────────────────────────────────────────

/**
 * Soft volumetric light rays fanning from [origin] — the Cosmic-Dawn sunbeam. Static;
 * calm by construction (very low alpha, wide spread).
 */
@Composable
fun CosmicLightRay(
    modifier: Modifier = Modifier,
    color: Color = FolioTheme.cosmic.glow.primary,
    origin: Offset = Offset(0.8f, 0f),
    rayCount: Int = 5,
    alpha: Float = 0.12f,
) {
    Canvas(modifier) {
        val o = Offset(size.width * origin.x, size.height * origin.y)
        val reach = maxOf(size.width, size.height) * 1.4f
        for (i in 0 until rayCount) {
            val angle = (0.9f + i * 0.16f)
            val end = Offset(o.x + cos(angle) * reach, o.y + sin(angle) * reach)
            drawLine(
                brush = Brush.linearGradient(
                    0f to color.copy(alpha = alpha.coerceIn(0f, 1f)),
                    1f to Color.Transparent,
                    start = o,
                    end = end,
                ),
                start = o,
                end = end,
                strokeWidth = size.width * 0.06f,
            )
        }
    }
}

/**
 * A soft aurora band — two drifting ribbons of accent colour, for light "Cosmic Dawn"
 * faces. Drifts on the slow particle clock; frozen under reduce-motion.
 */
@Composable
fun Aurora(
    modifier: Modifier = Modifier,
    colorA: Color = FolioTheme.cosmic.accent.secondary,
    colorB: Color = FolioTheme.cosmic.glow.primary,
    alpha: Float = 0.16f,
) {
    val phases = rememberCosmicPhases(listOf(CosmicMotion.particleSpeedMs, CosmicMotion.nebulaDriftMs))
    Canvas(modifier) {
        val p0 = phases.value.getOrElse(0) { 0f }
        val p1 = phases.value.getOrElse(1) { 0f }
        listOf(colorA to p0, colorB to p1).forEachIndexed { idx, (col, p) ->
            val cy = size.height * (0.3f + idx * 0.18f) + sin(p) * size.height * 0.03f
            val radius = size.width * 0.8f
            drawCircle(
                brush = Brush.radialGradient(
                    0f to col.copy(alpha = alpha.coerceIn(0f, 1f)),
                    1f to Color.Transparent,
                    center = Offset(size.width * (0.4f + 0.2f * idx) + cos(p) * size.width * 0.04f, cy),
                    radius = radius,
                ),
                radius = radius,
                center = Offset(size.width * (0.4f + 0.2f * idx), cy),
            )
        }
    }
}

// ── Particles / dust ───────────────────────────────────────────────────────────────────

/**
 * Drifting gold sunlight particles (light faces) / cosmic dust (dark): a sparse field of
 * motes that gently rise and fade in and out. Rides the slow particle clock; still under
 * reduce-motion.
 */
@Composable
fun CosmicParticleField(
    modifier: Modifier = Modifier,
    color: Color = FolioTheme.cosmic.glow.primary,
    count: Int = 24,
    alpha: Float = 0.5f,
    seed: Int = FolioTheme.cosmic.glow.primary.value.toInt(),
) {
    val phases = rememberCosmicPhases(listOf(CosmicMotion.particleSpeedMs))
    Canvas(modifier) {
        val p = phases.value.getOrElse(0) { 0f }
        for (i in 0 until count) {
            val x0 = rnd(seed, i * 5 + 1)
            val y0 = rnd(seed, i * 5 + 2)
            val speed = 0.5f + rnd(seed, i * 5 + 3)
            val ph = rnd(seed, i * 5 + 4) * TWO_PI
            // Slow vertical drift that wraps, plus a gentle fade in/out.
            val y = ((y0 - (p / TWO_PI) * 0.15f * speed) % 1f + 1f) % 1f
            val fade = 0.5f + 0.5f * sin(p * speed + ph)
            val a = (alpha * fade).coerceIn(0f, 1f)
            if (a <= 0.02f) continue
            val r = 0.8f + rnd(seed, i * 5 + 5) * 1.4f
            drawCircle(color = color.copy(alpha = a), radius = r, center = Offset(x0 * size.width, y * size.height))
        }
    }
}

/** The brief's other name for the dust layer. */
@Composable
fun CosmicDust(
    modifier: Modifier = Modifier,
    color: Color = FolioTheme.cosmic.artwork.nebulaCool,
    alpha: Float = 0.35f,
) = CosmicParticleField(modifier = modifier, color = color, alpha = alpha)

// ── Shooting star (the one "event" motion) ──────────────────────────────────────────────

/**
 * A **rare** shooting star: a streak that crosses once, then waits a long, jittered
 * interval before the next. The only motion in the system allowed to catch the eye, and
 * only once in a long while. Never fires under reduce-motion.
 */
@Composable
fun ShootingStar(
    modifier: Modifier = Modifier,
    color: Color = FolioTheme.cosmic.artwork.star,
    seed: Int = FolioTheme.cosmic.artwork.star.value.toInt(),
) {
    val motion = rememberMotionEnabled()
    var progress by remember { mutableFloatStateOf(-1f) }
    var fromX by remember { mutableFloatStateOf(0.1f) }
    var fromY by remember { mutableFloatStateOf(0.1f) }
    var angle by remember { mutableFloatStateOf(0.5f) }
    if (motion) {
        androidx.compose.runtime.LaunchedEffect(seed) {
            var salt = seed
            while (true) {
                // Long, jittered wait: base interval plus 0..2× more.
                salt = signatureHash(salt)
                val extra = ((salt ushr 8) and 0xFFFF) / 65535f
                kotlinx.coroutines.delay(CosmicMotion.shootingStarIntervalMs + (extra * 2f * CosmicMotion.shootingStarIntervalMs).toLong())
                salt = signatureHash(salt)
                fromX = 0.1f + ((salt ushr 8) and 0xFF) / 255f * 0.6f
                salt = signatureHash(salt)
                fromY = 0.05f + ((salt ushr 8) and 0xFF) / 255f * 0.3f
                salt = signatureHash(salt)
                angle = 0.4f + ((salt ushr 8) and 0xFF) / 255f * 0.5f
                val start = withFrameNanos { it }
                while (true) {
                    val now = withFrameNanos { it }
                    val t = (now - start) / (CosmicMotion.shootingStarTravelMs * 1_000_000f)
                    if (t >= 1f) break
                    progress = t
                }
                progress = -1f
            }
        }
    }
    Canvas(modifier) {
        val t = progress
        if (t < 0f) return@Canvas
        val reach = maxOf(size.width, size.height) * 0.5f
        val head = Offset(
            fromX * size.width + cos(angle) * reach * t,
            fromY * size.height + sin(angle) * reach * t,
        )
        val tailLen = reach * 0.18f
        val tail = Offset(head.x - cos(angle) * tailLen, head.y - sin(angle) * tailLen)
        // Fade in over the first 20%, out over the last 40%.
        val fade = (minOf(t / 0.2f, (1f - t) / 0.4f, 1f)).coerceIn(0f, 1f)
        drawLine(
            brush = Brush.linearGradient(
                0f to Color.Transparent,
                1f to color.copy(alpha = (0.9f * fade).coerceIn(0f, 1f)),
                start = tail,
                end = head,
            ),
            start = tail,
            end = head,
            strokeWidth = 2f,
        )
        drawCircle(color = color.copy(alpha = (0.9f * fade).coerceIn(0f, 1f)), radius = 2f, center = head)
    }
}

// ── Convenience full-bleed modifier for behind-content use ──────────────────────────────

/** A primitive drawn to fill its parent, the usual "behind content in a Box" placement. */
@Composable
fun CosmicLayer(
    primitive: @Composable (Modifier) -> Unit,
) = primitive(Modifier.fillMaxSize())

/** Alignment-based safe-zone placement helper, re-exported so call sites read cleanly. */
val CosmicTopEnd: Alignment = Alignment.TopEnd
val CosmicTopStart: Alignment = Alignment.TopStart
val CosmicCenter: Alignment = Alignment.Center
