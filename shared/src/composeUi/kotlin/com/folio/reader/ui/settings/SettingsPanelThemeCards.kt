package com.folio.reader.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 */
@Composable
internal fun ThemePackCard(
    pack: com.folio.reader.ui.theme.ThemePack,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = com.folio.reader.ui.theme.FolioTheme.colors
    val chrome = com.folio.reader.ui.theme.AppPalette.byId(pack.appPaletteId).colors
    val page = com.folio.reader.settings.Theme.getPreset(pack.readerThemeId)
    val shape = FolioShapes.inset
    val interaction = rememberFolioInteraction()
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
                .background(chrome.background)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .background(chrome.primary, CircleShape)
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp)
                    .background(chrome.onBackground.copy(alpha = 0.35f), RoundedCornerShape(2.dp))
            )
        }
        // Mini page: heading and body lines in the reader theme's own ink.
        androidx.compose.foundation.layout.Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(page.background))
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .height(6.dp)
                    .background(Color(page.headingText), RoundedCornerShape(3.dp))
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(Color(page.secondaryText).copy(alpha = 0.75f), RoundedCornerShape(3.dp))
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .height(4.dp)
                    .background(Color(page.secondaryText).copy(alpha = 0.55f), RoundedCornerShape(3.dp))
            )
        }
        Text(
            pack.name,
            style = MaterialTheme.typography.titleSmall,
            color = if (selected) colors.primary else colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
        )
    }
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
            style = MaterialTheme.typography.titleSmall,
            color = if (selected) colors.primary else colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
