package com.folio.reader.nav

/**
 * Navigation destinations for the Android app (§3.2–§3.3 FOLIO_IMPLEMENTATION_SPEC).
 *
 * Four bottom-bar destinations (never more) plus pushed routes that hide the bar.
 * Route strings follow the exact patterns mandated by the spec; arguments are
 * declared in [FolioNavArgs] so parsing stays in one place.
 */
object FolioRoutes {
    // Bottom bar — exactly these four, left to right (§3.3).
    const val HOME = "home"
    const val LIBRARY = "library"
    const val STATS = "stats"
    const val MORE = "more"

    // Pushed routes (bar hidden, §3.3).
    const val READER = "reader/{bookId}?spine={spine}&frac={frac}"
    const val DOCUMENT_READER = "document/{documentId}"
    const val BOOK_DETAIL = "book/{bookId}"
    const val MANGA_DETAIL = "manga/{mangaId}"
    const val MANGA_READER = "mangaReader/{mangaId}/{chapterId}"
    const val MANGA_SOURCE_BROWSE = "source/{sourceId}?query={query}"
    const val SEARCH = "search"
    /**
     * Search opened *from the reader*.
     *
     * A separate destination from [SEARCH] because the two have different backs and the nav
     * stack is the only thing that can tell them apart. Both are pushed on top of the reader,
     * so leaving either pops back to it — but search opened from the Library must return to
     * the Library, and search opened from the reader must return to the reader. What actually
     * went wrong is subtler than that, though: the reader is reachable from Home *and* from
     * the Library, and when it is opened from Home the underlying tab is Home, so popping to
     * it landed the reader — and then the app — on Home however the reader was entered.
     *
     * This destination exists so the pair (reader, search) can be popped together and land on
     * a named tab. See `FolioNavHost`'s `pairReturn`.
     */
    const val SEARCH_FROM_READER = "search/reader"
    const val SETTINGS = "settings/{category}"
    const val EXTENSIONS = "extensions"
    const val MANGA_BROWSE = "mangaBrowse"
    const val MANGA_DOWNLOADS = "mangaDownloads"
    const val MANGA_HISTORY = "mangaHistory"
    const val TAGS = "tags"
    const val QUOTES = "quotes"
    const val REVISIT = "revisit"

    /** Atlas — the full-screen semantic map of the library. Pushed, bar hidden. */
    const val ATLAS = "atlas"

    /** Routes on which the bottom bar is visible — nothing else (§3.3). */
    val BAR_ROUTES = setOf(HOME, LIBRARY, STATS, MORE)
}

/** Builds route strings for parameterized destinations. */
object FolioDestination {
    // IDs can embed file paths (local manga chapters), whose '/' would otherwise
    // split the route into extra segments and fail graph matching; navigation
    // decodes path arguments again when extracting them.
    private fun enc(value: String): String = android.net.Uri.encode(value)

    /**
     * @param fractionMille intra-chapter landing as a fraction of the chapter, in per-mille
     *   (0..1000), or null for "chapter top". Per-mille keeps it an [Int] nav arg like [spine]
     *   rather than encoding a float into the route.
     */
    fun reader(bookId: String, spine: Int? = null, fractionMille: Int? = null): String =
        "reader/${enc(bookId)}?spine=${spine ?: -1}&frac=${fractionMille ?: -1}"

    fun documentReader(documentId: String): String =
        "document/${enc(documentId)}"

    fun bookDetail(bookId: String): String = "book/${enc(bookId)}"

    fun mangaDetail(mangaId: String): String = "manga/${enc(mangaId)}"

    fun mangaReader(mangaId: String, chapterId: String): String =
        "mangaReader/${enc(mangaId)}/${enc(chapterId)}"

    fun mangaSourceBrowse(sourceId: Long, query: String = ""): String =
        "source/$sourceId?query=${java.net.URLEncoder.encode(query, "UTF-8")}"

    fun settings(category: String): String = "settings/$category"
}

/** Argument names for route parameters — single source of truth. */
object FolioNavArgs {
    const val BOOK_ID = "bookId"
    const val DOCUMENT_ID = "documentId"
    const val SPINE = "spine"
    const val FRAC = "frac"
    const val MANGA_ID = "mangaId"
    const val CHAPTER_ID = "chapterId"
    const val SOURCE_ID = "sourceId"
    const val QUERY = "query"
    const val CATEGORY = "category"
}
