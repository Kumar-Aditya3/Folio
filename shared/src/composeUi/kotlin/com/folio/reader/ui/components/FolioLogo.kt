package com.folio.reader.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import com.folio.reader.ui.theme.FolioTheme

/**
 * Folio's mark: an open book whose right page grows into a leaf.
 *
 * The artwork is vector, not the source raster. `icon/App image.png` was traced
 * into one even-odd contour set inside a 100×100 box, so the identical geometry
 * drives three places at once — this composable, `ic_launcher_foreground.xml`
 * and `ic_launcher_monochrome.xml`. Dropping the PNG into an `Image` instead
 * would have shipped the artwork's grey studio field into the app and gone soft
 * on every density.
 *
 * Only `M`, `L` and `Z` appear in the data (the trace emits polygons), which is
 * why [foliologoPath] can be twenty lines instead of a general SVG parser.
 */
object FolioLogo {
    /** Traced contours in a 100×100 space, even-odd (the leaf's two voids are holes). */
    const val PATH: String = "M95.14,6.94 L95.65,7.72 L97.47,12.78 L99.29,20.43 L99.94,26.78 L99.81,33.66 L99.03,38.72 L97.73,43.26 L95.65,47.67 L92.8,51.69 L88.39,55.84 L82.68,59.21 L76.98,61.28 L67.38,63.62 L63.88,65.18 L61.8,66.6 L62.06,65.3 L63.36,62.32 L64.66,60.25 L66.08,58.69 L70.88,57 L76.59,55.71 L81.65,53.63 L86.19,50.52 L88.78,47.92 L90.47,45.59 L92.15,42.48 L93.19,39.49 L94.49,32.49 L94.49,26.26 L94.23,23.67 L93.19,18.48 L92.67,17.57 L88.91,20.3 L85.15,22.37 L80.61,24.19 L68.68,28.08 L65.18,29.9 L61.41,32.75 L59.21,35.21 L57,38.85 L55.71,42.35 L55.19,44.68 L54.67,50.91 L52.59,53.63 L49.74,59.08 L49.35,54.54 L49.35,48.44 L50.13,42.48 L51.95,36.77 L53.89,33.27 L55.84,30.67 L59.21,27.43 L63.36,24.58 L69.71,21.73 L80.35,18.35 L86.71,15.24 L90.47,12.39 Z M19.39,17.06 L24.32,17.32 L28.47,18.09 L33.4,19.65 L37.29,21.47 L41.18,23.93 L44.03,26.26 L46.63,28.99 L49.22,32.49 L46.89,38.59 L46.5,38.85 L45.46,36.64 L43.26,33.4 L40.53,30.42 L38.33,28.6 L35.08,26.52 L31.84,24.97 L28.08,23.67 L24.84,23.02 L24.58,23.28 L24.45,56.36 L19.26,55.32 L19.13,23.02 Z M11.35,24.32 L14.98,24.45 L14.98,59.21 L15.37,59.6 L20.95,60.12 L26.65,61.41 L31.58,63.23 L35.73,65.3 L40.66,68.68 L44.29,72.18 L48.83,78.92 L49.09,78.79 L49.35,75.03 L50.13,70.88 L51.69,65.05 L54.8,58.04 L58.43,52.46 L60.25,50.26 L65.43,45.33 L70.36,41.83 L75.42,38.85 L79.96,35.73 L82.17,33.92 L85.15,30.8 L83.46,34.95 L81.65,37.55 L78.53,40.79 L69.46,48.05 L65.69,51.82 L62.45,55.97 L59.34,61.15 L56.74,67.77 L55.45,73.61 L54.8,80.48 L54.93,88.78 L54.02,91.12 L52.72,92.28 L51.3,92.93 L49.61,93.06 L48.57,92.93 L46.76,92.15 L45.46,90.99 L41.96,86.32 L37.81,82.43 L33.27,79.44 L30.03,77.89 L26.39,76.59 L22.5,75.55 L16.93,74.77 L9.53,74.77 L0.19,76.33 L0.06,36.64 L0.32,34.95 L1.49,32.88 L2.92,31.84 L5.25,31.32 L5.51,31.71 L5.38,69.46 L6.03,69.97 L9.14,69.46 L15.63,69.33 L21.08,69.84 L24.45,70.49 L29.77,72.05 L33.66,73.61 L37.68,75.68 L41.83,78.66 L42.35,78.66 L40.79,76.46 L37.55,73.22 L34.18,70.75 L30.16,68.55 L26.91,67.25 L21.34,65.69 L14.85,64.92 L9.66,64.92 L9.53,25.88 L9.66,24.58 Z"

