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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
 *
 * The scope selector rides *inside* the field as a dropdown on the leading
 * search icon: books search hides its Titles/Content/Highlights/Notes scopes
 * and manga its Library/All-sources toggle in a chip row below the field that
 * phones fold out of sight, so the one control that decides what the query
 * touches now sits where the thumb already is. The chevron beside the icon is
 * the only extra ink it costs.
 */
@Composable
internal fun FolioSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    scopeOptions: List<String> = emptyList(),
    scopeSelected: Int = 0,
    onScopeSelect: ((Int) -> Unit)? = null,
) {
    val colors = FolioTheme.colors
    val atmos = FolioTheme.atmosphere
    var scopeMenuOpen by remember { mutableStateOf(false) }
    val hasScopes = scopeOptions.isNotEmpty() && onScopeSelect != null
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
                if (hasScopes) {
                    Box {
                        Row(
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable { scopeMenuOpen = true }
                                // Reserve a >=48dp thumb target around the ~18dp
                                // icon+chevron, which was far below a reliable tap.
                                .minimumInteractiveComponentSize()
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.Search,
                                contentDescription = "Search scope",
                                tint = colors.primary,
                                modifier = Modifier.size(18.dp),
                            )
                            Icon(
                                Icons.Filled.KeyboardArrowDown,
                                contentDescription = null,
                                tint = colors.onSurfaceVariant,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                        DropdownMenu(
                            expanded = scopeMenuOpen,
                            onDismissRequest = { scopeMenuOpen = false },
                        ) {
                            scopeOptions.forEachIndexed { index, label ->
                                val selected = index == scopeSelected
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            label,
                                            color = if (selected) colors.primary else colors.onSurface,
                                        )
                                    },
                                    leadingIcon = {
                                        if (selected) {
                                            Icon(
                                                Icons.Filled.Check,
                                                contentDescription = null,
                                                tint = colors.primary,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        } else {
                                            Spacer(Modifier.size(18.dp))
                                        }
                                    },
                                    onClick = {
                                        scopeMenuOpen = false
                                        onScopeSelect?.invoke(index)
                                    },
                                )
                            }
                        }
                    }
                } else {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = null,
                        tint = colors.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
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
                    // >=48dp thumb target around the 20dp glyph; a bare 20dp
                    // clickable was well under a reliable tap.
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable { onQueryChange("") }
                            .minimumInteractiveComponentSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Clear search",
                            tint = colors.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
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
 *   second row — the same head-of-row position it holds when the chips are
 *   filter chips, so the rail still reads as one control line, just folded
 *   while you search.
 *
 * Search scopes are not chips on either measure: they live in the dropdown on
 * the field's own leading icon (see [FolioSearchField]), where a second row
 * cannot hide them.
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
    scopeOptions: List<String> = emptyList(),
    scopeSelected: Int = 0,
    onScopeSelect: ((Int) -> Unit)? = null,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        if (maxWidth >= 600.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = FolioTokens.gutter, vertical = 6.dp),
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
                    scopeOptions = scopeOptions,
                    scopeSelected = scopeSelected,
                    onScopeSelect = onScopeSelect,
                )
                SearchCloseIcon(onClose)
            }
        } else {
            // Narrow: field on its own row, switch below it.
            //
            // Both rows are padded by `folioGutter` rather than one by `gutter` and the next
            // by whatever the close icon happened to leave. The field row used to be
            // `padding(horizontal = gutter)` *and* carry a 36dp close icon inside it, so the
            // icon's right edge landed past the margin — measured on the device at 42px from
            // the screen edge against the grid's 60px — and the rail read as wider than the
            // shelf it sits over. The fix is to make the row's own padding authoritative and
            // let the icon live inside it.
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
                        scopeOptions = scopeOptions,
                        scopeSelected = scopeSelected,
                        onScopeSelect = onScopeSelect,
                    )
                    SearchCloseIcon(onClose)
                }
                LazyRow(
                    contentPadding = PaddingValues(horizontal = FolioTokens.gutter),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    item(key = "switch") { switch() }
                }
            }
        }
    }
}

/**
 * The rail's close/leave-search affordance, drawn so its **ink** lands on the row's trailing
 * margin rather than past it.
 *
 * The glyph is 20dp; the touch target is 36dp because a 20dp tap area is below what a thumb
 * hits reliably. That extra 16dp must not sit between the glyph and the screen edge, though —
 * centring it, which is what a bare `size(36.dp)` does, left 8dp of invisible slack there, so
 * the glyph floated inside the shelf's margin and the rail read as misaligned with the grid
 * under it. Measured on the device: the glyph ended 42px from the screen edge against the
 * grid's 60px.
 *
 * A `Box` rather than a `padding` chain, because the two paddings are asymmetric and the
 * modifier order that gives *outer* spacing is the opposite of the one that shrinks the glyph:
 * `size(36).padding(...)` would inset the icon's own drawing area and render a smaller glyph,
 * which is not what is wanted here. The box is the touch target; the icon is 20dp inside it,
 * pushed to the trailing edge by the 16dp leading inset.
 */
@Composable
private fun SearchCloseIcon(onClose: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(onClick = onClose),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Close search",
            tint = FolioTheme.colors.onSurfaceVariant,
            modifier = Modifier
                .padding(start = 16.dp)
                .size(20.dp),
        )
    }
}
