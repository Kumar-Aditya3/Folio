package com.folio.reader.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.folio.reader.ui.theme.FolioShapes
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens
import com.folio.reader.ui.theme.LocalFolioAmbient
import com.folio.reader.ui.theme.LocalFolioDaylight
import com.folio.reader.ui.theme.atmosphere
import com.folio.reader.ui.theme.rememberMotionEnabled
import com.folio.reader.ui.theme.surfaceOpacity
import com.folio.reader.ui.theme.topBarFill
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Screen chrome: the collapsing masthead and the controls that live in it.
 *
 * Home's hero already dissolved into its top bar as the page scrolled, and that
 * is the one piece of motion in the app that makes the chrome read as part of the
 * page rather than a lid on it. This file generalises the behaviour so any screen
 * can have it:
 *
 *  - [rememberFolioHeaderState] watches a scrolling child through nested scroll,
 *    so no list has to hoist its own state to get the effect;
 *  - [FolioTopBar] takes that collapse fraction and, over the first ~56dp of
 *    scroll, fades its glass in, shrinks its title and folds its rail away.
 *
 * At rest the bar has **no fill at all** beyond a soft scrim under the OS status
 * icons: the page runs to the top of the window. Glass only appears once content
 * is passing underneath it, which is the only reason a bar ever needs a fill.
 */

/**
 * Scroll-linked collapse for a screen's masthead.
 *
 * [collapse] runs 0 (at rest) to 1 (fully collapsed) over the range passed to
 * [rememberFolioHeaderState], tracked 1:1 off what the scrolling child actually
 * consumed — an over-scroll pull at the top of a list moves nothing, which is
 * what made a plain `firstVisibleItemScrollOffset` reading jitter on bounce.
 *
 * Frozen at 0 under reduce-motion: this is scroll-linked animation, and §13.9
 * already holds Home's hero to the same rule.
 */
@Stable
class FolioHeaderState internal constructor(
    private val rangePx: Float,
    private val enabled: Boolean,
) {
    private var offset by mutableStateOf(0f)

    /** 0 at rest, 1 fully collapsed. */
    val collapse: Float
        get() = if (!enabled || rangePx <= 0f) 0f else (offset / rangePx).coerceIn(0f, 1f)

    /**
     * Snap back to the at-rest, expanded state. The offset is otherwise a sticky
     * accumulator with no reset path, so a screen that swaps the scrollable beneath
     * the bar (Library's Books/Manga shelves) inherits the old shelf's collapse and
     * arrives with its rail still folded away.
     */
    fun reset() {
        offset = 0f
    }

    /** Attach to the container that holds the scrolling content. */
    val nestedScrollConnection: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPostScroll(
            consumed: Offset,
            available: Offset,
            source: NestedScrollSource,
        ): Offset {
            if (enabled) offset = (offset - consumed.y).coerceIn(0f, rangePx)
            return Offset.Zero
        }
    }
}

@Composable
fun rememberFolioHeaderState(range: Dp = 56.dp): FolioHeaderState {
    val rangePx = with(LocalDensity.current) { range.toPx() }
    val motion = rememberMotionEnabled()
    return remember(rangePx, motion) { FolioHeaderState(rangePx, motion) }
}

/**
 * The screen's masthead. Editorial, not Material: the title carries the display
 * face at `headlineMedium`, the bar has no fill of its own so the page's field
 * runs behind it, and the only structure at rest is a soft scrim holding the OS
 * status icons off the page.
 *
 * [collapse] drives the entire scrolled state in one number: glass fades in, the
 * hairline draws itself, the title and mark step down in size, and [rail] folds up
 * under the bar. Nothing changes the bar's own height, so the page never jumps.
 *
 * [titleStyle] lets the §13.9 collapsing Home hero migrate its title in at a
 * smaller size; [titleContent] replaces the title outright for callers that
 * cross-fade two of them.
 *
 * Top-level destinations pass no [navigationIcon], so they get the Folio mark in
 * that slot and the persistent screens read as one product masthead; pushed
 * screens keep their back arrow exactly where it was.
 *
 * [rail] is the one row a screen may hang under its title — filter chips, a
 * segmented switch. Deliberately *one*: a mode row over a filter row over the bar
 * is what made Library read as a control panel.
 */
