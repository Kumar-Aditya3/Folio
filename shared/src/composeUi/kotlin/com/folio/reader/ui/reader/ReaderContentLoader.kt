package com.folio.reader.ui.reader

import com.folio.reader.model.Chapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Loads the open book's chapter HTML behind a bounded LRU cache. */
internal class ReaderContentLoader(
    private val scope: CoroutineScope,
    private val chapterContentProvider: suspend (bookId: String, chapterHref: String) -> String,
    private val chapterHtmlState: MutableStateFlow<String>,
    private val isLoadingContentState: MutableStateFlow<Boolean>,
    private val loadErrorState: MutableStateFlow<String?>,
    private val currentBookId: () -> String?,
    private val chapters: () -> List<Chapter>,
    private val currentChapterIndex: () -> Int
) {
    /** Access-order LRU of loaded chapter HTML keyed "bookId:chapterHref"; bounded to [MAX_HTML_CACHE] entries. */
    private val htmlCache = LinkedHashMap<String, String>(16, 0.75f, true)

    suspend fun loadChapterHtml() {
        val bookId = currentBookId() ?: return
        val chapter = chapters().getOrNull(currentChapterIndex()) ?: return
        isLoadingContentState.value = true
        loadErrorState.value = null
        try {
            val key = "$bookId:${chapter.href}"
            val html = htmlCache[key]
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
            chapterHtmlState.value = html
            if (html.isBlank()) {
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

    private companion object {
        const val MAX_HTML_CACHE = 12
    }
}
