package com.folio.reader.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.folio.reader.ui.theme.FolioTheme
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * **The page block.** Position carried by the material of the page rather than by
 * chrome about it.
 *
 * Every reading app puts a bar and a "3 / 12" at the foot of the reader. A codex does
 * neither: it has a *block* of pages whose thickness you can see and feel — the read
 * pages accumulate under the left thumb, the unread pages thin out under the right.
 * This draws exactly that, edge-on, where the bar used to be, with the leaf the reader
 * is on standing proud between the two stacks.
 *
 * Three rules shaped it. It is cut from the **book's own paper** ([paper], [ink] come
 * from the reader theme, which is independent of the app palette), not from the app's
 * chrome. It is **whole-book** — a block that emptied at every chapter boundary would
 * be a lie about how much of the book is left — while [chapterStops] keep the chapter
 * structure legible as notches and a faint wash. And it **is the affordance**: the
 * band takes the tap and drag the scrubber used to, so losing the bar strands nobody.
 *
 * Plain `Canvas`-level drawing, no shaders, and nothing that assumes it can composite
 * *into* the page: on Android the page is a native surface and on desktop a
 * heavyweight browser window, so the cue is drawn beside the page area, inside the
 * reader's bottom chrome, where Compose is guaranteed to be composited.
 */

// ── pure geometry (unit-tested on desktop) ───────────────────────────────────

/**
 * The share of the block's thickness each stack keeps at its own extreme: the
 * trailing stack when nothing has been read, the leading stack when the book is
 * finished. Without it the cue vanishes exactly where the reader most wants to know
 * where they are — and it would be a lie besides, since the reader is always
 * holding a leaf and a closed book still has covers.
 */
internal const val PAGE_BLOCK_MIN_EDGE_SHARE = 0.14f

/** Closest two leaf edges may sit and still read as two edges, in the caller's unit. */
internal const val PAGE_BLOCK_MIN_LINE_PITCH = 2.5f

/** Past this a stack reads as noise rather than as pages. */
internal const val PAGE_BLOCK_MAX_LEAF_LINES = 12

/** Ceiling on chapter notches: a 900-chapter book must not become a solid hatch. */
internal const val PAGE_BLOCK_MAX_NOTCHES = 48

/**
 * The block's resolved geometry. [readThickness] and [unreadThickness] always sum
 * to [totalThickness] — no gap, no overlap — because that single invariant is what
 * makes the two stacks read as one object instead of as two bars.
 */
@Immutable
internal data class PageBlockGeometry(
    /** Reading position through the whole book, coerced into 0..1. */
    val fraction: Float,
    /** The block's full thickness. */
    val totalThickness: Float,
    /** Trailing stack: the pages already read. Grows with [fraction]. */
    val readThickness: Float,
    /** Leading stack: the pages still to come. Shrinks with [fraction]. */
    val unreadThickness: Float,
    /** Horizontal extents as fractions of the block's width, already mirrored. */
    val readStart: Float,
    val readEnd: Float,
    val unreadStart: Float,
    val unreadEnd: Float,
    /** Leaf edges to strike inside each stack. */
    val readLines: Int,
    val unreadLines: Int,
    /** True when the block runs right-to-left, so the read stack sits on the right. */
    val mirrored: Boolean,
) {
    /** Where the current leaf stands, as a fraction of the block's width. */
    val leaf: Float
        get() = if (mirrored) 1f - fraction else fraction
}

/**
 * `(fraction, availableThickness, pageCountHint)` → the block to draw. Pure and
 * unit-agnostic: [availableThickness] and [minLinePitch] must be in the same unit.
 */