@Composable
fun FolioTopBar(
    title: String,
    modifier: Modifier = Modifier,
    titleStyle: TextStyle? = null,
    navigationIcon: (@Composable () -> Unit)? = null,
    collapse: Float = 0f,
    titleContent: (@Composable () -> Unit)? = null,
    rail: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = FolioTheme.colors
    val atmos = FolioTheme.atmosphere
    val f = collapse.coerceIn(0f, 1f)
    val statusPx = WindowInsets.statusBars.getTop(LocalDensity.current).toFloat()
    // §16 liquid glass: the masthead's blur and fill are computed together, so
    // the tier is applied inside topBarFill — when the bar can actually blur,
    // the knob steps down to its glass tier, because blur fixes the
    // bleed-through the near-opaque default was calibrated against.
    val glassCaps = LocalGlassCapabilities.current
    val glassBackdrop = LocalGlassBackdrop.current
    val blurred = glassCaps.blur && glassBackdrop != null
    // The user's top-bar preference *is* the crown's alpha, not a factor on the
    // designed one: scaling a 0.36 `barGlass` could only ever go down, so the
    // slider ran from invisible to nearly invisible and its ends looked the same.
    val fill = FolioTheme.surfaceOpacity.topBarFill(f, blurred = blurred)
    val statusColor = atmos.barScrim.copy(alpha = atmos.barScrim.alpha * fill.scrim)
    val veil = atmos.barGlass
    // Specular catch for the masthead's mirror finish — the atmosphere's own rim
    // light, so a dark field emits at the crown and paper catches a white sheen.
    // Purely additive over the veil; it never touches barGlass's alpha, so the §15
    // glass window (DesignSystemTest.appBarsAreGlassNotLids) still holds.
    val sheen = atmos.rimLight
    // Blur only while something is passing underneath — the same condition as the
    // fill — fading in with the bar's presence. A bar at rest over the page's own
    // field has nothing to refract.
    val canBlur = blurred && fill.presence > 0.01f
    val daylight = LocalFolioDaylight.current
    // §17 living light, draw-phase read: the crown's specular catch drifts with
    // the room's slow light, so even a bar over an entirely still page has one
    // thing on it that is genuinely in transit.
    val ambient = LocalFolioAmbient.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (canBlur) {
                    Modifier.folioGlassEffect(
                        backdrop = glassBackdrop,
                        backgroundColor = colors.background,
                        alpha = fill.presence,
                        progressive = true,
                    )
                } else {
                    Modifier
                }
            )
            .then(if (glassCaps.noise && !canBlur) Modifier.folioGlassGrain() else Modifier)
            .drawBehind {
                // The OS icons need their own ground on every theme; the page does
                // not need a band. The scrim decays fast — full strength only in the
                // few pixels the icons actually occupy, and effectively gone by the
                // bar's foot — so there is no cut line across the screen and no
                // painted strip along the top of it.
                if (statusPx > 0f) {
                    val mid = (statusPx / size.height).coerceIn(0.05f, 0.9f)
                    val a = statusColor.alpha
                    drawRect(
                        Brush.verticalGradient(
                            0f to statusColor.copy(alpha = a),
                            mid to statusColor.copy(alpha = a * lerp(0.40f, 0.70f, f)),
                            1f to statusColor.copy(alpha = a * lerp(0f, 0.32f, f)),
                        )
                    )
                }
                // Glass, but only once something is passing underneath — and never a
                // flat slab, unless the reader has explicitly asked for one. The fill
                // decays toward the bar's foot so the collapsed masthead reads as the
                // page's own field thickening under the status icons instead of the
                // grey lid the redesign removed; that decay flattens out as the
                // preference approaches 100%, where the bar is meant to be a bar.
                if (fill.presence > 0.01f) {
                    drawRect(
                        Brush.verticalGradient(
                            0f to veil.copy(alpha = fill.crown),
                            0.6f to veil.copy(alpha = fill.waist),
                            1f to veil.copy(alpha = fill.foot),
                        )
                    )
                    // The mirror finish: a thin specular band along the crown that fades
                    // by ~38% of the bar's height, so the collapsed masthead reads as
                    // polished glass reflecting the light above rather than a flat tint.
                    // Tied to the bar's presence exactly like the veil — it appears only
                    // once the bar is a surface at all, which is the only time it needs
                    // to read as one. §16: the band runs along the daylight axis like
                    // every other material's sheen; at neutral daylight (and on
                    // platforms without the glass upgrade) it is the fixed top-light
                    // band this bar has always had.
                    if (glassCaps.specular) {
                        val (sheenStart, sheenEnd) = daylightGradient(size, daylight, ambient.value)
                        drawRect(
                            Brush.linearGradient(
                                0f to Color.Transparent,
                                0.62f to Color.Transparent,
                                1f to sheen.copy(alpha = sheen.alpha * 0.30f * fill.presence),
                                start = sheenStart,
                                end = sheenEnd,
                            )
                        )
                    } else {
                        drawRect(
                            Brush.verticalGradient(
                                0f to sheen.copy(alpha = sheen.alpha * 0.30f * fill.presence),
                                0.38f to Color.Transparent,
                            )
                        )
                    }
                    // The bar's lower boundary — glass fade thickening down into a
                    // hairline at the bar's very edge — painted inside the masthead's
                    // own footprint. As Column children they grew the bar 11dp past
                    // folioBarTopInset and overhung what was pinned there; higher up
                    // inside, the hairline would cut through the row's own content —
                    // a 40dp button centered in the 56dp row dips to 48dp — so the
                    // rule lives in the last dp, where only transparent padding
                    // reaches.
                    val fadePx = FolioTokens.barGlassFade.toPx()
                    val rulePx = 1.dp.toPx()
                    drawRect(
                        Brush.verticalGradient(
                            0f to veil.copy(alpha = fill.crown * 0.45f),
                            1f to Color.Transparent,
                        ),
                        topLeft = Offset(0f, size.height - fadePx),
                        size = Size(size.width, fadePx),
                    )
                    drawRect(
                        Brush.horizontalGradient(
                            listOf(
                                atmos.hairline.copy(alpha = 0.55f * fill.presence),
                                atmos.hairline.copy(alpha = 0.10f * fill.presence),
                            )
                        ),
                        topLeft = Offset(0f, size.height - rulePx),
                        size = Size(size.width, rulePx),
                    )
                }
            }
    ) {
        Box(modifier = Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(FolioTokens.barHeight)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (navigationIcon != null) {
                navigationIcon()
            } else {
                FolioMark(
                    modifier = Modifier.graphicsLayer {
                        val scale = lerp(1f, 0.82f, f)
                        scaleX = scale
                        scaleY = scale
                    }
                )
            }
            Box(
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (titleContent != null) {
                    titleContent()
                } else {
                    FolioBarTitle(
                        title = title,
                        preferred = titleStyle ?: FolioTheme.typography.headlineMedium,
                        collapse = f,
                        color = colors.onSurface,
                    )
                }
            }
            actions()
        }
        if (rail != null) {
            // Folds up *under* the bar: the height shrinks while the content stays
            // anchored to the fold's bottom edge, so the chips slide behind the
            // masthead instead of squashing.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clipToBounds()
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        val height = (placeable.height * (1f - f)).roundToInt()
                        layout(placeable.width, height) {
                            placeable.place(0, height - placeable.height)
                        }
                    }
                    .graphicsLayer { alpha = (1f - f * 1.5f).coerceIn(0f, 1f) }
                    .padding(bottom = 6.dp),
            ) {
                rail()
            }
        }
    }
}

