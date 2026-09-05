package com.folio.reader.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.folio.reader.settings.CustomAppTheme
import com.folio.reader.settings.ReaderSettings
import com.folio.reader.settings.Theme
import com.folio.reader.ui.components.FolioSliderRow
import com.folio.reader.ui.components.folioField
import com.folio.reader.ui.components.folioPanel
import com.folio.reader.ui.components.folioVeil
import com.folio.reader.ui.theme.AppPalette
import com.folio.reader.ui.theme.FolioColors
import com.folio.reader.ui.theme.FolioShapes
import com.folio.reader.ui.theme.FolioSurfaceOpacity
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens
import com.folio.reader.ui.theme.atmosphere
import com.folio.reader.ui.theme.customAppThemeFrom
import com.folio.reader.ui.theme.readerVeilAlpha
import com.folio.reader.ui.theme.surfaceOpacity
import com.folio.reader.ui.theme.toArgbInt
import com.folio.reader.ui.theme.toFolioColors
import com.folio.reader.ui.theme.topBarFill
import kotlin.math.roundToInt

/**
 * Appearance: the two settings screens that let a reader author the shell itself —
 * how solid its glass is, and what colours it is made of.
 *
 * Both are built on [AppearanceMockup], which is not a drawing of the app but the
 * app's own materials rendered small. It opens a nested `AppTheme` and uses
 * `folioField`, `folioVeil` and the real atmosphere, so a preview cannot drift
 * from the thing it previews: if the gradient changes, the preview changes.
 */

// ── Preview ──────────────────────────────────────────────────────────────────

/** Which element the preview should call attention to while a control is in use. */
enum class MockupFocus { NONE, TOP_BAR, NAV_BAR, PANEL, READER }

/**
 * A miniature of the shell in [colors] at [opacity].
 *
 * Every surface here is painted by the same modifier the real screen uses, at the
 * same alpha, so what the user sees is literally the result of their setting and
 * not an approximation of it. [focus] rings one element while its slider is being
 * dragged — with four translucent surfaces on screen at once, it is otherwise hard
 * to tell which one moved.
 */
@Composable
fun AppearanceMockup(
    colors: FolioColors,
    isDark: Boolean,
    opacity: FolioSurfaceOpacity,
    readerTheme: Theme,
    modifier: Modifier = Modifier,
    focus: MockupFocus = MockupFocus.NONE,
) {
    val shape = FolioShapes.card
    // The mockup is its own little app: nesting the theme means the miniature's
    // atmosphere is derived from the previewed palette, not the live one.
    FolioTheme.AppTheme(
        palette = if (isDark) AppPalette.DARK else AppPalette.LIGHT,
        colors = colors,
        isDark = isDark,
        opacity = opacity,
    ) {
        val atmos = FolioTheme.atmosphere
        Box(
            modifier = modifier
                .fillMaxWidth()
                // Big enough to judge. At the old 228dp the reader page — the one
                // surface people actually tune — was a sliver, and translucency
                // reads only when there is enough behind the glass to see through it.
                .height(320.dp)
                .clip(shape)
                // The page. This is the gradient and its three accent pools, so a
                // custom palette's background shows exactly as it will in the app.
                .folioField()
                .border(1.dp, atmos.hairline, shape)
        ) {
            // The page runs the miniature's full height and is deliberately *not*
            // inset for the bars — they are drawn over it, exactly as the real shell
            // overlays a scrolling library. This is the whole point: with nothing
            // passing underneath them, a see-through bar and a solid one look
            // identical, which is why the old Column layout made the top-bar and
            // nav sliders look like they did nothing.
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MockShelfRow(height = 74.dp)
                Row(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    MockContentCard(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        focus = focus == MockupFocus.PANEL,
                    )
                    MockReaderSheet(
                        readerTheme = readerTheme,
                        modifier = Modifier.weight(1.15f).fillMaxHeight(),
                        focus = focus == MockupFocus.READER,
                    )
                }
                MockShelfRow(height = 64.dp)
            }
            MockTopBar(
                modifier = Modifier.align(Alignment.TopCenter),
                focus = focus == MockupFocus.TOP_BAR,
            )
            MockNavCapsule(
                modifier = Modifier.align(Alignment.BottomCenter),
                focus = focus == MockupFocus.NAV_BAR,
            )
        }
    }
}

