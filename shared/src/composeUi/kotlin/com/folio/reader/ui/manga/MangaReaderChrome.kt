@file:OptIn(ExperimentalSharedTransitionApi::class)

package com.folio.reader.ui.manga

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.reader.ui.components.FolioDialog
import com.folio.reader.ui.components.PageBlock
import com.folio.reader.ui.reader.pageBlockChromeHeight
import com.folio.reader.ui.reader.ReaderChromeGroup
import com.folio.reader.ui.components.folioGlassEffect
import com.folio.reader.ui.components.folioSettingsChoice
import com.folio.reader.ui.settings.readableLabel
import com.folio.reader.ui.theme.FolioShapes
import com.folio.reader.ui.theme.readerVeilAlpha
import com.folio.reader.ui.theme.readerPanelVeilAlpha
import com.folio.reader.ui.theme.glassTierAlpha
import com.folio.reader.ui.theme.FolioSurfaceOpacity
import com.folio.reader.ui.theme.folioSheenGain
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.components.FolioSharedKeys
import com.folio.reader.ui.components.sharedTextOrNoop
import com.folio.reader.ui.theme.FolioTokens
import com.folio.reader.ui.theme.MotionKey
import com.folio.reader.ui.theme.rememberMotionAnimation
import kotlin.math.roundToInt

@Composable
internal fun ReaderSettingsDialog(
    mode: MangaReaderMode,
    onModeChange: (MangaReaderMode) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = FolioTheme.colors
    // §16 issue 3: an in-window glass pane rather than Material's `AlertDialog`, which is a
    // separate window and therefore an opaque slab over the art. See FolioDialog.
    FolioDialog(
        expanded = true,
        fillAlpha = FolioTheme.readerPanelVeilAlpha,
        readerMaterial = true,
        onDismissRequest = onDismiss,
        title = "Reader settings",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Reading mode",
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ReaderModeChip(
                    label = MangaReaderMode.WEBTOON.readableLabel(),
                    selected = mode == MangaReaderMode.WEBTOON,
                    onClick = { onModeChange(MangaReaderMode.WEBTOON) },
                )
                ReaderModeChip(
                    label = MangaReaderMode.PAGED_LTR.readableLabel(),
                    selected = mode == MangaReaderMode.PAGED_LTR,
                    onClick = { onModeChange(MangaReaderMode.PAGED_LTR) },
                )
                ReaderModeChip(
                    label = MangaReaderMode.PAGED_RTL.readableLabel(),
                    selected = mode == MangaReaderMode.PAGED_RTL,
                    onClick = { onModeChange(MangaReaderMode.PAGED_RTL) },
                )
                ReaderModeChip(
                    label = MangaReaderMode.PAGED_VERTICAL.readableLabel(),
                    selected = mode == MangaReaderMode.PAGED_VERTICAL,
                    onClick = { onModeChange(MangaReaderMode.PAGED_VERTICAL) },
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    }
}

@Composable
private fun ReaderModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = FolioTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.RadioButton(
            selected = selected,
            onClick = onClick,
            colors = androidx.compose.material3.RadioButtonDefaults.colors(
                selectedColor = colors.primary,
                unselectedColor = colors.onSurfaceVariant,
            ),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) colors.onSurface else colors.onSurfaceVariant,
        )
    }
}

