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
 */
@Composable
expect fun HtmlContentSurface(
    html: String,
    chapterHref: String,
    settings: ReaderSettings,
    position: ReadingPosition?,
    highlights: List<Highlight>,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onProgress: (Float) -> Unit,
    onPageChange: (Int, Int) -> Unit,
    onChapterEnd: () -> Unit = {},
    onChapterStart: () -> Unit = {},
    onTap: () -> Unit = {},
    onLinkClick: ((String) -> Unit)?,
    onResolveResource: suspend (chapterHref: String, src: String) -> String?,
    onHighlightParagraph: ((paragraphIndex: Int, selectedText: String) -> Unit)? = null,
    onSelectionChanged: ((paragraphIndex: Int, selectedText: String?) -> Unit)? = null,
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
