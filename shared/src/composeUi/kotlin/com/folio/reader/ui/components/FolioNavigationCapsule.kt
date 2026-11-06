package com.folio.reader.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.folio.reader.ui.components.FolioEdgeRole
import com.folio.reader.ui.components.folioSoftFill
import com.folio.reader.ui.components.folioGlassPress
import com.folio.reader.ui.components.folioPressable
import com.folio.reader.ui.components.GlassSpec
import com.folio.reader.ui.components.chromeFill
import com.folio.reader.ui.components.folioVeil
import com.folio.reader.ui.components.glassBlurred
import com.folio.reader.ui.components.rememberFolioChromeMaterial
import com.folio.reader.ui.components.rememberFolioInteraction
import com.folio.reader.ui.theme.FolioShapes
import com.folio.reader.ui.theme.elements
import com.folio.reader.ui.theme.folioGuillocheQuiet
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens
import com.folio.reader.ui.theme.rememberMotionEnabled
import com.folio.reader.ui.theme.surfaceOpacity

/** The navigation model is shared so visual review renders the shipping capsule. */
data class FolioNavigationItem(
    val route: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val label: String,
    val badgeCount: Int? = null,
    val outlinedIcon: androidx.compose.ui.graphics.vector.ImageVector = icon,
)

/** The app's actual capsule; the host owns system insets, routing and haptics. */
@Composable
fun FolioNavigationCapsule(
    items: List<FolioNavigationItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    suppressBlur: Boolean = false,
) {
    val chrome = rememberFolioChromeMaterial()
    val capsuleFill = FolioTheme.surfaceOpacity.chromeFill(glassBlurred() && !suppressBlur)
    val roomGlass = chrome.glass
    Row(
        modifier = modifier.widthIn(max = FolioTokens.navFloatMaxWidth)
            .folioGuillocheQuiet()
            .folioVeil(FolioShapes.pill, fillColor = chrome.plane, fillAlpha = capsuleFill,
                glass = GlassSpec.Control.copy(blurEnabled = !suppressBlur),
                weight = chrome.weight, edge = FolioEdgeRole.Chrome)
            .then(if (roomGlass == chrome.plane) Modifier else Modifier.drawBehind {
                drawRect(roomGlass, blendMode = BlendMode.Color)
            })
            .height(FolioTokens.navFloatHeight)
            .padding(horizontal = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items.forEachIndexed { index, item ->
            FolioNavItem(item, selected = index == selectedIndex, onClick = { onSelect(index) })
        }
    }
}

/**
 * One capsule item. Selected: a soft accent pill, accent icon, label visible.
 * Unselected: icon only in `onSurfaceVariant`. The width animates so the capsule
 * breathes as the selection moves rather than snapping — and freezes under
 * reduce-motion.
 *
 * Sizes are deliberately smaller than a Material bar's: at 20dp the icon reads as
 * a mark rather than a button face, which is what keeps the capsule from looking
 * like a toolbar that happens to have rounded ends.
 */
@Composable
private fun FolioNavItem(
    item: FolioNavigationItem,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = FolioTheme.colors
    val motion = rememberMotionEnabled()
    val interaction = rememberFolioInteraction()
    // §16: the pill lights where the thumb lands rather than flashing its whole
    // outline uniformly. Same interaction source the press and the tick already use.
    val focal = com.folio.reader.ui.theme.rememberFolioPressFocal(interaction)
    val targetWidth = if (selected) 86.dp else 50.dp
    val width by animateDpAsState(
        targetValue = targetWidth,
        animationSpec = if (motion) {
            spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 700f)
        } else {
            snap()
        },
        label = "navItemWidth",
    )
    // Icon size animates on the same spring as the box width, so the mark
    // settles into its selected size instead of popping 21dp -> 19dp in one frame.
    val iconSize by animateDpAsState(
        targetValue = if (selected) 19.dp else 21.dp,
        animationSpec = if (motion) {
            spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 700f)
        } else {
            snap()
        },
        label = "navIconSize",
    )
    // The selected state always wears the theme's interaction accent. Destination
    // identity is carried by the icon and label, with supporting colors inside content.
    val elements = FolioTheme.elements
    val onPill = elements.onNavigationSelection
    val pillFill by animateColorAsState(
        // Pushed above the contract band's top (0.65) to 0.76: at 0.60 the blurred veil under
        // the capsule bled through the chip and muddied the selected icon. A more solid pill
        // gives the glyph a clean, chromatic bed so it reads crisp.
        targetValue = if (selected) elements.navigationSelection else Color.Transparent,
        animationSpec = if (motion) spring(stiffness = 700f) else snap(),
        label = "navPillFill",
    )
    val iconTint by animateColorAsState(
        targetValue = if (selected) {
            onPill
        } else {
            colors.onSurfaceVariant.copy(alpha = 0.78f)
        },
        animationSpec = if (motion) spring(stiffness = 700f) else snap(),
        label = "navIconTint",
    )
    val labelColor by animateColorAsState(
        targetValue = onPill,
        animationSpec = if (motion) spring(stiffness = 700f) else snap(),
        label = "navLabelColor",
    )
    Box(
        modifier = Modifier
            .width(width + 2.dp) // bleed into the inter-item gap: no dead columns
            .heightIn(min = 48.dp) // 42dp pill inside a 48dp minimum hit target
            .folioPressable(interaction, scaleTo = 0.93f)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            // Announce this as a tab and whether it is the current one, so TalkBack
            // says "selected" rather than leaving the state to tint alone.
            .semantics {
                this.selected = selected
                role = Role.Tab
            },
        contentAlignment = Alignment.Center,
    ) {
        // The visual pill stays exactly 42dp — the deliberate capsule aesthetic —
        // drawn on an inner child centered inside the larger hit target. §16: the
        // pill carries the liquid press (rim flash; the corner term is a no-op on
        // a pill by construction), so the flash tracks the capsule's own shape.
        //
        // §18: the fill is **feathered**, and this is the nested shape in the user's
        // screenshot. `clip(pill).background(accent, pill)` at 0.76 alpha is a hard-edged,
        // near-opaque 42dp pill sitting centred inside a 54dp capsule whose own edge the
        // whole §18 ladder exists to dissolve — 6dp of clearance all round, so the eye
        // gets two concentric outlines 6dp apart and reads the obvious thing. Feathering
        // it costs the selection nothing: the interior keeps the full 0.76 that the glyph
        // needs for a clean chromatic bed (that alpha was raised deliberately and is
        // untouched), and only the outermost ~3dp ramps, so the pill stops being an
        // outline and becomes a pool of accent the mark sits in.
        //
        // `Tile` is the right rung and not a guess: a 42dp short side puts
        // FOLIO_EDGE_MAX_SHARE's clamp at 5dp, so the ladder would cut anything longer
        // down to about this anyway, and a selection chip is exactly the small-chip case
        // the rung is named for.
        Box(
            modifier = Modifier
                .width(width)
                .height(42.dp)
                .clip(FolioShapes.pill)
                .folioSoftFill(FolioShapes.pill, pillFill, FolioEdgeRole.Tile)
                .folioGlassPress(interaction, FolioShapes.pill, focal)
                .drawWithCache {
                    onDrawWithContent {
                        drawContent()
                        if (selected) {
                            val y = size.height - 3.dp.toPx()
                            drawLine(iconTint.copy(alpha = .82f), Offset(size.width * .37f, y),
                                Offset(size.width * .63f, y), 1.4.dp.toPx(), androidx.compose.ui.graphics.StrokeCap.Round)
                            drawLine(iconTint.copy(alpha = .34f), Offset(size.width * .44f, y - 3.dp.toPx()),
                                Offset(size.width * .63f, y - 3.dp.toPx()), .7.dp.toPx(), androidx.compose.ui.graphics.StrokeCap.Round)
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BadgedBox(badge = {
                    if (item.badgeCount != null) {
                        Badge { Text(item.badgeCount.toString()) }
                    }
                }) {
                    Icon(
                        // §17 contrast: filled glyph when chosen, outlined when
                        // resting — the weight change is legible before the tint
                        // even lands.
                        if (selected) item.icon else item.outlinedIcon,
                        // When selected the label Text below is shown and read by
                        // TalkBack, so the icon must not repeat it — null keeps the
                        // glyph decorative and avoids the double read.
                        contentDescription = if (selected) null else item.label,
                        tint = iconTint,
                        modifier = Modifier.size(iconSize),
                    )
                }
                // The label's *slot* grows 0 -> full width in step with its alpha and
                // the box-width spring. Entering at full width in one frame used to
                // shove the centred icon toward the leading edge before the text was
                // even visible; expandHorizontally keeps the reflow continuous.
                AnimatedVisibility(
                    visible = selected,
                    enter = expandHorizontally(
                        animationSpec = if (motion) {
                            spring(stiffness = 700f, visibilityThreshold = IntSize.VisibilityThreshold)
                        } else {
                            snap()
                        },
                    ) + fadeIn(
                        animationSpec = if (motion) spring(stiffness = 900f) else snap(),
                    ),
                    exit = shrinkHorizontally(
                        animationSpec = if (motion) {
                            spring(stiffness = 700f, visibilityThreshold = IntSize.VisibilityThreshold)
                        } else {
                            snap()
                        },
                    ) + fadeOut(
                        animationSpec = if (motion) spring(stiffness = 900f) else snap(),
                    ),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = item.label,
                            style = FolioTheme.typography.labelSmall,
                            color = labelColor,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}