    /** Deep blue at the book's foot — the gradient's origin, bottom-left. */
    val InkStart: Color = Color(0xFF27378E)

    /** The teal the stroke passes through as it climbs into the leaf. */
    val InkMid: Color = Color(0xFF137B90)

    /** Leaf green at the tip — the gradient's end, top-right. */
    val InkEnd: Color = Color(0xFF229C81)
}

/**
 * The mark, filled with its own blue→green gradient along the bottom-left →
 * top-right diagonal the artwork was drawn on.
 *
 * On dark palettes both stops are lifted toward white: the brand blue is a 4%
 * luminance ink, and against Folio's night surface it turns into a dark smudge
 * at 30dp. A brand mark keeps its hue everywhere; it does not have to keep its
 * exact value.
 *
 * Pass [tint] to draw the silhouette in one flat colour instead (the same shape
 * the themed launcher icon uses).
 */
@Composable
fun FolioLogoMark(
    modifier: Modifier = Modifier,
    tint: Color? = null,
) {
    val path = remember { foliologoPath() }
    val onDark = FolioTheme.colors.surface.luminance() < 0.35f
    val brush = remember(tint, onDark) {
        when {
            tint != null -> SolidColor(tint)
            onDark -> Brush.linearGradient(
                0f to lerp(FolioLogo.InkStart, Color.White, 0.34f),
                0.52f to lerp(FolioLogo.InkMid, Color.White, 0.22f),
                1f to lerp(FolioLogo.InkEnd, Color.White, 0.16f),
                start = Offset(0f, 1f),
                end = Offset(1f, 0f),
            )
            else -> Brush.linearGradient(
                0f to FolioLogo.InkStart,
                0.52f to FolioLogo.InkMid,
                1f to FolioLogo.InkEnd,
                start = Offset(0f, 1f),
                end = Offset(1f, 0f),
            )
        }
    }
    Canvas(modifier) {
        // Uniform fit: the trace is centred in its own box, so a single scale and
        // one centring translate is the whole layout.
        val factor = size.minDimension / VIEWPORT
        val dx = (size.width - VIEWPORT * factor) / 2f
        val dy = (size.height - VIEWPORT * factor) / 2f
        translate(dx, dy) {
            scale(factor, factor, pivot = Offset.Zero) {
                drawPath(path, brush)
            }
        }
    }
}

/** The box the traced contours live in. */
private const val VIEWPORT = 100f

/**
 * Parses [FolioLogo.PATH]. Deliberately narrow: `M x,y`, `L x,y`, `Z`, space
 * separated — anything else is skipped rather than guessed at, because the data
 * is generated, not hand-written.
 */
private fun foliologoPath(): Path {
    val path = Path()
    path.fillType = PathFillType.EvenOdd
    val data = FolioLogo.PATH
    var i = 0
    while (i < data.length) {
        when (data[i]) {
            'M', 'L' -> {
                val command = data[i]
                var end = i + 1
                while (end < data.length && data[end] != ' ') end++
                val token = data.substring(i + 1, end)
                val comma = token.indexOf(',')
                if (comma > 0) {
                    val x = token.substring(0, comma).toFloatOrNull()
                    val y = token.substring(comma + 1).toFloatOrNull()
                    if (x != null && y != null) {
                        if (command == 'M') path.moveTo(x, y) else path.lineTo(x, y)
                    }
                }
                i = end + 1
            }
            'Z' -> {
                path.close()
                i++
            }
            else -> i++
        }
    }
    return path
}