/**
 * The screen's name, sized to the slot the actions left behind.
 *
 * A masthead that renders "Lib…" has failed at the one job it has. The type steps
 * down the display ladder until the whole name fits, so a crowded bar costs a few
 * points of size instead of the screen's identity; ellipsis remains only as the
 * last resort for a genuinely long title.
 */
@Composable
private fun FolioBarTitle(
    title: String,
    preferred: TextStyle,
    collapse: Float,
    color: Color,
) {
    val typography = FolioTheme.typography
    BoxWithConstraints {
        val measurer = rememberTextMeasurer()
        val slot = constraints.maxWidth
        val ladder = listOf(preferred, typography.titleLarge, typography.titleMedium)
        val style = if (slot <= 0) {
            preferred
        } else {
            ladder.firstOrNull { candidate ->
                measurer.measure(text = title, style = candidate, maxLines = 1).size.width <= slot
            } ?: ladder.last()
        }
        Text(
            text = title,
            style = style,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // Scaled, not re-styled, for the collapse: a font-size change here
            // relayouts the whole bar on every scroll frame.
            modifier = Modifier.graphicsLayer {
                val scale = lerp(1f, 0.84f, collapse)
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0.5f)
            },
        )
    }
}

/**
 * The Folio mark: the app's own logo — the open book whose right page grows into a
 * leaf — drawn as vector geometry by [FolioLogoMark]. No plate behind it: the
 * artwork carries its own blue→green gradient, so a container would only add a
 * second shape.
 */
