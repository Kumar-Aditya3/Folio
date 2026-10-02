package com.folio.reader.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp as lerpDp
import com.folio.reader.ui.theme.FolioColors
import com.folio.reader.ui.theme.FolioAtmosphere
import com.folio.reader.ui.theme.FolioShapes
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens
import com.folio.reader.ui.theme.atmosphere
import com.folio.reader.ui.theme.rememberMotionEnabled

/**
 * Folio's own controls.
 *
 * The redesign gave every surface a material, but the *controls* on those surfaces
 * were still stock Material: a `Switch` whose track and thumb are Material 3's, a
 * `RadioButton` ring, an `OutlinedTextField` with its enforced minimum height and
 * its own notched border. Each one reads as a different design language dropped
 * onto the page, which is exactly the "flat, belongs-to-another-app" complaint the
 * audit raised for the settings and manga screens.
 *
 * These atoms replace them with the same vocabulary the surfaces already speak:
 * the pill/sunken-well shapes, the `atmosphere`'s hairline and sunken fill, the
 * `primary`/`onPrimary` selection pair, and — crucially — the one motion rule the
 * rest of the app honours. Every animated term here collapses to a snap under
 * reduce-motion, so the thumb slides and the selection cross-fades only when the
 * reader allows motion, and freezes to a correct static form otherwise.
 *
 * The colour choices live in [folioToggleTrack]/[folioToggleThumb] rather than
 * inline, so `DesignSystemTest` can pin their contrast across every palette
 * without a Compose test rig and the control and its guard cannot drift (Rule 3).
 */

/** The sunken input well's fill alpha — shared with [FolioSearchField]'s idiom and
 *  read by the guard so the two cannot disagree. */
internal const val FOLIO_FIELD_WELL_ALPHA = 0.5f

/** The toggle track's colour for the given state. On = the theme's own primary; off
 *  = the sunken well the rest of the control vocabulary uses. Pure so it is pinned. */
internal fun folioToggleTrack(on: Boolean, colors: FolioColors, atmos: FolioAtmosphere): Color =
    if (on) colors.primary else atmos.sunkenFill.copy(alpha = 0.9f)

/** The toggle thumb's colour. On = `onPrimary` (the pair the palette guarantees against
 *  `primary`); off = a strong-enough ink to read on the quiet track. Pure so it is pinned. */
internal fun folioToggleThumb(on: Boolean, colors: FolioColors): Color =
    if (on) colors.onPrimary else colors.onSurfaceVariant

private val TOGGLE_W = 44.dp
private val TOGGLE_H = 26.dp
private val TOGGLE_THUMB = 18.dp
private val TOGGLE_PAD = 4.dp

/**
 * A Folio switch. One track, one thumb that *travels* — the same "follow the light"
 * idiom [FolioSegmented] uses for its lit segment — rather than Material's crossfade.
 * Honours reduce-motion: the thumb snaps and the track colour switches with no spring.
 */
@Composable
fun FolioToggle(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = FolioTheme.colors
    val atmos = FolioTheme.atmosphere
    val motion = rememberMotionEnabled()
    val track by animateColorAsState(
        targetValue = folioToggleTrack(checked, colors, atmos),
        animationSpec = if (motion) spring() else snap(),
        label = "toggleTrack",
    )
    val thumbColor = folioToggleThumb(checked, colors)
    // The thumb travels from the leading pad to the trailing pad; a slight overshoot
    // (damping ≈ 0.7) lands it like liquid, matching the press springs' calibration.
    val fraction by animateDpAsState(
        targetValue = if (checked) 1.dp else 0.dp,
        animationSpec = if (motion) spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow) else snap(),
        label = "toggleThumb",
    )
    val rim = atmos.hairline
    Box(
        modifier = modifier
            .then(
                if (onCheckedChange != null) {
                    Modifier.toggleable(
                        value = checked,
                        enabled = enabled,
                        role = Role.Switch,
                        onValueChange = onCheckedChange,
                    )
                } else {
                    Modifier
                },
            )
            .minimumInteractiveComponentSize()
            .size(TOGGLE_W, TOGGLE_H)
            .clip(FolioShapes.pill)
            .background(track, FolioShapes.pill)
            .border(1.dp, rim, FolioShapes.pill),
        contentAlignment = Alignment.CenterStart,
    ) {
        // The thumb travels across the free span (track width - 2*pad - thumb). Its
        // start offset is resolved in dp from the animated fraction, so the glide is a
        // single layout read, not a per-frame relayout of the row around it.
        val startX = TOGGLE_PAD
        val endX = TOGGLE_W - TOGGLE_PAD - TOGGLE_THUMB
        val thumbX = lerpDp(startX, endX, (fraction / 1.dp).coerceIn(0f, 1f))
        Box(
            modifier = Modifier
                .padding(start = thumbX)
                .size(TOGGLE_THUMB)
                .clip(CircleShape)
                .background(thumbColor, CircleShape),
        )
    }
}

