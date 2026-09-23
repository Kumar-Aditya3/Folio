package com.folio.reader.ui.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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

/**
 * A theme pack, previewed as a **miniature of the app itself**: chrome band, page
 * with heading and body lines, name beneath. Selection lifts the card to the raised
 * material with an accent-lit rim, so choosing a theme feels like picking up an
 * object rather than ticking a radio button.
 *
 * The miniature always shows the pack's face for the mode the picker is in, so
 * flipping the switch turns every card at once — and both the colours and the
 * name crossfade into their other face rather than snapping.
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
    val spec = previewColors
        ?: com.folio.reader.ui.theme.AppPalette.byId(pack.appPaletteId(dark)).colors
    val page = com.folio.reader.settings.Theme.getPreset(pack.readerThemeId(dark))
    val shape = FolioShapes.inset
    val interaction = rememberFolioInteraction()
    val fade = tween<androidx.compose.ui.graphics.Color>(FolioTokens.motionStandard.toInt())
    val chromeBackground by animateColorAsState(spec.background, fade, label = "chromeBg")
    val chromePrimary by animateColorAsState(spec.primary, fade, label = "chromeDot")
    val chromeInk by animateColorAsState(spec.onBackground, fade, label = "chromeInk")
    val pageBackground by animateColorAsState(Color(page.background), fade, label = "pageBg")
    val pageHeading by animateColorAsState(Color(page.headingText), fade, label = "pageHeading")
    val pageInk by animateColorAsState(Color(page.secondaryText), fade, label = "pageInk")
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
                    )
                } else {
                    Modifier.folioPanel(shape)
                }
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
    ) {
        // Mini app chrome: palette dot plus a title-bar line.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(chromeBackground)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .background(chromePrimary, CircleShape)
            )
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