internal fun pageBlockGeometry(
    fraction: Float,
    availableThickness: Float,
    pageCountHint: Int = 0,
    rtl: Boolean = false,
    minEdgeShare: Float = PAGE_BLOCK_MIN_EDGE_SHARE,
    minLinePitch: Float = PAGE_BLOCK_MIN_LINE_PITCH,
    maxLines: Int = PAGE_BLOCK_MAX_LEAF_LINES,
): PageBlockGeometry {
    val f = if (fraction.isFinite()) fraction.coerceIn(0f, 1f) else 0f
    val total = if (availableThickness.isFinite()) max(0f, availableThickness) else 0f
    val share = if (minEdgeShare.isFinite()) minEdgeShare.coerceIn(0f, 0.5f) else 0f
    val read = total * (share + (1f - 2f * share) * f)
    // Subtracted rather than computed twice so the pair cannot drift apart.
    val unread = total - read
    // An RTL codex is held the other way round: the read stack sits on the right.
    val split = if (rtl) 1f - f else f
    return PageBlockGeometry(
        fraction = f,
        totalThickness = total,
        readThickness = read,
        unreadThickness = unread,
        readStart = if (rtl) split else 0f,
        readEnd = if (rtl) 1f else split,
        unreadStart = if (rtl) 0f else split,
        unreadEnd = if (rtl) split else 1f,
        readLines = leafLines(read, f, pageCountHint, minLinePitch, maxLines),
        unreadLines = leafLines(unread, 1f - f, pageCountHint, minLinePitch, maxLines),
        mirrored = rtl,
    )
}

/**
 * How many leaf edges a stack shows: as many as it *holds*, capped by as many as
 * physically fit at [minLinePitch]. A four-leaf chapter shows four edges; a
 * four-hundred-page book shows as many as the thickness can separate.
 */
private fun leafLines(
    thickness: Float,
    pageShare: Float,
    pageCountHint: Int,
    minLinePitch: Float,
    maxLines: Int,
): Int {
    if (maxLines <= 0 || thickness <= 0f) return 0
    val capacity = if (minLinePitch > 0f) floor(thickness / minLinePitch).toInt() else maxLines
    val held = if (pageCountHint > 1) ceil(pageCountHint * pageShare.coerceIn(0f, 1f)).toInt() else 1
    return min(maxLines, min(capacity, max(1, held))).coerceAtLeast(0)
}

/**
 * Where each chapter begins along the block, as cumulative fractions of the whole
 * book: one entry more than there are chapters, first 0, last exactly 1. Weighted
 * by word count so a long chapter takes the length of block it is actually worth;
 * uniform when the parser reported no counts, because a block that lies about
 * chapter lengths is worse than one that guesses.
 */
internal fun pageBlockChapterStops(wordCounts: List<Long>): List<Float> {
    if (wordCounts.isEmpty()) return listOf(0f, 1f)
    val total = wordCounts.sum()
    val uniform = total <= 0L
    val weight = if (uniform) wordCounts.size.toDouble() else total.toDouble()
    val stops = ArrayList<Float>(wordCounts.size + 1)
    stops.add(0f)
    var acc = 0.0
    for (words in wordCounts) {
        acc += if (uniform) 1.0 else max(0L, words).toDouble()
        stops.add((acc / weight).toFloat().coerceIn(0f, 1f))
    }
    // Float accumulation must not leave the book ending at 0.9999.
    stops[stops.lastIndex] = 1f
    return stops
}

/**
 * The band [bandFirst]..[bandLast] of [stops] is the span of the book the page
 * surface is actually rendering; [bandFraction] is the fraction the surface
 * reports of *that* document. Maps it onto the whole book, which is what the block
 * shows — in continuous mode one report can span several chapters.
 */
internal fun pageBlockBandFraction(
    stops: List<Float>,
    bandFirst: Int,
    bandLast: Int,
    bandFraction: Float,
): Float {
    val f = if (bandFraction.isFinite()) bandFraction.coerceIn(0f, 1f) else 0f
    if (stops.size < 2) return f
    val first = bandFirst.coerceIn(0, stops.size - 2)
    val last = bandLast.coerceIn(first, stops.size - 2)
    val start = stops[first]
    val width = stops[last + 1] - start
    return if (width > 0f) (start + width * f).coerceIn(0f, 1f) else start.coerceIn(0f, 1f)
}

/** Where a whole-book fraction lands: the chapter, and the place inside it. */
@Immutable
internal data class PageBlockSeek(val chapterIndex: Int, val chapterFraction: Float)

/**
 * The inverse of [pageBlockBandFraction] for a single chapter, which is what the
 * page surface understands as a seek. The end of one chapter is the start of the
 * next, so a fraction on a boundary resolves to the later chapter.
 */
