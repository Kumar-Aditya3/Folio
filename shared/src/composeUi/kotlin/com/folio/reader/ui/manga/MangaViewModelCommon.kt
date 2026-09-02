package com.folio.reader.ui.manga

import com.folio.reader.manga.ChapterNumberParser
import com.folio.reader.manga.MangaBrowseItem
import com.folio.reader.manga.MangaChapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Folio ViewModels are plain Kotlin classes; this keeps their scope style consistent. */
internal fun mangaVmScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/** Library-level "Downloaded" mode: the shelf stays whole, but detail screens
 *  narrow their chapter lists to downloaded chapters while this is on. */
internal const val KEY_LIBRARY_DOWNLOADED_FILTER = "manga.library.downloaded.filter"
/**
 * Story position of a chapter. Sources disagree on list direction (some fetch
 * newest-first, so sortOrder 0 is the LATEST chapter) and many leave
 * chapter_number unset, so the number is recovered from the title when needed.
 * Chapters without any recognizable number sort after numbered ones by source
 * list position.
 */
private fun storyNumber(chapter: MangaChapter): Float =
    if (chapter.chapterNumber >= 0f) chapter.chapterNumber else ChapterNumberParser.parse(chapter.name)

/**
 * The reading order of the story, independent of how the source lists chapters
 * or how the detail screen displays them. Continue, mark-previous-as-read and
 * reader navigation follow this; the display sort toggle only arranges the list.
 */
internal val STORY_ORDER = Comparator<MangaChapter> { a, b ->
    val an = storyNumber(a)
    val bn = storyNumber(b)
    when {
        an >= 0f && bn >= 0f -> {
            val byNumber = an.compareTo(bn)
            if (byNumber != 0) byNumber else a.sortOrder.compareTo(b.sortOrder)
        }
        an >= 0f -> -1
        bn >= 0f -> 1
        else -> a.sortOrder.compareTo(b.sortOrder)
    }
}

enum class ChapterFilter(val label: String) {
    ALL("All chapters"),
    HIDE_READ("Hide read"),
    UNREAD("Unread only"),
    DOWNLOADED("Downloaded only"),
    BOOKMARKED("Bookmarked only"),
}

fun filterChapters(list: List<MangaChapter>, filter: ChapterFilter): List<MangaChapter> =
    when (filter) {
        ChapterFilter.ALL -> list
        ChapterFilter.HIDE_READ -> list.filter { !it.read }
        ChapterFilter.UNREAD -> list.filter { !it.read }
        ChapterFilter.DOWNLOADED -> list.filter { it.downloadedPages > 0 }
        ChapterFilter.BOOKMARKED -> list.filter { it.bookmarked }
    }

internal const val KEY_CHAPTER_FILTER = "manga.chapter.filter"
internal const val KEY_CHAPTER_SORT = "manga.chapter.sort"

// ---------- Search relevance ----------

/**
 * Relevance ranking for manga search results. Sources return matches in their own
 * order (popularity, freshness, …), which regularly buries the exact title match
 * under fuzzy ones. This scores each result against the query and sorts stably, so
 * equal scores keep the source's own ordering (source-specific behavior preserved).
 */
internal object MangaSearchRanker {

    /** Lowercase, letters/digits only, runs of other characters collapse to one space. */
    fun normalize(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text.lowercase()) {
            if (ch.isLetterOrDigit()) {
                sb.append(ch)
            } else if (sb.isNotEmpty() && sb.last() != ' ') {
                sb.append(' ')
            }
        }
        return sb.toString().trim()
    }

    /** Higher = closer match. 0 means "the source returned it, nothing textual matched". */
    fun score(query: String, title: String): Int {
        val q = normalize(query)
        val t = normalize(title)
        if (q.isEmpty() || t.isEmpty()) return 0
        if (t == q) return 100
        if (t.startsWith(q)) return 85
        val qTokens = q.split(' ').filter { it.isNotEmpty() }
        val tTokens = t.split(' ').filter { it.isNotEmpty() }
        if (qTokens.isEmpty()) return 0
        if (qTokens.all { qw -> tTokens.any { it.startsWith(qw) } }) return 70
        if (qTokens.all { qw -> t.contains(qw) }) return 55
        if (qTokens.any { qw -> tTokens.any { it.startsWith(qw) } }) return 40
        if (qTokens.any { qw -> t.contains(qw) }) return 25
        return 0
    }

    /** Stable descending sort: stronger matches first, source order breaks ties. */
    fun rank(items: List<MangaBrowseItem>, query: String): List<MangaBrowseItem> {
        if (query.isBlank()) return items
        return items.sortedByDescending { score(query, it.title) }
    }
}
