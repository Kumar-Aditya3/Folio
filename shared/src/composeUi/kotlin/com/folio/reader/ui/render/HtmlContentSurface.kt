package com.folio.reader.ui.render

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.folio.reader.model.Highlight
import com.folio.reader.model.ReadingPosition
import com.folio.reader.settings.ReaderSettings

/**
 * Platform HTML surface. Android uses WebView so EPUB CSS and resources are
 * interpreted by a browser. Desktop uses the embedded JCEF Chromium runtime.
 * No Compose fallback — the browser owns rendering to preserve EPUB CSS/pagination.
 *
 * Continuous layout renders [sections] — one entry per chapter of the window —
 * as a single scrolling document (see [ContinuousEngine]); [windowed] is true
 * whenever the sections come from a chapter window, even a one-section one, and
 * false for plain single-section content like reflowed documents. The
 * [WindowOp] mutations let the host grow or trim the window on screen without a
 * reload.
 */
@Composable
expect fun HtmlContentSurface(
    sections: List<ReaderSection>,
    windowed: Boolean,
    anchorChapterId: String?,
    settings: ReaderSettings,
    position: ReadingPosition?,
    highlights: List<Highlight>,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onProgress: (Float) -> Unit,
    onPageChange: (Int, Int) -> Unit,
    /** Spine index of the section the viewport centre sits in (-1 when unknown). */
    onVisibleSection: (spineIndex: Int) -> Unit = {},
    onExtendForward: () -> Unit = {},
    onExtendBackward: () -> Unit = {},
    windowOp: WindowOp? = null,
    /** Acknowledges an applied window mutation, gating the next extension. */
    onWindowOpApplied: (nonce: Long) -> Unit = {},
    onChapterEnd: () -> Unit = {},
    onChapterStart: () -> Unit = {},
    onTap: () -> Unit = {},
    onLinkClick: ((String) -> Unit)?,
    onResolveResource: suspend (chapterHref: String, src: String) -> String?,
    /** Carries the section's chapter id: a windowed selection may sit in a chapter that is not the anchor. */
    onHighlightParagraph: ((chapterId: String, paragraphIndex: Int, selectedText: String) -> Unit)? = null,
    onSelectionChanged: ((chapterId: String, paragraphIndex: Int, selectedText: String?) -> Unit)? = null,
    clearSelectionRequest: Long? = null,
    seekRequest: Pair<Float, Long>? = null,
    seekTargetRequest: Pair<String, Long>? = null
)

/**
 * True when the platform HTML surface is a heavyweight native window that paints
 * above anything Compose draws on top of it. The reader chrome must then reserve
 * its own space (bars/panels sit beside the page) instead of floating over it.
 */
expect fun htmlSurfaceOccludesOverlays(): Boolean