internal fun pageBlockSeekTarget(stops: List<Float>, fraction: Float): PageBlockSeek {
    val f = if (fraction.isFinite()) fraction.coerceIn(0f, 1f) else 0f
    if (stops.size < 2) return PageBlockSeek(0, f)
    val last = stops.size - 2
    var index = 0
    for (i in 0..last) {
        if (stops[i] <= f) index = i else break
    }
    val start = stops[index]
    val width = stops[index + 1] - start
    val local = if (width > 0f) ((f - start) / width).coerceIn(0f, 1f) else 0f
    return PageBlockSeek(index, local)
}

// ── the block's own palette ─────────────────────────────────────────────────

@Immutable
internal data class PageBlockPalette(
    /** Vertical fill of a stack: lit fore-edge down to the shade it rests in. */
    val slabTop: Color,
    val slabBase: Color,
    /** The fore-edge catch — emission on a dark page, sheen on a light one. */
    val rim: Color,
    /** Silhouette hairline: separates the block from whatever it is laid on. */
    val outline: Color,
    /** Leaf edges inside a stack. */
    val striae: Color,
    /** Cylinder shading into the gutter at the current leaf. */
    val gutter: Color,
    /** The wear colour for [pageFoxing]. */
    val foxing: Color,
)

/**
 * Derived from the *reader's* paper and ink, not the app palette, and lit by the
 * rule the app's own atmosphere obeys: a dark page gets its depth from emission
 * (the fore-edges brighten, the body stays deep), a light page from occlusion (the
 * shadows deepen toward the ink). That is why a near-black OLED preset and a cream
 * paper preset both still read as paper.
 */
internal fun pageBlockPalette(paper: Color, ink: Color): PageBlockPalette {
    val dark = paper.red * 0.2126f + paper.green * 0.7152f + paper.blue * 0.0722f < 0.45f
    return if (dark) {
        PageBlockPalette(
            slabTop = lerp(paper, Color.White, 0.16f),
            slabBase = lerp(paper, Color.Black, 0.34f),
            rim = lerp(paper, Color.White, 0.40f).copy(alpha = 0.80f),
            outline = lerp(paper, Color.White, 0.30f).copy(alpha = 0.40f),
            striae = lerp(paper, Color.White, 0.45f).copy(alpha = 0.20f),
            gutter = Color.Black.copy(alpha = 0.34f),
            foxing = Color.Black,
        )
    } else {
        PageBlockPalette(
            slabTop = lerp(paper, Color.White, 0.60f),
            slabBase = lerp(paper, ink, 0.26f),
            rim = Color.White.copy(alpha = 0.70f),
            outline = lerp(ink, paper, 0.35f).copy(alpha = 0.38f),
            striae = lerp(ink, paper, 0.25f).copy(alpha = 0.20f),
            gutter = lerp(ink, Color.Black, 0.25f).copy(alpha = 0.20f),
            // Foxing is the brown a handled page picks up; on a dark page the only
            // wear left to show is the absence of light.
            foxing = lerp(ink, Color(0xFF7A5230), 0.45f),
        )
    }
}

// ── the cue ─────────────────────────────────────────────────────────────────

/**
 * Draws the page block and takes the seek gesture the progress bar used to.
 *
 * [fraction] is position through the **whole book**, and [thickness] is only the
 * drawn block — the touch band is the component's full height, so a caller that wants
 * a comfortable target gives it more room and the block centres itself in it. [paper]
 * and [ink] are the reader theme's own page and text colours. [accent] colours the
 * leaf and should be the `accentProgress` role (Rule 14: forward motion); it is
 * contrast-guarded against [paper] here, because the leaf is a fine line on the page's
 * own material. [pageCountHint] sets how many leaf edges the stacks strike,
 * [chapterStops] (from [pageBlockChapterStops]) are struck as notches, [stateLabel] is
 * what a screen reader announces in place of the numerals this cue removed, and
 * [onSeek] is handed a whole-book fraction — null makes the block read-only.
 */
