package com.folio.reader.ui.atlas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.folio.reader.ml.AtlasBook
import com.folio.reader.ml.AtlasModel
import com.folio.reader.ui.components.FolioCoverGridSkeleton
import com.folio.reader.ui.components.folioField
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.atmosphere
import com.folio.reader.ui.theme.folioAtlasShader
import com.folio.reader.ui.theme.rememberMotionEnabled
import com.folio.reader.ui.theme.rememberShaderSupported
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Past this camera scale the map shows a book's topic constellation and taps open the reader. */
private const val DETAIL_ZOOM = 2.2f

/**
 * The Atlas — a full-screen cartographic map of the library by semantic topic.
 *
 * A portable Compose canvas is the base (it works on desktop and pre-API-33 devices unchanged);
 * an AGSL caustic sheen is layered over it only where the device can carry it. Two-level zoom:
 * far out, books are lit regions the reader taps to open Book Detail; pinched in, a book's topics
 * spread into a constellation whose exemplar passages deep-link into the reader.
 */
@Composable
fun AtlasScreen(
    state: AtlasUiState,
    exemplarTexts: Map<String, String>,
    onBack: () -> Unit,
    onOpenBook: (String) -> Unit,
    onOpenExemplar: (bookId: String, spineIndex: Int?, fraction: Float?) -> Unit,
    onLoadExemplars: (Collection<String>) -> Unit,
    onRunBackfill: () -> Unit = {},
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .folioField()
    ) {
        when (state) {
            is AtlasUiState.Loading -> AtlasSkeleton()
            is AtlasUiState.Unavailable -> AtlasMessage(
                title = "The atlas is empty",
                body = "Index your library to map it by meaning. Once your books are embedded on this device, the atlas draws itself.",
                actionLabel = "Index now",
                onAction = onRunBackfill,
            )
            is AtlasUiState.TooFewBooks -> AtlasMessage(
                title = "A map needs a few shores",
                body = "The atlas appears once ${state.threshold} of your books are embedded — you have ${state.count} so far.",
            )
            is AtlasUiState.Forming -> AtlasMessage(
                title = "The atlas is forming",
                body = "Your library is still being embedded on this device (${(state.fraction * 100).toInt()}%). The map appears once there is enough to chart.",
                actionLabel = "Keep indexing",
                onAction = onRunBackfill,
            )
            is AtlasUiState.Map -> AtlasMap(
                model = state.model,
                forming = state.forming,
                fraction = state.fraction,
                exemplarTexts = exemplarTexts,
                onOpenBook = onOpenBook,
                onOpenExemplar = onOpenExemplar,
                onLoadExemplars = onLoadExemplars,
            )
        }

        // Back button floats over everything, on the status-bar inset.
        IconButton(
            onClick = onBack,
            modifier = Modifier.statusBarsPadding().padding(4.dp).align(Alignment.TopStart),
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = FolioTheme.colors.onSurface)
        }
    }
}

