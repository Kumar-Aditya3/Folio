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

        // Try to find the target chapter
        val targetIndex = chapters.indexOfFirst { ch ->
            ch.href == resolvedHref || ch.href.endsWith("/$resolvedHref") || resolvedHref.endsWith("/${ch.href}")
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