/**
 * A row of covers: the ground the bars float over.
 *
 * Covers rather than grey blocks because the bars are translucent over *colour* in
 * the real app, and grey tells you nothing about how a tint reads through glass.
 */
@Composable
private fun MockShelfRow(height: Dp, modifier: Modifier = Modifier) {
    val colors = FolioTheme.colors
    Row(
        modifier = modifier.fillMaxWidth().height(height),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(
            colors.accentProgress,
            colors.accentDiscovery,
            colors.accentStreak,
            colors.primary,
        ).forEach { tint ->
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    Modifier.fillMaxWidth().weight(1f)
                        .background(tint.copy(alpha = 0.85f), FolioShapes.plateSmall)
                )
                Box(
                    Modifier.fillMaxWidth(0.8f).height(4.dp)
                        .background(colors.onSurfaceVariant.copy(alpha = 0.5f), CircleShape)
                )
            }
        }
    }
}

/** The masthead, painted with the same three-stop decay as [com.folio.reader.ui.components.FolioTopBar]. */
@Composable
private fun MockTopBar(modifier: Modifier = Modifier, focus: Boolean) {
    val colors = FolioTheme.colors
    val atmos = FolioTheme.atmosphere
    // The miniature always depicts a screen that has been scrolled — that is the
    // state in which a top bar has anything behind it — so it takes the fill at
    // full collapse. The knob is the crown's alpha, not a factor on it.
    val fill = FolioTheme.surfaceOpacity.topBarFill(1f)
    val veil = atmos.barGlass
    val scrim = atmos.barScrim.copy(alpha = atmos.barScrim.alpha * fill.scrim)
    val sheen = atmos.rimLight
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    0f to scrim,
                    0.35f to veil.copy(alpha = fill.crown),
                    0.75f to veil.copy(alpha = fill.waist),
                    1f to veil.copy(alpha = fill.foot),
                )
            )
            .background(
                Brush.verticalGradient(
                    0f to sheen.copy(alpha = sheen.alpha * 0.30f * fill.presence),
                    0.38f to Color.Transparent,
                )
            )
            .focusRing(focus)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Library",
                style = FolioTheme.typography.titleMedium,
                color = colors.onBackground,
                maxLines = 1,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(2) {
                    Box(
                        Modifier.size(11.dp)
                            .background(colors.onSurfaceVariant.copy(alpha = 0.55f), CircleShape)
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(atmos.hairline))
        // The fade below the rule — depth is the hairline's job, not the fill's.
        Box(
            Modifier.fillMaxWidth().height(FolioTokens.barGlassFade).background(
                Brush.verticalGradient(
                    listOf(veil.copy(alpha = fill.crown * 0.45f), Color.Transparent)
                )
            )
        )
    }
}

/** A grouped-content panel: the app's most common surface. */
@Composable
private fun MockContentCard(modifier: Modifier = Modifier, focus: Boolean) {
    val colors = FolioTheme.colors
    Column(
        modifier = modifier
            .folioVeil(FolioShapes.inset)
            .focusRing(focus, FolioShapes.inset)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.width(26.dp).height(38.dp)
                    .background(colors.accentProgress, FolioShapes.plateSmall)
            )
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    text = "Continue",
                    style = FolioTheme.typography.labelMedium,
                    color = colors.onSurface,
                    maxLines = 1,
                )
                Box(Modifier.width(58.dp).height(5.dp).background(colors.outline, CircleShape))
                Box(
                    Modifier.width(46.dp).height(5.dp)
                        .background(colors.accentStreak, CircleShape)
                )
            }
        }
        Box(
            Modifier.fillMaxWidth().height(6.dp)
                .background(colors.surfaceContainerHighest, CircleShape)
        ) {
            Box(
                Modifier.fillMaxWidth(0.62f).fillMaxHeight()
                    .background(colors.primary, CircleShape)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            listOf(colors.accentDiscovery, colors.accentAnnotation, colors.primary).forEach {
                Box(Modifier.size(width = 24.dp, height = 9.dp).background(it, FolioShapes.chip))
            }
        }
    }
}

