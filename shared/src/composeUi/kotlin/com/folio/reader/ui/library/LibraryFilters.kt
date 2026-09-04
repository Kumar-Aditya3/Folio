package com.folio.reader.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.folio.reader.model.BookStatus
import com.folio.reader.model.Collection as FolioCollection
import com.folio.reader.model.Series
import com.folio.reader.ui.theme.FolioTokens

/**
 * Status / series / collection filter chips for the books shelf
 * (§6 split of LibraryScreen.kt). These are filters, not navigation,
 * so chips are the mandated control (§3.4 step 2).
 *
 * [leading] takes the Books/Manga switch, so the mode control and the filters share
 * one rail. They used to be two stacked rows above the shelf, which — with the bar
 * and the status band over them — put four bands of chrome between the top of the
 * screen and the first cover.
 */
@Composable
internal fun LibraryFilterChips(
    filter: LibraryViewModel.FilterState,
    allSeries: List<Series>,
    allCollections: List<FolioCollection>,
    seriesFilterOpen: Boolean,
    collectionFilterOpen: Boolean,
    onFilterChange: (LibraryViewModel.FilterState) -> Unit,
    onSeriesFilterOpen: (Boolean) -> Unit,
    onCollectionFilterOpen: (Boolean) -> Unit,
    leading: (@Composable () -> Unit)? = null
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = FolioTokens.gutter),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leading != null) {
            item { leading() }
        }
        item {
            com.folio.reader.ui.components.FolioChip(
                selected = filter.statuses.contains(BookStatus.READING),
                onClick = {
                    onFilterChange(
                        if (filter.statuses.contains(BookStatus.READING)) filter.copy(statuses = filter.statuses - BookStatus.READING)
                        else filter.copy(statuses = filter.statuses + BookStatus.READING)
                    )
                },
                label = "Reading"
            )
        }
        item {
            com.folio.reader.ui.components.FolioChip(
                selected = filter.statuses.contains(BookStatus.FINISHED),
                onClick = {
                    onFilterChange(
                        if (filter.statuses.contains(BookStatus.FINISHED)) filter.copy(statuses = filter.statuses - BookStatus.FINISHED)
                        else filter.copy(statuses = filter.statuses + BookStatus.FINISHED)
                    )
                },
                label = "Finished"
            )
        }
        item {
            com.folio.reader.ui.components.FolioChip(
                selected = filter.statuses.contains(BookStatus.UNREAD),
                onClick = {
                    onFilterChange(
                        if (filter.statuses.contains(BookStatus.UNREAD)) filter.copy(statuses = filter.statuses - BookStatus.UNREAD)
                        else filter.copy(statuses = filter.statuses + BookStatus.UNREAD)
                    )
                },
                label = "Unread"
            )
        }

        item {
            Box {
                com.folio.reader.ui.components.FolioChip(
                    selected = filter.seriesId != null,
                    onClick = { onSeriesFilterOpen(true) },
                    label = allSeries.firstOrNull { it.id == filter.seriesId }?.name ?: "Series"
                )
                DropdownMenu(
                    expanded = seriesFilterOpen,
                    onDismissRequest = { onSeriesFilterOpen(false) },
                    offset = DpOffset(0.dp, 36.dp)
                ) {
                    DropdownMenuItem(text = { Text("All series") }, onClick = {
                        onFilterChange(filter.copy(seriesId = null)); onSeriesFilterOpen(false)
                    })
                    allSeries.forEach { series ->
                        DropdownMenuItem(text = { Text(series.name) }, onClick = {
                            onFilterChange(filter.copy(seriesId = series.id)); onSeriesFilterOpen(false)
                        })
                    }
                    if (allSeries.isEmpty()) {
                        DropdownMenuItem(text = { Text("No series yet") }, onClick = { onSeriesFilterOpen(false) })
                    }
                }
            }
        }

        item {
            Box {
                com.folio.reader.ui.components.FolioChip(
                    selected = filter.collectionId != null,
                    onClick = { onCollectionFilterOpen(true) },
                    label = allCollections.firstOrNull { it.id == filter.collectionId }?.name ?: "Collections"
                )
                DropdownMenu(
                    expanded = collectionFilterOpen,
                    onDismissRequest = { onCollectionFilterOpen(false) },
                    offset = DpOffset(0.dp, 36.dp)
                ) {
                    DropdownMenuItem(text = { Text("All collections") }, onClick = {
                        onFilterChange(filter.copy(collectionId = null)); onCollectionFilterOpen(false)
                    })
                    allCollections.forEach { collection ->
                        DropdownMenuItem(text = { Text(collection.name) }, onClick = {
                            onFilterChange(filter.copy(collectionId = collection.id)); onCollectionFilterOpen(false)
                        })
                    }
                    if (allCollections.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("No collections yet") },
                            onClick = { onCollectionFilterOpen(false) })
                    }
                }
            }
        }

        if (filter.hasFilters()) {
            item {
                // Must be a chip, not a TextButton: the button is taller, and being
                // the only conditional item it changed the row's measured height as it
                // scrolled in and out of composition, nudging every other chip.
                com.folio.reader.ui.components.FolioChip(
                    selected = false,
                    onClick = { onFilterChange(LibraryViewModel.FilterState()) },
                    label = "Clear"
                )
            }
        }
    }
}
