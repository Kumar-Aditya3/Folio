package com.folio.reader.ui.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min

/**
 * The central gradient library: every cosmic brush, built from a [CosmicScheme] and the
 * target [Size], in one place.
 *
 * The app grew ~67 inline `Brush.*Gradient` sites, each re-deciding a theme's gradient by
 * hand — which is how light faces ended up with white→saturated two-stop washes that read
 * as inverted dark themes. These builders replace that: a call site asks for a *named*
 * composition (`hero`, `chart`, `navigation`…) and the library decides the stops from the
 * palette's own roles, differently for the two polarities.
 *
 * **The polarity split is the point.** Dark builds *deep base → nebula*: a dark ground
 * lifting into the theme's cool/warn nebula pools — "looking into deep space". Light builds
 * a layered, predominantly-light *atmospheric base → pale-blue → soft-violet → localized
 * warm highlight* — "Cosmic Dawn, looking through a luminous atmosphere" — and never a
 * white→saturated two-stop. Both draw only from [CosmicScheme] (no raw hex).
 */
object CosmicGradients {

    // ── Internal shaping helpers (package-private colour math reused) ───────────────

    /** A light-face stop lifted toward white so the composition stays luminous. */
    private fun pale(c: Color, amount: Float): Color = liftG(c, amount)

    /** A dark-face stop deepened toward black so the composition stays spatial. */
    private fun deep(c: Color, amount: Float): Color = deepenG(c, amount)

    private fun Size.minDim(): Float = min(width, height).coerceAtLeast(1f)
    private fun Size.maxDim(): Float = max(width, height).coerceAtLeast(1f)

    // ── Backgrounds ────────────────────────────────────────────────────────────────

    /**
     * The page atmosphere as a vertical wash. Dark lifts out of a deep base toward the
     * sky's indigo top; light runs base → pale-blue → soft-violet → a warm horizon lift,
     * all kept light. This is a surface brush — the real root field is `folioField`, which
     * owns the contrast-guarded version; use this for panels/scenes that want the room's
     * gradient without being the field itself.
     */
    fun backgroundAtmosphere(scheme: CosmicScheme, size: Size): Brush {
        val art = scheme.artwork
        return if (scheme.isDark) {
            Brush.verticalGradient(
                0f to deep(art.skyTop, 0.10f),
                0.45f to scheme.background.primary,
                1f to scheme.background.secondary,
                startY = 0f,
                endY = size.height,
            )
        } else {
            Brush.verticalGradient(
                0f to scheme.background.primary,
                0.42f to pale(art.nebulaCool, 0.62f),
                0.74f to pale(scheme.accent.secondary, 0.70f),
                1f to pale(art.glow, 0.68f),
                startY = 0f,
                endY = size.height,
            )
        }
    }

    /**
     * The drifting nebula/cloud pools as a radial, parked off a top corner. Dark reads as
     * two cool/warm clouds in deep space; light as a soft tinted haze through the morning
     * atmosphere. Fades to transparent so it layers over [backgroundAtmosphere].
     */
    fun backgroundNebula(scheme: CosmicScheme, size: Size): Brush {
        val art = scheme.artwork
        val center = Offset(size.width * if (scheme.isDark) 0.74f else 0.28f, size.height * 0.22f)
        val radius = size.maxDim() * 0.9f
        val peak = if (scheme.isDark) 0.26f else 0.18f
        val inner = if (scheme.isDark) art.nebulaCool else pale(art.nebulaCool, 0.35f)
        val mid = if (scheme.isDark) art.nebulaWarm else pale(art.nebulaWarm, 0.40f)
        return Brush.radialGradient(
            0f to inner.copy(alpha = peak),
            0.55f to mid.copy(alpha = peak * 0.5f),
            1f to Color.Transparent,
            center = center,
            radius = radius,
        )
    }

    // ── Surfaces ─────────────────────────────────────────────────────────────────────

    /** A resting surface fill with a whisper of top-light. */
    fun surface(scheme: CosmicScheme, size: Size): Brush {
        val base = scheme.surface.primary
        val top = if (scheme.isDark) liftG(base, 0.05f) else liftG(base, 0.08f)
        val bottom = if (scheme.isDark) deepenG(base, 0.06f) else base
        return Brush.verticalGradient(listOf(top, bottom), startY = 0f, endY = size.height)
    }

    /** A glass/elevated surface fill — carries the veil's own translucency. */
    fun surfaceElevated(scheme: CosmicScheme, size: Size): Brush {
        val base = scheme.surface.elevated
        val top = liftG(base, if (scheme.isDark) 0.06f else 0.10f).copy(alpha = base.alpha)
        return Brush.verticalGradient(listOf(top, base), startY = 0f, endY = size.height)
    }

