package com.folio.reader.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** Dark cloth dyes keep ivory lettering legible throughout the cover. */
internal data class BindingPalette(val cloth: Color, val light: Color)

private val bindingPalettes = listOf(
    BindingPalette(Color(0xFF192F37), Color(0xFF294B55)),
    BindingPalette(Color(0xFF303C2C), Color(0xFF49563A)),
    BindingPalette(Color(0xFF4B2834), Color(0xFF683C49)),
    BindingPalette(Color(0xFF292D4C), Color(0xFF414668)),
    BindingPalette(Color(0xFF50372A), Color(0xFF6B4B35)),
    BindingPalette(Color(0xFF3E2E47), Color(0xFF59425F)),
)

internal fun bindingPalette(title: String): BindingPalette =
    bindingPalettes[Math.floorMod(title.hashCode(), bindingPalettes.size)]

/**
 * Procedural bookcloth: a recessed spine, inset foil rules and quiet woven lines.
 * Geometry is cached by size; no textures, clocks or additional offscreen layers.
 */
internal fun Modifier.folioBinding(palette: BindingPalette): Modifier = drawWithCache {
    val cloth = Brush.linearGradient(listOf(palette.cloth, palette.light))
    val spineWidth = size.width * 0.09f
    val spine = Brush.horizontalGradient(
        listOf(Color.Black.copy(alpha = 0.30f), Color.White.copy(alpha = 0.07f), Color.Transparent),
        endX = spineWidth.coerceAtLeast(1f),
    )
    val foil = Color(0xFFE7C68C)
    val inset = size.width * 0.14f
    val stroke = 0.65.dp.toPx()
    val weave = 4.dp.toPx()
    onDrawBehind {
        drawRect(cloth)
        // Sparse, low-contrast weave remains subordinate to the title.
        var y = 0f
        while (y < size.height) {
            drawLine(Color.White.copy(alpha = 0.025f), Offset(0f, y), Offset(size.width, y), stroke)
            y += weave
        }
        drawRect(spine, size = Size(spineWidth, size.height))
        drawLine(foil.copy(alpha = 0.28f), Offset(spineWidth, 0f), Offset(spineWidth, size.height), stroke)
        if (size.width > inset * 2 && size.height > inset * 2) {
            drawRect(
                foil.copy(alpha = 0.28f),
                topLeft = Offset(inset, inset),
                size = Size(size.width - inset * 2, size.height - inset * 2),
                style = Stroke(stroke),
            )
            // Paired publisher's rules leave the centre clear for the typography.
            for (fraction in listOf(0.19f, 0.81f)) {
                val lineY = size.height * fraction
                drawLine(foil.copy(alpha = 0.6f), Offset(size.width * 0.42f, lineY),
                    Offset(size.width * 0.58f, lineY), stroke)
            }
        }
    }
}
