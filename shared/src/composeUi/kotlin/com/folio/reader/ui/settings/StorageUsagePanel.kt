package com.folio.reader.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.folio.reader.ui.theme.FolioShapes
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens
import java.util.Locale

/** One labelled slice of storage, in bytes. Color is assigned by the panel. */
data class StorageUsageSlice(val label: String, val bytes: Long)

/**
 * Storage-usage panel: a donut of where the app's on-disk space goes, with the total in the
 * middle and a legend of category / size / share beneath it.
 *
 * Slices are drawn largest-first (the caller need not pre-sort) and each is given a distinct hue
 * from [storagePalette] rather than the theme accents, which collapse to the primary colour in the
 * default palette and would make neighbouring segments indistinguishable.
 */
@Composable
fun StorageUsagePanel(slices: List<StorageUsageSlice>, modifier: Modifier = Modifier) {
    val colors = FolioTheme.colors
    val ordered = slices.filter { it.bytes > 0L }.sortedByDescending { it.bytes }
    val total = ordered.sumOf { it.bytes }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(FolioTokens.space3)) {
        if (total <= 0L) {
            Text(
                "Nothing stored yet.",
                style = FolioTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
            return@Column
        }

        val palette = storagePalette(colors)
        val colored = ordered.mapIndexed { i, s -> s to palette[i % palette.size] }

        Box(
            modifier = Modifier.fillMaxWidth().padding(vertical = FolioTokens.space2),
            contentAlignment = Alignment.Center,
        ) {
            Box(modifier = Modifier.size(180.dp), contentAlignment = Alignment.Center) {
                Canvas(modifier = Modifier.size(180.dp)) {
                    val strokePx = 26.dp.toPx()
                    val inset = strokePx / 2f
                    val arc = Size(size.width - strokePx, size.height - strokePx)
                    val topLeft = Offset(inset, inset)
                    var start = -90f
                    // A 1.5° gap between segments so adjacent slices read as separate.
                    val gap = if (colored.size > 1) 1.5f else 0f
                    colored.forEach { (slice, color) ->
                        val sweep = (slice.bytes.toFloat() / total) * 360f
                        drawArc(
                            color = color,
                            startAngle = start + gap / 2f,
                            sweepAngle = (sweep - gap).coerceAtLeast(0f),
                            useCenter = false,
                            topLeft = topLeft,
                            size = arc,
                            style = Stroke(width = strokePx, cap = StrokeCap.Butt),
                        )
                        start += sweep
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        formatStorageBytes(total),
                        style = FolioTheme.typography.titleMedium,
                        color = colors.onSurface,
                    )
                    Text(
                        "total",
                        style = FolioTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(FolioTokens.space2)) {
            colored.forEach { (slice, color) ->
                val pct = (slice.bytes.toDouble() / total * 100).let {
                    if (it >= 1.0) "${it.toInt()}%" else "<1%"
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(FolioTokens.space2),
                ) {
                    Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(color))
                    Text(
                        slice.label,
                        style = FolioTheme.typography.bodyMedium,
                        color = colors.onSurface,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${formatStorageBytes(slice.bytes)} · $pct",
                        style = FolioTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        textAlign = TextAlign.End,
                        modifier = Modifier.wrapContentSize(),
                    )
                }
            }
        }
    }
}

/** One embedding model that still has stored vectors but is not the one in use. */
data class StorageOtherModel(val modelId: String, val label: String, val chunkCount: Int)

/**
 * Card offering to clear the vector index of models the reader has switched away from.
 *
 * Those vectors are dead weight: search only ever loads the selected model's index, so another
 * model's chunks occupy the database without ever being read. Clearing them frees that space and is
 * safe — re-selecting the model rebuilds its index from the library. Only the *current* model is
 * excluded by the caller, so this never offers to delete the index in use.
 */
@Composable
fun StorageOtherModelsCard(
    models: List<StorageOtherModel>,
    busy: Boolean,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (models.isEmpty()) return
    val colors = FolioTheme.colors
    val totalChunks = models.sumOf { it.chunkCount }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(FolioTokens.space2)) {
        Text(
            "Other models' search data",
            style = FolioTheme.typography.titleSmall,
            color = colors.onSurface,
        )
        Text(
            "$totalChunks chunks from ${models.size} model(s) you're not using are stored in the " +
                "database. Clearing them frees space; re-selecting a model rebuilds its index.",
            style = FolioTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
        models.forEach { m ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FolioTokens.space2),
            ) {
                Text(m.label, style = FolioTheme.typography.bodyMedium, color = colors.onSurface)
                Spacer(Modifier.weight(1f))
                Text(
                    "${m.chunkCount} chunks",
                    style = FolioTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
        Text(
            text = if (busy) "Clearing…" else "Clear other models' data",
            style = FolioTheme.typography.labelLarge,
            color = if (busy) colors.onSurfaceVariant else colors.primary,
            modifier = Modifier
                .clip(FolioShapes.pill)
                .clickable(enabled = !busy, onClick = onClear)
                .padding(vertical = FolioTokens.space2, horizontal = FolioTokens.space3),
        )
    }
}

/**
 * Distinct, theme-aware hues for the donut. Uses the theme's multi-series [chartSeries] palette,
 * which already adapts to every theme and dark mode; the call site cycles it with modulo when there
 * are more slices than series.
 */
private fun storagePalette(colors: com.folio.reader.ui.theme.FolioColors): List<Color> =
    colors.chartSeries

fun formatStorageBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> String.format(Locale.US, "%.0f MB", bytes / 1_000_000.0)
    bytes >= 1_000L -> String.format(Locale.US, "%.0f KB", bytes / 1_000.0)
    else -> "$bytes B"
}
