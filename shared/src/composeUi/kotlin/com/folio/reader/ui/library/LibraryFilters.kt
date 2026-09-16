package com.folio.reader.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.folio.reader.model.BookStatus
import com.folio.reader.model.Collection as FolioCollection
import com.folio.reader.model.Series
import com.folio.reader.ui.components.FolioChip
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens

/**
 * The books shelf's rail: the collection shelves lead it in the manga and
 * documents libraries' style — the mode switch, then Main and the other
 * collection chips, then the Edit chip — while every other filter (status,
 * series, clear) folds into one dropdown pinned at the rail's right-most end.
 *
 * The shelf chips scroll between the two anchors, so the switch stays at the
 * head of the row and the filter dropdown stays reachable no matter how many
 * collections exist.
 */
@Composable
internal fun LibraryFilterChips(
    filter: LibraryViewModel.FilterState,
    allSeries: List<Series>,
    onFilterChange: (LibraryViewModel.FilterState) -> Unit,
    collections: List<FolioCollection>,
    selectedCollectionId: String?,
    onSelectCollection: (String) -> Unit,
    onEditCollections: () -> Unit,
    leading: (@Composable () -> Unit)? = null
) {
    // One rail, one scroller. The Filters dropdown used to be pinned as an
    // unweighted child to the right of the chip row; pinned, it permanently
    // occupied the rail's right-hand end and clipped the collections scrolling
    // underneath it. It is now the rail's last chip, so the switch, the
    // collections, Edit and Filters all share one uninterrupted scroll and
    // nothing overlaps the categories.
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = FolioTokens.gutter),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            item(key = "mode-switch") { leading() }
        }
        items(collections, key = { it.id }) { collection ->
            FolioChip(
                selected = selectedCollectionId == collection.id,
                onClick = { onSelectCollection(collection.id) },
                label = collection.name
            )
        }
        item(key = "edit-collections") {
            FolioChip(
                selected = false,
                onClick = onEditCollections,
                label = "Edit"
            )
        }
        item(key = "filters") {
            FilterDropdown(
                filter = filter,
                allSeries = allSeries,
                onFilterChange = onFilterChange,
            )
        }
    }
}

/**
 * The rest of the books filters — Reading/Finished/Unread statuses and the
 * series picker — in one dropdown at the rail's right-most end. Status items
 * toggle in place (several can combine); picking a series closes the menu.
 */
@Composable
private fun FilterDropdown(
    filter: LibraryViewModel.FilterState,
    allSeries: List<Series>,
    onFilterChange: (LibraryViewModel.FilterState) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val statusOptions = listOf(
        "Reading" to BookStatus.READING,
        "Finished" to BookStatus.FINISHED,
        "Unread" to BookStatus.UNREAD,
    )
    val activeCount = filter.statuses.size + (if (filter.seriesId != null) 1 else 0)

    Box {
        FolioChip(
            selected = filter.hasFilters(),
            onClick = { open = true },
            label = if (activeCount > 0) "Filters · $activeCount" else "Filters",
        )
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.heightIn(max = 420.dp),
        ) {
            com.folio.reader.ui.components.FolioMenuLabel("Status")
            statusOptions.forEach { (label, status) ->
                val active = status in filter.statuses
                DropdownMenuItem(
                    text = { Text(label, color = if (active) FolioTheme.colors.primary else FolioTheme.colors.onSurface) },
                    leadingIcon = { MenuCheck(active) },
                    // Stays open: statuses combine.
                    onClick = {
                        onFilterChange(
                            if (active) filter.copy(statuses = filter.statuses - status)
                            else filter.copy(statuses = filter.statuses + status)
                        )
                    },
                )
            }
            HorizontalDivider()
            com.folio.reader.ui.components.FolioMenuLabel("Series")
            DropdownMenuItem(
                text = {
                    Text(
                        "All series",
                        color = if (filter.seriesId == null) FolioTheme.colors.primary else FolioTheme.colors.onSurface
                    )
                },
                leadingIcon = { MenuCheck(filter.seriesId == null) },
                onClick = {
                    onFilterChange(filter.copy(seriesId = null))
                    open = false
                },
            )
            allSeries.forEach { series ->
                val active = filter.seriesId == series.id
                DropdownMenuItem(
                    text = { Text(series.name, color = if (active) FolioTheme.colors.primary else FolioTheme.colors.onSurface) },
                    leadingIcon = { MenuCheck(active) },
                    onClick = {
                        onFilterChange(filter.copy(seriesId = series.id))
                        open = false
                    },
                )
            }
            if (allSeries.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("No series yet") },
                    onClick = { open = false },
                )
            }
            if (filter.hasFilters()) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Clear filters") },
                    onClick = {
                        onFilterChange(LibraryViewModel.FilterState())
                        open = false
                    },
                )
            }
        }
    }
}

@Composable
private fun MenuCheck(active: Boolean) {
    if (active) {
        Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = null,
            tint = FolioTheme.colors.primary,
            modifier = Modifier.size(18.dp),
        )
    } else {
        Spacer(modifier = Modifier.size(18.dp))
    }
}