/** Reader chrome over a page: the one surface whose ground is not the app's field. */
@Composable
private fun MockReaderSheet(readerTheme: Theme, modifier: Modifier = Modifier, focus: Boolean) {
    val colors = FolioTheme.colors
    val page = Color(readerTheme.background)
    val ink = Color(readerTheme.secondaryText)
    Box(
        modifier = modifier
            .clip(FolioShapes.inset)
            .background(page)
            .border(1.dp, FolioTheme.atmosphere.hairline, FolioShapes.inset),
    ) {
        // The page underneath, so the reader panel's translucency is legible.
        Column(
            modifier = Modifier.fillMaxSize().padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "Chapter",
                style = FolioTheme.typography.labelMedium,
                color = Color(readerTheme.headingText),
                maxLines = 1,
            )
            repeat(10) { i ->
                Box(
                    Modifier
                        .fillMaxWidth(if (i % 3 == 2) 0.7f else 1f)
                        .height(4.dp)
                        .background(ink.copy(alpha = 0.5f), CircleShape)
                )
            }
        }
        // The reader's settings sheet, anchored to the bottom like the real one. It
        // deliberately covers several lines of the page: that overlap is the only
        // way "how transparent is this" is a question the preview can answer.
        val sheetShape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .folioVeil(sheetShape, fillAlpha = FolioTheme.readerVeilAlpha)
                .focusRing(focus, sheetShape)
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Aa",
                    style = FolioTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = colors.onSurface,
                )
                Text(
                    text = "Text size",
                    style = FolioTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            listOf(0.45f, 0.7f).forEach { filled ->
                Box(
                    Modifier.fillMaxWidth().height(6.dp)
                        .background(colors.surfaceContainerHighest, CircleShape)
                ) {
                    Box(
                        Modifier.fillMaxWidth(filled).fillMaxHeight()
                            .background(colors.primary, CircleShape)
                    )
                }
            }
        }
    }
}

/** The floating nav capsule, at the same fill alpha the shell gives it. */
@Composable
private fun MockNavCapsule(modifier: Modifier = Modifier, focus: Boolean) {
    val colors = FolioTheme.colors
    Box(
        modifier = modifier.fillMaxWidth().padding(bottom = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .folioVeil(FolioShapes.pill, fillAlpha = FolioTheme.surfaceOpacity.navBar)
                .focusRing(focus, FolioShapes.pill)
                .padding(horizontal = 12.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(4) { i ->
                Box(
                    Modifier.size(if (i == 0) 12.dp else 10.dp).background(
                        if (i == 0) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.6f),
                        CircleShape,
                    )
                )
            }
        }
    }
}

/** A soft accent ring, only while the matching control is in use. */
@Composable
private fun Modifier.focusRing(
    active: Boolean,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(0.dp),
): Modifier =
    if (active) this.border(2.dp, FolioTheme.colors.primary.copy(alpha = 0.85f), shape) else this

// ── Transparency ─────────────────────────────────────────────────────────────

/**
 * The four glass surfaces, each with its own knob.
 *
 * Split this way because they answer different questions: the top bar and nav sit
 * over the app's own page (taste), while reader chrome sits over a page being read
 * (legibility). One global "transparency" slider would force the same answer to both.
 */
