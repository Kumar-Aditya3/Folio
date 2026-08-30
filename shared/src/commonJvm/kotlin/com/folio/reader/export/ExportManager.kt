package com.folio.reader.export

import com.folio.reader.database.BookRepository
import com.folio.reader.database.BookmarkRepository
import com.folio.reader.database.CollectionRepository
import com.folio.reader.database.HighlightRepository
import com.folio.reader.database.NoteRepository
import com.folio.reader.database.QuoteRepository
import com.folio.reader.database.ReadingPositionRepository
import com.folio.reader.database.ReadingSessionRepository
import com.folio.reader.database.RevisitRepository
import com.folio.reader.database.SeriesRepository
import com.folio.reader.database.SettingsRepository
import com.folio.reader.database.StatisticsRepository
import com.folio.reader.database.TagRepository
import com.folio.reader.model.Book
import com.folio.reader.model.Bookmark
import com.folio.reader.model.Chapter
import com.folio.reader.model.Collection as FolioCollection
import com.folio.reader.model.Highlight
import com.folio.reader.model.Note
import com.folio.reader.model.Quote
import com.folio.reader.model.ReadingPosition
import com.folio.reader.model.ReadingSession
import com.folio.reader.model.RevisitItem
import com.folio.reader.model.Series
import com.folio.reader.model.Tag
import com.folio.reader.settings.BookReaderSettings
import com.folio.reader.settings.ReaderSettings
import com.folio.reader.statistics.BookStatistics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class BookTagRef(val bookId: String, val tagId: String)

@Serializable
data class HighlightTagRef(val highlightId: String, val tagId: String)

@Serializable
data class BookCollectionRef(val bookId: String, val collectionId: String)

@Serializable
data class FolioBackupV2(
    val version: Int = 2,
    val exportedAt: String,
    val deviceId: String,
    val appVersion: String,
    val books: List<Book> = emptyList(),
    val chapters: Map<String, List<Chapter>> = emptyMap(),
    val positions: List<ReadingPosition> = emptyList(),
    val sessions: List<ReadingSession> = emptyList(),
    val highlights: List<Highlight> = emptyList(),
    val notes: List<Note> = emptyList(),
    val bookmarks: List<Bookmark> = emptyList(),
    val tags: List<Tag> = emptyList(),
    val bookTags: List<BookTagRef> = emptyList(),
    val highlightTags: List<HighlightTagRef> = emptyList(),
    val collections: List<FolioCollection> = emptyList(),
    val bookCollections: List<BookCollectionRef> = emptyList(),
    val series: List<Series> = emptyList(),
    val quotes: List<Quote> = emptyList(),
    val revisitItems: List<RevisitItem> = emptyList(),
    val globalSettings: ReaderSettings,
    val bookSettings: Map<String, BookReaderSettings> = emptyMap(),
    val bookStatistics: Map<String, BookStatistics> = emptyMap()
)

@Serializable
data class BookAnnotationExport(
    val bookId: String,
    val title: String,
    val subtitle: String?,
    val authors: List<String>,
    val highlights: List<Highlight>,
    val notes: List<Note>,
    val bookmarks: List<Bookmark>,
    val quotes: List<Quote>
)

@Serializable
data class AnnotationExportFile(
    val version: Int = 1,
    val exportedAt: String,
    val books: List<BookAnnotationExport>
)

enum class RestoreMode { MERGE, REPLACE }

data class RestoreSummary(
    val booksRestored: Int,
    val highlightsRestored: Int,
    val notesRestored: Int,
    val bookmarksRestored: Int,
    val tagsRestored: Int,
    val collectionsRestored: Int,
    val quotesRestored: Int,
    val revisitRestored: Int,
    val errors: List<String>
)

