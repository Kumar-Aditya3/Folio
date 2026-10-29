package com.folio.reader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.reader.ui.theme.*

/** Shared by More, manga sources and the extension catalog. */
@Composable
fun FolioHubHeading(title: String, accent: Color, modifier: Modifier = Modifier) {
    FolioEyebrow(title, modifier.fillMaxWidth().folioTypeGround(), accent = accent,
        background = folioFieldBottom())
}

@Composable
fun FolioHubSection(title: String, accent: Color, content: @Composable () -> Unit) {
    Column(Modifier.padding(bottom = FolioTokens.spaceMovement)) {
        FolioHubHeading(title, accent)
        Spacer(Modifier.height(FolioTokens.space2))
        FolioGlassPanel(contentPadding = PaddingValues(horizontal = FolioTokens.space3, vertical = FolioTokens.space1),
            verticalArrangement = Arrangement.spacedBy(0.dp)) { content() }
    }
}

@Composable
internal fun FolioHubSkeleton(title: String, rows: Int = 6) {
    val phase = rememberShimmerPhase()
    FolioHubSection(title, FolioTheme.colors.tertiary) {
        repeat(rows) { index ->
            Row(Modifier.fillMaxWidth().padding(vertical = FolioTokens.space2), verticalAlignment = Alignment.CenterVertically) {
                FolioSkeletonBlock(Modifier.size(38.dp), shape = FolioShapes.inset, phase = phase)
                Spacer(Modifier.width(FolioTokens.space3))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FolioSkeletonLine(.58f, phase = phase)
                    FolioSkeletonLine(.82f, height = 8.dp, phase = phase)
                }
            }
            if (index != rows - 1) FolioRule()
        }
    }
}

/** A contiguous panel slice keeps large catalogs lazy without turning every row into a card. */
@Composable
internal fun FolioHubGroupItem(first: Boolean, last: Boolean, content: @Composable () -> Unit) {
    val shape = FolioSoftShape(topStart = if (first) 22.dp else 0.dp, topEnd = if (first) 22.dp else 0.dp,
        bottomStart = if (last) 22.dp else 0.dp, bottomEnd = if (last) 22.dp else 0.dp)
    val fill = folioDataGlassPlane()
    Column(Modifier.fillMaxWidth().folioDataGlass(shape, drawRim = false)
        .padding(start = FolioTokens.space3, end = FolioTokens.space3,
            top = if (first) FolioTokens.space1 else 0.dp, bottom = if (last) FolioTokens.space1 else 0.dp)) {
        FolioSurfaceContents(fill) { content() }
    }
}

@Composable
fun FolioHubRow(icon: ImageVector, title: String, subtitle: String, accent: Color,
    last: Boolean = false, onClick: (() -> Unit)? = null, trailingInline: Boolean = false,
    trailing: (@Composable () -> Unit)? = null) {
    val colors = FolioTheme.colors
    val interaction = rememberFolioInteraction()
    Column {
        BoxWithConstraints(Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.folioPressable(interaction, scaleTo = .99f)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick) else Modifier)
            .padding(vertical = FolioTokens.space2)) {
            val stacked = trailing != null && !trailingInline && (maxWidth < 260.dp || LocalDensity.current.fontScale > 1.2f)
            Column(verticalArrangement = Arrangement.spacedBy(if (stacked) 4.dp else 0.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(38.dp).clip(FolioShapes.inset).background(accent.copy(alpha = .09f))
                        .border(.7.dp, accent.copy(alpha = .16f), FolioShapes.inset), contentAlignment = Alignment.Center) {
                        Icon(icon, null, Modifier.size(19.dp), accent)
                    }
                    Spacer(Modifier.width(FolioTokens.space3))
                    Column(Modifier.weight(1f)) {
                        Text(title, style = FolioTheme.typography.titleSmall, color = colors.onSurface,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(subtitle, style = FolioTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (!stacked) {
                        Spacer(Modifier.width(FolioTokens.space2))
                        if (trailing != null) trailing()
                        else if (onClick != null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                            Modifier.size(18.dp), colors.onSurfaceVariant.copy(alpha = .6f))
                    }
                }
                if (stacked) Row(Modifier.fillMaxWidth().padding(start = 54.dp),
                    horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) { trailing?.invoke() }
            }
        }
        if (!last) FolioRule()
    }
}
