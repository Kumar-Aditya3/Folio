package com.folio.reader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.reader.ui.components.rememberLegibleAccent
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens

/** A label with equal-width tappable options; the current one is accent-filled. */
@Composable
internal fun QuickChoiceRow(
    label: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    overrideDot: Boolean = false
) {
    // The selected pill is filled with `primary`, so its label must be `onPrimary`
    // and the fill itself must clear the panel it sits on. Guarding here keeps
    // every palette's own hue while making the selection unambiguous.
    val selectedFill = rememberLegibleAccent(
        FolioTheme.colors.primary,
        fallback = FolioTheme.colors.onSurface,
        minRatio = 3.0,
    )
    // The label is guarded against the fill that actually renders, so a palette
    // whose primary had to move does not end up with an unreadable onPrimary.
    val selectedLabel = rememberLegibleAccent(
        FolioTheme.colors.onPrimary,
        background = selectedFill,
        fallback = FolioTheme.colors.onPrimary,
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FolioTokens.space1)
        ) {
            Text(label, style = FolioTheme.typography.labelLarge, color = FolioTheme.colors.onSurfaceVariant)
            OverrideDot(overrideDot)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (value, title) ->
                val isSel = value == selected
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(9.dp))
                        .background(
                            if (isSel) selectedFill else FolioTheme.colors.surfaceVariant
                        )
                        .clickable { onSelect(value) }
                        .padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = title,
                        style = FolioTheme.typography.labelMedium,
                        color = if (isSel) selectedLabel else FolioTheme.colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

/**
 * A miniature "page" rendered in the theme's own colors — heading, body lines
 * and an accent bar — so each entry in the theme slider shows how reading will
 * actually look before it is applied.
 */
@Composable
internal fun ThemePreviewCard(theme: com.folio.reader.settings.Theme, selected: Boolean, modifier: Modifier = Modifier) {
    // The preview renders the reading theme's own colours, so its selection ring
    // has to clear *that* background rather than the app surface.
    val ring = rememberLegibleAccent(
        FolioTheme.colors.primary,
        background = Color(theme.background),
        fallback = FolioTheme.colors.onSurface,
        minRatio = 3.0,
    )
    Row(
        modifier = modifier
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(theme.background))
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) ring else FolioTheme.colors.outline,
                shape = RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 12.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Box(
                Modifier
                    .fillMaxWidth(0.55f)
                    .height(7.dp)
                    .background(Color(theme.headingText), RoundedCornerShape(4.dp))
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(Color(theme.secondaryText).copy(alpha = 0.75f), RoundedCornerShape(3.dp))
            )
            Box(
                Modifier
                    .fillMaxWidth(0.88f)
                    .height(4.dp)
                    .background(Color(theme.secondaryText).copy(alpha = 0.75f), RoundedCornerShape(3.dp))
            )
            Box(
                Modifier
                    .fillMaxWidth(0.66f)
                    .height(4.dp)
                    .background(Color(theme.secondaryText).copy(alpha = 0.55f), RoundedCornerShape(3.dp))
            )
        }
        // Accent chip showing the theme's progress color
        Box(
            Modifier
                .size(width = 6.dp, height = 34.dp)
                .background(Color(theme.progress), RoundedCornerShape(3.dp))
        )
    }
}

/** Trailing dot marking a setting this book overrides (§4.2). */
@Composable
internal fun OverrideDot(visible: Boolean) {
    if (!visible) return
    Box(
        modifier = Modifier
            .size(FolioTokens.dotIndicator)
            .clip(CircleShape)
            .background(FolioTheme.colors.primary)
            .semantics { contentDescription = "Overridden for this book" }
    )
}