@Composable
fun TransparencySettingsPanel(
    settings: ReaderSettings,
    onSettingsChange: (ReaderSettings) -> Unit,
) {
    val colors = FolioTheme.colors
    var focus by remember { mutableStateOf(MockupFocus.NONE) }
    val appColors = resolveAppColors(settings)
    val opacity = FolioSurfaceOpacity(
        topBar = settings.topBarOpacity,
        navBar = settings.navBarOpacity,
        panel = settings.panelOpacity,
        readerChrome = settings.readerChromeOpacity,
    )

    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        AppearanceMockup(
            colors = appColors.first,
            isDark = appColors.second,
            opacity = opacity,
            readerTheme = settings.customTheme ?: Theme.getPreset(settings.themeId),
            focus = focus,
        )
        Text(
            text = "Drag a slider to highlight the surface it controls in the preview. " +
                "Each value is the surface's own opacity: 100% is solid and nothing " +
                "behind it shows through, while the designed glass look sits partway " +
                "along — which is where “Reset” puts it.",
            style = FolioTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )

        OpacityRow(
            label = "Top bar",
            value = settings.topBarOpacity,
            accent = colors.accentProgress,
            onFocus = { focus = if (it) MockupFocus.TOP_BAR else MockupFocus.NONE },
            onChange = { onSettingsChange(settings.copy(topBarOpacity = it)) },
        )
        OpacityRow(
            label = "Navigation bar",
            value = settings.navBarOpacity,
            accent = colors.accentDiscovery,
            onFocus = { focus = if (it) MockupFocus.NAV_BAR else MockupFocus.NONE },
            onChange = { onSettingsChange(settings.copy(navBarOpacity = it)) },
        )
        OpacityRow(
            label = "Cards and sheets",
            value = settings.panelOpacity,
            accent = colors.primary,
            onFocus = { focus = if (it) MockupFocus.PANEL else MockupFocus.NONE },
            onChange = { onSettingsChange(settings.copy(panelOpacity = it)) },
        )
        OpacityRow(
            label = "In-reader controls",
            value = settings.readerChromeOpacity,
            accent = colors.accentStreak,
            onFocus = { focus = if (it) MockupFocus.READER else MockupFocus.NONE },
            onChange = { onSettingsChange(settings.copy(readerChromeOpacity = it)) },
        )
        Text(
            text = "In-reader bars, the side rail and the reader's own sheets. 100% is " +
                "fully solid so nothing bleeds through; low values trade legibility " +
                "for immersion.",
            style = FolioTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )

        TextButton(
            onClick = {
                onSettingsChange(
                    settings.copy(
                        topBarOpacity = FolioSurfaceOpacity.BAR_GLASS,
                        navBarOpacity = FolioSurfaceOpacity.NAV_GLASS,
                        panelOpacity = FolioSurfaceOpacity.PANEL_GLASS,
                        readerChromeOpacity = 1f,
                    )
                )
            }
        ) { Text("Reset to designed values") }
    }
}

@Composable
private fun OpacityRow(
    label: String,
    value: Float,
    accent: Color,
    onFocus: (Boolean) -> Unit,
    onChange: (Float) -> Unit,
) {
    FolioSliderRow(
        label = label,
        valueLabel = "${(value.coerceIn(FolioSurfaceOpacity.MIN, 1f) * 100).roundToInt()}%",
        value = value.coerceIn(FolioSurfaceOpacity.MIN, 1f),
        onValueChange = {
            onFocus(true)
            onChange(it)
        },
        valueRange = FolioSurfaceOpacity.MIN..1f,
        onValueChangeFinished = { onFocus(false) },
        accent = accent,
    )
}

// ── Custom theme ─────────────────────────────────────────────────────────────

/** The six authored roles, in the order they matter when building a theme. */
private enum class ThemeRole(val label: String, val hint: String) {
    BACKGROUND("Page", "The ground everything sits on. Its lightness decides dark or light."),
    SURFACE("Surface", "Cards, bars and sheets."),
    PRIMARY("Primary", "Selection, switches, progress."),
    PROGRESS("Progress accent", "Continue-reading cues — and the first background pool."),
    DISCOVERY("Discovery accent", "Browse and recommendations — the second pool."),
    STREAK("Streak accent", "Streaks and warnings — the third pool."),
}

private fun CustomAppTheme.role(role: ThemeRole): Int = when (role) {
    ThemeRole.BACKGROUND -> background
    ThemeRole.SURFACE -> surface
    ThemeRole.PRIMARY -> primary
    ThemeRole.PROGRESS -> accentProgress
    ThemeRole.DISCOVERY -> accentDiscovery
    ThemeRole.STREAK -> accentStreak
}

private fun CustomAppTheme.withRole(role: ThemeRole, argb: Int): CustomAppTheme = when (role) {
    ThemeRole.BACKGROUND -> copy(background = argb)
    ThemeRole.SURFACE -> copy(surface = argb)
    ThemeRole.PRIMARY -> copy(primary = argb)
    ThemeRole.PROGRESS -> copy(accentProgress = argb)
    ThemeRole.DISCOVERY -> copy(accentDiscovery = argb)
    ThemeRole.STREAK -> copy(accentStreak = argb)
}

