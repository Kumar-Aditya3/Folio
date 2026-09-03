package com.folio.reader.ui.manga

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.reader.manga.MangaChapter
import com.folio.reader.ui.components.FolioTopBar
import com.folio.reader.ui.components.glassPanel
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
internal fun ChapterRow(
    chapter: MangaChapter,
    download: com.folio.reader.manga.MangaDownload?,
    downloadsAvailable: Boolean,
    selected: Boolean,
    inSelectionMode: Boolean,
    onRead: () -> Unit,
    onLongClick: () -> Unit,
    onToggleRead: () -> Unit,
    onMarkPrevious: () -> Unit,
    onToggleBookmark: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onDeleteDownload: () -> Unit,
) {
    val colors = FolioTheme.colors
    var menuOpen by remember { mutableStateOf(false) }
    val status = download?.status
    val dlTotal = download?.totalPages ?: 0
    val dlDone = download?.downloadedPages ?: 0
    val isDownloaded = chapter.downloadedPages > 0 || status == com.folio.reader.manga.MangaDownloadStatus.DOWNLOADED
    val inFlight = status == com.folio.reader.manga.MangaDownloadStatus.QUEUED ||
        status == com.folio.reader.manga.MangaDownloadStatus.DOWNLOADING ||
        status == com.folio.reader.manga.MangaDownloadStatus.ERROR
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onRead, onLongClick = onLongClick)
            .background(
                if (selected) FolioTheme.colors.primaryContainer else FolioTheme.colors.surface,
                RoundedCornerShape(FolioTokens.radiusControl),
            )
            .padding(horizontal = FolioTokens.space3, vertical = FolioTokens.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                chapter.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (chapter.read) FontWeight.Normal else FontWeight.SemiBold,
                color = if (chapter.read) colors.onSurfaceVariant else colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = listOfNotNull(
                chapter.scanlator?.takeIf { it.isNotBlank() },
                chapter.chapterNumber.takeIf { it > 0 }?.let { "Ch. ${formatChapterNumber(it)}" },
                if (!chapter.read && chapter.lastPageRead > 0)
                    if (chapter.totalPages > 0) "p.${chapter.lastPageRead + 1}/${chapter.totalPages}" else "p.${chapter.lastPageRead + 1}"
                else null,
                when (status) {
                    com.folio.reader.manga.MangaDownloadStatus.QUEUED -> "Queued"
                    com.folio.reader.manga.MangaDownloadStatus.DOWNLOADING ->
                        if (dlTotal > 0) "Downloading $dlDone/$dlTotal" else "Downloading"
                    com.folio.reader.manga.MangaDownloadStatus.ERROR ->
                        download?.error?.takeIf { it.isNotBlank() }?.let { "Download failed · $it" } ?: "Download failed"
                    else -> if (isDownloaded && chapter.downloadedPages > 0) "Downloaded" else null
                },
            ).joinToString(" • ")
            if (sub.isNotBlank()) {
                Text(sub, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            if (status == com.folio.reader.manga.MangaDownloadStatus.DOWNLOADING && dlTotal > 0) {
                LinearProgressIndicator(
                    progress = (dlDone.toFloat() / dlTotal).coerceIn(0f, 1f),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(3.dp),
                )
            }
        }
        when {
            isDownloaded -> {
                Icon(
                    Icons.Filled.DownloadDone,
                    contentDescription = "Downloaded",
                    tint = colors.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
            }

            status == com.folio.reader.manga.MangaDownloadStatus.QUEUED -> {
                Icon(
                    Icons.Outlined.HourglassEmpty,
                    contentDescription = "Queued for download",
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
            }

            else -> Unit
        }
        IconButton(onClick = onToggleBookmark) {
            Icon(
                imageVector = if (chapter.bookmarked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                contentDescription = "Bookmark",
                tint = if (chapter.bookmarked) colors.primary else colors.onSurfaceVariant,
            )
        }
        if (downloadsAvailable) {
            if (inFlight) {
                IconButton(onClick = onCancelDownload) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Cancel download",
                        tint = if (status == com.folio.reader.manga.MangaDownloadStatus.ERROR) colors.error else colors.onSurfaceVariant,
                    )
                }
            } else if (!isDownloaded) {
                IconButton(onClick = onDownload) {
                    Icon(Icons.Filled.Download, contentDescription = "Download", tint = colors.onSurfaceVariant)
                }
            }
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "More", tint = colors.onSurfaceVariant)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(if (chapter.read) "Mark as unread" else "Mark as read") },
                    onClick = {
                        menuOpen = false
                        onToggleRead()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Mark previous as read") },
                    onClick = {
                        menuOpen = false
                        onMarkPrevious()
                    },
                )
                if (inFlight && downloadsAvailable) {
                    DropdownMenuItem(
                        text = { Text("Cancel download") },
                        onClick = {
                            menuOpen = false
                            onCancelDownload()
                        },
                    )
                }
                if (isDownloaded && downloadsAvailable) {
                    DropdownMenuItem(
                        text = { Text("Delete download") },
                        onClick = {
                            menuOpen = false
                            onDeleteDownload()
                        },
                    )
                }
            }
        }
    }
}

