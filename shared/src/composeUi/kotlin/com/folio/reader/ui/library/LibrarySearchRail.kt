package com.folio.reader.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.folio.reader.ui.theme.FolioShapes
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens
import com.folio.reader.ui.theme.atmosphere

/**
 * The rail's search field, drawn in the same visual language as the
 * Books/Manga/Documents switch: the same pill shape, hairline border and
 * sunken fill as [com.folio.reader.ui.components.FolioSegmented], so switch and
 * field read as siblings wherever they end up sitting. A Material
 * [androidx.compose.material3.OutlinedTextField] was tried here first, but its
 * enforced minimum height towered over the switch at the rail's default size
 * and collapsed to a stub when constrained; BasicTextField has no minimums to
 * fight.
 */
@Composable
internal fun FolioSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = FolioTheme.colors
    val atmos = FolioTheme.atmosphere
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        enabled = enabled,
        singleLine = true,
        textStyle = FolioTheme.typography.bodyMedium.copy(color = colors.onSurface),
        cursorBrush = SolidColor(colors.primary),
        modifier = modifier
            .clip(FolioShapes.pill)
            .background(atmos.sunkenFill.copy(alpha = 0.5f), FolioShapes.pill)
            .border(1.dp, atmos.hairline, FolioShapes.pill)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        decorationBox = { inner ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = null,
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(
                            placeholder,
                            style = FolioTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    inner()
                }
                if (query.isNotEmpty()) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Clear search",
                        tint = colors.onSurfaceVariant,
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .clickable { onQueryChange("") },
                    )
                }
            }
        },
    )
}

/**
 * The library rail while a search is active, shared verbatim by Books and
 * Documents (the manga shelf composes the same layout inside its own furniture
 * band). The mode switch never disappears while searching — that is the whole
 * point of the unified search — but where it sits depends on the measure:
 *
 * - Wide window (desktop): one line — switch, field, close.
 * - Narrow window (phones): the field gets the full row. A three-segment
 *   switch eats ~230dp of a ~380dp rail, and the field that survives beside it
 *   is a stub too small to read what you typed into it. The switch drops to the
 *   second row and leads the scope chips there — the same head-of-row position
 *   it holds when the chips are filter chips, so the rail still reads as one
 *   control line, just folded while you search.
 *
 * [scopeChips] must be a plain [Row]: in the narrow layout it rides the same
 * scrollable row as the switch.
 */
@Composable
internal fun LibrarySearchRail(
    switch: @Composable () -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    scopeChips: (@Composable () -> Unit)? = null,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        if (maxWidth >= 600.dp) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = FolioTokens.gutter, vertical = 6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    switch()
                    Spacer(Modifier.width(10.dp))
                    FolioSearchField(
                        query = query,
                        onQueryChange = onQueryChange,
                        placeholder = placeholder,
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                    )
                    SearchCloseIcon(onClose)
                }
                if (scopeChips != null) {
                    Spacer(Modifier.height(6.dp))
                    scopeChips()
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = FolioTokens.gutter),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FolioSearchField(
                        query = query,
                        onQueryChange = onQueryChange,
                        placeholder = placeholder,
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                    )
                    SearchCloseIcon(onClose)
                }
                Spacer(Modifier.height(6.dp))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = FolioTokens.gutter),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    item(key = "switch") { switch() }
                    if (scopeChips != null) {
                        item(key = "scopes") { scopeChips() }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchCloseIcon(onClose: () -> Unit) {
    Icon(
        Icons.AutoMirrored.Filled.ArrowBack,
        contentDescription = "Close search",
        tint = FolioTheme.colors.onSurfaceVariant,
        modifier = Modifier
            .padding(start = 6.dp)
            .size(36.dp)
            .clip(CircleShape)
            .clickable(onClick = onClose)
            .padding(8.dp),
    )
}
