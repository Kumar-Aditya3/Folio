package com.folio.reader.ui.reader

import com.folio.reader.model.Chapter
import com.folio.reader.ui.render.FixedLayoutDetector
import com.folio.reader.ui.render.READER_WINDOW_MAX_CHARS
import com.folio.reader.ui.render.READER_WINDOW_MAX_SECTIONS
import com.folio.reader.ui.render.READER_WINDOW_PRELOAD
import com.folio.reader.ui.render.ReaderSection
import com.folio.reader.ui.render.WindowOp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        // Every early exit clears the loading flag. `_isLoadingContent` starts
        // true (a reader is constructed in order to load something), so a path
        // that returns without clearing it would leave the spinner up forever.
        // There is nothing to show in these cases, so "not loading, no content"
        // is the honest state — the empty branch handles it.
        val bookId = currentBookId() ?: run {
            isLoadingContentState.value = false
            return
        }
        val allChapters = chapters()
        val index = currentChapterIndex()
        val chapter = allChapters.getOrNull(index) ?: run {
            isLoadingContentState.value = false
            return
        }
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
            // A present-but-empty chapter (some EPUBs have placeholder spine entries) is valid
            // content, not a failure: loadHtml throws on a genuine missing-file/parse error, which
            // the catch below surfaces. Blank here therefore renders an empty chapter rather than
            // a load-error card the reader cannot dismiss.
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
     * (clamped to the book).
     *
     * Every chapter can join a window, chapter 0 included. The floor used to be 1 on the
     * assumption that the first spine item is the cover page, but books whose first file
     * carries prose (a pdftohtml/Calibre conversion opening with the Prologue) then had
     * Contents rows pointing at a chapter no window could ever contain.
     */
    private suspend fun loadWindow(bookId: String, allChapters: List<Chapter>, center: Int) {
        isLoadingContentState.value = true
        loadErrorState.value = null
        try {
            // Reading every chapter's HTML and probing each for fixed layout is work proportional to
            // the window's size — 0.5-1.3 MB on the books that jump badly — and this runs on the main
            // dispatcher, so it froze the very frame a Contents jump is supposed to feel instant.
            val (range, sections) = withContext(Dispatchers.IO) {
                buildWindow(bookId, allChapters, center)
            }
            windowSections.value = sections
            windowRange.value = range
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
     * The chapters a jump to [center] loads: the anchor plus one chapter either side, then outward
     * while the window stays inside [READER_WINDOW_MAX_CHARS] and never wider than
     * [READER_WINDOW_PRELOAD] chapters from the anchor. Returns the chapter range and its sections in
     * spine order.
     *
     * Chapters are loaded as the spread grows, so nothing is read only to be dropped. One neighbour
     * either side always loads: a single huge chapter must still flow into the chapters around it,
     * which is the whole reason a window exists.
     *
     * This bounds the *cold* build — the one a Contents jump pays for, where the WebView parses and
     * lays out the whole document before the jump can land. Growth while scrolling is left alone on
     * purpose: it arrives as appends into the live document, and trimming the far end to hold a
     * ceiling there would change the scroll geometry under the reader.
     */
    private suspend fun buildWindow(
        bookId: String,
        allChapters: List<Chapter>,
        center: Int,
    ): Pair<IntRange, List<ReaderSection>> {
        val last = allChapters.lastIndex
        val sections = ArrayList<ReaderSection>()
        var chars = 0
        suspend fun load(index: Int): ReaderSection {
            val chapter = allChapters[index]
            return sectionOf(chapter, loadHtml(bookId, chapter)).also { chars += it.html.length }
        }
        var from = (center - 1).coerceAtLeast(0)
        var to = (center + 1).coerceAtMost(last)
        for (index in from..to) sections.add(load(index))
        while (chars < READER_WINDOW_MAX_CHARS) {
            val canLeft = from > 0 && center - from < READER_WINDOW_PRELOAD
            val canRight = to < last && to - center < READER_WINDOW_PRELOAD
            if (!canLeft && !canRight) break
            // Grow toward whichever neighbour is smaller, so a 250 KB chapter on one side does not
            // spend the whole budget while a 40 KB one waits on the other. Estimated from the stored
            // counts, because measuring it properly would mean loading it first.
            val leftChars = if (canLeft) estimatedChars(allChapters[from - 1]) else Long.MAX_VALUE
            val rightChars = if (canRight) estimatedChars(allChapters[to + 1]) else Long.MAX_VALUE
            if (canLeft && leftChars <= rightChars) {
                sections.add(0, load(from - 1))
                from--
            } else {
                sections.add(load(to + 1))
                to++
            }
        }
        return from..to to sections
    }

    /** Rough text length of a chapter, only ever used to choose between two unloaded neighbours. */
    private fun estimatedChars(chapter: Chapter): Long =
        if (chapter.characterCount > 0) chapter.characterCount else chapter.wordCount * CHARS_PER_WORD

    /**
     * Grows the window in [forward] direction, filling the lead in one call.
     *
     * Fetching one chapter per edge event is what made artwork arrive a page at a time. The
     * engine deliberately throttles its own requests — a 500 ms gate per direction, re-armed by
     * every arrival, and never both directions at once — because before that it ping-ponged
     * prepend/append every frame and threw the reader around. Those gates are right; the cost is
     * that the pipeline caps out near two chapters a second, which a single flick outruns in a
     * book that is one full-page figure per chapter. Filling [READER_WINDOW_PRELOAD] chapters
     * per request lifts the cap without touching the anti-thrash logic.
     *
     * Ops are still emitted and awaited one at a time: `windowOp` is a conflated StateFlow, so
     * an op overwritten before the surface applied it would leave a chapter counted in the
     * window but permanently missing from the document.
     */
    suspend fun extendWindow(forward: Boolean) {
        var added = false
        for (i in 0 until READER_WINDOW_PRELOAD) {
            if (!extendOne(forward)) break
            added = true
        }
        // Once for the whole batch: a trim emitted between two appends can be conflated away
        // with one of them.
        if (added) trimAfter(forward, chapters())
    }

    /** Adds the next chapter in [forward] direction. False when the window has nothing left to add. */
    private suspend fun extendOne(forward: Boolean): Boolean {
        val bookId = currentBookId() ?: return false
        val allChapters = chapters()
        val range = windowRange.value ?: return false
        val nextIndex = if (forward) range.last + 1 else range.first - 1
        if (forward && nextIndex > allChapters.lastIndex) return false
        if (!forward && nextIndex < 0) return false
        val chapter = allChapters.getOrNull(nextIndex) ?: return false
        // Defensive: never emit an op for a spine already in the window. Even with the
        // caller serialised, a stale range read could otherwise re-add a section and the
        // surface would render it twice, corrupting scroll geometry.
        if (windowSections.value.any { it.spineIndex == chapter.spineIndex }) return false
        val section = sectionOf(chapter, loadHtml(bookId, chapter))
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
        // Wait for this chapter to reach the document before the next op overwrites it.
        kotlinx.coroutines.withTimeoutOrNull(2500) {
            windowOpAck.first { it >= nonce }
        }
        return true
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

    /**
     * Builds a [ReaderSection] for [chapter], tagging fixed-layout (pre-paginated)
     * pages and their pixel size from the chapter HTML so the render layer can
     * scale rather than reflow them.
     */
    private fun sectionOf(chapter: Chapter, html: String): ReaderSection {
        val fxl = FixedLayoutDetector.detect(html)
        return ReaderSection(
            spineIndex = chapter.spineIndex,
            chapterId = chapter.id,
            href = chapter.href,
            html = html,
            isFixedLayout = fxl.isFixedLayout,
            fxlWidth = fxl.width,
            fxlHeight = fxl.height,
        )
    }

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

        /** Fallback text length when a chapter stored no character count: only ever a comparison. */
        const val CHARS_PER_WORD = 6L
    }
}
