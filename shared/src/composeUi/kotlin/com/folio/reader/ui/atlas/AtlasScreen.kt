package com.folio.reader.ui.atlas

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.folio.reader.ml.AtlasBook
import com.folio.reader.ml.AtlasModel
import com.folio.reader.ui.components.FolioCoverGridSkeleton
import com.folio.reader.ui.components.rememberCoverAccent
import com.folio.reader.ui.components.LocalGlassCapabilities
import com.folio.reader.ui.components.folioField
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.LocalFolioDaylight
import com.folio.reader.ui.theme.atmosphere
import com.folio.reader.ui.theme.folioAtlasShader
import com.folio.reader.ui.theme.lightDirection
import com.folio.reader.ui.theme.rememberMotionEnabled
import com.folio.reader.ui.theme.rememberShaderSupported
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/** Past this zoom the map surfaces each topic's own words and taps deep-link into the passage. */
private const val DETAIL_ZOOM = 2.2f

private val ATLAS_STOPWORDS = setOf(
    "the", "and", "that", "with", "from", "this", "have", "were", "what", "when", "which", "their",
    "there", "they", "them", "then", "than", "into", "over", "your", "you", "was", "are", "for",
    "not", "but", "his", "her", "she", "him", "had", "has", "would", "could", "should", "about",
    "been", "will", "upon", "said", "such", "only", "very", "more", "most", "some", "like", "just",
)

/** A few salient words from a passage — a legible topic label rather than an abstract dot. */
private fun topWords(text: String, n: Int = 3): String =
    text.lowercase()
        .split(Regex("[^a-z]+"))
        .filter { it.length >= 4 && it !in ATLAS_STOPWORDS }
        .distinct()
        .take(n)
        .joinToString(" · ")

