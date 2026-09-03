package com.folio.reader.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.folio.reader.ui.theme.FolioAtmosphere
import com.folio.reader.ui.theme.FolioShapes
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens
import com.folio.reader.ui.theme.atmosphere
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
 * The lighting is consistent: one source above and leading. Raised things catch
 * it on the top edge and occlude below; sunken things do the exact inverse. That
 * single rule makes depth legible without putting a shadow on everything.
 */

/** Elevation is a token, not a literal, and scales per atmosphere. */
private fun FolioAtmosphere.scaled(dp: Dp): Dp = dp * shadowScale

/**
 * Floating surface. The highest material — at most one or two per screen.
 *
 * [accent] mixes into the top light catch, so a hero lit by a book cover reports
 * that cover's colour at its edge. That is what starts making the cover behave
 * like an object in the room rather than a picture in a box.
 */
@Composable
fun Modifier.folioRaised(
    shape: Shape = FolioShapes.card,
    elevation: Dp = FolioTokens.elevationRaised,
    accent: Color? = null,
    fill: Color? = null,
): Modifier {
    val atmos = FolioTheme.atmosphere
    val base = fill ?: atmos.raisedFill
    val light = if (accent != null) {
        lerp(atmos.rimLight, accent, 0.35f).copy(alpha = atmos.rimLight.alpha)
    } else {
        atmos.rimLight
    }
    return this
        .shadow(
            elevation = atmos.scaled(elevation),
            shape = shape,
            ambientColor = atmos.shadowAmbient,
            spotColor = atmos.shadowSpot,
        )
        .background(base, shape)
        .background(
            brush = Brush.verticalGradient(
                0f to light.copy(alpha = light.alpha * 0.55f),
                0.45f to Color.Transparent,
                1f to atmos.rimShade.copy(alpha = atmos.rimShade.alpha * 0.5f),
            ),
            shape = shape,
        )
        .border(1.dp, Brush.verticalGradient(listOf(light, atmos.rimShade)), shape)
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
 * Embedded surface: a well cut into the page. Inverted lighting — dark at the top
 * edge where the wall occludes, catching light along the bottom.
 *
 * Charts, heatmaps and figure fields live here. The inversion is what stops a
 * chart from reading as one more card.
 */
@Composable
fun Modifier.folioSunken(
    shape: Shape = FolioShapes.inset,
    accent: Color? = null,
): Modifier {
    val atmos = FolioTheme.atmosphere
    val tinted = if (accent != null) lerp(atmos.sunkenFill, accent, 0.07f) else atmos.sunkenFill
    return this
        .background(tinted, shape)
        .background(
            brush = Brush.verticalGradient(
                0f to atmos.rimShade.copy(alpha = atmos.rimShade.alpha * 0.9f),
                0.35f to Color.Transparent,
                1f to atmos.rimLight.copy(alpha = atmos.rimLight.alpha * 0.30f),
            ),
            shape = shape,
        )
        .clip(shape)
}

/**
 * Glass over content: bars, floating navigation, sheets. Near-opaque on purpose —
 * Android cannot blur a separate native surface (the reader page), so
 * translucency alone would let text bleed through at full contrast. The glass is
 * carried by the rim and the sheen.
 */
@Composable
fun Modifier.folioVeil(
    shape: Shape = FolioShapes.card,
    elevation: Dp = FolioTokens.elevationVeil,
): Modifier {
    val atmos = FolioTheme.atmosphere
    return this
        .shadow(
            elevation = atmos.scaled(elevation),
            shape = shape,
            ambientColor = atmos.shadowAmbient,
            spotColor = atmos.shadowSpot,
        )
        .background(atmos.veilFill, shape)
        .background(
            brush = Brush.verticalGradient(
                listOf(atmos.rimLight.copy(alpha = atmos.rimLight.alpha * 0.35f), Color.Transparent),
            ),
            shape = shape,
        )
        .border(1.dp, atmos.hairline, shape)
        .clip(shape)
}

/**
 * The page itself. A vertical field wash plus up to three enormous, very
 * low-alpha colour pools drawn from the palette's accent roles, so the background
 * has a temperature and a direction of light instead of one flat fill.
 *
 * Static by default: this is atmosphere, not motion. One drawing pass via
 * [drawWithCache]; nothing recomposes per frame.
 */
@Composable
fun Modifier.folioField(
    /** Optional cover-derived hue that tints the top of the page. */
    tint: Color? = null,
): Modifier {
    val atmos = FolioTheme.atmosphere
    val pools = atmos.pools
    val poolAlpha = atmos.poolAlpha
    val top = if (tint != null) lerp(atmos.fieldTop, tint, 0.16f) else atmos.fieldTop
    val bottom = atmos.fieldBottom
    return this.drawWithCache {
        val w = size.width
        val h = size.height
        val field = Brush.verticalGradient(listOf(top, bottom))
        // Centres sit outside the bounds so no pool ever shows an edge; radii are
        // large multiples of the width for the same reason.
        val spec = listOf(
            Triple(Offset(-0.15f * w, -0.10f * h), 1.15f * w, 0),
            Triple(Offset(1.10f * w, 0.28f * h), 0.95f * w, 1),
            Triple(Offset(0.35f * w, 1.15f * h), 1.30f * w, 2),
        )
        onDrawBehind {
            drawRect(field)
            spec.forEach { (center, radius, index) ->
                val color = pools.getOrNull(index) ?: return@forEach
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(color.copy(alpha = poolAlpha), Color.Transparent),
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

@Composable
fun rememberFolioInteraction(): MutableInteractionSource =
    remember { MutableInteractionSource() }

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
