package com.folio.reader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.folio.reader.ui.theme.FolioShapes
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens

/**
 * Typography as structure.
 *
 * The previous build put a `titleSmall` in `primary` at the top of every card and
 * called that hierarchy — so every section had the same voice at the same volume.
 * These primitives give sections real typographic rank:
 *
 *  - [FolioEyebrow] — a small, letterspaced, accent-coloured kicker *outside* any
 *    container. It labels a region of the page rather than titling a box.
 *  - [FolioSectionHead] — the display-face section heading, optionally with a
 *    trailing action. The loudest thing on a non-hero screen.
 *  - [FolioFigure] — a large numeral treated as an editorial figure: the number in
 *    the display face, its unit riding small alongside, caption beneath.
 *  - [FolioCallout] — a pull-quote block: a rule on the leading edge, no box.
 */

/** Small letterspaced kicker. Labels a region; never sits inside a card. */
@Composable
fun FolioEyebrow(
    text: String,
    modifier: Modifier = Modifier,
    accent: Color? = null,
) {
    Text(
        text = text.uppercase(),
        style = FolioTheme.typography.labelSmall,
        letterSpacing = 1.4.sp,
        color = accent ?: FolioTheme.colors.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/**
 * A section heading in the display face, with an optional trailing affordance.
 * [eyebrow] rides above it when the section needs a category as well as a name.
 */
@Composable
fun FolioSectionHead(
    title: String,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    accent: Color? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            if (eyebrow != null) {
                FolioEyebrow(eyebrow, accent = accent ?: FolioTheme.colors.accentDiscovery)
                Spacer(Modifier.height(3.dp))
            }
            Text(
                text = title,
                style = FolioTheme.typography.headlineSmall,
                color = FolioTheme.colors.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(FolioTokens.space2))
            trailing()
        }
    }
}

/**
 * A number treated as a figure, not a label. The value carries the display face at
 * figure scale; the unit rides small on the baseline beside it, so "9" and "m" are
 * not the same size; the caption sits beneath.
 *
 * [emphasis] scales the whole figure: the one number a screen is about goes to
 * [FigureScale.Hero], supporting numbers stay [FigureScale.Standard], and the long
 * tail is [FigureScale.Quiet]. That spread *is* the hierarchy.
 */
enum class FigureScale { Hero, Standard, Quiet }

@Composable
fun FolioFigure(
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    label: String? = null,
    caption: String? = null,
    accent: Color? = null,
    emphasis: FigureScale = FigureScale.Standard,
) {
    val tint = accent ?: FolioTheme.colors.onSurface
    val valueStyle = when (emphasis) {
        FigureScale.Hero -> FolioTheme.typography.displayMedium
        FigureScale.Standard -> FolioTheme.typography.displaySmall
        FigureScale.Quiet -> FolioTheme.typography.headlineSmall
    }
    Column(modifier = modifier) {
        if (label != null) {
            FolioEyebrow(label, accent = FolioTheme.colors.onSurfaceVariant)
            Spacer(Modifier.height(if (emphasis == FigureScale.Hero) 6.dp else 3.dp))
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                style = valueStyle,
                color = tint,
                maxLines = 1,
            )
            if (unit != null) {
                Spacer(Modifier.width(3.dp))
                Text(
                    text = unit,
                    style = FolioTheme.typography.titleSmall,
                    color = tint.copy(alpha = 0.70f),
                    maxLines = 1,
                    modifier = Modifier.padding(
                        bottom = if (emphasis == FigureScale.Hero) 6.dp else 3.dp,
                    ),
                )
            }
        }
        if (caption != null) {
            Spacer(Modifier.height(5.dp))
            Text(
                text = caption,
                style = FolioTheme.typography.bodySmall,
                color = FolioTheme.colors.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Pull-quote block. A 2dp accent rule on the leading edge instead of a card:
 * quotes are the author's voice, and boxing them makes them read as UI.
 *
 * `height(IntrinsicSize.Min)` on the row is what lets the rule match the text it
 * annotates — `fillMaxHeight` alone inside a wrap-content parent measures to zero.
 */
@Composable
fun FolioCallout(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val rule = accent ?: FolioTheme.colors.accentAnnotation
    Row(modifier = modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(
            Modifier
                .width(2.dp)
                .fillMaxHeight()
                .background(rule.copy(alpha = 0.65f), FolioShapes.plateSmall),
        )
        Spacer(Modifier.width(FolioTokens.space2))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            content = content,
        )
    }
}