@Composable
private fun FolioMark(modifier: Modifier = Modifier) {
    FolioLogoMark(modifier = modifier.size(28.dp))
}

/**
 * A segmented switch: one track, one lit segment — and the segment *travels*.
 *
 * This replaces a pair of loose [FolioChip]s wherever the choice is *exclusive*.
 * Two chips side by side say "two independent filters"; one track with a lit
 * segment says "one of these" — which is what Books/Manga actually is — and it
 * costs a third of the width, so it fits on the same rail as the filters instead
 * of demanding a row of its own.
 *
 * §17 liquid selection: the lit segment is a single indicator that glides from
 * the old slot to the new one, stretching and squeezing through the gap, instead
 * of two fills cross-fading in place. The eye follows the motion, so selection
 * is legible by *where the light went* — the same argument that made the nav
 * capsule's pill breathe. Selection is never carried by weight: a heavier label
 * would re-measure the track and nudge everything beside it.
 *
 * The indicator is drawn behind the labels in one `drawWithCache`, and the
 * travel is animated by `animateDpAsState` read in the draw phase — the
 * geometry recomputes per frame but nothing recomposes and no layout pass runs
 * for the glide. Under reduce-motion the indicator snaps between slots.
 */
private val SEGMENT_H_PAD = 14.dp
private val SEGMENT_V_PAD = 7.dp
private val SEGMENT_GAP = 2.dp

/**
 * The lit segment's slot geometry, in the caller's units: (left, width) of
 * segment [index] given every segment's [widths] and the [gap] between them.
 * Pure so the travel is pinned by test rather than by eye — the indicator must
 * land exactly on the slot the labels laid out, or the two disagree by a pixel
 * and the control reads broken.
 */
internal fun segmentedSlotBounds(
    widths: List<Float>,
    index: Int,
    gap: Float,
): Pair<Float, Float> {
    val i = index.coerceIn(0, widths.lastIndex)
    val left = widths.take(i).sum() + gap * i
    return left to widths[i]
}