@Composable
fun PageBlock(
    fraction: Float,
    modifier: Modifier = Modifier,
    thickness: Dp = 18.dp,
    paper: Color = FolioTheme.colors.surface,
    ink: Color = FolioTheme.colors.onSurface,
    accent: Color = FolioTheme.colors.accentProgress,
    pageCountHint: Int = 0,
    chapterStops: List<Float> = emptyList(),
    stateLabel: String? = null,
    onSeek: ((Float) -> Unit)? = null,
    rtl: Boolean = LocalLayoutDirection.current == LayoutDirection.Rtl,
) {
    val palette = remember(paper, ink) { pageBlockPalette(paper, ink) }
    // The leaf is a fine line on the page's own paper, so it is guarded against
    // that paper rather than against the app surface behind the chrome.
    val leafAccent = rememberLegibleAccent(
        accent = accent,
        background = paper,
        fallback = ink,
        minRatio = 3.0,
    )
    val motion = com.folio.reader.ui.theme.rememberMotionEnabled()
    val target = if (fraction.isFinite()) fraction.coerceIn(0f, 1f) else 0f
    // Direct manipulation stays direct: under a finger the leaf tracks exactly, and
    // only a jump (chapter change, annotation, restore) is eased. §13.3: with motion
    // off this settles immediately, which is the correct static form.
    var dragging by remember { mutableStateOf(false) }
    val shown by animateFloatAsState(
        targetValue = target,
        animationSpec = if (motion && !dragging) tween(180) else snap(),
        label = "page-block-fraction",
    )
    // Held in a state rather than keyed into pointerInput: the caller's lambda is
    // rebuilt on every scroll tick, and restarting the detector mid-drag would drop
    // the gesture.
    val seek by rememberUpdatedState(onSeek)

    Spacer(
        modifier = modifier
            .then(stateLabel?.let { label -> Modifier.semantics { contentDescription = label } } ?: Modifier)
            .then(
                if (onSeek == null) {
                    Modifier
                } else {
                    Modifier.pointerInput(rtl) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            dragging = true
                            seek?.invoke(blockFractionAt(down.position.x, size.width, rtl))
                            // Consumed so the page under the chrome does not also read
                            // this as its centre tap and hide the cue being dragged.
                            down.consume()
                            do {
                                val event = awaitPointerEvent()
                                event.changes.forEach { change ->
                                    if (change.pressed) {
                                        seek?.invoke(blockFractionAt(change.position.x, size.width, rtl))
                                        change.consume()
                                    }
                                }
                            } while (event.changes.any { it.pressed })
                            dragging = false
                        }
                    }
                }
            )
            .drawWithCache {
                val w = size.width
                val h = size.height
                // A slim rounded track, not a tapering slab. Its thickness is a
                // fraction of the old block's — a reading thread laid on the page,
                // not a wedge of paper — so it reads as a fine progress line the
                // reader can still grab. Capped so a generous touch band does not
                // inflate the visible rail.
                val trackH = min(min(thickness.toPx() * 0.34f, 5.dp.toPx()), h)
                val cy = h * 0.5f
                val trackTop = cy - trackH * 0.5f
                val radius = CornerRadius(trackH * 0.5f, trackH * 0.5f)
                val f = if (shown.isFinite()) shown.coerceIn(0f, 1f) else 0f
                // Fill runs from the reader's held edge toward the fore-edge: LTR
                // fills from the left, RTL from the right, so "how far in" always
                // grows in the direction the reader turns pages.
                val fillW = (w * f).coerceIn(0f, w)
                val handleX = (if (rtl) w - fillW else fillW).coerceIn(0f, w)
                val hair = max(1f, 1.dp.toPx())
                // The handle: a round bead sitting on the thread, ringed in the
                // page's own paper so it lifts off both the track and the page.
                val beadR = min(h * 0.5f, 7.dp.toPx())
                // Chapter ticks: faint hairlines crossing the track, the chapter
                // structure kept without the notch-and-wash machinery.
                val ticks = if (chapterStops.size < 3) emptyList() else buildList {
                    val minGap = max(4f, 5.dp.toPx())
                    var last = -minGap * 2f
                    for (i in 1 until chapterStops.lastIndex) {
                        val stop = chapterStops[i].coerceIn(0f, 1f)
                        val x = (if (rtl) 1f - stop else stop) * w
                        if (x - last >= minGap) { add(x); last = x }
                    }
                }
                val fillBrush = Brush.horizontalGradient(
                    colors = listOf(leafAccent.copy(alpha = 0.85f), leafAccent),
                    startX = if (rtl) w else 0f,
                    endX = if (rtl) 0f else w,
                )
                onDrawBehind {
                    // 1) The unread track: the whole span, in a hairline of the
                    //    page's ink so it belongs to the paper, not the chrome.
                    drawRoundRect(
                        color = palette.striae.copy(alpha = 0.55f),
                        topLeft = Offset(0f, trackTop),
                        size = Size(w, trackH),
                        cornerRadius = radius,
                    )
                    // 2) The read portion, filled in the forward-motion accent,
                    //    clipped to the rounded track so its caps stay round.
                    if (fillW > 0f) {
                        clipPath(Path().apply {
                            addRoundRect(
                                androidx.compose.ui.geometry.RoundRect(
                                    left = 0f, top = trackTop, right = w, bottom = trackTop + trackH,
                                    cornerRadius = radius,
                                )
                            )
                        }) {
                            drawRect(
                                brush = fillBrush,
                                topLeft = Offset(if (rtl) w - fillW else 0f, trackTop),
                                size = Size(fillW, trackH),
                            )
                        }
                    }
                    // 3) Chapter ticks, struck across the track only.
                    for (x in ticks) {
                        drawRect(
                            color = palette.outline.copy(alpha = 0.5f),
                            topLeft = Offset(x - hair * 0.5f, trackTop),
                            size = Size(hair, trackH),
                        )
                    }
                    // 4) The position handle: paper ring under an accent bead, so
                    //    the reader's spot reads at a glance and gives the drag a
                    //    clear target.
                    drawCircle(color = palette.slabTop, radius = beadR + hair, center = Offset(handleX, cy))
                    drawCircle(color = leafAccent, radius = beadR, center = Offset(handleX, cy))
                    drawCircle(
                        color = palette.rim,
                        radius = beadR,
                        center = Offset(handleX, cy),
                        style = Stroke(hair),
                    )
                }
            }
    )
}