@Composable
internal fun ReaderControls(
    /**
     * Whether the bars are shown. Passed in rather than wrapped in an `if` at the call site: an
     * `AnimatedVisibility` only animates *out* while it stays composed, so a caller that composes
     * this conditionally gets an instant disappear however the inside is written.
     */
    visible: Boolean = true,
    mangaTitle: String,
    /** §17: the manga's id, so this title pairs with the shelf tile's. Null disables the pairing. */
    mangaId: String? = null,
    chapterName: String,
    pageCount: Int,
    currentPage: Int,
    mode: MangaReaderMode,
    rtl: Boolean,
    bookmarked: Boolean,
    onToggleBookmark: () -> Unit,
    onShowNotes: () -> Unit,
    onShowSettings: () -> Unit,
    onSeek: (Int) -> Unit,
    onBack: () -> Unit,
    onShowPages: () -> Unit = onShowSettings,
    onPreview: (Int?) -> Unit = {},
) {
    // The same thin reader material as books and documents; the page backdrop and
    // reader opacity preference feed readerIsland through ReaderChromeGroup.

    // The chrome's own row in the motion table — the same key the text reader's bars were built
    // for. It resolves to a `tween` on `motionStandard`, not Compose's default spring, which
    // matters here for the reason the text reader documents: a spring's start time comes from the
    // frame clock, so on a heavy first frame a dropped frame can pin the bar at its start value.
    // Reduce-motion skips the transition entirely rather than running it at zero duration, so the
    // bars render their final static form (Rule 19).
    val chromeMotion = com.folio.reader.ui.theme.rememberMotionEnabled()
    val chromeSpec = rememberMotionAnimation<Float>(MotionKey.ReaderChromeToggle)
    // The slide animates an offset, so it needs its own spec: a `FiniteAnimationSpec<Float>` will
    // not unify with `FiniteAnimationSpec<IntOffset>`.
    val chromeSlide = rememberMotionAnimation<androidx.compose.ui.unit.IntOffset>(
        MotionKey.ReaderChromeToggle
    )

    Column(Modifier.fillMaxSize()) {
        // The two bars leave toward opposite edges of the screen — the top bar up to the status
        // edge, the bottom bar down to the gesture bar — so a tap reads as the chrome folding away
        // from the page instead of the whole overlay being cut. The spacer between them stays
        // composed either way, so the layout never reflows; it is empty, and it claims no touches.
        AnimatedVisibility(
            visible = visible,
            enter = if (chromeMotion) {
                fadeIn(chromeSpec) + slideInVertically(chromeSlide) { -it }
            } else {
                androidx.compose.animation.EnterTransition.None
            },
            exit = if (chromeMotion) {
                fadeOut(chromeSpec) + slideOutVertically(chromeSlide) { -it }
            } else {
                androidx.compose.animation.ExitTransition.None
            },
        ) {
            com.folio.reader.ui.reader.ReaderTopBar(
                bookTitle = mangaTitle, showClock = false, occludes = false,
                pageSelection = null, onHighlightParagraph = null, onSelectionConsumed = {},
                isBookmarked = bookmarked, bookmarkColor = FolioTheme.colors.accentAnnotation,
                onBackPress = onBack, onSearchClick = onShowPages, onBookmarkClick = onToggleBookmark,
                onOpenToc = onShowPages, onOpenAnnotations = onShowNotes, onToggleReaderPanel = onShowSettings)
        }

        Spacer(Modifier.weight(1f))

        AnimatedVisibility(
            visible = visible,
            enter = if (chromeMotion) {
                fadeIn(chromeSpec) + slideInVertically(chromeSlide) { it }
            } else {
                androidx.compose.animation.EnterTransition.None
            },
            exit = if (chromeMotion) {
                fadeOut(chromeSpec) + slideOutVertically(chromeSlide) { it }
            } else {
                androidx.compose.animation.ExitTransition.None
            },
        ) {
            Column {
                ReaderChromeGroup(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp), verticalInset = 0.dp) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(pageBlockChromeHeight),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val lastPage = (pageCount - 1).coerceAtLeast(0)
                    // Progress uses the same leaf-stack PageBlock the document and book readers draw,
                    // so all three bottom bars match; PageBlock mirrors the fill and the seek maths for
                    // RTL internally, so the flanking numerals just swap which end they sit at.
                    val fraction = if (lastPage <= 0) 0f else currentPage.toFloat() / lastPage.toFloat()
                    Text(
                        if (rtl) "$pageCount" else "${currentPage + 1}",
                        color = FolioTheme.colors.onSurface,
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    )
                    PageBlock(
                        fraction = fraction,
                        modifier = Modifier
                            .weight(1f)
                            .height(pageBlockChromeHeight)
                            .padding(horizontal = FolioTokens.space2),
                        thickness = 9.dp,
                        paper = FolioTheme.colors.surface,
                        ink = FolioTheme.colors.onSurface,
                        accent = FolioTheme.colors.accentProgress,
                        pageCountHint = pageCount,
                        stateLabel = "Page ${currentPage + 1} of $pageCount",
                        onSeek = { f -> onSeek((f * lastPage).roundToInt().coerceIn(0, lastPage)) },
                        onPreview = { f -> onPreview(f?.let { (it * lastPage).roundToInt().coerceIn(0, lastPage) }) },
                        rtl = rtl,
                    )
                    Text(
                        if (rtl) "${currentPage + 1}" else "$pageCount",
                        color = FolioTheme.colors.onSurface,
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    )
                }
                com.folio.reader.ui.reader.ReaderLocationLabel(chapterName,
                    Modifier.fillMaxWidth().padding(bottom = 2.dp))
                }
                }
            }
        }
    }
}

