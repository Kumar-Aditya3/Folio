package com.folio.reader.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.animation.core.animateFloatAsState
import com.folio.reader.ui.theme.MotionKey
import com.folio.reader.ui.theme.rememberMotionAnimation
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.folio.reader.ui.theme.FolioSurfaceContents
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens
import com.folio.reader.ui.theme.FolioSoftShape
import com.folio.reader.ui.theme.tintFill
import com.folio.reader.ui.theme.surfaceOpacity
import com.folio.reader.ui.theme.panelKnobScale

/** Material silhouettes are stable across peer items, independent of their identity. */
object FolioMaterialShapes {
    val paper = FolioSoftShape(topStart = 16.dp, topEnd = 22.dp, bottomEnd = 22.dp, bottomStart = 16.dp)
    val reference = FolioSoftShape(22.dp)
    val dock = RoundedCornerShape(topStart = 24.dp, topEnd = 14.dp, bottomEnd = 24.dp, bottomStart = 24.dp)
    val shelfHero = FolioSoftShape(topStart = 24.dp, topEnd = 14.dp, bottomEnd = 24.dp, bottomStart = 24.dp)
}

/** A softly lit reading jacket. Stronger than a reference panel, with no animated rim. */
@Composable
fun Modifier.folioShelfHero(accent: Color): Modifier {
    val colors = FolioTheme.colors
    val shape = FolioMaterialShapes.shelfHero
    return clip(shape).folioDataGlass(shape).drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val lightOrigin = Offset(if (layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl)
            size.width else 0f, size.height * .3f)
        val light = Brush.radialGradient(listOf(accent.copy(alpha = .08f), Color.Transparent),
            center = lightOrigin, radius = size.width * .85f)
        val edge = Brush.linearGradient(listOf(colors.onSurface.copy(alpha = .15f),
            accent.copy(alpha = .20f), colors.onSurface.copy(alpha = .055f)),
            Offset.Zero, Offset(size.width, size.height))
        onDrawBehind {
            drawOutline(outline, light)
            drawOutline(outline, edge, style = Stroke(.8.dp.toPx()))
        }
    }
}

@Composable
internal fun folioPaperColor(): Color = tintFill(
    lerp(lerp(FolioTheme.colors.surface, FolioTheme.colors.background, .20f), FolioTheme.colors.onSurface, .012f), FolioTheme.colors.primary, .06f)

@Composable
internal fun folioReferenceColor(): Color = tintFill(
    lerp(lerp(FolioTheme.colors.surface, FolioTheme.colors.background, .12f), FolioTheme.colors.onSurface, .022f), FolioTheme.colors.primary, .09f)

/** Matte, palette-relative paper. This deliberately never passes through the optical ladder. */
@Composable
fun Modifier.folioPaper(accent: Color? = null, layered: Boolean = false): Modifier {
    val fill = folioPaperColor()
    val ink = FolioTheme.colors.onSurface
    val shape = FolioMaterialShapes.paper
    return clip(shape).drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            // A bounded, deterministic grain field, cached per size; no bitmap or idle clock.
            val edge = Brush.verticalGradient(listOf(ink.copy(alpha = .09f), ink.copy(alpha = .025f)))
            val dots = List(96) { i -> Offset(((i * 73 + 17) % 997) / 997f * size.width,
                ((i * 193 + 41) % 991) / 991f * size.height) }
            onDrawBehind {
                drawOutline(outline, fill)
                drawOutline(outline, edge, style = Stroke(.7.dp.toPx()))
                drawPoints(dots, PointMode.Points, ink.copy(alpha = .025f), strokeWidth = .7.dp.toPx())
                if (accent != null) {
                    drawLine(accent.copy(alpha = .65f), Offset(6.dp.toPx(), 20.dp.toPx()),
                        Offset(6.dp.toPx(), (size.height - 20.dp.toPx()).coerceIn(20.dp.toPx(), 48.dp.toPx())),
                        1.5.dp.toPx(), cap = StrokeCap.Round)
                    drawLine(accent.copy(alpha = .28f), Offset(9.dp.toPx(), 28.dp.toPx()),
                        Offset(9.dp.toPx(), (size.height - 20.dp.toPx()).coerceIn(28.dp.toPx(), 54.dp.toPx())),
                        1.dp.toPx(), cap = StrokeCap.Round)
                }
                if (layered) {
                    drawLine(ink.copy(alpha = .09f), Offset(7.dp.toPx(), size.height - 3.dp.toPx()),
                        Offset(size.width - 7.dp.toPx(), size.height - 3.dp.toPx()), .7.dp.toPx())
                    drawLine(ink.copy(alpha = .06f), Offset(7.dp.toPx(), size.height - 1.dp.toPx()),
                        Offset(size.width - 7.dp.toPx(), size.height - 1.dp.toPx()), .7.dp.toPx())
                }
            }
        }
}

/** Reference surfaces share the same diffuse glass and live opacity as settings and statistics. */
@Composable
fun Modifier.folioReference(shape: Shape = FolioMaterialShapes.reference): Modifier = folioDataGlass(shape)

/** A quiet embedded choice, sharing its parent's glass rather than adding another lens. */
@Composable
internal fun Modifier.folioSettingsChoice(selected: Boolean, shape: Shape): Modifier {
    val colors = FolioTheme.colors
    val response = animateFloatAsState(if (selected) 1f else 0f,
        rememberMotionAnimation(MotionKey.SegmentedIndicator), label = "settingsSignal")
    return clip(shape).drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        onDrawBehind {
            val level = response.value.coerceIn(0f, 1f)
            drawOutline(outline, colors.primary.copy(alpha = .11f * level))
            drawOutline(outline, colors.onSurface.copy(alpha = .025f * (1f - level)))
            drawOutline(outline, androidx.compose.ui.graphics.lerp(colors.onSurface.copy(alpha = .12f),
                colors.primary.copy(alpha = .70f), level), style = Stroke(.8.dp.toPx()))
            if (level > .01f) {
                val x = if (layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl) size.width - 4.dp.toPx() else 4.dp.toPx()
                drawLine(colors.primary.copy(alpha = level), Offset(x, size.height * .30f),
                    Offset(x, size.height * .70f), 1.5.dp.toPx(), StrokeCap.Round)
            }
        }
    }
}

@Composable
fun FolioPaperSheet(modifier: Modifier = Modifier, accent: Color? = null, layered: Boolean = false,
    content: @Composable ColumnScope.() -> Unit) {
    val fill = folioPaperColor()
    Column(modifier.fillMaxWidth().folioPaper(accent, layered).padding(FolioTokens.space3),
        verticalArrangement = Arrangement.spacedBy(FolioTokens.space2)) {
        val scope = this
        FolioSurfaceContents(fill) { scope.content() }
    }
}

@Composable
fun FolioReferencePanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    FolioGlassPanel(modifier = modifier, content = content)
}

/** Shared control/data surface for More, settings sections and reference content. */
@Composable
fun FolioGlassPanel(modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(FolioTokens.space3),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(FolioTokens.space2),
    blurEnabled: Boolean = true,
    clipEnabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit) {
    // Disabling capture alone must not change the panel's tint or opacity tier.
    val fill = folioDataGlassPlane()
    val material = if (blurEnabled) Modifier.folioDataGlass(clipEnabled = clipEnabled)
        else Modifier.folioDataGlass(fillAlpha = fill.alpha, blurEnabled = false, clipEnabled = clipEnabled)
    Column(modifier.fillMaxWidth().then(material).padding(contentPadding),
        verticalArrangement = verticalArrangement) {
        val scope = this
        FolioSurfaceContents(fill) { scope.content() }
    }
}
