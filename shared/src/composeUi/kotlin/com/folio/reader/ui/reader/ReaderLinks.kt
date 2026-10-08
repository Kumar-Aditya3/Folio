package com.folio.reader.ui.reader

import com.folio.reader.model.Chapter
import com.folio.reader.ui.render.LinkClickResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** Resolves taps on in-content links to chapter jumps, inline content or the browser. */
internal class ReaderLinks(
    private val currentBookId: () -> String?,
    private val chapters: () -> List<Chapter>,
    private val currentChapterIndex: () -> Int
) {
    private val _linkClickResult = MutableStateFlow<LinkClickResult?>(null)
    val linkClickResult: Flow<LinkClickResult?> = _linkClickResult

    fun handleLinkClick(href: String) {
        currentBookId() ?: return
        val chapters = chapters()

        // External link
        if (href.startsWith("http://") || href.startsWith("https://")) {
            _linkClickResult.value = LinkClickResult.ExternalUrl(href)
            return
        }

        // Internal link - resolve relative to current chapter
        val currentChapter = chapters.getOrNull(currentChapterIndex())
        val resolvedHref = resolveInternalHref(currentChapter?.href ?: "", href)

        // Match the target spine file. The href reaches here in several shapes depending on
        // the surface: desktop passes the raw attribute ("chapter_2.xhtml"), the Android
        // paged base yields a book-root path, and the continuous base yields a bare file
        // name. Compare on the path without its fragment, and fall back to the file name so
        // every shape resolves to the same chapter. The exact/suffix checks run first so the
        // file-name fallback only settles genuinely ambiguous cases.
        val targetPath = resolvedHref.substringBefore("#")
        val targetFile = targetPath.substringAfterLast('/')
        val targetIndex = chapters.indexOfFirst { ch ->
            val chPath = ch.href.substringBefore("#")
            chPath == targetPath || chPath.endsWith("/$targetPath") || targetPath.endsWith("/$chPath") ||
                (targetFile.isNotEmpty() && chPath.substringAfterLast('/') == targetFile)
        }

        if (targetIndex >= 0) {
            _linkClickResult.value = LinkClickResult.InternalChapter(targetIndex)
        } else {
            // Might be a footnote or non-chapter content - show inline
            _linkClickResult.value = LinkClickResult.InlineContent(href, resolvedHref)
        }
    }

    fun clearLinkClickResult() {
        _linkClickResult.value = null
    }

    private fun resolveInternalHref(currentHref: String, href: String): String {
        if (href.startsWith("/")) return href.substring(1)
        if (href.contains("://")) return href
        val base = currentHref.substringBeforeLast('/', "")
        return if (base.isNotEmpty()) "$base/$href" else href
    }
}