private fun formatChapterNumber(number: Float): String =
    if (number == number.toLong().toFloat()) number.toLong().toString() else number.toString()

@Composable
fun DownloadsScreen(
    viewModel: DownloadsViewModel,
    onBack: () -> Unit,
    onPickLocation: () -> Unit = {},
) {
    val queue by viewModel.queue.collectAsState()
    val titles by viewModel.mangaTitles.collectAsState()
    val chapterNames by viewModel.chapterNames.collectAsState()
    val storageDescription by viewModel.storageDescription.collectAsState()

    Column(Modifier.fillMaxSize()) {
        FolioTopBar(
            title = "Downloads",
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            },
            actions = {
                // A sweep icon reads as "clean up"; the previous ✕ looked like "close screen".
                IconButton(onClick = { viewModel.clearFinished() }) {
                    Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear finished")
                }
            },
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = FolioTokens.space3, vertical = FolioTokens.space2)
                .glassPanel(RoundedCornerShape(FolioTokens.radiusControl))
                .padding(horizontal = FolioTokens.space3, vertical = FolioTokens.space2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Folder,
                contentDescription = null,
                tint = FolioTheme.colors.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(FolioTokens.space3))
            Column(Modifier.weight(1f)) {
                Text(
                    "Download location",
                    style = MaterialTheme.typography.labelSmall,
                    color = FolioTheme.colors.onSurfaceVariant,
                )
                Text(
                    storageDescription,
                    style = MaterialTheme.typography.bodySmall,
                    color = FolioTheme.colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = onPickLocation) {
                Text("Change", style = MaterialTheme.typography.labelMedium)
            }
        }
        if (queue.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    "No downloads in the queue.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = FolioTheme.colors.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(FolioTokens.space3),
                verticalArrangement = Arrangement.spacedBy(FolioTokens.space2),
            ) {
                items(queue, key = { it.id }) { download ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .glassPanel(RoundedCornerShape(FolioTokens.radiusControl))
                            .padding(FolioTokens.space3),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                titles[download.mangaId] ?: download.mangaId,
                                style = MaterialTheme.typography.titleSmall,
                                color = FolioTheme.colors.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            chapterNames[download.chapterId]?.takeIf { it.isNotBlank() }?.let { name ->
                                Text(
                                    name,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = FolioTheme.colors.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                when (download.status) {
                                    com.folio.reader.manga.MangaDownloadStatus.QUEUED -> "Queued"
                                    com.folio.reader.manga.MangaDownloadStatus.DOWNLOADING ->
                                        "Downloading ${download.downloadedPages}/${download.totalPages}"
                                    com.folio.reader.manga.MangaDownloadStatus.DOWNLOADED -> "Downloaded"
                                    com.folio.reader.manga.MangaDownloadStatus.ERROR ->
                                        download.error?.takeIf { it.isNotBlank() }?.let { "Failed · $it" } ?: "Failed"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (download.status == com.folio.reader.manga.MangaDownloadStatus.ERROR) {
                                    FolioTheme.colors.error
                                } else {
                                    FolioTheme.colors.onSurfaceVariant
                                },
                            )
                            if (download.status == com.folio.reader.manga.MangaDownloadStatus.DOWNLOADING &&
                                download.totalPages > 0
                            ) {
                                LinearProgressIndicator(
                                    progress = (download.downloadedPages.toFloat() / download.totalPages).coerceIn(0f, 1f),
                                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(3.dp),
                                )
                            }
                        }
                        if (download.status == com.folio.reader.manga.MangaDownloadStatus.ERROR) {
                            TextButton(onClick = { viewModel.retry(download.id) }) {
                                Text("Retry", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        if (download.status != com.folio.reader.manga.MangaDownloadStatus.DOWNLOADED) {
                            IconButton(onClick = { viewModel.cancel(download.id) }) {
                                Icon(Icons.Filled.Close, contentDescription = "Cancel", tint = FolioTheme.colors.error)
                            }
                        }
                    }
                }
            }
        }
    }
}
