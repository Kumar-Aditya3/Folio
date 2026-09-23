package com.folio.reader.ui.atlas

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.folio.reader.ml.AtlasBook
import com.folio.reader.ml.AtlasModel
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.rememberMotionEnabled
import com.folio.reader.ui.theme.rememberSlowPhases
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

// Deep-space palette. The Atlas is always this dark universe, independent of the app's light/dark
// theme, because the galaxy metaphor only reads on a near-black field.
private val COSMIC_TOP = Color(0xFF0B1020)
private val COSMIC_MID = Color(0xFF11152C)
private val COSMIC_BOTTOM = Color(0xFF060811)
private val COSMIC_INK = Color(0xFFEDECFA)
private val COSMIC_MUTED = Color(0xFFA6AAcc)

/**
 * Three enormous, very low-alpha tonal pools for the void. Hoisted to a constant so the background
 * Canvas does not rebuild this list (and its three [Triple]s) on every draw pass.
 */
private val ATLAS_POOLS = listOf(
    Triple(0.26f, 0.30f, Color(0xFF2A2350)),
    Triple(0.80f, 0.24f, Color(0xFF17324F)),
    Triple(0.55f, 0.82f, Color(0xFF15303A)),
)

/**
 * Ambient animation periods, sampled at ~10 Hz via [rememberSlowPhases] instead of a per-vsync
 * [rememberInfiniteTransition]: index 0 is the star twinkle (matching the old 5.2s cycle), index 1
 * is the pool drift (a full sine over 60s, matching the old 30s-each-way reverse tween). At these
 * periods 10 Hz is the same picture, and the background stops invalidating itself every frame.
 */
private val ATLAS_AMBIENT_PERIODS_MS = listOf(5_200L, 60_000L)

/** One topic cluster, prepared for the map's region headings and zoom-in passage reveal. */
private data class ClusterInfo(
    val bookId: String,
    val x: Float,
    val y: Float,
    val mass: Float,
    val color: Color,
    val chunkId: String,
    val label: String,
    val candidates: List<String>,
)

/** A four-point star marker with a soft glow — the map's "book" / region glyph. */
private fun DrawScope.drawSparkle(c: Offset, r: Float, color: Color, alpha: Float) {
    val a = alpha.coerceIn(0f, 1f)
    if (a <= 0.01f) return
    drawCircle(
        brush = Brush.radialGradient(listOf(color.copy(alpha = 0.5f * a), Color.Transparent), center = c, radius = r * 2.4f),
        radius = r * 2.4f, center = c,
    )
    val col = color.copy(alpha = a)
    drawLine(col, Offset(c.x - r, c.y), Offset(c.x + r, c.y), strokeWidth = 1.6f, cap = StrokeCap.Round)
    drawLine(col, Offset(c.x, c.y - r), Offset(c.x, c.y + r), strokeWidth = 1.6f, cap = StrokeCap.Round)
    val d = r * 0.5f
    val diag = color.copy(alpha = a * 0.5f)
    drawLine(diag, Offset(c.x - d, c.y - d), Offset(c.x + d, c.y + d), strokeWidth = 1f, cap = StrokeCap.Round)
    drawLine(diag, Offset(c.x - d, c.y + d), Offset(c.x + d, c.y - d), strokeWidth = 1f, cap = StrokeCap.Round)
    drawCircle(color = Color.White.copy(alpha = a), radius = 1.4f, center = c)
}

/** Hoisted so the deep-zoom label path does not recompile this pattern per cluster per frame. */
private val WHITESPACE_RUN = Regex("\\s+")

/** The first sentence of a passage (bounded), for the deep-zoom label. */
private fun firstSentence(text: String): String {
    val t = text.trim().replace(WHITESPACE_RUN, " ")
    if (t.isEmpty()) return ""
    val end = t.indexOfFirst { it == '.' || it == '!' || it == '?' }
    val s = if (end in 0..159) t.substring(0, end + 1) else t.take(140)
    return s.trim()
}

/**
 * The Atlas — the library rendered as a **galactic map**.
 *
 * Every book is a star, placed by the roll-up's semantic layout (unchanged); books that share
 * meaning sit in the same glowing nebula, coloured by their position so the whole field is one
 * continuous flow of colour rather than flat regions. Similarity edges become constellation lines
 * that light up around a selected book, and selecting a star raises a cosmic bottom sheet. The map
 * pans and pinch-zooms; the nebula is baked once and blitted, so gestures stay cheap.
 *
 * The data (books, positions, similarity edges, reading progress, labels) is exactly what the
 * previous topographic Atlas consumed — only the rendering and interaction changed.
 */
