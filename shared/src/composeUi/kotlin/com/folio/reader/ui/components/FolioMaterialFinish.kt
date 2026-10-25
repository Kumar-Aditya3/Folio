package com.folio.reader.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.dp
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.LocalFolioPackId
import com.folio.reader.ui.theme.LocalFolioSurfaceDepth
import com.folio.reader.ui.theme.folioSheenGain

data class FolioMaterialFinish(val spread: Float, val strength: Float, val brushing: Float, val interference: Float)

/** Surface finish changes the light response; navigation geometry remains consistent. */
fun folioMaterialFinishFor(packId: String): FolioMaterialFinish = when (packId) {
    "paper" -> FolioMaterialFinish(.90f, .22f, 9f, .08f)
    "forest" -> FolioMaterialFinish(.68f, .48f, 0f, .22f)
    "ocean" -> FolioMaterialFinish(.76f, .58f, 0f, .34f)
    "editorial" -> FolioMaterialFinish(.50f, .50f, 0f, .48f)
    "blush" -> FolioMaterialFinish(.85f, .52f, 0f, .85f)
    "acid" -> FolioMaterialFinish(.28f, .58f, 12f, .12f)
    "ceramic" -> FolioMaterialFinish(.94f, .62f, 0f, .10f)
    "copper" -> FolioMaterialFinish(.45f, .62f, 5f, .18f)
    else -> FolioMaterialFinish(.55f, .40f, 0f, 0f)
}

/** Bounded reflected light; no fill plane, extra blur pass or idle animation clock. */
@Composable
internal fun Modifier.folioMaterialFinish(shape: Shape): Modifier {
    if (LocalFolioSurfaceDepth.current > 0) return this
    val finish = folioMaterialFinishFor(LocalFolioPackId.current)
    val colors = FolioTheme.colors
    val sheen = folioSheenGain()
    return drawWithCache {
        val path = shapePath(shape)
        val light = Brush.linearGradient(listOf(Color.Transparent,
            colors.onSurface.copy(alpha = .065f * finish.strength * sheen),
            colors.accentDiscovery.copy(alpha = .075f * finish.interference * sheen), Color.Transparent),
            Offset(-size.width * finish.spread, 0f), Offset(size.width * (1f + finish.spread), size.height))
        val brushing = Brush.horizontalGradient(listOf(Color.Transparent,
            colors.onSurface.copy(alpha = .028f * sheen), Color.Transparent))
        onDrawBehind {
            if (sheen > 0f) clipPath(path) {
                drawRect(light)
                if (finish.brushing > 0f) {
                    val gap = finish.brushing.dp.toPx()
                    var y = gap * .5f
                    while (y < size.height) {
                        drawLine(brushing, Offset(0f, y), Offset(size.width, y - size.height * .015f), .45.dp.toPx())
                        y += gap
                    }
                }
            }
        }
    }
}