/**
 * The Atlas — the library charted by meaning.
 *
 * A real map: each book is a lit landmass placed by the roll-up's 2D topic layout, its coastline
 * ragged in proportion to how diffuse its topics are, joined to kindred books by faint borders,
 * floating on a sea washed in the theme's own deep tint. It is deliberately **fit-to-screen** — no
 * free pan/zoom and no canvas-drawn text — so the whole class of off-screen `drawText` crashes and
 * gesture-math bugs simply cannot occur: the geometry is drawn on a [Canvas], and the labels are
 * ordinary Compose [Text] laid over it (which clip harmlessly rather than throw). A tap on a
 * landmass opens that book.
 *
 * [exemplarTexts]/[onOpenExemplar]/[onLoadExemplars] are kept in the signature so the route wiring
 * is unchanged; this fit-to-screen map does not surface per-passage text, so they go unused here.
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
    Box(Modifier.fillMaxSize().folioField()) {
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
    val pools = atmosphere.pools.ifEmpty { listOf(FolioTheme.colors.accentProgress) }
    // Per-book accent (cover-derived, cached inside rememberCoverAccent). Cycled fallback so books
    // without a cover still read apart.
    val accents: Map<String, Color> = model.books.mapIndexed { i, b ->
        b.bookId to rememberCoverAccent(b.coverPath, pools[i % pools.size])
    }.toMap()

    // Book centroid = mass-weighted mean of its cluster positions, in layout space [-1, 1].
    val centroids: Map<String, Offset> = remember(model) {
        model.books.associate { book ->
            var sx = 0f; var sy = 0f; var sw = 0f
            book.clusters.forEach { c -> sx += c.x * c.mass; sy += c.y * c.mass; sw += c.mass }
            book.bookId to if (sw > 0f) Offset(sx / sw, sy / sw) else Offset(0f, 0f)
        }
    }

    val seaColor = atmosphere.sunkenFill
    val edgeColor = FolioTheme.colors.onSurface
    val labelColor = FolioTheme.colors.onSurface
    val shadowColor = atmosphere.shadowSpot
    val density = LocalDensity.current

    // Lighting + capability gates for the visual layer.
    val light = LocalFolioDaylight.current.lightDirection()
    val motion = rememberMotionEnabled()
    val shaderEnabled = LocalGlassCapabilities.current.specular && rememberShaderSupported()

    // Entrance: the continents rise into place once. Skipped entirely under reduce-motion.
    val reveal = remember { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(model, motion) {
        if (motion) { reveal.snapTo(0f); reveal.animateTo(1f, animationSpec = tween(durationMillis = 750)) }
        else reveal.snapTo(1f)
    }

    // Camera. Pinch to zoom, drag to pan — safe now that labels are Compose Text (they clip) rather
    // than canvas drawText (which threw on off-screen anchors). Both the Canvas geometry and the
    // label offsets read the same camera, so they stay locked together.
    var scale by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val zoomedIn = scale >= DETAIL_ZOOM

    // Zoomed in, the map means something concrete: load each topic's exemplar passage so its own
    // words can label it and a tap can dive into that passage in the reader.
    LaunchedEffect(zoomedIn, model) {
        if (zoomedIn) onLoadExemplars(model.books.flatMap { b -> b.clusters.map { it.exemplarChunkId } })
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        val padX = wPx * 0.12f
        val padY = hPx * 0.16f
        val minDim = min(wPx, hPx)
        val cx = wPx / 2f
        val cy = hPx / 2f
        fun px(world: Offset): Offset {
            val bx = padX + (world.x * 0.5f + 0.5f) * (wPx - 2 * padX)
            val by = padY + (world.y * 0.5f + 0.5f) * (hPx - 2 * padY)
            return Offset(cx + (bx - cx) * scale + pan.x, cy + (by - cy) * scale + pan.y)
        }

        Canvas(
            Modifier
                .fillMaxSize()
                .folioAtlasShader(sea = seaColor, light = atmosphere.rimLight, enabled = shaderEnabled)
                .pointerInput(model) {
                    detectTransformGestures { _, panChange, zoomChange, _ ->
                        scale = (scale * zoomChange).coerceIn(0.7f, 6f)
                        pan += panChange
                    }
                }
                .pointerInput(model, wPx, hPx, zoomedIn) {
                    detectTapGestures { tap ->
                        if (zoomedIn) {
                            // Deep zoom: tap the nearest topic region → open that passage in the reader.
                            var hitBook: String? = null
                            var hitCluster: com.folio.reader.ml.AtlasCluster? = null
                            var bestD = Float.MAX_VALUE
                            model.books.forEach { b ->
                                b.clusters.forEach { c ->
                                    val p = px(Offset(c.x, c.y))
                                    val d = hypot(tap.x - p.x, tap.y - p.y)
                                    if (d < bestD) { bestD = d; hitBook = b.bookId; hitCluster = c }
                                }
                            }
                            val c = hitCluster
                            if (c != null && bestD < minDim * 0.14f * scale) {
                                onOpenExemplar(hitBook!!, c.exemplarSpineIndex.takeIf { it >= 0 }, c.exemplarFraction.takeIf { it >= 0f })
                            }
                        } else {
                            var bestId: String? = null
                            var bestD = Float.MAX_VALUE
                            centroids.forEach { (id, w) ->
                                val p = px(w)
                                val d = hypot(tap.x - p.x, tap.y - p.y)
                                if (d < bestD) { bestD = d; bestId = id }
                            }
                            bestId?.let { if (bestD < minDim * 0.18f * scale) onOpenBook(it) }
                        }
                    }
                }
        ) {
            // The sea: a soft central wash so the land floats on ocean rather than void.
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(seaColor.copy(alpha = 0.22f), seaColor.copy(alpha = 0.06f), Color.Transparent),
                    center = Offset(wPx / 2f, hPx / 2f),
                    radius = maxOf(wPx, hPx) * 0.7f,
                ),
            )

            // Borders under the land: faint lines between books that share a coastline.
            model.edges.forEach { edge ->
                val a = centroids[edge.bookIdA] ?: return@forEach
                val b = centroids[edge.bookIdB] ?: return@forEach
                val w = (edge.weight.toFloat() / 6f).coerceIn(0f, 1f)
                drawLine(
                    color = edgeColor.copy(alpha = 0.05f + 0.16f * w),
                    start = px(a), end = px(b), strokeWidth = 1f + 2.5f * w,
                )
            }

            // Shadow pass first (all landmasses), offset away from the sun so the continents read
            // as raised off the sea. Drawn under every landmass so no shadow falls on top of land.
            val shadowShift = minDim * 0.02f * scale * reveal.value
            model.books.forEach { book ->
                book.clusters.forEach { c ->
                    val center = px(Offset(c.x, c.y))
                    val radius = minDim * (0.055f + 0.11f * c.mass) * scale * reveal.value
                    if (radius > 0f) {
                        val s = Offset(center.x - light.first * shadowShift, center.y - light.second * shadowShift)
                        drawCoastline(s, radius * 1.04f, c.tightness, shadowColor.copy(alpha = 0.28f * reveal.value))
                    }
                }
            }

            // Land: each cluster is a topic region, tinted by its book, lit by reading progress,
            // its coastline raggeder the more diffuse the topic. Radius/alpha ride the entrance reveal.
            model.books.forEach { book ->
                val accent = accents[book.bookId] ?: return@forEach
                val lit = (0.40f + 0.45f * book.readFraction) * reveal.value
                book.clusters.forEach { c ->
                    val center = px(Offset(c.x, c.y))
                    val radius = minDim * (0.055f + 0.11f * c.mass) * scale * reveal.value
                    drawCoastline(center, radius, c.tightness, accent.copy(alpha = lit))
                }
            }
        }

        // Labels as Compose Text (bounded, clipped — never the off-screen drawText crash).
        // Far out: one title per book. Zoomed in: each topic region labelled with its own words,
        // so the map reads as "this part of this book is about X" rather than an abstract blob.
        val halfLabel = with(density) { 58.dp.toPx() }
        if (zoomedIn) {
            model.books.forEach { book ->
                book.clusters.forEach { c ->
                    val words = exemplarTexts[c.exemplarChunkId]?.let { topWords(it) }.orEmpty()
                    if (words.isBlank()) return@forEach
                    val p = px(Offset(c.x, c.y))
                    Text(
                        text = words,
                        style = FolioTheme.typography.labelSmall,
                        color = labelColor.copy(alpha = 0.9f),
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .widthIn(max = 116.dp)
                            .offset { IntOffset((p.x - halfLabel).toInt(), p.y.toInt()) },
                    )
                }
            }
        } else {
            model.books.forEach { book ->
                if (book.title.isBlank()) return@forEach
                val p = px(centroids[book.bookId] ?: Offset.Zero)
                Text(
                    text = book.title,
                    style = FolioTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = labelColor,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .widthIn(max = 116.dp)
                        .offset { IntOffset((p.x - halfLabel).toInt(), p.y.toInt()) },
                )
            }
        }

        if (forming) {
            Text(
                text = "Atlas is still forming · ${(fraction * 100).toInt()}%",
                style = FolioTheme.typography.labelSmall,
                color = FolioTheme.colors.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 20.dp)
                    .background(FolioTheme.colors.surface.copy(alpha = 0.85f), RoundedCornerShape(percent = 50))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
}

/** A wobbly radial blob: a coastline whose raggedness falls as cluster tightness rises. */
private fun DrawScope.drawCoastline(center: Offset, radius: Float, tightness: Float, color: Color) {
    if (radius <= 0f) return
    val wobble = (1f - tightness.coerceIn(0f, 1f)) * 0.26f
    val points = 26
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
            colors = listOf(color, color.copy(alpha = color.alpha * 0.35f), Color.Transparent),
            center = center,
            radius = radius * 1.35f,
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