@Composable
fun AtlasScreen(
    state: AtlasUiState,
    exemplarTexts: Map<String, String>,
    refinedLabels: Map<String, String> = emptyMap(),
    authorByBook: Map<String, String> = emptyMap(),
    descriptionByBook: Map<String, String> = emptyMap(),
    onBack: () -> Unit,
    onOpenBook: (String) -> Unit,
    onOpenExemplar: (bookId: String, spineIndex: Int?, fraction: Float?) -> Unit,
    onLoadExemplars: (Collection<String>) -> Unit,
    onRefineBooks: (List<AtlasBook>) -> Unit = {},
    onRunBackfill: () -> Unit = {},
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(COSMIC_TOP, COSMIC_MID, COSMIC_BOTTOM))),
    ) {
        when (state) {
            is AtlasUiState.Loading -> AtlasSkeleton()
            is AtlasUiState.Unavailable -> AtlasMessage(
                title = "The universe is empty",
                body = "Index your library to map it by meaning. Once your books are embedded on this device, the galaxy draws itself.",
                actionLabel = "Index now",
                onAction = onRunBackfill,
            )
            is AtlasUiState.TooFewBooks -> AtlasMessage(
                title = "Not enough stars yet",
                body = "The galaxy appears once ${state.threshold} of your books are embedded — you have ${state.count} so far.",
            )
            is AtlasUiState.Forming -> AtlasMessage(
                title = "The galaxy is forming",
                body = "Your library is still being embedded on this device (${(state.fraction * 100).toInt()}%). The map appears once there is enough to chart.",
                actionLabel = "Keep indexing",
                onAction = onRunBackfill,
            )
            is AtlasUiState.Map -> AtlasGalaxyMap(
                model = state.model,
                forming = state.forming,
                fraction = state.fraction,
                exemplarTexts = exemplarTexts,
                refinedLabels = refinedLabels,
                authorByBook = authorByBook,
                descriptionByBook = descriptionByBook,
                onOpenBook = onOpenBook,
                onOpenExemplar = onOpenExemplar,
                onLoadExemplars = onLoadExemplars,
                onRefineBooks = onRefineBooks,
            )
        }

        // Top bar — deliberately chromeless: just a back affordance and the title over the stars,
        // no opaque panel. Double-tap the map to recenter.
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 4.dp, top = 4.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = COSMIC_INK)
            }
            Spacer(Modifier.width(4.dp))
            Column {
                Text("Atlas", style = FolioTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold), color = COSMIC_INK)
                Text(
                    "YOUR LIBRARY, A UNIVERSE",
                    style = FolioTheme.typography.labelSmall,
                    color = COSMIC_MUTED.copy(alpha = 0.8f),
                )
            }
        }
    }
}

