package com.folio.reader.ui.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.folio.reader.ui.components.FolioSegmented
import com.folio.reader.ui.components.folioPanel
import com.folio.reader.ui.components.folioPressable
import com.folio.reader.ui.components.folioRaised
import com.folio.reader.ui.components.rememberFolioInteraction
import com.folio.reader.ui.theme.FolioShapes
import com.folio.reader.ui.theme.FolioTokens
import com.folio.reader.ui.theme.atmosphere
import com.folio.reader.ui.theme.describe
import com.folio.reader.ui.theme.displayName
import com.folio.reader.ui.theme.paneFill

/**
 * A theme pack, previewed as a **miniature of the app itself**: a live field band in that
 * theme's own animation, a row of its real accents, a page with heading and body lines, name
 * beneath. Selection lifts the card to the raised material with an accent-lit rim, so choosing a
 * theme feels like picking up an object rather than ticking a radio button.
 *
 * Three things make the card tell the truth about the theme:
 *  - **The field band runs the theme's own Mirage shader** (frozen to one frame for the row's
 *    sake — [folioFieldKindFor] on the pack id), so Honey's metaballs and Arctic's swirl look
 *    different at a glance, not just differently tinted. Where the shader can't run it falls back
 *    to a gradient of the theme's accents, so the band is never blank.
 *  - **The accents are the real multi-hue palette**, four swatches rather than one dot, so a
 *    theme that is cobalt + sienna + verdigris + gold reads as four colours.
 *  - **Every colour is read post-[folioRenderedColors]**, the same deepen-ink + legible-accents
 *    pass the app itself applies, so the preview matches what renders.
 *
 * The miniature always shows the pack's face for the mode the picker is in, so flipping the
 * switch turns every card at once — colours and name crossfade into their other face.
 */
@Composable
internal fun ThemePackCard(
    pack: com.folio.reader.ui.theme.ThemePack,
    dark: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    // §16: the System pack's live preview — the wallpaper-derived colours of the
    // face being shown. Null for every other pack (and for System where the
    // platform cannot derive one, in which case the card is not shown at all).
    previewColors: com.folio.reader.ui.theme.FolioColors? = null,
) {
    val colors = com.folio.reader.ui.theme.FolioTheme.colors
    val raw = previewColors
        ?: com.folio.reader.ui.theme.AppPalette.byId(pack.appPaletteId(dark)).colors
    // The colours the app will actually render for this palette — not the raw authored set.
    val spec = com.folio.reader.ui.theme.folioRenderedColors(raw)
    val page = com.folio.reader.settings.Theme.getPreset(pack.readerThemeId(dark))
    val shape = FolioShapes.inset
    val interaction = rememberFolioInteraction()
    val fade = tween<androidx.compose.ui.graphics.Color>(FolioTokens.motionStandard.toInt())
    val chromeBackground by animateColorAsState(spec.background, fade, label = "chromeBg")
    val chromeInk by animateColorAsState(spec.onBackground, fade, label = "chromeInk")
    val pageBackground by animateColorAsState(Color(page.background), fade, label = "pageBg")
    val pageHeading by animateColorAsState(Color(page.headingText), fade, label = "pageHeading")
    val pageInk by animateColorAsState(Color(page.secondaryText), fade, label = "pageInk")
    // The theme's four most telling accents, in palette order.
    val accents = listOf(spec.primary, spec.secondary, spec.tertiary, spec.accentAnnotation)
    androidx.compose.foundation.layout.Column(
        modifier = Modifier
            .width(148.dp)
            .folioPressable(interaction, scaleTo = 0.96f)
            .then(
                if (selected) {
                    Modifier.folioRaised(
                        shape = shape,
                        elevation = FolioTokens.elevationVeil,
                        accent = colors.primary,
                        // A pane, not an opaque slab: the selected tile used the opaque
                        // raised treatment whose directional gradient border laid a
                        // bright-then-dark band just inside the edge — the hard "square
                        // within" the pack picker. As a pane it takes the same uniform
                        // hairline rim and frosted body as every other glass surface, so
                        // the selected card matches the section card it sits on.
                        fill = com.folio.reader.ui.theme.FolioTheme.atmosphere.paneFill(),
                        // …and it matches that card's *tier* too, which is the point: this
                        // tile is an item in a scrolling row inside an in-flow section card,
                        // so nothing passes behind it but the ground plane. At the Overlay
                        // default it was one of the last surfaces on the Appearance panel
                        // still paying a full-area Haze capture-and-blur and an AGSL lens
                        // every frame, to frost a smooth ramp. See FolioGlassTier.
                        tier = com.folio.reader.ui.theme.FolioGlassTier.InFlow,
                    )
                } else {
                    Modifier.folioPanel(shape)
                }
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
    ) {
        // The accent band: a gradient of the theme's own accents. The animation
        // frame used to sit here, but the background animation is its own setting
        // now (the picker below the cards), so the card is purely the palette: no
        // shader frame on top, just the theme's colours.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .background(
                    androidx.compose.ui.graphics.Brush.horizontalGradient(
                        accents.ifEmpty { listOf(chromeBackground, chromeBackground) }
                    )
                )
        )
        // Mini app chrome: the real accent row plus a title-bar line.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(chromeBackground)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                accents.forEach { accent ->
                    val dot by animateColorAsState(accent, fade, label = "accentDot")
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(dot, CircleShape)
                    )
                }
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp)
                    .background(chromeInk.copy(alpha = 0.35f), RoundedCornerShape(2.dp))
            )
        }
        // Mini page: heading and body lines in the reader theme's own ink.
        androidx.compose.foundation.layout.Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(pageBackground)
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .height(6.dp)
                    .background(pageHeading, RoundedCornerShape(3.dp))
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(pageInk.copy(alpha = 0.75f), RoundedCornerShape(3.dp))
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .height(4.dp)
                    .background(pageInk.copy(alpha = 0.55f), RoundedCornerShape(3.dp))
            )
        }
        // The pair's name for this face, dissolving into its other face as the
        // mode flips — "Honey" fading into "Ember" is the whole point of pairs.
        androidx.compose.animation.AnimatedContent(
            targetState = pack.name(dark),
            transitionSpec = {
                fadeIn(tween(FolioTokens.motionStandard.toInt())) togetherWith
                    fadeOut(tween(FolioTokens.motionStandard.toInt()))
            },
            label = "packName",
        ) { name ->
            Text(
                name,
                style = com.folio.reader.ui.theme.FolioTheme.typography.titleSmall,
                color = if (selected) colors.primary else colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
            )
        }
    }
}