/**
 * The theme maker.
 *
 * Six colours, not forty: the rest of the role set is derived (see
 * `CustomAppTheme.toFolioColors`), which is also why the page gradient and its
 * colour pools update as the three accents are edited — they are the pools.
 */
@Composable
fun CustomThemeSettingsPanel(
    settings: ReaderSettings,
    onSettingsChange: (ReaderSettings) -> Unit,
) {
    val colors = FolioTheme.colors
    val active = settings.customAppTheme
    var editing by remember { mutableStateOf(ThemeRole.BACKGROUND) }
    val appColors = resolveAppColors(settings)

    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        AppearanceMockup(
            colors = appColors.first,
            isDark = appColors.second,
            opacity = settings.let {
                FolioSurfaceOpacity(
                    it.topBarOpacity, it.navBarOpacity, it.panelOpacity, it.readerChromeOpacity
                )
            },
            readerTheme = settings.customTheme ?: Theme.getPreset(settings.themeId),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Use a custom theme",
                    style = FolioTheme.typography.bodyLarge,
                    color = colors.onSurface,
                )
                Text(
                    text = if (active != null) {
                        "Replacing the ${AppPalette.byId(settings.appThemeId).label} pack."
                    } else {
                        "Off — the selected theme pack is in use."
                    },
                    style = FolioTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            Switch(
                checked = active != null,
                onCheckedChange = { on ->
                    onSettingsChange(
                        settings.copy(
                            customAppTheme = if (on) {
                                customAppThemeFrom(AppPalette.byId(settings.appThemeId))
                            } else {
                                null
                            }
                        )
                    )
                },
            )
        }

        if (active == null) {
            Text(
                text = "Turn this on to start from the pack you are using now, then edit " +
                    "any of its six colours. The background gradient is generated from " +
                    "your three accents, so it follows every change.",
                style = FolioTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
            return@Column
        }

        Text(
            text = "Colours",
            style = FolioTheme.typography.bodyLarge,
            color = colors.onSurface,
        )
        ThemeRole.entries.forEach { role ->
            RoleRow(
                role = role,
                argb = active.role(role),
                selected = editing == role,
                onClick = { editing = role },
            )
        }

        ColorEditor(
            argb = active.role(editing),
            onChange = { onSettingsChange(settings.copy(customAppTheme = active.withRole(editing, it))) },
        )

        Text(
            text = "Start from a pack",
            style = FolioTheme.typography.bodyLarge,
            color = colors.onSurface,
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(AppPalette.entries) { palette ->
                SeedChip(
                    palette = palette,
                    onClick = {
                        onSettingsChange(settings.copy(customAppTheme = customAppThemeFrom(palette)))
                    },
                )
            }
        }
    }
}

/** One role: its swatch, its name, and what it does. Tapping opens the editor. */
@Composable
private fun RoleRow(role: ThemeRole, argb: Int, selected: Boolean, onClick: () -> Unit) {
    val colors = FolioTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(FolioShapes.inset)
            .then(if (selected) Modifier.folioPanel(FolioShapes.inset, accent = colors.primary) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .background(Color(argb), CircleShape)
                .border(1.dp, FolioTheme.atmosphere.hairline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = if (isLightArgb(argb)) Color.Black.copy(alpha = 0.7f) else Color.White,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = role.label,
                style = FolioTheme.typography.labelLarge,
                color = colors.onSurface,
            )
            Text(
                text = role.hint,
                style = FolioTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = hexLabel(argb),
            style = FolioTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
        )
    }
}

/**
 * Hue/saturation/lightness rather than RGB. Three sliders that each mean something
 * ("more blue", "less grey", "darker") beat three that mean nothing on their own,
 * and the hue track shows the choice rather than describing it.
 */