class ExportManager(
    private val bookRepository: BookRepository,
    private val positionRepository: ReadingPositionRepository,
    private val sessionRepository: ReadingSessionRepository,
    private val highlightRepository: HighlightRepository,
    private val noteRepository: NoteRepository,
    private val bookmarkRepository: BookmarkRepository,
    private val tagRepository: TagRepository,
    private val collectionRepository: CollectionRepository,
    private val seriesRepository: SeriesRepository,
    private val quoteRepository: QuoteRepository,
    private val revisitRepository: RevisitRepository,
    private val settingsRepository: SettingsRepository,
    private val statisticsRepository: StatisticsRepository? = null,
    private val appVersion: String = "1.0.0"
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun createFullBackup(destination: File, deviceId: String): Result<FolioBackupV2> {
        return withContext(Dispatchers.IO) {
            try {
                val backup = buildBackup(deviceId)
                destination.parentFile?.mkdirs()
                destination.writeText(json.encodeToString(backup))
                Result.success(backup)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun restoreBackup(source: File, mode: RestoreMode): Result<RestoreSummary> {
        return withContext(Dispatchers.IO) {
            try {
                val backup = json.decodeFromString<FolioBackupV2>(source.readText())
                Result.success(applyBackup(backup, mode))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    fun getBackupInfo(source: File): Result<FolioBackupV2> {
        return try {
            Result.success(json.decodeFromString<FolioBackupV2>(source.readText()))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun exportAnnotationsMarkdown(destination: File, book: Book?): Result<File> {
        return withContext(Dispatchers.IO) {
            try {
                val bundles = loadAnnotationBundles(book)
                val exportedAt = formatDate(Clock.System.now())
                val sb = StringBuilder()
                bundles.forEachIndexed { index, bundle ->
                    if (index > 0) sb.append("\n---\n\n")
                    sb.append("# Annotations — ").append(bundle.book.displayTitle).append("\n")
                    sb.append("_Author: ")
                        .append(bundle.book.displayAuthor.ifBlank { "Unknown" })
                        .append(" | Exported: ")
                        .append(exportedAt)
                        .append("_\n")
                    val titlesBySpine = bundle.chapters.associate { it.spineIndex to it.title }
                    val spineByChapterId = bundle.chapters.associate { it.id to it.spineIndex }
                    val spines = buildSet {
                        bundle.highlights.forEach { add(it.spineIndex) }
                        bundle.notes.forEach { add(it.spineIndex ?: return@forEach) }
                        bundle.bookmarks.forEach { add(it.spineIndex) }
                        bundle.quotes.forEach { spineByChapterId[it.chapterId]?.let(::add) }
                    }.sorted()
                    for (spine in spines) {
                        val title = titlesBySpine[spine]
                        sb.append("\n## ")
                            .append(spine + 1)
                            .append(if (title.isNullOrBlank()) "" else " — ")
                            .append(if (title.isNullOrBlank()) "" else title)
                            .append("\n")
                        val chapterHighlights = bundle.highlights.filter { it.spineIndex == spine }
                        val chapterNotes = bundle.notes.filter { it.spineIndex == spine }
                        val chapterBookmarks = bundle.bookmarks.filter { it.spineIndex == spine }
                        val chapterQuotes =
                            bundle.quotes.filter { spineByChapterId[it.chapterId] == spine }
                        if (chapterHighlights.isNotEmpty()) {
                            sb.append("\n### Highlights\n")
                            for (h in chapterHighlights) {
                                sb.append("- > \"").append(h.selectedText).append("\" _(")
                                    .append(colorName(h)).append(", ")
                                    .append(formatDate(h.createdAt))
                                    .append(")_\n")
                            }
                        }
                        if (chapterNotes.isNotEmpty()) {
                            sb.append("\n### Notes\n")
                            for (n in chapterNotes) {
                                sb.append("- **Note**: ").append(n.content.replace('\n', ' '))
                                    .append(" _(").append(formatDate(n.createdAt)).append(")_\n")
                            }
                        }
                        if (chapterBookmarks.isNotEmpty()) {
                            sb.append("\n### Bookmarks\n")
                            for (b in chapterBookmarks) {
                                sb.append("- 🔖 ").append(b.label ?: b.locator).append("\n")
                            }
                        }
                        if (chapterQuotes.isNotEmpty()) {
                            sb.append("\n### Quotes\n")
                            for (q in chapterQuotes) {
                                sb.append("- > \"").append(q.text).append("\"")
                                q.note?.let { sb.append(" — ").append(it.replace('\n', ' ')) }
                                sb.append("\n")
                            }
                        }
                    }
                }
                destination.parentFile?.mkdirs()
                destination.writeText(sb.toString())
                Result.success(destination)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun exportAnnotationsJson(destination: File, book: Book?): Result<File> {
        return withContext(Dispatchers.IO) {
            try {
                val bundles = loadAnnotationBundles(book)
                val export = AnnotationExportFile(
                    exportedAt = Clock.System.now().toString(),
                    books = bundles.map { bundle ->
                        BookAnnotationExport(
                            bookId = bundle.book.id,
                            title = bundle.book.displayTitle,
                            subtitle = bundle.book.subtitle,
                            authors = bundle.book.authors,
                            highlights = bundle.highlights,
                            notes = bundle.notes,
                            bookmarks = bundle.bookmarks,
                            quotes = bundle.quotes
                        )
                    }
                )
                destination.parentFile?.mkdirs()
                destination.writeText(json.encodeToString(export))
                Result.success(destination)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun exportAnnotationsCsv(destination: File, book: Book?): Result<File> {
        return withContext(Dispatchers.IO) {
            try {
                val bundles = loadAnnotationBundles(book)
                val rows = mutableListOf("type,book,chapter_spine,text,color,created_at,device_id")
                for (bundle in bundles) {
                    val bookName = bundle.book.displayTitle
                    val spineByChapterId = bundle.chapters.associate { it.id to it.spineIndex }
                    for (h in bundle.highlights) {
                        rows += listOf(
                            "highlight",
                            bookName,
                            h.spineIndex.toString(),
                            h.selectedText,
                            colorName(h),
                            h.createdAt.toString(),
                            h.deviceId
                        ).toCsvRow()
                    }
                    for (n in bundle.notes) {
                        rows += listOf(
                            "note",
                            bookName,
                            n.spineIndex?.toString() ?: "",
                            n.content,
                            "",
                            n.createdAt.toString(),
                            n.deviceId
                        ).toCsvRow()
                    }
                    for (b in bundle.bookmarks) {
                        rows += listOf(
                            "bookmark",
                            bookName,
                            b.spineIndex.toString(),
                            b.label ?: b.locator,
                            "",
                            b.createdAt.toString(),
                            b.deviceId
                        ).toCsvRow()
                    }
                    for (q in bundle.quotes) {
                        rows += listOf(
                            "quote",
                            bookName,
                            (spineByChapterId[q.chapterId] ?: -1).toString(),
                            q.text,
                            "",
                            q.createdAt.toString(),
                            q.deviceId
                        ).toCsvRow()
                    }
                }
                destination.parentFile?.mkdirs()
                destination.writeText(rows.joinToString("\n") + "\n")
                Result.success(destination)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    private suspend fun buildBackup(deviceId: String): FolioBackupV2 {
        val books = bookRepository.getAllBooks().first()
        val chaptersByBook = LinkedHashMap<String, List<Chapter>>()
        val positions = mutableListOf<ReadingPosition>()
        val sessions = mutableListOf<ReadingSession>()
        val highlights = mutableListOf<Highlight>()
        val notes = mutableListOf<Note>()
        val bookmarks = mutableListOf<Bookmark>()
        val bookTags = mutableListOf<BookTagRef>()
        val highlightTags = mutableListOf<HighlightTagRef>()
        val seenHighlightTagPairs = HashSet<Pair<String, String>>()
        val bookCollections = mutableListOf<BookCollectionRef>()
        val revisitById = LinkedHashMap<String, RevisitItem>()
        val bookSettings = LinkedHashMap<String, BookReaderSettings>()

        val unresolvedRevisit = revisitRepository.getUnresolvedRevisitItems().first()

        for (book in books) {
            chaptersByBook[book.id] = bookRepository.getChaptersForBook(book.id)
            positions += positionRepository.getAllPositionsForBook(book.id)
            sessions += sessionRepository.getSessionsForBook(book.id).first()
            val bookHighlights = highlightRepository.getHighlightsForBook(book.id).first()
            highlights += bookHighlights
            notes += noteRepository.getNotesForBook(book.id).first()
            bookmarks += bookmarkRepository.getBookmarksForBook(book.id).first()
            tagRepository.getTagsForBook(book.id).forEach { tag ->
                bookTags += BookTagRef(book.id, tag.id)
            }
            for (highlight in bookHighlights) {
                tagRepository.getTagsForHighlight(highlight.id).forEach { tag ->
                    if (seenHighlightTagPairs.add(highlight.id to tag.id)) {
                        highlightTags += HighlightTagRef(highlight.id, tag.id)
                    }
                }
            }
            collectionRepository.getCollectionsForBook(book.id).forEach { collection ->
                bookCollections += BookCollectionRef(book.id, collection.id)
            }
            revisitRepository.getRevisitItemsForBook(book.id).forEach { revisitById[it.id] = it }
            settingsRepository.getBookSettings(book.id)?.let { bookSettings[book.id] = it }
        }
        unresolvedRevisit.forEach { revisitById[it.id] = it }

        val tags = tagRepository.getAllTags().first()
        val collections = collectionRepository.getAllCollections().first()
        val series = seriesRepository.getAllSeries().first()
        val quotes = quoteRepository.getAllQuotes().first()

        val statsRepo = statisticsRepository
        val bookStatistics = if (statsRepo != null) {
            buildMap {
                for (book in books) {
                    statsRepo.getBookStats(book.id)?.let { put(book.id, it) }
                }
            }
        } else {
            emptyMap()
        }

        return FolioBackupV2(
            exportedAt = Clock.System.now().toString(),
            deviceId = deviceId,
            appVersion = appVersion,
            books = books,
            chapters = chaptersByBook,
            positions = positions,
            sessions = sessions,
            highlights = highlights,
            notes = notes,
            bookmarks = bookmarks,
            tags = tags,
            bookTags = bookTags.distinctBy { it.bookId to it.tagId },
            highlightTags = highlightTags,
            collections = collections,
            bookCollections = bookCollections.distinctBy { it.bookId to it.collectionId },
            series = series,
            quotes = quotes,
            revisitItems = revisitById.values.toList(),
            globalSettings = settingsRepository.getGlobalSettings().withoutCredentials(),
            bookSettings = bookSettings,
            bookStatistics = bookStatistics
        )
    }

    /** Credentials stay on the device — backups are shared/plain files. */
    private fun com.folio.reader.settings.ReaderSettings.withoutCredentials() = copy(
        firebaseApiKey = "",
        syncAccountEmail = "",
        syncAccountPassword = ""
    )

    private suspend fun applyBackup(backup: FolioBackupV2, mode: RestoreMode): RestoreSummary {
        val errors = mutableListOf<String>()
        var booksRestored = 0
        var highlightsRestored = 0
        var notesRestored = 0
        var bookmarksRestored = 0
        var tagsRestored = 0
        var collectionsRestored = 0
        var quotesRestored = 0
        var revisitRestored = 0

        if (mode == RestoreMode.REPLACE) {
            bookRepository.getAllBooks().first().forEach { book ->
                runCatching { bookRepository.deleteBook(book.id) }
                    .onFailure { errors += "Failed to delete book ${book.id}: ${it.message}" }
            }
            collectionRepository.getAllCollections().first().forEach { collection ->
                runCatching { collectionRepository.deleteCollection(collection.id) }
                    .onFailure { errors += "Failed to delete collection ${collection.id}: ${it.message}" }
            }
            seriesRepository.getAllSeries().first().forEach { series ->
                runCatching { seriesRepository.deleteSeries(series.id) }
                    .onFailure { errors += "Failed to delete series ${series.id}: ${it.message}" }
            }
        }

        for (book in backup.books) {
            try {
                val exists = bookRepository.getBook(book.id) != null
                if (mode == RestoreMode.MERGE && exists) continue
                if (exists) bookRepository.updateBook(book) else bookRepository.insertBook(book)
                booksRestored++
            } catch (e: Exception) {
                errors += "Failed to restore book ${book.id}: ${e.message}"
            }
        }

        for ((bookId, chapters) in backup.chapters) {
            if (chapters.isEmpty()) continue
            try {
                if (mode == RestoreMode.MERGE &&
                    bookRepository.getChaptersForBook(bookId).isNotEmpty()
                ) continue
                bookRepository.insertChapters(bookId, chapters)
            } catch (e: Exception) {
                errors += "Failed to restore chapters for book $bookId: ${e.message}"
            }
        }

        for (series in backup.series) {
            try {
                val exists = seriesRepository.getSeries(series.id) != null
                if (mode == RestoreMode.MERGE && exists) continue
                if (exists) seriesRepository.updateSeries(series) else seriesRepository.insertSeries(series)
            } catch (e: Exception) {
                errors += "Failed to restore series ${series.id}: ${e.message}"
            }
        }

        val existingTagIds = tagRepository.getAllTags().first().map { it.id }.toSet()
        for (tag in backup.tags) {
            try {
                if (tag.id in existingTagIds) continue
                tagRepository.insertTag(tag)
                tagsRestored++
            } catch (e: Exception) {
                errors += "Failed to restore tag ${tag.id}: ${e.message}"
            }
        }

        val existingCollectionIds =
            collectionRepository.getAllCollections().first().map { it.id }.toSet()
        for (collection in backup.collections) {
            try {
                if (mode == RestoreMode.MERGE && collection.id in existingCollectionIds) continue
                collectionRepository.insertCollection(collection)
                collectionsRestored++
            } catch (e: Exception) {
                errors += "Failed to restore collection ${collection.id}: ${e.message}"
            }
        }

        val bookTagCache = HashMap<String, Set<String>>()
        for (ref in backup.bookTags) {
            try {
                if (bookRepository.getBook(ref.bookId) == null) continue
                val existing = bookTagCache.getOrPut(ref.bookId) {
                    tagRepository.getTagsForBook(ref.bookId).map { it.id }.toSet()
                }
                if (mode == RestoreMode.MERGE && ref.tagId in existing) continue
                tagRepository.addTagToBook(ref.bookId, ref.tagId)
            } catch (e: Exception) {
                errors += "Failed to link tag ${ref.tagId} to book ${ref.bookId}: ${e.message}"
            }
        }

        for (position in backup.positions) {
            try {
                val exists =
                    positionRepository.getPosition(position.bookId, position.deviceId) != null
                if (mode == RestoreMode.MERGE && exists) continue
                positionRepository.upsertPosition(position)
            } catch (e: Exception) {
                errors += "Failed to restore position for book ${position.bookId}: ${e.message}"
            }
        }

        val sessionIdCache = HashMap<String, Set<String>>()
        for (session in backup.sessions) {
            try {
                val existing = sessionIdCache.getOrPut(session.bookId) {
                    sessionRepository.getSessionsForBook(session.bookId).first().map { it.id }.toSet()
                }
                if (mode == RestoreMode.MERGE && session.id in existing) continue
                sessionRepository.insertSession(session)
            } catch (e: Exception) {
                errors += "Failed to restore session ${session.id}: ${e.message}"
            }
        }

        for (highlight in backup.highlights) {
            try {
                if (mode == RestoreMode.MERGE &&
                    highlightRepository.getHighlight(highlight.id) != null
                ) continue
                highlightRepository.insertHighlight(highlight)
                highlightsRestored++
            } catch (e: Exception) {
                errors += "Failed to restore highlight ${highlight.id}: ${e.message}"
            }
        }

        for (note in backup.notes) {
            try {
                if (mode == RestoreMode.MERGE && noteRepository.getNote(note.id) != null) continue
                noteRepository.insertNote(note)
                notesRestored++
            } catch (e: Exception) {
                errors += "Failed to restore note ${note.id}: ${e.message}"
            }
        }

        for (bookmark in backup.bookmarks) {
            try {
                if (mode == RestoreMode.MERGE &&
                    bookmarkRepository.getBookmark(bookmark.id) != null
                ) continue
                bookmarkRepository.insertBookmark(bookmark)
                bookmarksRestored++
            } catch (e: Exception) {
                errors += "Failed to restore bookmark ${bookmark.id}: ${e.message}"
            }
        }

        val highlightTagCache = HashMap<String, Set<String>>()
        for (ref in backup.highlightTags) {
            try {
                if (highlightRepository.getHighlight(ref.highlightId) == null) continue
                val existing = highlightTagCache.getOrPut(ref.highlightId) {
                    tagRepository.getTagsForHighlight(ref.highlightId).map { it.id }.toSet()
                }
                if (mode == RestoreMode.MERGE && ref.tagId in existing) continue
                tagRepository.addTagToHighlight(ref.highlightId, ref.tagId)
            } catch (e: Exception) {
                errors += "Failed to link tag ${ref.tagId} to highlight ${ref.highlightId}: ${e.message}"
            }
        }

        for (ref in backup.bookCollections) {
            try {
                if (bookRepository.getBook(ref.bookId) == null) continue
                if (collectionRepository.getCollectionsForBook(ref.bookId).none { it.id == ref.collectionId } ||
                    mode == RestoreMode.REPLACE
                ) {
                    collectionRepository.addBookToCollection(ref.bookId, ref.collectionId)
                }
            } catch (e: Exception) {
                errors += "Failed to add book ${ref.bookId} to collection ${ref.collectionId}: ${e.message}"
            }
        }

        val quoteIdCache = HashMap<String, Set<String>>()
        for (quote in backup.quotes) {
            try {
                val existing = quoteIdCache.getOrPut(quote.bookId) {
                    quoteRepository.getQuotesForBook(quote.bookId).first().map { it.id }.toSet()
                }
                if (mode == RestoreMode.MERGE && quote.id in existing) continue
                quoteRepository.insertQuote(quote)
                quotesRestored++
            } catch (e: Exception) {
                errors += "Failed to restore quote ${quote.id}: ${e.message}"
            }
        }

        val revisitIdCache = HashMap<String, Set<String>>()
        for (item in backup.revisitItems) {
            try {
                val existing = revisitIdCache.getOrPut(item.bookId) {
                    revisitRepository.getRevisitItemsForBook(item.bookId).map { it.id }.toSet()
                }
                if (mode == RestoreMode.MERGE && item.id in existing) continue
                revisitRepository.insertRevisitItem(item)
                revisitRestored++
            } catch (e: Exception) {
                errors += "Failed to restore revisit item ${item.id}: ${e.message}"
            }
        }

        val statsRepo = statisticsRepository
        if (statsRepo != null) {
            for ((bookId, stats) in backup.bookStatistics) {
                try {
                    if (mode == RestoreMode.MERGE && statsRepo.getBookStats(bookId) != null) continue
                    statsRepo.upsertBookStats(stats)
                } catch (e: Exception) {
                    errors += "Failed to restore statistics for book $bookId: ${e.message}"
                }
            }
        }

        try {
            // Older backups may still carry credentials; never restore them.
            settingsRepository.saveGlobalSettings(backup.globalSettings.withoutCredentials())
        } catch (e: Exception) {
            errors += "Failed to restore global settings: ${e.message}"
        }
        for ((bookId, settings) in backup.bookSettings) {
            try {
                settingsRepository.saveBookSettings(bookId, settings)
            } catch (e: Exception) {
                errors += "Failed to restore settings for book $bookId: ${e.message}"
            }
        }

        return RestoreSummary(
            booksRestored = booksRestored,
            highlightsRestored = highlightsRestored,
            notesRestored = notesRestored,
            bookmarksRestored = bookmarksRestored,
            tagsRestored = tagsRestored,
            collectionsRestored = collectionsRestored,
            quotesRestored = quotesRestored,
            revisitRestored = revisitRestored,
            errors = errors
        )
    }

    private data class AnnotationBundle(
        val book: Book,
        val chapters: List<Chapter>,
        val highlights: List<Highlight>,
        val notes: List<Note>,
        val bookmarks: List<Bookmark>,
        val quotes: List<Quote>
    )

    private suspend fun loadAnnotationBundles(target: Book?): List<AnnotationBundle> {
        val books = if (target != null) listOf(target) else bookRepository.getAllBooks().first()
        return books.map { book ->
            AnnotationBundle(
                book = book,
                chapters = bookRepository.getChaptersForBook(book.id),
                highlights = highlightRepository.getHighlightsForBook(book.id).first()
                    .sortedWith(
                        compareBy({ it.spineIndex }, { it.createdAt.toEpochMilliseconds() })
                    ),
                notes = noteRepository.getNotesForBook(book.id).first()
                    .sortedWith(
                        compareBy({ it.spineIndex ?: Int.MAX_VALUE }, { it.createdAt.toEpochMilliseconds() })
                    ),
                bookmarks = bookmarkRepository.getBookmarksForBook(book.id).first()
                    .sortedWith(
                        compareBy({ it.spineIndex }, { it.createdAt.toEpochMilliseconds() })
                    ),
                quotes = quoteRepository.getQuotesForBook(book.id).first()
                    .sortedBy { it.createdAt.toEpochMilliseconds() }
            )
        }
    }

    private fun colorName(highlight: Highlight): String {
        val custom = highlight.customColor
        return if (custom != null) {
            "#%06X".format(custom and 0xFFFFFF)
        } else {
            highlight.color.label
        }
    }

    private fun formatDate(instant: Instant): String = instant.toString().take(10)

    private fun List<String>.toCsvRow(): String = joinToString(",") { escapeCsvField(it) }

    private fun escapeCsvField(field: String): String {
        return if (field.contains(',') || field.contains('"') ||
            field.contains('\n') || field.contains('\r')
        ) {
            "\"" + field.replace("\"", "\"\"") + "\""
        } else {
            field
        }
    }
}