@Composable
internal fun PageActionsDialog(
    pageIndex: Int,
    onNote: () -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    FolioDialog(
        expanded = true,
        fillAlpha = FolioTheme.readerPanelVeilAlpha,
        readerMaterial = true,
        onDismissRequest = onDismiss,
        title = "Page ${pageIndex + 1}",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onNote)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.EditNote,
                    contentDescription = null,
                    tint = FolioTheme.colors.primary,
                )
                Spacer(Modifier.width(12.dp))
                Text("Add a note", style = MaterialTheme.typography.bodyLarge)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onSave)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Download,
                    contentDescription = null,
                    tint = FolioTheme.colors.primary,
                )
                Spacer(Modifier.width(12.dp))
                Text("Save page to Downloads", style = MaterialTheme.typography.bodyLarge)
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    }
}

@Composable
internal fun NoteDialog(
    pageIndex: Int,
    existing: com.folio.reader.manga.MangaNote?,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember(existing?.id, pageIndex) { mutableStateOf(existing?.content ?: "") }
    FolioDialog(
        expanded = true,
        fillAlpha = FolioTheme.readerPanelVeilAlpha,
        readerMaterial = true,
        onDismissRequest = onDismiss,
        title = "Note — page ${pageIndex + 1}",
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Note") },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onDismiss) { Text("Cancel") }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    onSave(text.trim())
                    onDismiss()
                },
                enabled = text.isNotBlank(),
            ) { Text("Save") }
        }
    }
}

@Composable
internal fun NotesListDialog(
    viewModel: MangaReaderViewModel,
    onDismiss: () -> Unit,
) {
    var notes by remember { mutableStateOf<List<com.folio.reader.manga.MangaNote>>(emptyList()) }
    val revision by viewModel.notesRevision.collectAsState()
    val colors = FolioTheme.colors

    LaunchedEffect(revision) { notes = viewModel.mangaNotes() }

    FolioDialog(
        expanded = true,
        fillAlpha = FolioTheme.readerPanelVeilAlpha,
        readerMaterial = true,
        onDismissRequest = onDismiss,
        title = "Manga notes",
    ) {
        if (notes.isEmpty()) {
            Text(
                "No notes yet. Long-press a page to add one.",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Column(
                // No inner `verticalScroll` / `heightIn`: the host already scrolls the pane
                // (FolioOverlayHost), and a second scrollable in the same direction only puts
                // the two in competition for the drag.
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                notes.forEach { note ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.openNote(note); onDismiss() }
                            // The row shares the reader dialog's material instead of adding an opaque well.
                            .folioSettingsChoice(false, FolioShapes.inset)
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Page ${note.pageIndex + 1}",
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.primary,
                            )
                            Text(note.content, style = MaterialTheme.typography.bodyMedium)
                        }
                        IconButton(onClick = { viewModel.deleteNote(note.id) }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "Delete note",
                                tint = colors.error,
                            )
                        }
                    }
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    }
}
