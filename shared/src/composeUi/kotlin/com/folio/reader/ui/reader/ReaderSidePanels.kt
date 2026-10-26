package com.folio.reader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import com.folio.reader.model.Bookmark
import com.folio.reader.model.BookTocRow
import com.folio.reader.model.Highlight
import com.folio.reader.model.Note
import com.folio.reader.settings.ReaderSettings
import com.folio.reader.ui.components.folioBackdropSource
import com.folio.reader.ui.components.folioSheetDragToDismiss
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.atmosphere
import com.folio.reader.ui.theme.MotionKey
import com.folio.reader.ui.theme.rememberMotionAnimation

/**
 * Scrim plus the three end-edge slide-in panels (TOC, annotations, quick
 * settings). Compose overlays on Android only — on occluding platforms the
 * page-side glass overlays take over, so none of this is drawn there.
 */
@Composable
internal fun BoxScope.ReaderSidePanels(
    occludes: Boolean,
    showToc: Boolean,
    showAnnotations: Boolean,
    showReaderPanel: Boolean,
    showEchoes: Boolean = false,
    echoesEnabled: Boolean = false,
    echoesState: EchoesState = EchoesState.Idle,
    onCloseEchoes: () -> Unit = {},
    onOpenEcho: (bookId: String, spineIndex: Int?, fraction: Float?) -> Unit = { _, _, _ -> },
    showSearch: Boolean = false,
    searchQuery: String = "",
    searchResults: List<com.folio.reader.database.SearchResult> = emptyList(),
    searching: Boolean = false,
    onSearchQueryChange: (String) -> Unit = {},
    onSearchResultClick: (com.folio.reader.database.SearchResult) -> Unit = {},
    onCloseSearch: () -> Unit = {},
    tocEntries: List<BookTocRow>,
    currentChapterIndex: Int,
    currentFraction: Float,
    onTocRowClick: (BookTocRow) -> Unit,
    onToggleToc: () -> Unit,
    onToggleAnnotations: () -> Unit,
    bookmarks: List<Bookmark>,
    highlights: List<Highlight>,
    notes: List<Note>,
    onRemoveBookmark: (String) -> Unit,
    onRemoveHighlight: (String) -> Unit,
    onRemoveNote: (String) -> Unit,
    onSetHighlightNote: (highlightId: String, content: String) -> Unit,
    chapterLabel: (spineIndex: Int?, chapterId: String?) -> String,
    onJumpToAnnotation: (kind: String, id: String) -> Unit,
    settings: ReaderSettings,
    onSettingsChange: (ReaderSettings) -> Unit,
    onDismissReaderPanel: () -> Unit,
    onOpenFullSettings: () -> Unit,
    scopeControlEnabled: Boolean = false,
    overriddenFields: Set<String> = emptySet(),
    onWriteGlobal: ((ReaderSettings) -> Unit)? = null,
    onResetBook: (() -> Unit)? = null,
    onSelectTool: (ReaderTool) -> Unit,
    layout: ReaderWorkspaceLayout? = null,
    contents: (@Composable () -> Unit)? = null,
    noteEditor: (@Composable () -> Unit)? = null,
    showListen: Boolean = false,
    onCloseListen: () -> Unit = {},
    listenContent: (@Composable () -> Unit)? = null,
) {
    val active = when {
        showListen -> ReaderTool.Listen
        showReaderPanel -> ReaderTool.Settings
        showSearch -> ReaderTool.Search
        showEchoes -> ReaderTool.Echoes
        showAnnotations -> ReaderTool.Saved
        showToc -> ReaderTool.Contents
        else -> null
    }
    fun close() {
        onCloseListen()
        if (showToc) onToggleToc()
        if (showAnnotations) onToggleAnnotations()
        if (showSearch) onCloseSearch()
        if (showEchoes) onCloseEchoes()
        onDismissReaderPanel()
    }
    ReaderWorkspace(
        active = active,
        tools = listOfNotNull(ReaderTool.Contents, ReaderTool.Search, ReaderTool.Saved,
            ReaderTool.Echoes.takeIf { echoesEnabled }, ReaderTool.Listen.takeIf { listenContent != null }, ReaderTool.Settings),
        location = chapterLabel(currentChapterIndex, null),
        onSelect = onSelectTool,
        onDismiss = ::close,
        layout = layout,
    ) { tool ->
        when (tool) {
            ReaderTool.Listen -> listenContent?.invoke()
            ReaderTool.Contents -> if (contents != null) contents() else
                TOCSidebar(tocEntries, currentChapterIndex, currentFraction, onChapterClick = onTocRowClick, onDismiss = ::close)
            ReaderTool.Saved -> if (noteEditor != null) noteEditor() else
                AnnotationsSidebar(bookmarks, highlights, notes, onDismiss = ::close,
                    onRemoveBookmark = onRemoveBookmark, onRemoveHighlight = onRemoveHighlight,
                    onRemoveNote = onRemoveNote, onSetHighlightNote = onSetHighlightNote,
                    chapterLabel = chapterLabel, onJump = onJumpToAnnotation)
            ReaderTool.Search -> ReaderSearchPanel(searchQuery, onSearchQueryChange, searchResults,
                searching, currentChapterIndex, onSearchResultClick, ::close)
            ReaderTool.Echoes -> EchoesPanel(echoesState, ::close, onOpenEcho)
            ReaderTool.Settings -> ReaderSettingsPanel(settings, onSettingsChange, ::close,
                onOpenFullSettings, scopeControlEnabled, overriddenFields, onWriteGlobal, onResetBook)
            ReaderTool.Pages -> Unit
        }
    }
}