@Composable
private fun ColorEditor(argb: Int, onChange: (Int) -> Unit) {
    val colors = FolioTheme.colors
    val hsv = remember(argb) { argbToHsv(argb) }
    Column(
        modifier = Modifier.fillMaxWidth().folioPanel(FolioShapes.inset).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.size(44.dp).background(Color(argb), FolioShapes.inset)
                    .border(1.dp, FolioTheme.atmosphere.hairline, FolioShapes.inset)
            )
            Text(
                text = hexLabel(argb),
                style = FolioTheme.typography.titleSmall,
                color = colors.onSurface,
            )
        }
        // The hue rail, drawn in hue itself.
        Box(
            Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(
                Brush.horizontalGradient(
                    (0..6).map { Color.hsv(it * 60f % 360f, 0.85f, 0.95f) }
                )
            )
        )
        FolioSliderRow(
            label = "Hue",
            valueLabel = "${hsv[0].roundToInt()}°",
            value = hsv[0],
            onValueChange = { onChange(hsvToArgb(it, hsv[1], hsv[2])) },
            valueRange = 0f..360f,
        )
        FolioSliderRow(
            label = "Saturation",
            valueLabel = "${(hsv[1] * 100).roundToInt()}%",
            value = hsv[1],
            onValueChange = { onChange(hsvToArgb(hsv[0], it, hsv[2])) },
            accent = Color.hsv(hsv[0], 0.85f, if (FolioTheme.atmosphere.isDark) 0.9f else 0.7f),
        )
        FolioSliderRow(
            label = "Brightness",
            valueLabel = "${(hsv[2] * 100).roundToInt()}%",
            value = hsv[2],
            onValueChange = { onChange(hsvToArgb(hsv[0], hsv[1], it)) },
            accent = Color.hsv(hsv[0], hsv[1].coerceAtLeast(0.15f), 0.85f),
        )
    }
}

/** Seeds the six roles from a built-in pack, so authoring never starts from nothing. */
@Composable
private fun SeedChip(palette: AppPalette, onClick: () -> Unit) {
    val c = palette.colors
    Column(
        modifier = Modifier
            .width(74.dp)
            .clip(FolioShapes.inset)
            .clickable(onClick = onClick)
            .folioPanel(FolioShapes.inset)
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().height(26.dp).clip(RoundedCornerShape(7.dp))
                .background(Brush.verticalGradient(listOf(c.background, c.surface)))
        ) {
            Row(
                modifier = Modifier.align(Alignment.BottomStart).padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                listOf(c.accentProgress, c.accentDiscovery, c.accentStreak).forEach {
                    Box(Modifier.size(6.dp).background(it, CircleShape))
                }
            }
        }
        Text(
            text = palette.label,
            style = FolioTheme.typography.labelSmall,
            color = FolioTheme.colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ── Shared helpers ───────────────────────────────────────────────────────────

/** The palette the app is actually running, custom theme included. */
@Composable
internal fun resolveAppColors(settings: ReaderSettings): Pair<FolioColors, Boolean> {
    val custom = settings.customAppTheme
    val palette = AppPalette.byId(settings.appThemeId)
    // Keyed on both so the slot survives toggling the custom theme on and off.
    return remember(custom, palette) {
        if (custom != null) {
            custom.toFolioColors() to custom.isDark
        } else {
            palette.colors to palette.isDark
        }
    }
}

private fun hexLabel(argb: Int): String {
    val hex = (argb and 0xFFFFFF).toString(16).uppercase().padStart(6, '0')
    return "#$hex"
}

private fun isLightArgb(argb: Int): Boolean {
    val r = ((argb shr 16) and 0xFF) / 255f
    val g = ((argb shr 8) and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    return r * 0.2126f + g * 0.7152f + b * 0.0722f > 0.5f
}

/** [hue 0..360, saturation 0..1, value 0..1]. */
private fun argbToHsv(argb: Int): FloatArray {
    val r = ((argb shr 16) and 0xFF) / 255f
    val g = ((argb shr 8) and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val delta = max - min
    val hue = when {
        delta < 0.0001f -> 0f
        max == r -> 60f * (((g - b) / delta) % 6f)
        max == g -> 60f * (((b - r) / delta) + 2f)
        else -> 60f * (((r - g) / delta) + 4f)
    }
    return floatArrayOf(if (hue < 0f) hue + 360f else hue, if (max <= 0f) 0f else delta / max, max)
}

private fun hsvToArgb(hue: Float, saturation: Float, value: Float): Int =
    Color.hsv(
        hue.coerceIn(0f, 360f) % 360f,
        saturation.coerceIn(0f, 1f),
        value.coerceIn(0f, 1f),
    ).toArgbInt()