@Composable
fun FolioSegmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (options.isEmpty()) return
    val colors = FolioTheme.colors
    val atmos = FolioTheme.atmosphere
    val motion = rememberMotionEnabled()
    val style = FolioTheme.typography.labelMedium
    // Measured with the same measurer and style that render the labels, so the
    // slot widths are the pills' actual widths — a hand-rolled width estimate
    // is what would make the indicator land half a millimetre off its label.
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val slotWidths: List<Dp> = remember(options, style, density) {
        with(density) {
            options.map { label ->
                measurer.measure(text = label, style = style, maxLines = 1).size.width.toDp() +
                    SEGMENT_H_PAD * 2
            }
        }
    }
    val clamped = selectedIndex.coerceIn(0, options.lastIndex)
    val targetLeft = remember(slotWidths, clamped) {
        val (left, _) = segmentedSlotBounds(slotWidths.map { it.value }, clamped, SEGMENT_GAP.value)
        left.dp
    }
    val targetWidth = slotWidths[clamped]
    // Liquid, not sloppy: a touch of overshoot on the glide (damping ≈ 0.78),
    // matching the press springs' own calibration.
    val glideSpec: AnimationSpec<Dp> =
        if (motion) spring(dampingRatio = 0.78f, stiffness = 480f) else snap()
    val indicatorLeft by animateDpAsState(targetLeft, glideSpec, label = "segmentLeft")
    val indicatorWidth by animateDpAsState(targetWidth, glideSpec, label = "segmentWidth")
    Box(
        modifier = modifier
            .clip(FolioShapes.pill)
            .background(atmos.sunkenFill.copy(alpha = 0.5f), FolioShapes.pill)
            .border(1.dp, atmos.hairline, FolioShapes.pill)
            .padding(3.dp)
            .drawWithCache {
                // Behind the labels (drawBehind runs before the content), inside
                // the track's own padding, so the coordinate space *is* the row's.
                val radius = CornerRadius(size.height / 2f)
                onDrawBehind {
                    drawRoundRect(
                        color = colors.primary.copy(alpha = 0.20f),
                        topLeft = Offset(indicatorLeft.toPx(), 0f),
                        size = Size(indicatorWidth.toPx(), size.height),
                        cornerRadius = radius,
                    )
                }
            },
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(SEGMENT_GAP),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            options.forEachIndexed { index, label ->
                val selected = index == clamped
                // Ink still cross-fades per segment: the label tells you where
                // the light landed, the indicator shows it travelling.
                val ink by animateColorAsState(
                    targetValue = if (selected) colors.primary else colors.onSurfaceVariant,
                    label = "segmentInk",
                )
                Text(
                    text = label,
                    style = style,
                    color = ink,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(FolioShapes.pill)
                        .clickable(enabled = !selected) { onSelect(index) }
                        .padding(horizontal = SEGMENT_H_PAD, vertical = SEGMENT_V_PAD),
                )
            }
        }
    }
}

/**
 * §17 liquid selection, geometry half: where the capsule's specular sweep band
 * sits for [progress] 0..1 across a capsule [width] px wide with a [band] px
 * band, travelling toward [direction] (+1 left→right, -1 the reverse). At 0 the
 * band spans entirely off the leading edge and at 1 entirely past the trailing
 * edge — the same off-edge contract as [shimmerSweep], tightened by one band so
 * no travel is spent invisible. Pure so it is pinned by test. Public because
 * the capsule itself lives in the app module, which cannot see this module's
 * internal members.
 */
fun navSweepBand(progress: Float, width: Float, band: Float, direction: Float): Float {
    val x = -band + progress.coerceIn(0f, 1f) * (width + band)
    // Mirror the forward position about the capsule: -band ↔ width.
    return if (direction >= 0f) x else width - band - x
}

/**
 * The sweep's alpha envelope: 0 at rest at both ends, one soft peak mid-travel.
 * Sine rather than a triangle so the band eases in and out instead of switching
 * on. Pure so it is pinned by test; public for [navSweepBand]'s reason.
 */
fun navSweepAlpha(progress: Float): Float =
    sin(PI * progress.coerceIn(0f, 1f).toDouble()).toFloat().coerceIn(0f, 1f)

/**
 * Section label inside a menu, so one menu can carry two or three groups and still
 * be read at a glance. A twenty-item flat list is not a menu, it is an inventory.
 */
@Composable
fun FolioMenuLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = FolioTheme.typography.labelSmall,
        color = FolioTheme.colors.onSurfaceVariant,
        modifier = modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 4.dp),
    )
}