    // ── Accents ───────────────────────────────────────────────────────────────────────

    /** A full-strength accent sweep: hero → discovery, diagonal. */
    fun accent(scheme: CosmicScheme, size: Size): Brush = Brush.linearGradient(
        colors = listOf(scheme.accent.primary, scheme.accent.secondary),
        start = Offset(0f, 0f),
        end = Offset(size.width, size.height),
    )

    /** A soft accent wash that fades out — for tints behind content. */
    fun accentSoft(scheme: CosmicScheme, size: Size): Brush = Brush.linearGradient(
        colors = listOf(
            scheme.accent.primary.copy(alpha = if (scheme.isDark) 0.28f else 0.20f),
            scheme.accent.secondary.copy(alpha = 0f),
        ),
        start = Offset(0f, 0f),
        end = Offset(size.width, size.height),
    )

    /** A radial glow around a focal point — progress rings, active nav, artwork focals. */
    fun accentGlow(scheme: CosmicScheme, size: Size): Brush {
        val glow = scheme.glow.primary
        return Brush.radialGradient(
            0f to glow.copy(alpha = if (scheme.isDark) 0.45f else 0.30f),
            0.6f to glow.copy(alpha = if (scheme.isDark) 0.14f else 0.10f),
            1f to Color.Transparent,
            center = Offset(size.width * 0.5f, size.height * 0.5f),
            radius = size.minDim() * 0.75f,
        )
    }

    // ── Named compositions screens ask for ─────────────────────────────────────────────

    /** The hero region: the strongest base atmosphere, with the accent folded in. */
    fun hero(scheme: CosmicScheme, size: Size): Brush {
        val art = scheme.artwork
        return if (scheme.isDark) {
            Brush.verticalGradient(
                0f to mixG(deep(art.skyTop, 0.05f), scheme.accent.primary, 0.14f),
                0.5f to scheme.background.primary,
                1f to scheme.background.secondary,
                startY = 0f,
                endY = size.height,
            )
        } else {
            Brush.verticalGradient(
                0f to pale(mixG(art.nebulaCool, scheme.accent.secondary, 0.30f), 0.55f),
                0.5f to pale(art.nebulaCool, 0.70f),
                1f to scheme.background.primary,
                startY = 0f,
                endY = size.height,
            )
        }
    }

    /**
     * A chart fill: a vertical ramp from the accent to transparent, kept above the
     * Rule-15 minimum alpha so bars/areas stay legible as data.
     */
    fun chart(scheme: CosmicScheme, size: Size): Brush = Brush.verticalGradient(
        colors = listOf(
            scheme.accent.primary.copy(alpha = if (scheme.isDark) 0.9f else 0.8f),
            scheme.accent.primary.copy(alpha = FolioTokens.gradientMinAlpha),
        ),
        startY = 0f,
        endY = size.height,
    )

    /** The floating nav capsule glass: the elevated surface with a cool top-light. */
    fun navigation(scheme: CosmicScheme, size: Size): Brush {
        val base = scheme.surface.elevated
        return Brush.verticalGradient(
            colors = listOf(
                liftG(base, if (scheme.isDark) 0.08f else 0.12f).copy(alpha = base.alpha),
                base,
            ),
            startY = 0f,
            endY = size.height,
        )
    }

    /** The quiet settings backdrop: barely-there, so forms stay the subject. */
    fun settings(scheme: CosmicScheme, size: Size): Brush {
        val top = if (scheme.isDark) deep(scheme.background.primary, 0.02f) else liftG(scheme.background.primary, 0.02f)
        return Brush.verticalGradient(
            listOf(top, scheme.background.secondary),
            startY = 0f,
            endY = size.height,
        )
    }

    /** A dialog/sheet surface: the glass veil, so the field shows faintly behind the scrim. */
    fun dialog(scheme: CosmicScheme, size: Size): Brush = surfaceElevated(scheme, size)

    /** A featured card: the accent-soft wash over the resting surface. */
    fun featured(scheme: CosmicScheme, size: Size): Brush {
        val base = scheme.surface.primary
        return if (scheme.isDark) {
            Brush.linearGradient(
                colors = listOf(mixG(base, scheme.accent.primary, 0.16f), base),
                start = Offset(0f, 0f),
                end = Offset(size.width, size.height),
            )
        } else {
            Brush.linearGradient(
                colors = listOf(mixG(base, pale(scheme.accent.secondary, 0.4f), 0.18f), liftG(base, 0.04f)),
                start = Offset(0f, 0f),
                end = Offset(size.width, size.height),
            )
        }
    }
}