@Composable
private fun AtlasGalaxyMap(
    model: AtlasModel,
    forming: Boolean,
    fraction: Float,
    exemplarTexts: Map<String, String>,
    refinedLabels: Map<String, String>,
    authorByBook: Map<String, String>,
    descriptionByBook: Map<String, String>,
    onOpenBook: (String) -> Unit,
    onOpenExemplar: (bookId: String, spineIndex: Int?, fraction: Float?) -> Unit,
    onLoadExemplars: (Collection<String>) -> Unit,
    onRefineBooks: (List<AtlasBook>) -> Unit,
) {
    val density = LocalDensity.current
    val motion = rememberMotionEnabled()
    val scope = rememberCoroutineScope()
    val textMeasurer = rememberTextMeasurer(cacheSize = 96)

    // ── derived, cached per model ──────────────────────────────────────────────────────────
    val nodes = remember(model) { AtlasGalaxy.nodes(model) }
    val nodeById = remember(nodes) { nodes.associateBy { it.bookId } }
    val bookById = remember(model) { model.books.associateBy { it.bookId } }
    val bgStars = remember(model) { AtlasGalaxy.starfield(model) }
    // Star radii are in dp and density is stable, so resolve them to px once instead of per star
    // per draw pass.
    val bgStarRadii = remember(bgStars, density) {
        FloatArray(bgStars.size) { with(density) { bgStars[it].radiusDp.dp.toPx() } }
    }
    val neighbors = remember(model) {
        val m = HashMap<String, MutableList<Pair<String, Int>>>()
        model.edges.forEach { e ->
            m.getOrPut(e.bookIdA) { mutableListOf() }.add(e.bookIdB to e.weight)
            m.getOrPut(e.bookIdB) { mutableListOf() }.add(e.bookIdA to e.weight)
        }
        m.mapValues { entry -> entry.value.sortedByDescending { it.second } }
    }
    val priorityOrder = remember(nodes) {
        nodes.sortedByDescending { it.degree * 2f + it.sizeScale + it.readFraction }
    }
    // Every topic cluster in the library, largest first — the "regions" the map names at overview
    // and the anchors whose theme phrases + passage appear as you zoom in.
    val clusters = remember(model) {
        val out = ArrayList<ClusterInfo>()
        model.books.forEach { b ->
            b.clusters.forEach { c ->
                out.add(
                    ClusterInfo(
                        bookId = b.bookId, x = c.x, y = c.y, mass = c.mass,
                        color = AtlasGalaxy.ringColor(AtlasGalaxy.angle01(c.x, c.y)),
                        chunkId = c.exemplarChunkId, label = c.label, candidates = c.labelCandidates,
                    )
                )
            }
        }
        out.sortedByDescending { it.mass }
    }

    // Nebula bitmap — baked off the main thread; blitted under the camera once ready.
    val nebula: ImageBitmap? by produceState<ImageBitmap?>(null, model) {
        value = withContext(Dispatchers.Default) { AtlasGalaxy.bakeNebula(model) }
    }

    // ── camera (Animatable so gestures snap and focus transitions can animate) ──────────────
    val scaleAnim = remember(model) { Animatable(1f) }
    val panAnim = remember(model) { Animatable(Offset.Zero, Offset.VectorConverter) }

    // Zoom reveals meaning: crossing into a detail band lazily refines every book's theme labels and
    // loads exemplar passage text, so the phrases and sentences shown when zoomed in are real, not
    // fabricated. snapshotFlow keeps this off the per-frame path (fires only when the band changes).
    LaunchedEffect(model) {
        snapshotFlow { when { scaleAnim.value >= 3.0f -> 2; scaleAnim.value >= 1.9f -> 1; else -> 0 } }
            .distinctUntilChanged()
            .collect { band ->
                if (band >= 1) onRefineBooks(model.books)
                if (band >= 2) onLoadExemplars(clusters.map { it.chunkId })
            }
    }

    // ── selection + its animations ─────────────────────────────────────────────────────────
    var selectedId by remember(model) { mutableStateOf<String?>(null) }
    val halo = remember { Animatable(0f) }
    val connections = remember { Animatable(0f) }
    LaunchedEffect(selectedId) {
        if (selectedId != null) {
            halo.snapTo(0f); connections.snapTo(0f)
            launch { halo.animateTo(1f, tween(340)) }
            launch { connections.animateTo(1f, tween(440)) }
        } else {
            halo.snapTo(0f); connections.snapTo(0f)
        }
    }
    // Lazy label refine + passage load for the selected book only (preserves existing behaviour).
    LaunchedEffect(selectedId) {
        val id = selectedId ?: return@LaunchedEffect
        bookById[id]?.let { b ->
            onRefineBooks(listOf(b))
            onLoadExemplars(b.clusters.map { it.exemplarChunkId })
        }
    }

    // ── open + ambient animations ───────────────────────────────────────────────────────────
    val reveal = remember(model) { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(model, motion) {
        if (motion) { reveal.snapTo(0f); reveal.animateTo(1f, tween(900)) } else reveal.snapTo(1f)
    }
    // Ambient twinkle + pool drift sampled at ~10 Hz (see [ATLAS_AMBIENT_PERIODS_MS]) instead of a
    // per-vsync rememberInfiniteTransition, so an idle Atlas stops invalidating the background every
    // frame. Null under reduce-motion, which also means nothing subscribes and the field is static.
    val ambient = if (motion) rememberSlowPhases(ATLAS_AMBIENT_PERIODS_MS) else null

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        val padX = wPx * 0.08f
        val padY = hPx * 0.10f
        val cx = wPx / 2f
        val cy = hPx / 2f
        fun project(x: Float, y: Float, sc: Float, pn: Offset): Offset {
            val bx = padX + (x * 0.5f + 0.5f) * (wPx - 2 * padX)
            val by = padY + (y * 0.5f + 0.5f) * (hPx - 2 * padY)
            return Offset(cx + (bx - cx) * sc + pn.x, cy + (by - cy) * sc + pn.y)
        }
        fun onScreen(p: Offset, m: Float) = p.x > -m && p.x < wPx + m && p.y > -m && p.y < hPx + m

        val baseCore = with(density) { 2.4.dp.toPx() }
        val ringStroke = with(density) { 1.2.dp.toPx() }
        val lineStroke = with(density) { 1.1.dp.toPx() }
        val labelMaxPx = with(density) { 116.dp.toPx() }.roundToInt()
        val labelBelow = with(density) { 6.dp.toPx() }
        val labelStyle = FolioTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
        val regionStyle = FolioTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
        val descStyle = FolioTheme.typography.labelSmall.copy(letterSpacing = 1.4.sp)
        val passageStyle = FolioTheme.typography.bodySmall.copy(fontWeight = FontWeight.Light)

        // ── Label layouts, measured once per data change instead of per frame ───────────────
        // The draw lambda below runs every pan/zoom frame; measuring text (plus the
        // firstSentence regex and the candidate joinToString/uppercase) inside it
        // re-laid ~40 strings per frame and was the Atlas's chief gesture-jank source.
        // Positions still depend on the camera, but the glyph layouts do not, so they
        // are hoisted here keyed on the data — re-measured only when a label refines or
        // a passage loads, never on pan or zoom.
        val titleLayouts = remember(nodes, labelStyle, labelMaxPx, textMeasurer) {
            HashMap<String, TextLayoutResult>(nodes.size).apply {
                nodes.forEach { n ->
                    put(
                        n.bookId,
                        textMeasurer.measure(
                            n.title.ifBlank { "Untitled" }, labelStyle,
                            overflow = TextOverflow.Ellipsis, maxLines = 2,
                            constraints = Constraints(maxWidth = labelMaxPx),
                        ),
                    )
                }
            }
        }
        val regionLayouts = remember(clusters, refinedLabels, regionStyle, descStyle, labelMaxPx, textMeasurer) {
            val regionMax = (labelMaxPx * 1.4f).toInt()
            HashMap<String, Pair<TextLayoutResult, TextLayoutResult?>>().apply {
                clusters.forEach { cl ->
                    val primary = (refinedLabels[cl.chunkId]?.takeIf { it.isNotBlank() } ?: cl.label)
                        .ifBlank { cl.candidates.firstOrNull().orEmpty() }
                    if (primary.isBlank()) return@forEach
                    val res = textMeasurer.measure(
                        primary, regionStyle, overflow = TextOverflow.Ellipsis, maxLines = 1,
                        constraints = Constraints(maxWidth = regionMax),
                    )
                    val desc = cl.candidates.take(3).joinToString("  ·  ").uppercase()
                    val dRes = if (desc.isNotBlank()) textMeasurer.measure(
                        desc, descStyle, overflow = TextOverflow.Ellipsis, maxLines = 1,
                        constraints = Constraints(maxWidth = regionMax),
                    ) else null
                    put(cl.chunkId, res to dRes)
                }
            }
        }
        val passageLayouts = remember(clusters, exemplarTexts, passageStyle, labelMaxPx, textMeasurer) {
            val passMax = (labelMaxPx * 1.7f).toInt()
            HashMap<String, TextLayoutResult>().apply {
                clusters.forEach { cl ->
                    val raw = exemplarTexts[cl.chunkId] ?: return@forEach
                    val sentence = firstSentence(raw)
                    if (sentence.isBlank()) return@forEach
                    put(
                        cl.chunkId,
                        textMeasurer.measure(
                            "\u201C$sentence\u201D", passageStyle,
                            overflow = TextOverflow.Ellipsis, maxLines = 3,
                            constraints = Constraints(maxWidth = passMax),
                        ),
                    )
                }
            }
        }

        // Clamp a pan offset so the galaxy can never be flung entirely off-screen: the
        // screen centre must stay inside the (scaled) content box, which still allows
        // generous panning while always keeping stars in view.
        fun clampPan(p: Offset, sc: Float): Offset {
            val maxX = (cx - padX) * sc
            val maxY = (cy - padY) * sc
            return Offset(p.x.coerceIn(-maxX, maxX), p.y.coerceIn(-maxY, maxY))
        }

        fun coreRadiusOf(sizeScale: Float, selected: Boolean) = baseCore * sizeScale * (if (selected) 1.7f else 1f)

        fun focusOn(id: String) {
            val n = nodeById[id] ?: return
            val cur = project(n.x, n.y, scaleAnim.value, panAnim.value)
            // Lift the star toward the upper third so the sheet won't cover it.
            val target = panAnim.value + Offset(cx - cur.x, hPx * 0.36f - cur.y)
            scope.launch { panAnim.animateTo(target, tween(340)) }
        }
        fun select(id: String) {
            selectedId = id
            focusOn(id)
        }

        // ── Layer 1 (behind): cosmic wash + drifting pools + twinkling background stars ───────
        Canvas(Modifier.fillMaxSize()) {
            val sc = scaleAnim.value
            val pn = panAnim.value
            val rv = reveal.value
            val bgReveal = AtlasGalaxy.smoothstep(0f, 0.3f, rv)
            // One read of the 10 Hz ambient state drives both the pool drift and the star twinkle.
            val phases = ambient?.value
            val dx = if (phases != null) sin(phases[1]) * 0.02f else 0f

            drawRect(Brush.verticalGradient(listOf(COSMIC_TOP, COSMIC_MID, COSMIC_BOTTOM)))
            // Three enormous, very low-alpha pools give the void tonal variation without lifting it.
            ATLAS_POOLS.forEach { (fx, fy, c) ->
                val center = Offset((fx + dx) * wPx, fy * hPx)
                val rad = maxOf(wPx, hPx) * 0.62f
                drawCircle(
                    brush = Brush.radialGradient(listOf(c.copy(alpha = 0.55f * bgReveal), Color.Transparent), center = center, radius = rad),
                    radius = rad, center = center,
                )
            }

            val starReveal = AtlasGalaxy.smoothstep(0.3f, 1f, rv)
            if (starReveal > 0.01f) {
                val phase = phases?.get(0) ?: 0f
                for (i in bgStars.indices) {
                    val s = bgStars[i]
                    val p = project(s.x, s.y, sc, pn)
                    if (!onScreen(p, 8f)) continue
                    val tw = if (phases != null) (1f - s.twinkleAmp) + s.twinkleAmp * (0.5f + 0.5f * sin(phase + s.twinklePhase)) else 1f
                    val a = (s.baseAlpha * tw * starReveal).coerceIn(0f, 0.85f)
                    val rPx = bgStarRadii[i]
                    if (s.glow) {
                        drawCircle(
                            brush = Brush.radialGradient(listOf(s.color.copy(alpha = a * 0.55f), Color.Transparent), center = p, radius = rPx * 3.2f),
                            radius = rPx * 3.2f, center = p,
                        )
                    }
                    drawCircle(color = s.color.copy(alpha = a), radius = rPx, center = p)
                }
            }
        }

        // ── Layer 2 (front): nebula, constellation lines, book stars, labels ──────────────────
        Canvas(
            Modifier
                .fillMaxSize()
                .semantics {
                    contentDescription = buildString {
                        append("Galaxy map of ${nodes.size} books, each a star; closer stars are more similar.")
                        val sel = selectedId?.let { nodeById[it]?.title }
                        if (sel != null) append(" Selected book: $sel.")
                        else append(" No book selected. Pinch to zoom, drag to pan, double-tap to recenter.")
                    }
                }
                .pointerInput(model) {
                    detectTransformGestures { centroid, panChange, zoomChange, _ ->
                        val sc0 = scaleAnim.value
                        val sc1 = (sc0 * zoomChange).coerceIn(0.7f, 6f)
                        val factor = sc1 / sc0 - 1f
                        val p0 = panAnim.value
                        // Keep the point under the fingers fixed while zooming (pivot on
                        // the gesture centroid, not the screen centre), then apply the drag.
                        val pivoted = p0 - (centroid - Offset(cx, cy) - p0) * factor
                        val next = clampPan(pivoted + panChange, sc1)
                        scope.launch { scaleAnim.snapTo(sc1) }
                        scope.launch { panAnim.snapTo(next) }
                    }
                }
                .pointerInput(model, wPx, hPx) {
                    detectTapGestures(
                        onDoubleTap = {
                            selectedId = null
                            scope.launch { scaleAnim.animateTo(1f, tween(420)) }
                            scope.launch { panAnim.animateTo(Offset.Zero, tween(420)) }
                        },
                        onTap = { tap ->
                            val sc = scaleAnim.value
                            val pn = panAnim.value
                            var best: String? = null
                            var bestD = Float.MAX_VALUE
                            nodes.forEach { n ->
                                val p = project(n.x, n.y, sc, pn)
                                val d = hypot(tap.x - p.x, tap.y - p.y)
                                if (d < bestD) { bestD = d; best = n.bookId }
                            }
                            val threshold = with(density) { 34.dp.toPx() }
                            if (best != null && bestD <= threshold) select(best!!) else selectedId = null
                        },
                    )
                },
        ) {
            val sc = scaleAnim.value
            val pn = panAnim.value
            val rv = reveal.value

            // Nebula — additive glow so it reads as luminous gas over the stars.
            val bmp = nebula
            if (bmp != null) {
                val nebAlpha = AtlasGalaxy.smoothstep(0.15f, 0.7f, rv)
                if (nebAlpha > 0.01f) {
                    val tl = project(-AtlasGalaxy.EXTENT, -AtlasGalaxy.EXTENT, sc, pn)
                    val br = project(AtlasGalaxy.EXTENT, AtlasGalaxy.EXTENT, sc, pn)
                    val w = br.x - tl.x
                    val h = br.y - tl.y
                    if (w > 0f && h > 0f) {
                        drawImage(
                            image = bmp,
                            dstOffset = IntOffset(tl.x.roundToInt(), tl.y.roundToInt()),
                            dstSize = IntSize(w.roundToInt(), h.roundToInt()),
                            alpha = nebAlpha,
                            blendMode = BlendMode.Plus,
                        )
                    }
                }
            }

            val selId = selectedId
            val neighborSet: Set<String>? = selId?.let { neighbors[it]?.map { p -> p.first }?.toHashSet() ?: hashSetOf() }

            // Constellation lines from the selected book to its nearest neighbours.
            if (selId != null) {
                val sel = nodeById[selId]
                if (sel != null) {
                    val sp = project(sel.x, sel.y, sc, pn)
                    val cAlpha = connections.value
                    neighbors[selId]?.take(6)?.forEach { (nid, _) ->
                        val nn = nodeById[nid] ?: return@forEach
                        val np = project(nn.x, nn.y, sc, pn)
                        drawLine(
                            color = lerp(sel.color, nn.color, 0.5f).copy(alpha = 0.4f * cAlpha),
                            start = sp, end = np, strokeWidth = lineStroke, cap = StrokeCap.Round,
                        )
                    }
                }
            }

            // Book stars.
            val starReveal = AtlasGalaxy.smoothstep(0.35f, 1f, rv)
            nodes.forEach { n ->
                val p = project(n.x, n.y, sc, pn)
                val isSel = n.bookId == selId
                val glowR = coreRadiusOf(n.sizeScale, isSel) * (if (isSel) 6.5f else 3.4f)
                if (!onScreen(p, glowR)) return@forEach
                val related = neighborSet?.contains(n.bookId) == true
                val dim = when {
                    selId == null -> 1f
                    isSel -> 1f
                    related -> 0.92f
                    else -> 0.4f
                }
                val bright = (0.45f + 0.55f * n.readFraction) * dim * starReveal
                val coreR = coreRadiusOf(n.sizeScale, isSel)

                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(n.color.copy(alpha = 0.5f * bright), n.color.copy(alpha = 0.12f * bright), Color.Transparent),
                        center = p, radius = glowR,
                    ),
                    radius = glowR, center = p,
                )
                val coreColor = lerp(n.color, Color.White, 0.72f)
                drawCircle(color = coreColor.copy(alpha = (0.85f * bright + 0.12f).coerceIn(0f, 1f)), radius = coreR, center = p)

                if (isSel) {
                    val hv = halo.value
                    drawCircle(
                        brush = Brush.radialGradient(listOf(n.color.copy(alpha = 0.16f * hv), Color.Transparent), center = p, radius = glowR * 1.5f),
                        radius = glowR * 1.5f, center = p,
                    )
                    drawCircle(color = Color.White.copy(alpha = 0.22f * hv), radius = coreR * 4.2f * (0.7f + 0.3f * hv), center = p, style = Stroke(width = ringStroke))
                    drawCircle(color = n.color.copy(alpha = 0.30f * hv), radius = coreR * 6.6f * (0.6f + 0.4f * hv), center = p, style = Stroke(width = ringStroke * 0.85f))
                }
            }

            // ── Labels & meaning, revealed progressively by zoom ──────────────────────────────
            // Overview: region (genre-like) headings dominate. Zoom in: book titles fill in, region
            // headings recede. Zoom deep: a real passage sentence surfaces under each cluster.
            val placed = ArrayList<Rect>(48)
            val reveal2 = AtlasGalaxy.smoothstep(0.4f, 1f, rv)
            val regionAlpha = reveal2 * (1f - AtlasGalaxy.smoothstep(1.9f, 2.9f, sc))
            val titleAlpha = reveal2
            val passageAlpha = AtlasGalaxy.smoothstep(3.2f, 4.3f, sc) * reveal2

            fun tryPlace(left: Float, top: Float, w: Float, h: Float): Boolean {
                if (left < 6f || left + w > wPx - 6f || top + h > hPx - 6f || top < hPx * 0.02f) return false
                val r = Rect(left - 5f, top - 4f, left + w + 5f, top + h + 4f)
                if (placed.any { it.overlaps(r) }) return false
                placed.add(r); return true
            }

            // Selected book — full title on a dark pill, always shown.
            if (selId != null) {
                val n = nodeById[selId]
                val res = n?.let { titleLayouts[it.bookId] }
                if (n != null && res != null) {
                    val p = project(n.x, n.y, sc, pn)
                    val w = res.size.width.toFloat(); val h = res.size.height.toFloat()
                    val coreR = coreRadiusOf(n.sizeScale, true)
                    val above = p.y > hPx * 0.5f
                    val tlY = if (above) p.y - coreR - 12f - h else p.y + coreR + labelBelow
                    val tlX = (p.x - w / 2f).coerceIn(10f, (wPx - 10f - w).coerceAtLeast(10f))
                    drawRoundRect(Color(0xF00A0C18), topLeft = Offset(tlX - 8f, tlY - 6f), size = Size(w + 16f, h + 12f), cornerRadius = CornerRadius(10f, 10f))
                    drawText(res, color = Color(0xFFF4F2FF), topLeft = Offset(tlX, tlY))
                    placed.add(Rect(tlX - 8f, tlY - 6f, tlX + w + 8f, tlY + h + 6f))
                }
            }

            // Region headings at the biggest clusters — sparkle + theme phrase + descriptor words.
            if (regionAlpha > 0.02f) {
                var placedR = 0; var scanned = 0
                for (cl in clusters) {
                    if (placedR >= 9 || scanned > 44) break
                    val p = project(cl.x, cl.y, sc, pn)
                    if (!onScreen(p, 20f)) continue
                    scanned++
                    val (res, dRes) = regionLayouts[cl.chunkId] ?: continue
                    val w = maxOf(res.size.width.toFloat(), dRes?.size?.width?.toFloat() ?: 0f)
                    val totalH = res.size.height.toFloat() + (dRes?.let { it.size.height.toFloat() + 3f } ?: 0f)
                    val top = p.y + 10f
                    if (!tryPlace(p.x - w / 2f, top, w, totalH)) continue
                    // Dark backing so a heading stays legible over a bright nebula core.
                    drawRoundRect(Color(0xFF070912).copy(alpha = 0.5f * regionAlpha), topLeft = Offset(p.x - w / 2f - 7f, top - 5f), size = Size(w + 14f, totalH + 10f), cornerRadius = CornerRadius(9f, 9f))
                    drawSparkle(p, 5.5f, lerp(cl.color, Color.White, 0.5f), regionAlpha * 0.9f)
                    drawText(res, color = Color(0xFFF1F0FA).copy(alpha = regionAlpha), topLeft = Offset(p.x - res.size.width / 2f, top))
                    if (dRes != null) drawText(dRes, color = cl.color.copy(alpha = regionAlpha * 0.85f), topLeft = Offset(p.x - dRes.size.width / 2f, top + res.size.height + 3f))
                    placedR++
                }
            }

            // Book-title labels — a limited, collision-free set, more of them as you zoom.
            if (titleAlpha > 0.02f) {
                val maxLabels = when { sc < 1.5f -> 6; sc < 2.4f -> 14; else -> 26 }
                var count = 0; var scanned = 0
                for (n in priorityOrder) {
                    if (count >= maxLabels || scanned > 90) break
                    if (n.bookId == selId || n.title.isBlank()) continue
                    val p = project(n.x, n.y, sc, pn)
                    if (!onScreen(p, 22f)) continue
                    scanned++
                    val res = titleLayouts[n.bookId] ?: continue
                    val w = res.size.width.toFloat(); val h = res.size.height.toFloat()
                    val top = p.y + coreRadiusOf(n.sizeScale, false) + labelBelow
                    if (!tryPlace(p.x - w / 2f, top, w, h)) continue
                    val a = titleAlpha * (if (selId == null) 0.92f else if (neighborSet?.contains(n.bookId) == true) 0.95f else 0.35f)
                    // Faint dark backing keeps titles readable over bright nebula.
                    drawRoundRect(Color(0xFF070912).copy(alpha = 0.42f * a), topLeft = Offset(p.x - w / 2f - 6f, top - 3f), size = Size(w + 12f, h + 6f), cornerRadius = CornerRadius(8f, 8f))
                    drawText(res, color = Color(0xFFDDE0F4), topLeft = Offset(p.x - w / 2f, top), alpha = a)
                    count++
                }
            }

            // Deep zoom: a real sentence from the book, under its cluster (loaded lazily on zoom-in).
            if (passageAlpha > 0.02f) {
                var placedP = 0; var scanned = 0
                for (cl in clusters) {
                    if (placedP >= 6 || scanned > 44) break
                    val res = passageLayouts[cl.chunkId] ?: continue
                    val p = project(cl.x, cl.y, sc, pn)
                    if (!onScreen(p, 20f)) continue
                    scanned++
                    val w = res.size.width.toFloat(); val h = res.size.height.toFloat()
                    val top = p.y + 12f
                    if (!tryPlace(p.x - w / 2f, top, w, h)) continue
                    drawRoundRect(Color(0xC00A0C18), topLeft = Offset(p.x - w / 2f - 6f, top - 4f), size = Size(w + 12f, h + 8f), cornerRadius = CornerRadius(8f, 8f))
                    drawText(res, color = Color(0xFFE7E5F2).copy(alpha = passageAlpha), topLeft = Offset(p.x - w / 2f, top))
                    placedP++
                }
            }
        }

        // Forming indicator, top-center under the bar.
        if (forming) {
            Text(
                text = "Galaxy still forming · ${(fraction * 100).toInt()}%",
                style = FolioTheme.typography.labelSmall,
                color = COSMIC_MUTED,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 64.dp)
                    .background(Color(0xCC0A0C18), RoundedCornerShape(percent = 50))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }

        // Legend — the map's key. Hidden while a book is selected (the sheet takes the stage).
        if (selectedId == null) {
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .navigationBarsPadding()
                    .padding(start = 16.dp, bottom = 22.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0x73090C18))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                LegendRow("Each dot is a book", Color(0xFFDDE0F4))
                LegendRow("Closer = more similar", Color(0xFF8FA6E6))
                LegendRow("Colours = themes", Color(0xFFB98FC9))
                LegendRow("Pinch to explore", Color(0xFF7FC7C0))
            }
        }

        // Recenter — a small compass that eases the camera back to the whole galaxy.
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 16.dp, bottom = 22.dp)
                .size(46.dp)
                .clip(CircleShape)
                .background(Color(0x73090C18))
                .clickable {
                    selectedId = null
                    scope.launch { scaleAnim.animateTo(1f, tween(440)) }
                    scope.launch { panAnim.animateTo(Offset.Zero, tween(440)) }
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(22.dp)) {
                val cx0 = size.width / 2f; val cy0 = size.height / 2f
                val r = size.minDimension * 0.42f
                val path = Path().apply {
                    moveTo(cx0, cy0 - r); lineTo(cx0 + r * 0.62f, cy0); lineTo(cx0, cy0 + r); lineTo(cx0 - r * 0.62f, cy0); close()
                }
                drawPath(path, color = Color(0xFFB9C0E8), style = Stroke(width = 1.6f))
                // North needle, brighter.
                drawLine(Color(0xFFE8ECFF), Offset(cx0, cy0), Offset(cx0, cy0 - r), strokeWidth = 1.6f, cap = StrokeCap.Round)
                drawCircle(Color(0xFFE8ECFF), radius = 1.6f, center = Offset(cx0, cy0))
            }
        }

        // ── Selected-book cosmic sheet ────────────────────────────────────────────────────────
        val selBook = selectedId?.let { bookById[it] }
        val selNode = selectedId?.let { nodeById[it] }
        val accent = selNode?.color?.let { lerp(it, Color.White, 0.18f) } ?: Color(0xFF8AA0FF)
        val related = remember(selectedId, model) {
            val id = selectedId ?: return@remember emptyList<AtlasRelated>()
            (neighbors[id] ?: emptyList()).take(10).mapNotNull { (nid, _) ->
                val b = bookById[nid] ?: return@mapNotNull null
                AtlasRelated(
                    id = nid,
                    title = b.title,
                    author = authorByBook[nid].orEmpty(),
                    coverPath = b.coverPath,
                    accent = nodeById[nid]?.color ?: accent,
                )
            }
        }
        val themes = remember(selectedId, refinedLabels) {
            val b = selBook ?: return@remember emptyList<String>()
            b.clusters.sortedByDescending { it.mass }
                .map { c -> (refinedLabels[c.exemplarChunkId]?.takeIf { it.isNotBlank() } ?: c.label) }
                .filter { it.isNotBlank() }
                .distinct()
        }
        val passage = selBook?.clusters?.sortedByDescending { it.mass }
            ?.firstNotNullOfOrNull { c -> exemplarTexts[c.exemplarChunkId]?.takeIf { it.isNotBlank() } }

        AtlasBookSheet(
            book = selBook,
            author = selectedId?.let { authorByBook[it] },
            accent = accent,
            related = related,
            themes = themes,
            passage = passage,
            description = selectedId?.let { descriptionByBook[it] },
            onOpen = onOpenBook,
            onReadPassage = {
                val b = selBook
                if (b != null) {
                    val c = b.clusters.maxByOrNull { it.mass }
                    onOpenExemplar(b.bookId, c?.exemplarSpineIndex?.takeIf { it >= 0 }, c?.exemplarFraction?.takeIf { it >= 0f })
                }
            },
            onSelectRelated = { id -> select(id) },
            onDismiss = { selectedId = null },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun AtlasSkeleton() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        androidx.compose.material3.CircularProgressIndicator(color = COSMIC_INK)
        Spacer(Modifier.height(16.dp))
        Text("Charting your universe…", style = FolioTheme.typography.bodyMedium, color = COSMIC_MUTED)
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
        Text(title, style = FolioTheme.typography.headlineSmall, color = COSMIC_INK)
        Spacer(Modifier.height(12.dp))
        Text(body, style = FolioTheme.typography.bodyMedium, color = COSMIC_MUTED, modifier = Modifier.fillMaxWidth(0.86f))
        if (actionLabel != null) {
            Spacer(Modifier.height(20.dp))
            androidx.compose.material3.Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
private fun LegendRow(text: String, dot: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(8.dp))
        Text(text, style = FolioTheme.typography.labelSmall, color = COSMIC_MUTED)
    }
}