/** x within the band → a whole-book fraction, inverted for a right-to-left block. */
private fun blockFractionAt(x: Float, width: Int, rtl: Boolean): Float {
    if (width <= 0) return 0f
    val raw = (x / width).coerceIn(0f, 1f)
    return if (rtl) 1f - raw else raw
}

/**
 * **Foxing.** The supporting cue: the outer margins of the page darken a little and
 * the wear concentrates on the edge the reader's thumb has been working, so the page
 * looks handled rather than printed. Capped at six percent alpha over the outer tenth
 * of the page, which cannot reach the text column. Drawn by Compose *over* the page,
 * so a caller whose page paints over Compose layers (desktop's embedded browser)
 * should skip it rather than pay for a layer nobody sees.
 */
@Composable
fun Modifier.pageFoxing(
    fraction: Float,
    paper: Color,
    ink: Color,
    rtl: Boolean = LocalLayoutDirection.current == LayoutDirection.Rtl,
): Modifier {
    val palette = remember(paper, ink) { pageBlockPalette(paper, ink) }
    return this.drawWithCache {
        val w = size.width
        val h = size.height
        val reach = w * 0.10f
        val wear = palette.foxing
        // Built once at full strength and scaled by the draw alpha below, so the wear
        // is a draw-time amount rather than a new gradient on every change.
        val trailingBrush = Brush.horizontalGradient(
            0f to wear,
            1f to Color.Transparent,
            startX = if (rtl) w else 0f,
            endX = if (rtl) w - reach else reach,
        )
        val leadingBrush = Brush.horizontalGradient(
            0f to wear,
            1f to Color.Transparent,
            startX = if (rtl) 0f else w,
            endX = if (rtl) reach else w - reach,
        )
        val trailingLeft = if (rtl) w - reach else 0f
        val leadingLeft = if (rtl) 0f else w - reach
        onDrawBehind {
            if (reach <= 0f || h <= 0f) return@onDrawBehind
            val worn = if (fraction.isFinite()) fraction.coerceIn(0f, 1f) else 0f
            // The trailing edge carries the wear of every page turned; the leading
            // edge keeps half of what is left, because a book that has never been
            // opened has still been shelved.
            val trailing = (0.020f + 0.040f * worn).coerceAtMost(0.060f)
            val leading = ((0.020f + 0.040f * (1f - worn)).coerceAtMost(0.060f)) * 0.5f
            drawRect(trailingBrush, topLeft = Offset(trailingLeft, 0f), size = Size(reach, h), alpha = trailing)
            drawRect(leadingBrush, topLeft = Offset(leadingLeft, 0f), size = Size(reach, h), alpha = leading)
        }
    }
}