@Composable
private fun AtlasMap(
    model: AtlasModel,
    forming: Boolean,
    fraction: Float,
    exemplarTexts: Map<String, String>,
    onOpenBook: (String) -> Unit,
    onOpenExemplar: (bookId: String, spineIndex: Int?, fraction: Float?) -> Unit,
    onLoadExemplars: (Collection<String>) -> Unit,
) {
    val atmosphere = FolioTheme.atmosphere
    val shaderEnabled = com.folio.reader.ui.components.LocalGlassCapabilities.current.specular &&
        com.folio.reader.ui.theme.rememberShaderSupported()
    val measurer = rememberTextMeasurer()

    // Per-book hue from the atmosphere's accent roles (Rule 14 — the map carries the theme's own
    // data-visual palette), cycled with a gentle rotation so neighbouring continents read apart.
    // Pure, so no per-book composable calls in a dynamic loop.
    val pools = atmosphere.pools.ifEmpty { listOf(FolioTheme.colors.accentProgress) }
    val accents: Map<String, Color> = remember(model, pools) {
        model.books.mapIndexed { i, book -> book.bookId to pools[i % pools.size] }.toMap()
    }

    // Book centroid = mass-weighted mean of its cluster positions, in layout space [-1, 1].
    val centroids: Map<String, Offset> = remember(model) {
        model.books.associate { book ->
            var sx = 0f; var sy = 0f; var sw = 0f
            book.clusters.forEach { c -> sx += c.x * c.mass; sy += c.y * c.mass; sw += c.mass }
            book.bookId to if (sw > 0f) Offset(sx / sw, sy / sw) else Offset(0f, 0f)
        }
    }
    val bookById = remember(model) { model.books.associateBy { it.bookId } }

    var scale by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var canvasSize by remember { mutableStateOf(Size.Zero) }

    fun worldRadius(size: Size) = minOf(size.width, size.height) * 0.42f
    fun toScreen(size: Size, x: Float, y: Float): Offset {
        val r = worldRadius(size) * scale
        return Offset(size.width / 2f + pan.x + x * r, size.height / 2f + pan.y + y * r)
    }

    // Request exemplar text for on-screen books once we're zoomed into detail.
    LaunchedEffect(scale >= DETAIL_ZOOM, model) {
        if (scale >= DETAIL_ZOOM) {
            onLoadExemplars(model.books.flatMap { b -> b.clusters.map { it.exemplarChunkId } })
        }
    }

    val labelColor = FolioTheme.colors.onSurface
    // Hoisted out of the (non-composable) Canvas draw lambda below.
    val fallbackAccent = FolioTheme.colors.accentProgress
    val detailLabelStyle = TextStyle(color = labelColor, fontSize = 11.sp)
    val titleStyle = TextStyle(color = labelColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)

    Box(
        Modifier
            .fillMaxSize()
            .then(if (shaderEnabled) Modifier.folioAtlasShader(sea = atmosphere.sunkenFill, light = atmosphere.rimLight) else Modifier)
            .pointerInput(model) {
                detectTransformGestures { _, panChange, zoomChange, _ ->
                    scale = (scale * zoomChange).coerceIn(0.6f, 6f)
                    pan += panChange
                }
            }
            .pointerInput(model, scale, pan, canvasSize) {
                detectTapGestures { tap ->
                    val size = canvasSize
                    if (size == Size.Zero) return@detectTapGestures
                    if (scale >= DETAIL_ZOOM) {
                        // Deep zoom: hit-test exemplars, deep-link into the reader.
                        var best: Pair<AtlasBook, com.folio.reader.ml.AtlasCluster>? = null
                        var bestD = Float.MAX_VALUE
                        model.books.forEach { b ->
                            b.clusters.forEach { c ->
                                val p = toScreen(size, c.x, c.y)
                                val d = hypot(tap.x - p.x, tap.y - p.y)
                                if (d < bestD) { bestD = d; best = b to c }
                            }
                        }
                        val hit = best
                        if (hit != null && bestD < 64f) {
                            val c = hit.second
                            onOpenExemplar(hit.first.bookId, c.exemplarSpineIndex.takeIf { it >= 0 }, c.exemplarFraction.takeIf { it >= 0f })
                        }
                    } else {
                        // Far out: hit-test book centroids, open Book Detail.
                        var bestId: String? = null
                        var bestD = Float.MAX_VALUE
                        centroids.forEach { (id, world) ->
                            val p = toScreen(size, world.x, world.y)
                            val d = hypot(tap.x - p.x, tap.y - p.y)
                            if (d < bestD) { bestD = d; bestId = id }
                        }
                        bestId?.let { if (bestD < 96f) onOpenBook(it) }
                    }
                }
            }
            .semantics {
                contentDescription = "Atlas map of ${model.books.size} books. Pinch to zoom into a book's topics; tap a region to open it."
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            canvasSize = size

            // Borders first, under the land: a faint line between books that share a coastline.
            model.edges.forEach { edge ->
                val a = centroids[edge.bookIdA] ?: return@forEach
                val b = centroids[edge.bookIdB] ?: return@forEach
                val pa = toScreen(size, a.x, a.y)
                val pb = toScreen(size, b.x, b.y)
                val w = (edge.weight.toFloat() / 6f).coerceIn(0f, 1f)
                drawLine(
                    color = labelColor.copy(alpha = 0.06f + 0.14f * w),
                    start = pa, end = pb, strokeWidth = 1f + 2f * w,
                )
            }

            // Land: each cluster is a topic region, tinted by its book, lit by reading progress,
            // and its coastline is raggeder the more diffuse the topic (low tightness).
            model.books.forEach { book ->
                val accent = accents[book.bookId] ?: fallbackAccent
                // Fog of war: unread regions are dim, read ones are present. Kept above a floor so
                // an unread book is still faintly visible (Rule 19 — the map never hides data).
                val litAlpha = 0.28f + 0.55f * book.readFraction
                book.clusters.forEach { c ->
                    val center = toScreen(size, c.x, c.y)
                    val radius = (worldRadius(size) * scale) * (0.05f + 0.13f * c.mass)
                    drawCoastline(center, radius, c.tightness, accent.copy(alpha = litAlpha))
                }
            }

            // Labels. Book titles far out; exemplar passages when zoomed into the constellation.
            if (scale >= DETAIL_ZOOM) {
                model.books.forEach { book ->
                    book.clusters.forEach { c ->
                        val text = exemplarTexts[c.exemplarChunkId]?.take(60) ?: return@forEach
                        val p = toScreen(size, c.x, c.y)
                        drawText(measurer, text, topLeft = Offset(p.x + 8f, p.y - 6f), style = detailLabelStyle)
                    }
                }
            } else {
                model.books.forEach { book ->
                    val world = centroids[book.bookId] ?: return@forEach
                    val p = toScreen(size, world.x, world.y)
                    if (book.title.isNotBlank()) {
                        drawText(measurer, book.title.take(28), topLeft = Offset(p.x + 6f, p.y - 8f), style = titleStyle)
                    }
                }
            }
        }

        if (forming) {
            Text(
                text = "Atlas is still forming · ${(fraction * 100).toInt()}%",
                style = FolioTheme.typography.labelSmall,
                color = FolioTheme.colors.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp)
                    .background(FolioTheme.colors.surface.copy(alpha = 0.7f), RoundedCornerShape(percent = 50))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
}

/** A wobbly radial blob: a coastline whose raggedness falls as cluster tightness rises. */
private fun DrawScope.drawCoastline(center: Offset, radius: Float, tightness: Float, color: Color) {
    if (radius <= 0f) return
    // Tight topics (high cosine) get a smooth coast; diffuse ones get a jagged one.
    val wobble = (1f - tightness.coerceIn(0f, 1f)) * 0.28f
    val points = 24
    val path = Path()
    for (i in 0..points) {
        val a = (i.toFloat() / points) * (2f * Math.PI.toFloat())
        val r = radius * (1f + wobble * sin(a * 5f))
        val x = center.x + r * cos(a)
        val y = center.y + r * sin(a)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    drawPath(
        path = path,
        brush = Brush.radialGradient(
            colors = listOf(color, color.copy(alpha = color.alpha * 0.15f), Color.Transparent),
            center = center,
            radius = radius * 1.3f,
        ),
    )
}

@Composable
private fun AtlasSkeleton() {
    Box(Modifier.fillMaxSize().statusBarsPadding().padding(top = 48.dp)) {
        FolioCoverGridSkeleton(count = 9)
    }
}

@Composable
private fun AtlasMessage(
    title: String,
    body: String,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = FolioTheme.typography.headlineSmall, color = FolioTheme.colors.onSurface)
        Spacer(Modifier.height(12.dp))
        Text(
            body,
            style = FolioTheme.typography.bodyMedium,
            color = FolioTheme.colors.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(0.86f),
        )
        if (actionLabel != null) {
            Spacer(Modifier.height(20.dp))
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}
