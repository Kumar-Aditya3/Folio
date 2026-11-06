@file:OptIn(ExperimentalSharedTransitionApi::class)

package com.folio.reader.ui.manga

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.reader.manga.MangaHistoryEntry
import com.folio.reader.manga.MangaHistoryRepository
import com.folio.reader.ui.components.EmptyState
import com.folio.reader.ui.components.FolioSharedKeys
import com.folio.reader.ui.components.sharedElementOrNoop
import com.folio.reader.ui.components.sharedTextOrNoop
import com.folio.reader.ui.components.FolioTopBar
import com.folio.reader.ui.components.FolioHubGroupItem
import com.folio.reader.ui.components.FolioHubHeading
import com.folio.reader.ui.components.FolioRule
import com.folio.reader.ui.components.folioPressable
import com.folio.reader.ui.components.rememberFolioInteraction
import com.folio.reader.ui.components.folioBackdropSource
import com.folio.reader.ui.components.rememberFolioHeaderState
import com.folio.reader.ui.components.folioPageWash
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens
import com.folio.reader.ui.theme.FolioSection
import com.folio.reader.ui.theme.elements
import com.folio.reader.ui.theme.LocalFolioBarInset
import com.folio.reader.ui.theme.folioBarTopInset
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun MangaHistoryScreen(
    backend: com.folio.reader.manga.MangaBackend,
    historyRepo: MangaHistoryRepository,
    onOpenManga: (MangaHistoryEntry) -> Unit,
    onBack: () -> Unit,
) {
    val entries by remember(historyRepo) { historyRepo.observeHistory() }.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    MangaHistoryContent(backend, entries, onOpenManga, onBack,
        onClearHistory = { scope.launch { historyRepo.clearHistory() } })
}

