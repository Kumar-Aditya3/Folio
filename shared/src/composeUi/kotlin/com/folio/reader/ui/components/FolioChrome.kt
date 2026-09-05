package com.folio.reader.ui.components

import androidx.compose.animation.animateColorAsState
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
import com.folio.reader.ui.theme.atmosphere
import com.folio.reader.ui.theme.rememberMotionEnabled
import com.folio.reader.ui.theme.surfaceOpacity
import com.folio.reader.ui.theme.topBarFill
import kotlin.math.roundToInt

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
    // The user's top-bar preference *is* the crown's alpha, not a factor on the
    // designed one: scaling a 0.36 `barGlass` could only ever go down, so the
    // slider ran from invisible to nearly invisible and its ends looked the same.
    val fill = FolioTheme.surfaceOpacity.topBarFill(f)
    val statusColor = atmos.barScrim.copy(alpha = atmos.barScrim.alpha * fill.scrim)
    val veil = atmos.barGlass
    // Specular catch for the masthead's mirror finish — the atmosphere's own rim
    // light, so a dark field emits at the crown and paper catches a white sheen.
    // Purely additive over the veil; it never touches barGlass's alpha, so the §15
    // glass window (DesignSystemTest.appBarsAreGlassNotLids) still holds.
    val sheen = atmos.rimLight
    Column(
        modifier = modifier
            .fillMaxWidth()
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
                    // to read as one.
                    drawRect(
                        Brush.verticalGradient(
                            0f to sheen.copy(alpha = sheen.alpha * 0.30f * fill.presence),
                            0.38f to Color.Transparent,
                        )
                    )
                    // The bar's lower boundary — hairline, then the glass fade under
                    // it — painted inside the masthead's own footprint. As Column
                    // children they grew the bar 11dp past folioBarTopInset, and the
                    // overhang cut across whatever was pinned at that inset (chip
                    // rails, search fields) — permanently once the opacity knob
                    // passes the glass point and presence is 1 at rest.
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
                        topLeft = Offset(0f, size.height - fadePx - rulePx),
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
 * A segmented switch: one track, one lit segment.
 *
 * This replaces a pair of loose [FolioChip]s wherever the choice is *exclusive*.
 * Two chips side by side say "two independent filters"; one track with a lit
 * segment says "one of these" — which is what Books/Manga actually is — and it
 * costs a third of the width, so it fits on the same rail as the filters instead
 * of demanding a row of its own.
 *
 * Selection is carried by a tinted segment and accent ink, never by weight: a
 * heavier label would re-measure the track and nudge everything beside it.
 */
@Composable
fun FolioSegmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FolioTheme.colors
    val atmos = FolioTheme.atmosphere
    Row(
        modifier = modifier
            .clip(FolioShapes.pill)
            .background(atmos.sunkenFill.copy(alpha = 0.5f), FolioShapes.pill)
            .border(1.dp, atmos.hairline, FolioShapes.pill)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            val fill by animateColorAsState(
                targetValue = if (selected) colors.primary.copy(alpha = 0.20f) else Color.Transparent,
                label = "segmentFill",
            )
            val ink by animateColorAsState(
                targetValue = if (selected) colors.primary else colors.onSurfaceVariant,
                label = "segmentInk",
            )
            Box(
                modifier = Modifier
                    .clip(FolioShapes.pill)
                    .background(fill, FolioShapes.pill)
                    .clickable(enabled = !selected) { onSelect(index) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            ) {
                Text(
                    text = label,
                    style = FolioTheme.typography.labelMedium,
                    color = ink,
                    maxLines = 1,
                )
            }
        }
    }
}

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
