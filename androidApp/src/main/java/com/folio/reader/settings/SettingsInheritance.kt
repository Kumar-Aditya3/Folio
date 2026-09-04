package com.folio.reader.settings

import com.folio.reader.AppGraph
import com.folio.reader.ui.manga.KEY_MANGA_READER_DEFAULT_MODE
import com.folio.reader.ui.manga.resolveMangaReaderMode
import kotlinx.coroutines.flow.first

/**
 * Who is still ignoring the global defaults, and how to make them stop.
 *
 * A book takes its own copy of the reading settings the first time it opens
 * (`ReaderSettings.toBookSettings`), which is why editing Typography or Layout
 * appears to do nothing to a book already in progress. The behaviour is right —
 * nobody wants their current read re-typeset from under them — but it needs an
 * escape hatch, and an escape hatch needs a count to be honest about.
 *
 * Field names are the ones `BookReaderSettings.overriddenFields` and
 * `BookReaderSettings.clearing` speak, so each settings screen can scope both the
 * count and the reset to exactly what it governs.
 */

/** What the Typography screen governs. */
internal val TYPE_FIELDS = setOf(
    "fontFamily", "fontSize", "fontWeight", "lineHeight",
    "letterSpacing", "wordSpacing", "paragraphSpacing",
)

/** What the Layout screen governs. */
internal val MEASURE_FIELDS = setOf("textWidth", "margins")

/** Everything a book can hold its own copy of. */
internal val ALL_INHERITED_FIELDS = TYPE_FIELDS + MEASURE_FIELDS + setOf(
    "themeId", "customTheme", "layoutMode",
    "showChapterTitle", "showProgress", "showClock", "highlightColorIndex",
)

/**
 * Ids of books whose stored snapshot differs from the current defaults in any of
 * [fields]. A snapshot that merely *repeats* the default is not an override —
 * counting those would report every book ever opened.
 */
internal suspend fun booksOverriding(graph: AppGraph, fields: Set<String>): List<String> {
    val global = runCatching { graph.settingsRepository.getGlobalSettings() }.getOrNull()
        ?: return emptyList()
    val books = runCatching { graph.bookRepository.getAllBooks().first() }.getOrDefault(emptyList())
    return books.mapNotNull { book ->
        val stored = runCatching { graph.settingsRepository.getBookSettings(book.id) }.getOrNull()
            ?: return@mapNotNull null
        if (stored.overriddenFields(global).any { it in fields }) book.id else null
    }
}

/**
 * Drops [fields] from every book's snapshot, so the defaults reach them. The row
 * is kept rather than deleted: an all-null snapshot means "follows the defaults",
 * while no snapshot at all would be re-seeded from the defaults at the next open
 * — same result today, but it would silently re-freeze them tomorrow.
 */
internal suspend fun clearBookOverrides(graph: AppGraph, fields: Set<String>) {
    val books = runCatching { graph.bookRepository.getAllBooks().first() }.getOrDefault(emptyList())
    for (book in books) {
        val stored = runCatching { graph.settingsRepository.getBookSettings(book.id) }.getOrNull()
            ?: continue
        runCatching {
            graph.settingsRepository.saveBookSettings(book.id, stored.clearing(fields))
        }
    }
}

/**
 * Ids of library manga whose saved reader mode differs from the global default.
 * The reader writes the resolved mode back on first open, so equality with the
 * default has to be excluded here too.
 */
internal suspend fun mangaOverridingMode(graph: AppGraph): List<String> {
    val defaultName = runCatching {
        resolveMangaReaderMode(
            mangaModeName = null,
            defaultModeName = graph.settingsRepository.getRaw(KEY_MANGA_READER_DEFAULT_MODE),
        ).name
    }.getOrNull() ?: return emptyList()
    val library = runCatching { graph.mangaRepository.observeLibrary().first() }
        .getOrDefault(emptyList())
    return library.mapNotNull { manga ->
        val saved = runCatching {
            graph.settingsRepository.getRaw("$KEY_MANGA_READER_DEFAULT_MODE.${manga.id}")
        }.getOrNull()
        if (!saved.isNullOrBlank() && saved != defaultName) manga.id else null
    }
}

/**
 * Clears every per-manga mode. Blank rather than absent for the same reason as
 * above, and because `resolveMangaReaderMode` already treats an unparseable
 * value as "no choice saved".
 */
internal suspend fun clearMangaModes(graph: AppGraph) {
    val library = runCatching { graph.mangaRepository.observeLibrary().first() }
        .getOrDefault(emptyList())
    for (manga in library) {
        runCatching {
            graph.settingsRepository.setRaw("$KEY_MANGA_READER_DEFAULT_MODE.${manga.id}", "")
        }
    }
}
