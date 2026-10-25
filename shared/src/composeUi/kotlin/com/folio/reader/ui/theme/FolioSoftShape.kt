package com.folio.reader.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection

/** Gentle cubic shoulders for interface surfaces; printed cover plates keep their own geometry. */
@Immutable
data class FolioSoftShape(
    val topStart: Dp,
    val topEnd: Dp = topStart,
    val bottomEnd: Dp = topStart,
    val bottomStart: Dp = topStart,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val rtl = layoutDirection == LayoutDirection.Rtl
        val radii = with(density) {
            floatArrayOf((if (rtl) topEnd else topStart).toPx(),
                (if (rtl) topStart else topEnd).toPx(),
                (if (rtl) bottomStart else bottomEnd).toPx(),
                (if (rtl) bottomEnd else bottomStart).toPx())
        }
        val w = size.width.coerceAtLeast(0f)
        val h = size.height.coerceAtLeast(0f)
        // Normalize opposing shoulders together: narrow controls never self-intersect.
        val scale = minOf(1f, w / (radii[0] + radii[1]).coerceAtLeast(1f),
            w / (radii[3] + radii[2]).coerceAtLeast(1f),
            h / (radii[0] + radii[3]).coerceAtLeast(1f),
            h / (radii[1] + radii[2]).coerceAtLeast(1f))
        val (tl, tr, br, bl) = radii.map { it.coerceAtLeast(0f) * scale }
        val k = .72f
        return Outline.Generic(Path().apply {
            moveTo(tl, 0f); lineTo(w - tr, 0f)
            cubicTo(w - tr + tr * k, 0f, w, tr - tr * k, w, tr)
            lineTo(w, h - br)
            cubicTo(w, h - br + br * k, w - br + br * k, h, w - br, h)
            lineTo(bl, h)
            cubicTo(bl - bl * k, h, 0f, h - bl + bl * k, 0f, h - bl)
            lineTo(0f, tl)
            cubicTo(0f, tl - tl * k, tl - tl * k, 0f, tl, 0f)
            close()
        })
    }
}
