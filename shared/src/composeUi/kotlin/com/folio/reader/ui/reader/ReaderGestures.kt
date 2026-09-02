package com.folio.reader.ui.reader

import com.folio.reader.model.Bookmark
import com.folio.reader.model.Chapter
import com.folio.reader.model.Highlight
import com.folio.reader.model.Note
import com.folio.reader.model.locatorFraction
import com.folio.reader.settings.ReaderSettings

/**
 * Folio writes content locators as "/{spineIndex}/{paragraphIndex}:{offset}"
 * (see addHighlight / addBookmark), so the paragraph step is the last segment.
 */
private fun String.paragraphFromLocator(): Int? =
    substringAfterLast('/').substringBefore(':').trimEnd(')').toIntOrNull()

/**
 * Opens the spot a bookmark/highlight/note was taken at: switches chapters when
 * needed, then lands on the target. [markId] points at a painted highlight so the
 * jump is exact (older rows only stored paragraph 0 because the selection index
 * was read after the click collapsed it); otherwise the paragraph or a chapter
 * fraction is used.
 *
 * Same-chapter seeks go through [onSeek] (the caller bumps its seek nonce); a
 * different chapter is parked via [onPendingJump] until that chapter is on
 * screen, else the seek would move the old chapter.
 */
internal fun readerJumpToLocation(
    chapters: List<Chapter>,
    currentChapterIndex: Int,
    spineIndex: Int?,
    chapterId: String?,
    locator: String?,
    markId: String? = null,
    onSeek: (target: String?, fraction: Float?) -> Unit,
    onPendingJump: (Triple<Int, String?, Float?>) -> Unit,
    onChapterChange: (Int) -> Unit
) {
    val index = chapters.indexOfFirst { spineIndex != null && it.spineIndex == spineIndex }
        .takeIf { it >= 0 }
        ?: chapters.indexOfFirst { chapterId != null && it.id == chapterId }.takeIf { it >= 0 }
        ?: return
    val para = locator?.paragraphFromLocator()
    val frac = locator?.locatorFraction()
    // Carried all the way to the page so a jump degrades mark -> paragraph ->
    // fraction, never to the top of the chapter.
    val tail = "${para ?: ""}:${frac ?: ""}"
    val target = when {
        markId != null && markId.matches(Regex("[A-Za-z0-9_-]+")) -> "h:$markId:$tail"
        para != null -> "p:$para"
        else -> null
    }
    val fraction = if (target == null) frac else null
    if (index == currentChapterIndex) {
        onSeek(target, fraction)
    } else {
        onPendingJump(Triple(index, target, fraction))
        onChapterChange(index)
    }
}

internal fun readerJumpToAnnotation(
    kind: String,
    id: String,
    bookmarks: List<Bookmark>,
    highlights: List<Highlight>,
    notes: List<Note>,
    annotationsVisible: Boolean,
    onToggleAnnotations: () -> Unit,
    jump: (spineIndex: Int?, chapterId: String?, locator: String?, markId: String?) -> Unit
) {
    when (kind) {
        "bm" -> bookmarks.firstOrNull { it.id == id }
            ?.let { jump(it.spineIndex, it.chapterId, it.locator, null) }
        "hl" -> highlights.firstOrNull { it.id == id }
            ?.let { jump(it.spineIndex, it.chapterId, it.startLocator, it.id) }
        "nt" -> notes.firstOrNull { it.id == id }
            ?.let { jump(it.spineIndex, it.chapterId, it.locator, null) }
    }
    if (annotationsVisible) onToggleAnnotations()
}

/**
 * Dispatches an action string from the page-side glass overlays (desktop
 * platforms where the browser occludes Compose). Settings writes go through
 * [onSettingsChange] exactly as the compose panels do.
 */
internal fun handleReaderOverlayAction(
    a: String,
    settings: ReaderSettings,
    highlights: List<Highlight>,
    onClose: () -> Unit,
    onOpenAllSettings: () -> Unit,
    onChapterChange: (Int) -> Unit,
    onSettingsChange: (ReaderSettings) -> Unit,
    onSaveNote: (highlightId: String, text: String) -> Unit,
    onComposeNote: (highlightId: String) -> Unit,
    onJumpAnnotation: (kind: String, id: String) -> Unit,
    onRemoveBookmark: (String) -> Unit,
    onRemoveHighlight: (String) -> Unit,
    onRemoveNote: (String) -> Unit
) {
    when {
        a == "close" -> onClose()

        a == "allsettings" -> onOpenAllSettings()

        a.startsWith("toc:") -> {
            val i = a.substringAfter(':').toIntOrNull() ?: return
            // Stay open: the reader re-syncs the highlight in place and the
            // TOC only closes when the user taps the backdrop or X.
            onChapterChange(i)
        }

        a.startsWith("set:size:") -> onSettingsChange(
            settings.copy(fontSize = a.substringAfterLast(':').toFloatOrNull() ?: settings.fontSize)
        )

        a.startsWith("set:lh:") -> onSettingsChange(
            settings.copy(lineHeight = a.substringAfterLast(':').toFloatOrNull() ?: settings.lineHeight)
        )

        a.startsWith("set:mg:") -> a.substringAfterLast(':').toFloatOrNull()?.let { m ->
            onSettingsChange(settings.copy(margins = settings.margins.copy(left = m, right = m)))
        }

        a.startsWith("set:font:") -> {
            val name = runCatching {
                java.net.URLDecoder.decode(a.substringAfter("set:font:"), "UTF-8")
            }.getOrNull()
            if (!name.isNullOrBlank()) onSettingsChange(settings.copy(fontFamily = name))
        }

        a.startsWith("set:theme:") -> {
            val id = a.substringAfterLast(':')
            if (com.folio.reader.settings.Theme.PRESETS.containsKey(id)) {
                onSettingsChange(settings.copy(themeId = id, customTheme = null))
            }
        }

        a.startsWith("set:hlcolor:") -> {
            val idx = a.substringAfterLast(':').toIntOrNull() ?: return
            onSettingsChange(settings.copy(highlightColorIndex = idx))
        }

        a.startsWith("set:layout:") -> {
            val mode = a.substringAfterLast(':')
            val layout = com.folio.reader.settings.LayoutMode.entries.firstOrNull { it.name == mode }
            if (layout != null) onSettingsChange(settings.copy(layoutMode = layout))
        }

        a.startsWith("note:") -> {
            val id = a.substringAfterLast(':')
            if (highlights.any { it.id == id }) onComposeNote(id)
        }

        a.startsWith("savenote:") -> {
            val rest = a.substringAfter(':')
            val id = rest.substringBefore(':')
            val encoded = rest.substringAfter(':', "")
            val text = runCatching {
                java.net.URLDecoder.decode(encoded, "UTF-8")
            }.getOrNull().orEmpty().trim()
            onSaveNote(id, text)
        }

        a.startsWith("ann:") -> {
            val kind = a.substringAfter(':').substringBefore(':')
            val id = a.substringAfterLast(':')
            onJumpAnnotation(kind, id)
        }

        a.startsWith("del:bm:") -> onRemoveBookmark(a.substringAfterLast(':'))
        a.startsWith("del:hl:") -> onRemoveHighlight(a.substringAfterLast(':'))
        a.startsWith("del:nt:") -> onRemoveNote(a.substringAfterLast(':'))
    }
}
