package com.folio.reader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import com.folio.reader.model.Bookmark
import com.folio.reader.model.Chapter
import com.folio.reader.model.Highlight
import com.folio.reader.model.Note
import com.folio.reader.settings.ReaderSettings

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
    chapters: List<Chapter>,
    currentChapterIndex: Int,
    onChapterChange: (Int) -> Unit,
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
    onResetBook: (() -> Unit)? = null
) {
    if (occludes) return

    // Panels sit in reserved space on occluding platforms; a horizontal slide
    // into that strip reads as janky, so fade there and slide elsewhere.
    val panelEnter = androidx.compose.animation.slideInHorizontally(
        initialOffsetX = { it },
        animationSpec = androidx.compose.animation.core.tween(300, easing = androidx.compose.animation.core.FastOutSlowInEasing)
    ) + androidx.compose.animation.fadeIn(animationSpec = androidx.compose.animation.core.tween(300))
    val panelExit = androidx.compose.animation.slideOutHorizontally(
        targetOffsetX = { it },
        animationSpec = androidx.compose.animation.core.tween(300, easing = androidx.compose.animation.core.FastOutSlowInEasing)
    ) + androidx.compose.animation.fadeOut(animationSpec = androidx.compose.animation.core.tween(300))

    // Global scrim for sidebar sheets: only fades in/out statically, does not slide!
    androidx.compose.animation.AnimatedVisibility(
        visible = showToc || showAnnotations || showReaderPanel,
        enter = androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.fadeOut()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.35f))
                .pointerInput(Unit) {
                    detectTapGestures(onTap = {
                        if (showToc) onToggleToc()
                        if (showAnnotations) onToggleAnnotations()
                        onDismissReaderPanel()
                    })
                }
        )
    }

    // TOC sidebar: slide in from the end edge
    androidx.compose.animation.AnimatedVisibility(
        visible = showToc,
        modifier = Modifier.align(Alignment.CenterEnd).statusBarsPadding(),
        enter = panelEnter,
        exit = panelExit
    ) {
        TOCSidebar(
            chapters = chapters,
            currentIndex = currentChapterIndex,
            onChapterClick = { index ->
                onChapterChange(index)
            },
            onDismiss = onToggleToc
        )
    }

    // Annotations sidebar: slide in from the end edge
    androidx.compose.animation.AnimatedVisibility(
        visible = showAnnotations,
        modifier = Modifier.align(Alignment.CenterEnd).statusBarsPadding(),
        enter = panelEnter,
        exit = panelExit
    ) {
        AnnotationsSidebar(
            bookmarks = bookmarks,
            highlights = highlights,
            notes = notes,
            onDismiss = onToggleAnnotations,
            onRemoveBookmark = onRemoveBookmark,
            onRemoveHighlight = onRemoveHighlight,
            onRemoveNote = onRemoveNote,
            onSetHighlightNote = onSetHighlightNote,
            chapterLabel = chapterLabel,
            onJump = onJumpToAnnotation
        )
    }

    // Thorium-style reading settings panel: slides in from the right edge
    androidx.compose.animation.AnimatedVisibility(
        visible = showReaderPanel,
        modifier = Modifier.align(Alignment.CenterEnd).statusBarsPadding(),
        enter = panelEnter,
        exit = panelExit
    ) {
        ReaderSettingsPanel(
            settings = settings,
            onSettingsChange = onSettingsChange,
            onDismiss = onDismissReaderPanel,
            onOpenFullSettings = onOpenFullSettings,
            scopeControlEnabled = scopeControlEnabled,
            overriddenFields = overriddenFields,
            onWriteGlobal = onWriteGlobal,
            onResetBook = onResetBook
        )
    }
}
