package com.folio.reader.ui.reader

import com.folio.reader.model.Chapter
import com.folio.reader.ui.render.READER_WINDOW_MAX_SECTIONS
import com.folio.reader.ui.render.READER_WINDOW_PRELOAD
import com.folio.reader.ui.render.ReaderSection
import com.folio.reader.ui.render.WindowOp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Loads the open book's chapter HTML behind a bounded LRU cache.
 *
 * In continuous layout it loads a *window* of chapters around the current one
 * instead of a single document: the window renders as one scrolling page so
 * chapters flow into each other, and [extendWindow] grows it at either end as
 * the engine asks, trimming the far side once it passes
 * [READER_WINDOW_MAX_SECTIONS].
 */
internal class ReaderContentLoader(
    private val scope: CoroutineScope,
    private val chapterContentProvider: suspend (bookId: String, chapterHref: String) -> String,
    private val chapterHtmlState: MutableStateFlow<String>,
    private val isLoadingContentState: MutableStateFlow<Boolean>,
    private val loadErrorState: MutableStateFlow<String?>,
    private val currentBookId: () -> String?,
    private val chapters: () -> List<Chapter>,
    private val currentChapterIndex: () -> Int,
    private val windowMode: () -> Boolean
) {
    /** Access-order LRU of loaded chapter HTML keyed "bookId:chapterHref"; bounded to [MAX_HTML_CACHE] entries. */
    private val htmlCache = LinkedHashMap<String, String>(16, 0.75f, true)

    /** The chapters currently on screen together, and the one-shot mutations applied to them. */
    val windowSections = MutableStateFlow<List<ReaderSection>>(emptyList())
    val windowRange = MutableStateFlow<IntRange?>(null)
    val windowOp = MutableStateFlow<WindowOp?>(null)
    /**
     * The window as it was last *rebuilt* — the document the surface renders.
     * Extensions mutate the live DOM and must never rebuild it (a reload would
     * scroll-jump and defeat the whole point), so this list changes only when a
     * window is truly (re)loaded.
     */
    val windowLoad = MutableStateFlow<List<ReaderSection>>(emptyList())
    private val windowOpAck = MutableStateFlow(0L)
    private var windowNonce = 0L

    suspend fun loadChapterHtml() {
        val bookId = currentBookId() ?: return
        val allChapters = chapters()
        val index = currentChapterIndex()
        val chapter = allChapters.getOrNull(index) ?: return
        if (windowMode()) {
            loadWindow(bookId, allChapters, index)
            return
        }
        windowRange.value = null
        windowSections.value = emptyList()
        isLoadingContentState.value = true
        loadErrorState.value = null
        try {
            chapterHtmlState.value = loadHtml(bookId, chapter)
            if (chapterHtmlState.value.isBlank()) {
                loadErrorState.value = "Empty chapter (href=${chapter.href}) — file may be missing or parse failed"
            }
        } catch (e: Exception) {
            chapterHtmlState.value = ""
            loadErrorState.value = e.message ?: e::class.simpleName ?: "Unknown error"
            e.printStackTrace()
        } finally {
            isLoadingContentState.value = false
        }
    }

    fun reloadChapter() {
        scope.launch { loadChapterHtml() }
    }

    /**
     * Builds the window [READER_WINDOW_PRELOAD] chapters either side of [center]
     * (clamped to the book). The cover chapter renders through the app's cover
     * screen, so it never joins a window; chapter 1 is the first section.
     */
    private suspend fun loadWindow(bookId: String, allChapters: List<Chapter>, center: Int) {
        isLoadingContentState.value = true
        loadErrorState.value = null
        try {
            val last = allChapters.lastIndex
            val from = (center - READER_WINDOW_PRELOAD).coerceAtLeast(1)
            val to = (center + READER_WINDOW_PRELOAD).coerceAtMost(last)
            val sections = (from..to).map { i ->
                val chapter = allChapters[i]
                ReaderSection(chapter.spineIndex, chapter.id, chapter.href, loadHtml(bookId, chapter))
            }
            windowSections.value = sections
            windowRange.value = from..to
            windowLoad.value = sections
            // A fresh document supersedes any pending mutation; clear it so a
            // stale append can never inject a chapter into the new window.
            windowNonce++
            windowOp.value = null
            windowOpAck.value = windowNonce
            val anchor = sections.firstOrNull { it.chapterId == allChapters[center].id }
                ?: sections.firstOrNull()
            chapterHtmlState.value = anchor?.html.orEmpty()
            if (chapterHtmlState.value.isBlank()) {
                loadErrorState.value =
                    "Empty chapter (href=${allChapters[center].href}) — file may be missing or parse failed"
            }
        } catch (e: Exception) {
            chapterHtmlState.value = ""
            loadErrorState.value = e.message ?: e::class.simpleName ?: "Unknown error"
            e.printStackTrace()
        } finally {
            isLoadingContentState.value = false
        }
    }

    /**
     * Grows the window by one chapter in [forward] direction. The op is emitted
     * for the surface to inject; the surface acknowledges it, and the next
     * mutation waits for that ack — StateFlow conflates, so an op overwritten
     * before it was applied would leave a permanent gap between sections.
     */
    suspend fun extendWindow(forward: Boolean) {
        val bookId = currentBookId() ?: return
        val allChapters = chapters()
        val range = windowRange.value ?: return
        if (forward && range.last + 1 > allChapters.lastIndex) return
        if (!forward && range.first - 1 < 1) return
        val nextIndex = if (forward) range.last + 1 else range.first - 1
        val chapter = allChapters.getOrNull(nextIndex) ?: return
        val section = ReaderSection(chapter.spineIndex, chapter.id, chapter.href, loadHtml(bookId, chapter))
        windowNonce++
        val nonce = windowNonce
        windowOp.value = if (forward) {
            WindowOp.Append(section, nonce)
        } else {
            WindowOp.Prepend(section, nonce)
        }
        windowRange.value = if (forward) range.first..nextIndex else nextIndex..range.last
        windowSections.value = if (forward) {
            windowSections.value + section
        } else {
            listOf(section) + windowSections.value
        }
        // Let the surface apply the extension before trimming, so the two ops
        // cannot conflate into just the trim.
        kotlinx.coroutines.withTimeoutOrNull(2500) {
            windowOpAck.first { it >= nonce }
        }
        trimAfter(forward, allChapters)
    }

    /** Drops the far end once the window passes [READER_WINDOW_MAX_SECTIONS]. */
    private suspend fun trimAfter(forward: Boolean, allChapters: List<Chapter>) {
        var range = windowRange.value ?: return
        val before = range
        while (range.last - range.first + 1 > READER_WINDOW_MAX_SECTIONS) {
            range = if (forward) (range.first + 1)..range.last else range.first..(range.last - 1)
        }
        if (range == before) return
        windowRange.value = range
        val keptSpines = allChapters.slice(range).map { it.spineIndex }.toSet()
        windowSections.value = windowSections.value.filter { it.spineIndex in keptSpines }
        windowNonce++
        windowOp.value = WindowOp.Trim(
            fromSpine = allChapters.getOrNull(range.first)?.spineIndex ?: -1,
            toSpine = allChapters.getOrNull(range.last)?.spineIndex ?: -1,
            nonce = windowNonce
        )
    }

    /** Surfaces report each applied op; unacknowledged ops block further extension. */
    fun onWindowOpApplied(nonce: Long) {
        if (nonce > windowOpAck.value) windowOpAck.value = nonce
    }

    /** True when an extension op is still waiting to be applied on screen. */
    fun hasPendingWindowOp(): Boolean =
        (windowOp.value?.nonce ?: 0L) > windowOpAck.value

    private suspend fun loadHtml(bookId: String, chapter: Chapter): String {
        val key = "$bookId:${chapter.href}"
        return htmlCache[key]
            ?: chapterContentProvider(bookId, chapter.href).also { loaded ->
                if (loaded.isNotBlank()) {
                    htmlCache[key] = loaded
                    while (htmlCache.size > MAX_HTML_CACHE) {
                        val eldest = htmlCache.entries.iterator()
                        if (!eldest.hasNext()) break
                        eldest.next()
                        eldest.remove()
                    }
                }
            }
    }

    private companion object {
        const val MAX_HTML_CACHE = 12
    }
}
