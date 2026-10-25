package com.folio.reader.ui.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.reader.database.SearchResult
import com.folio.reader.ui.components.folioVeil
import com.folio.reader.ui.components.rememberFolioSheetMorphShape
import com.folio.reader.ui.components.rememberLegibleAccent
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens
import com.folio.reader.ui.theme.readerPanelVeilAlpha

/**
 * The in-reader search panel — the same end-edge sheet Contents, Annotations and Echoes use.
 *
 * It replaced a push to the full-screen search destination, and the difference is the point: that
 * screen searches the whole *library* (right for the Library tab, wrong for a reader mid-chapter),
 * and opening it also tore the reader down and rebuilt it on the way back. This searches the book
 * in hand, keeps the page behind it, and its hits arrive **nearest first** — see
 * `ReaderViewModel.updateSearchQuery`.
 */
@Composable
internal fun ReaderSearchPanel(
    query: String,
    onQueryChange: (String) -> Unit,
    results: List<SearchResult>,
    searching: Boolean,
    currentSpineIndex: Int,
    onResultClick: (SearchResult) -> Unit,
    onDismiss: () -> Unit,
) {
    // The same sweep as the other side panels: it enters reading as a capsule and settles to the
    // sheet's corner, so the panel reads as sliding in from the edge rather than being pasted on.
    val shape = rememberFolioSheetMorphShape(
        RoundedCornerShape(
            topStart = FolioTokens.radiusSheetSweep,
            bottomStart = FolioTokens.radiusSheetSweep
        )
    )
    // Monochromatic palettes put `primary` within a hair of `surface`; the "you are here" mark
    // has to pass the contrast guard rather than trust the raw accent.
    val accent = rememberLegibleAccent(FolioTheme.colors.primary)
    // The magnifier was just tapped, so the field is what the reader wants to type into. Guarded
    // because requesting focus before the node is attached throws.
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    Column(
        modifier = Modifier
            .readerPanelBody()
    ) {
        if (!LocalReaderWorkspace.current) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Search",
                style = FolioTheme.typography.titleMedium,
                color = FolioTheme.colors.onSurface
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Close",
                    tint = FolioTheme.colors.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        HorizontalDivider(color = FolioTheme.colors.outlineVariant)
        }

        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .focusRequester(focusRequester),
            singleLine = true,
            textStyle = FolioTheme.typography.bodyMedium,
            placeholder = {
                Text(
                    "Search this book",
                    style = FolioTheme.typography.bodySmall,
                    color = FolioTheme.colors.onSurfaceVariant
                )
            },
            leadingIcon = {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = null,
                    tint = FolioTheme.colors.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        )

        if (searching) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp))
        }

        when {
            query.isBlank() -> SearchHint("Type to search this book.")
            results.isEmpty() && !searching -> SearchHint("No matches in this book.")
            else -> LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(bottom = readerImeBottomPadding())) {
                items(results, key = { "${it.chapterId}:${it.spineIndex}:${it.context.hashCode()}" }) { hit ->
                    val here = hit.spineIndex == currentSpineIndex
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onResultClick(hit) }
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = hit.title.ifBlank { "Chapter ${hit.spineIndex + 1}" },
                                style = FolioTheme.typography.labelMedium,
                                fontWeight = if (here) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (here) accent else FolioTheme.colors.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            // The one thing the ordering cannot say by itself: whether the hit is
                            // the chapter the reader is looking at.
                            if (here) {
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "here",
                                    style = FolioTheme.typography.labelSmall,
                                    color = accent
                                )
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = hit.context.trim(),
                            style = FolioTheme.typography.bodySmall,
                            color = FolioTheme.colors.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    HorizontalDivider(color = FolioTheme.colors.outlineVariant)
                }
            }
        }
    }
}

/** The panel's two resting states: nothing typed, and nothing found. */
@Composable
private fun SearchHint(text: String) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 28.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Text(
            text = text,
            style = FolioTheme.typography.bodySmall,
            color = FolioTheme.colors.onSurfaceVariant
        )
    }
}