@Composable
internal fun MangaHistoryContent(
    backend: com.folio.reader.manga.MangaBackend,
    entries: List<MangaHistoryEntry>,
    onOpenManga: (MangaHistoryEntry) -> Unit,
    onBack: () -> Unit,
    onClearHistory: () -> Unit,
) {
    var confirmClear by remember { mutableStateOf(false) }
    val headerState = rememberFolioHeaderState()
    val topInset = folioBarTopInset()
    val groups = remember(entries) { mangaHistoryGroups(entries) }
    val accent = FolioTheme.colors.accentStreak

    FolioSection(accent) {
        Box(Modifier.fillMaxSize()) {
            if (entries.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().folioBackdropSource().folioPageWash()
                        .padding(top = topInset, bottom = LocalFolioBarInset.current),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyState(
                        icon = Icons.Outlined.History,
                        headline = "No reading history",
                        body = "Chapters you mark as read will appear here.",
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .nestedScroll(headerState.nestedScrollConnection)
                        .folioBackdropSource().folioPageWash(),
                    contentPadding = PaddingValues(
                        start = FolioTokens.gutter,
                        top = topInset + FolioTokens.space2,
                        end = FolioTokens.gutter,
                        bottom = FolioTokens.spaceMovement + LocalFolioBarInset.current,
                    ),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    groups.forEachIndexed { groupIndex, group ->
                        item(key = "date-${group.key}") {
                            Column(Modifier.padding(top = if (groupIndex == 0) 0.dp else FolioTokens.spaceMovement,
                                bottom = FolioTokens.space2)) {
                                FolioHubHeading(group.label, FolioTheme.elements.mutedInk)
                            }
                        }
                        itemsIndexed(group.entries, key = { _, entry -> entry.mangaId }) { index, entry ->
                            FolioHubGroupItem(first = index == 0, last = index == group.entries.lastIndex) {
                                HistoryRow(backend, entry, onClick = { onOpenManga(entry) },
                                    last = index == group.entries.lastIndex)
                            }
                        }
                    }
                }
            }

            FolioTopBar(
                title = "History",
                collapse = headerState.collapse,
                modifier = Modifier.align(Alignment.TopCenter),
                bottomRule = false,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (entries.isNotEmpty()) {
                        IconButton(onClick = { confirmClear = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Clear history")
                        }
                    }
                },
            )
        }

        if (confirmClear) {
            // §16 P5: the app's own in-window pane rather than Material's `AlertDialog` (see FolioDialog).
            com.folio.reader.ui.components.FolioDialog(
                expanded = true,
                onDismissRequest = { confirmClear = false },
                title = "Clear history",
            ) {
                Text("Remove all reading history? This cannot be undone.")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = {
                        confirmClear = false
                        onClearHistory()
                    }) { Text("Clear") }
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(
    backend: com.folio.reader.manga.MangaBackend,
    entry: MangaHistoryEntry,
    onClick: () -> Unit,
    last: Boolean,
) {
    val interaction = rememberFolioInteraction()
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .folioPressable(interaction, scaleTo = .99f)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                .padding(vertical = FolioTokens.space2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MangaCover(
                backend = backend,
                sourceId = entry.sourceId,
                thumbnailUrl = entry.coverUrl,
                coverPath = entry.coverPath,
                modifier = Modifier
                    .width(48.dp).height(68.dp)
                    // §17: history taps into the manga detail, which publishes the same
                    // cover key, so a row's cover flies there like a grid tile's does.
                    // The key is read from `mangaId`, not from anything display-shaped:
                    // history rows are deduped per manga and two rows can share a title.
                    .sharedElementOrNoop(FolioSharedKeys.mangaCover(entry.mangaId))
                    .clip(RoundedCornerShape(8.dp)),
            )
            Spacer(Modifier.width(FolioTokens.space3))
            Column(Modifier.weight(1f)) {
                Text(
                    text = entry.title.ifBlank { entry.mangaId },
                    style = FolioTheme.typography.titleSmall,
                    color = FolioTheme.colors.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.sharedTextOrNoop(FolioSharedKeys.mangaTitle(entry.mangaId)),
                )
                if (entry.chapterName.isNotBlank()) {
                    Text(
                        text = entry.chapterName,
                        style = FolioTheme.typography.bodySmall,
                        color = FolioTheme.colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = formatRelativeTime(entry.updatedAt),
                    style = FolioTheme.typography.labelSmall,
                    color = FolioTheme.colors.onSurfaceVariant,
                )
            }
        }
        if (!last) FolioRule(modifier = Modifier.padding(start = 48.dp + FolioTokens.space3))
    }
}

internal data class MangaHistoryGroup(val key: String, val label: String, val entries: List<MangaHistoryEntry>)

internal fun mangaHistoryGroups(entries: List<MangaHistoryEntry>, now: Long = System.currentTimeMillis()): List<MangaHistoryGroup> {
    val dateKey = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
    val dateLabel = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
    val today = dateKey.format(Date(now))
    val yesterday = Calendar.getInstance().apply { timeInMillis = now; add(Calendar.DAY_OF_YEAR, -1) }
        .let { dateKey.format(it.time) }
    return entries.sortedByDescending { it.updatedAt }.groupBy { dateKey.format(Date(it.updatedAt)) }
        .map { (key, rows) ->
            MangaHistoryGroup(key, when (key) {
                today -> "Today"
                yesterday -> "Yesterday"
                else -> dateLabel.format(Date(rows.first().updatedAt))
            }, rows)
        }
}

private fun formatRelativeTime(epochMs: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - epochMs
    if (diff < 0) return "Just now"
    val seconds = diff / 1000
    if (seconds < 60) return "Just now"
    val minutes = seconds / 60
    if (minutes < 60) return "${minutes} m ago"
    val hours = minutes / 60
    if (hours < 24) return "${hours} h ago"
    val days = hours / 24
    if (days == 1L) return "Yesterday"
    if (days < 7) return "${days} d ago"
    val sdf = java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(epochMs))
}