/**
 * A settings row: a label (and optional supporting line) as type on the page, with a
 * [FolioToggle] trailing. No card — most rows are type-on-field (§R1), and a toggle
 * on a hairline-less row is what keeps a settings screen from reading as a stack of
 * boxes. The whole row is the touch target.
 */
@Composable
fun FolioToggleRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    val colors = FolioTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(vertical = FolioTokens.space2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FolioTokens.space2),
    ) {
        if (leading != null) {
            leading()
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = FolioTheme.typography.bodyLarge,
                color = colors.onSurface,
            )
            if (supporting != null) {
                Text(
                    text = supporting,
                    style = FolioTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
        // Null handler: the row owns the toggleable, so the thumb is purely visual.
        FolioToggle(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/**
 * An exclusive-choice row: a Folio radio mark (a ring that fills with a primary dot
 * when chosen) and a label, as type on the page. Replaces stock `RadioButton` rows.
 * The ring/dot cross-fades under motion and snaps otherwise.
 */
@Composable
fun FolioRadioRow(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    enabled: Boolean = true,
) {
    val colors = FolioTheme.colors
    val atmos = FolioTheme.atmosphere
    val motion = rememberMotionEnabled()
    val ring by animateColorAsState(
        targetValue = if (selected) colors.primary else atmos.hairline.copy(alpha = 0.9f),
        animationSpec = if (motion) spring() else snap(),
        label = "radioRing",
    )
    val dot by animateColorAsState(
        targetValue = if (selected) colors.primary else Color.Transparent,
        animationSpec = if (motion) spring() else snap(),
        label = "radioDot",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(vertical = FolioTokens.space2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FolioTokens.space2),
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .drawBehind {
                    val stroke = 2.dp.toPx()
                    drawCircle(
                        color = ring,
                        radius = (size.minDimension - stroke) / 2f,
                        style = Stroke(width = stroke),
                    )
                    if (dot.alpha > 0f) {
                        drawCircle(color = dot, radius = size.minDimension * 0.26f)
                    }
                },
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = FolioTheme.typography.bodyLarge,
                color = colors.onSurface,
            )
            if (supporting != null) {
                Text(
                    text = supporting,
                    style = FolioTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * A selectable row carried by fill and an accent rim rather than a stock checkbox —
 * the multi-select sibling of [FolioRadioRow], for filter lists where more than one
 * may be on. Reads as a quiet panel that lights up when chosen.
 */
@Composable
fun FolioSelectableRow(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = FolioTheme.colors
    val atmos = FolioTheme.atmosphere
    val motion = rememberMotionEnabled()
    val fill by animateColorAsState(
        targetValue = if (selected) colors.primary.copy(alpha = 0.14f) else Color.Transparent,
        animationSpec = if (motion) spring() else snap(),
        label = "selectableFill",
    )
    val rim by animateColorAsState(
        targetValue = if (selected) colors.primary else atmos.hairline,
        animationSpec = if (motion) spring() else snap(),
        label = "selectableRim",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(FolioShapes.card)
            .background(fill, FolioShapes.card)
            .border(1.dp, rim, FolioShapes.card)
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.Checkbox,
                onClick = onClick,
            )
            .padding(horizontal = FolioTokens.space3, vertical = FolioTokens.space2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FolioTokens.space2),
    ) {
        Text(
            text = label,
            style = FolioTheme.typography.bodyLarge,
            color = if (selected) colors.onSurface else colors.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (trailing != null) trailing()
    }
}

/**
 * The sunken-well input the whole app should have had instead of
 * `OutlinedTextField`. A [BasicTextField] (no enforced minimum height to fight)
 * clipped into the atmosphere's own sunken well — the same fill, hairline and shape
 * [FolioSearchField] and [FolioSegmented] use — so an input reads as a well cut into
 * the page rather than a Material notch floating on it.
 *
 * The signature is close to `OutlinedTextField`'s common subset so a call site can be
 * swapped mechanically.
 */
@Composable
fun FolioSunkenField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    shape: Shape = FolioShapes.inset,
) {
    val colors = FolioTheme.colors
    val atmos = FolioTheme.atmosphere
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = singleLine,
        textStyle = FolioTheme.typography.bodyMedium.copy(color = colors.onSurface),
        cursorBrush = SolidColor(colors.primary),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        modifier = modifier
            .clip(shape)
            .background(atmos.sunkenFill.copy(alpha = FOLIO_FIELD_WELL_ALPHA), shape)
            .border(1.dp, atmos.hairline, shape)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        decorationBox = { inner ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (leadingIcon != null) leadingIcon()
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty() && placeholder != null) {
                        Text(
                            text = placeholder,
                            style = FolioTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    inner()
                }
                if (trailingIcon != null) trailingIcon()
            }
        },
    )
}