/**
 * The per-theme background-animation picker for the currently selected pack: a scrolling row of
 * **detail tiles** — **Default** (the pack's designed field, clears any override), **Plain** (no
 * animation, static ramp), then every [com.folio.reader.ui.theme.FolioFieldKind] family. The ten
 * designed-default families lead; the remaining Mirage families follow. Each tile carries a colour
 * swatch, the family's name and a one-line description of its motion, so the choice is legible
 * rather than a bare token. The lit tile is the pack's current effective choice; tapping writes
 * into `ReaderSettings.fieldOverrides` keyed by [packId] (Default stores nothing). Selection
 * language matches the cards: the chosen tile lifts to the raised material with an accent rim.
 */
@Composable
internal fun ThemeFieldOverridePicker(
    packId: String,
    current: String?,
    onChoose: (String?) -> Unit,
) {
    val colors = com.folio.reader.ui.theme.FolioTheme.colors
    val defaultKind = com.folio.reader.ui.theme.folioFieldKindFor(packId)
    // Options in a deliberate order: Default and Plain first, then the ten designed-default
    // families, then every other Mirage family the app can render.
    data class FieldOption(val token: String?, val title: String, val subtitle: String)
    val ordered = com.folio.reader.ui.theme.FOLIO_DEFAULT_FIELD_KINDS +
        com.folio.reader.ui.theme.FolioFieldKind.entries.filterNot {
            it in com.folio.reader.ui.theme.FOLIO_DEFAULT_FIELD_KINDS
        }
    val options: List<FieldOption> = buildList {
        add(FieldOption(null, "Default", defaultKind.displayName() + " · the app default"))
        add(FieldOption(com.folio.reader.ui.theme.FOLIO_FIELD_PLAIN, "Plain", "No animation — static ground"))
        ordered.forEach { add(FieldOption(it.name, it.displayName(), it.describe())) }
    }
    val accents = listOf(colors.primary, colors.secondary, colors.tertiary)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { opt ->
            val selected = opt.token == current
            val interaction = rememberFolioInteraction()
            val shape = FolioShapes.inset
            androidx.compose.foundation.layout.Column(
                modifier = Modifier
                    .width(150.dp)
                    .folioPressable(interaction, scaleTo = 0.95f)
                    .then(
                        if (selected) {
                            Modifier.folioRaised(
                                shape = shape,
                                elevation = FolioTokens.elevationPanel,
                                accent = colors.primary,
                                fill = com.folio.reader.ui.theme.FolioTheme.atmosphere.paneFill(),
                                // An item in a scrolling row inside an in-flow card — see
                                // the theme-pack tile above.
                                tier = com.folio.reader.ui.theme.FolioGlassTier.InFlow,
                            )
                        } else {
                            Modifier.folioPanel(shape)
                        }
                    )
                    .clickable(interactionSource = interaction, indication = null) {
                        onChoose(opt.token)
                    },
            ) {
                // Swatch band: Plain shows the flat ground; everything else a sweep of the
                // theme's accents, so the tile reads as part of this palette.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(26.dp)
                        .background(
                            if (opt.token == com.folio.reader.ui.theme.FOLIO_FIELD_PLAIN) {
                                androidx.compose.ui.graphics.Brush.horizontalGradient(
                                    listOf(colors.background, colors.background)
                                )
                            } else {
                                androidx.compose.ui.graphics.Brush.horizontalGradient(accents)
                            }
                        )
                )
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = opt.title,
                        style = com.folio.reader.ui.theme.FolioTheme.typography.titleSmall,
                        color = if (selected) colors.primary else colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = opt.subtitle,
                        style = com.folio.reader.ui.theme.FolioTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * The light/dark switch for the theme packs section, built on [FolioSegmented]
 * so its lit indicator glides between the two segments like every other
 * segmented control in the app. Tapping the unselected segment flips the active
 * pack to its other face; tapping the lit one is a no-op, so the control is a
 * switch, not two buttons.
 *
 * Disabled while a custom app theme is active: a custom theme owns its
 * polarity, and flipping underneath it would change nothing the user can see.
 */
@Composable
internal fun ThemeModeToggle(
    dark: Boolean,
    enabled: Boolean = true,
    onToggle: () -> Unit,
) {
    FolioSegmented(
        options = listOf("Light", "Dark"),
        selectedIndex = if (dark) 1 else 0,
        // FolioSegmented only fires onSelect for the segment that is not already
        // lit, so a bare onToggle() flips to the tapped face. Gated on [enabled]
        // because the control has no native disabled state: the dimmed alpha
        // shows the state and the no-op keeps a custom theme's polarity fixed.
        onSelect = { if (enabled) onToggle() },
        modifier = Modifier.alpha(if (enabled) 1f else 0.45f),
    )
}

/**
 * A typeface pairing, shown as a specimen: the display face at 28sp doing what it
 * actually does, over the pairing's name. Same selection language as the theme
 * cards — the chosen one lifts.
 */
@Composable
internal fun FontThemeCard(
    fontTheme: com.folio.reader.ui.theme.FontTheme,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = com.folio.reader.ui.theme.FolioTheme.colors
    val shape = FolioShapes.inset
    val interaction = rememberFolioInteraction()
    androidx.compose.foundation.layout.Column(
        modifier = Modifier
            .width(132.dp)
            .folioPressable(interaction, scaleTo = 0.96f)
            .then(
                if (selected) {
                    Modifier.folioRaised(
                        shape = shape,
                        elevation = FolioTokens.elevationVeil,
                        accent = colors.primary,
                        // Same as the theme-pack tile: a pane so the selected font card
                        // wears the uniform rim + frosted body instead of the opaque
                        // slab's directional border band.
                        fill = com.folio.reader.ui.theme.FolioTheme.atmosphere.paneFill(),
                        // …and in flow for the same reason — see the theme-pack tile.
                        tier = com.folio.reader.ui.theme.FolioGlassTier.InFlow,
                    )
                } else {
                    Modifier.folioPanel(shape)
                }
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Ag",
            fontFamily = com.folio.reader.ui.theme.UiFonts.display(fontTheme, weight = 600, opticalSize = 28f),
            fontSize = 30.sp,
            color = colors.onSurface,
        )
        Text(
            fontTheme.label,
            style = com.folio.reader.ui.theme.FolioTheme.typography.titleSmall,
            color = if (selected) colors.primary else colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
