package com.folio.reader.firebase

import com.folio.reader.model.*
import com.folio.reader.settings.ReaderSettings
import com.folio.reader.sync.SyncOperation
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

// Firestore document models (prefix with Fs for Firestore)

@Serializable
data class FsBook(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val authors: List<String> = emptyList(),
    val publisher: String? = null,
    val language: String? = null,
    val isbn: String? = null,
    val description: String? = null,
    val publicationDate: Long? = null, // epoch ms
    val coverPath: String? = null,
    val epubHash: String,
    val epubFileSize: Long,
    val addedAt: Long,
    val lastOpenedAt: Long? = null,
    val totalCharacters: Long = 0,
    val totalWords: Long = 0,
    val chapterCount: Int = 0,
    val seriesId: String? = null,
    val seriesNumber: Double? = null,
    val status: Int = 0,
    val formattingMode: Int = 1,
    val updatedAt: Long = Clock.System.now().toEpochMilliseconds(),
    val deviceId: String,
    val isDeleted: Boolean = false
) {
    fun toBook(): Book {
        return Book(
            id = id,
            title = title,
            subtitle = subtitle,
            authors = authors,
            publisher = publisher,
            language = language,
            isbn = isbn,
            description = description,
            publicationDate = publicationDate?.let { Instant.fromEpochMilliseconds(it) },
            coverPath = coverPath,
            epubHash = epubHash,
            epubFileSize = epubFileSize,
            addedAt = Instant.fromEpochMilliseconds(addedAt),
            lastOpenedAt = lastOpenedAt?.let { Instant.fromEpochMilliseconds(it) },
            totalCharacters = totalCharacters,
            totalWords = totalWords,
            chapterCount = chapterCount,
            seriesId = seriesId,
            seriesNumber = seriesNumber,
            status = BookStatus.fromValue(status),
            formattingMode = FormattingMode.fromValue(formattingMode)
        )
    }

    companion object {
        fun fromBook(book: Book, deviceId: String): FsBook {
            return FsBook(
                id = book.id,
                title = book.title,
                subtitle = book.subtitle,
                authors = book.authors,
                publisher = book.publisher,
                language = book.language,
                isbn = book.isbn,
                description = book.description,
                publicationDate = book.publicationDate?.toEpochMilliseconds(),
                coverPath = book.coverPath,
                epubHash = book.epubHash,
                epubFileSize = book.epubFileSize,
                addedAt = book.addedAt.toEpochMilliseconds(),
                lastOpenedAt = book.lastOpenedAt?.toEpochMilliseconds(),
                totalCharacters = book.totalCharacters,
                totalWords = book.totalWords,
                chapterCount = book.chapterCount,
                seriesId = book.seriesId,
                seriesNumber = book.seriesNumber,
                status = book.status.value,
                formattingMode = book.formattingMode.value,
                updatedAt = book.updatedAt.toEpochMilliseconds(),
                deviceId = deviceId
            )
        }
    }
}

@Serializable
data class FsReadingPosition(
    val bookId: String,
    val deviceId: String,
    val chapterId: String,
    val spineIndex: Int,
    val contentLocator: String,
    val characterOffset: Int = 0,
    val paragraphIndex: Int = 0,
    val normalizedProgress: Double = 0.0,
    val chapterProgress: Double = 0.0,
    val scrollOffset: Double = 0.0,
    val updatedAt: Long = Clock.System.now().toEpochMilliseconds(),
    val previousPosition: String? = null // JSON
)

@Serializable
data class FsHighlight(
    val id: String,
    val bookId: String,
    val chapterId: String,
    val spineIndex: Int,
    val startLocator: String,
    val endLocator: String,
    val selectedText: String,
    val color: Int = 0,
    val customColor: Int? = null,
    val noteId: String? = null,
    val createdAt: Long,
    val updatedAt: Long = Clock.System.now().toEpochMilliseconds(),
    val deviceId: String,
    val isDeleted: Boolean = false,
    val deletedAt: Long? = null,
    val surroundingTextBefore: String? = null,
    val surroundingTextAfter: String? = null
) {
    fun toHighlight(): Highlight {
        return Highlight(
            id = id,
            bookId = bookId,
            chapterId = chapterId,
            spineIndex = spineIndex,
            startLocator = startLocator,
            endLocator = endLocator,
            selectedText = selectedText,
            color = HighlightColor.fromArgb(color),
            customColor = customColor,
            noteId = noteId,
            createdAt = Instant.fromEpochMilliseconds(createdAt),
            updatedAt = Instant.fromEpochMilliseconds(updatedAt),
            deviceId = deviceId,
            isDeleted = isDeleted,
            deletedAt = deletedAt?.let { Instant.fromEpochMilliseconds(it) },
            surroundingTextBefore = surroundingTextBefore,
            surroundingTextAfter = surroundingTextAfter
        )
    }

    companion object {
        fun fromHighlight(highlight: Highlight): FsHighlight {
            return FsHighlight(
                id = highlight.id,
                bookId = highlight.bookId,
                chapterId = highlight.chapterId,
                spineIndex = highlight.spineIndex,
                startLocator = highlight.startLocator,
                endLocator = highlight.endLocator,
                selectedText = highlight.selectedText,
                color = highlight.color.argb,
                customColor = highlight.customColor,
                noteId = highlight.noteId,
                createdAt = highlight.createdAt.toEpochMilliseconds(),
                updatedAt = highlight.updatedAt.toEpochMilliseconds(),
                deviceId = highlight.deviceId,
                isDeleted = highlight.isDeleted,
                deletedAt = highlight.deletedAt?.toEpochMilliseconds(),
                surroundingTextBefore = highlight.surroundingTextBefore,
                surroundingTextAfter = highlight.surroundingTextAfter
            )
        }
    }
}

@Serializable
data class FsNote(
    val id: String,
    val bookId: String,
    val chapterId: String? = null,
    val spineIndex: Int? = null,
    val locator: String? = null,
    val content: String,
    val type: Int = 0,
    val createdAt: Long,
    val updatedAt: Long = Clock.System.now().toEpochMilliseconds(),
    val deviceId: String,
    val isDeleted: Boolean = false,
    val deletedAt: Long? = null
) {
    fun toNote(): Note {
        return Note(
            id = id,
            bookId = bookId,
            chapterId = chapterId,
            spineIndex = spineIndex,
            locator = locator,
            content = content,
            type = NoteType.fromValue(type),
            createdAt = Instant.fromEpochMilliseconds(createdAt),
            updatedAt = Instant.fromEpochMilliseconds(updatedAt),
            deviceId = deviceId,
            isDeleted = isDeleted,
            deletedAt = deletedAt?.let { Instant.fromEpochMilliseconds(it) }
        )
    }

    companion object {
        fun fromNote(note: Note): FsNote {
            return FsNote(
                id = note.id,
                bookId = note.bookId,
                chapterId = note.chapterId,
                spineIndex = note.spineIndex,
                locator = note.locator,
                content = note.content,
                type = note.type.value,
                createdAt = note.createdAt.toEpochMilliseconds(),
                updatedAt = note.updatedAt.toEpochMilliseconds(),
                deviceId = note.deviceId,
                isDeleted = note.isDeleted,
                deletedAt = note.deletedAt?.toEpochMilliseconds()
            )
        }
    }
}

@Serializable
data class FsBookmark(
    val id: String,
    val bookId: String,
    val chapterId: String,
    val spineIndex: Int,
    val locator: String,
    val label: String? = null,
    val createdAt: Long,
    val updatedAt: Long = Clock.System.now().toEpochMilliseconds(),
    val deviceId: String,
    val isDeleted: Boolean = false,
    val deletedAt: Long? = null
) {
    fun toBookmark(): Bookmark {
        return Bookmark(
            id = id,
            bookId = bookId,
            chapterId = chapterId,
            spineIndex = spineIndex,
            locator = locator,
            label = label,
            createdAt = Instant.fromEpochMilliseconds(createdAt),
            updatedAt = Instant.fromEpochMilliseconds(updatedAt),
            deviceId = deviceId,
            isDeleted = isDeleted,
            deletedAt = deletedAt?.let { Instant.fromEpochMilliseconds(it) }
        )
    }

    companion object {
        fun fromBookmark(bookmark: Bookmark): FsBookmark {
            return FsBookmark(
                id = bookmark.id,
                bookId = bookmark.bookId,
                chapterId = bookmark.chapterId,
                spineIndex = bookmark.spineIndex,
                locator = bookmark.locator,
                label = bookmark.label,
                createdAt = bookmark.createdAt.toEpochMilliseconds(),
                updatedAt = bookmark.updatedAt.toEpochMilliseconds(),
                deviceId = bookmark.deviceId,
                isDeleted = bookmark.isDeleted,
                deletedAt = bookmark.deletedAt?.toEpochMilliseconds()
            )
        }
    }
}

@Serializable
data class FsReadingSession(
    val id: String,
    val bookId: String,
    val cycleId: String? = null,
    val deviceId: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val durationMs: Long = 0,
    val startPosition: String, // JSON ReadingPosition
    val endPosition: String? = null, // JSON ReadingPosition
    val startProgress: Double = 0.0,
    val endProgress: Double = 0.0,
    val wordsRead: Long = 0,
    val isActive: Boolean = false
)

@Serializable
data class FsSettings(
    val userId: String,
    val global: String, // JSON GlobalSettings
    val bookSettings: Map<String, String> = emptyMap(), // bookId -> JSON BookReaderSettings
    val updatedAt: Long = Clock.System.now().toEpochMilliseconds(),
    val deviceId: String
)

@Serializable
data class FsTag(
    val id: String,
    val name: String,
    val color: Int? = null,
    val createdAt: Long,
    val deviceId: String = "",
    val updatedAt: Long = createdAt
)

@Serializable
data class FsCollection(
    val id: String,
    val name: String,
    val color: Int? = null,
    val sortOrder: Int = 0,
    val createdAt: Long,
    val deviceId: String = "",
    val updatedAt: Long = createdAt
)

@Serializable
data class FsSeries(
    val id: String,
    val name: String,
    val sortOrder: Int = 0,
    val deviceId: String = "",
    val updatedAt: Long = 0L
)

@Serializable
data class FsQuote(
    val id: String,
    val bookId: String,
    val chapterId: String,
    val highlightId: String,
    val text: String,
    val note: String? = null,
    val createdAt: Long,
    val deviceId: String
)

@Serializable
data class FsRevisitItem(
    val id: String,
    val bookId: String,
    val chapterId: String,
    val type: Int,
    val sourceId: String,
    val note: String? = null,
    val createdAt: Long,
    val resolvedAt: Long? = null,
    val deviceId: String
)

@Serializable
data class FsManga(
    val id: String,
    val sourceId: Long,
    val sourceName: String,
    val url: String,
    val title: String,
    val author: String? = null,
    val artist: String? = null,
    val description: String? = null,
    val genres: String = "[]",
    val status: Int = 0,
    val thumbnailUrl: String? = null,
    val favorite: Boolean = false,
    val initialized: Boolean = false,
    val addedAt: Long,
    val updatedAt: Long,
    val deviceId: String,
    val isDeleted: Boolean = false,
)

@Serializable
data class FsMangaChapter(
    val id: String,
    val mangaId: String,
    val url: String,
    val name: String,
    val read: Boolean = false,
    val bookmarked: Boolean = false,
    val lastPageRead: Int = 0,
    val totalPages: Int = 0,
    val updatedAt: Long,
    val deviceId: String,
)

@Serializable
data class FsMangaNote(
    val id: String,
    val mangaId: String,
    val chapterId: String,
    val pageIndex: Int,
    val content: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deviceId: String,
    val isDeleted: Boolean = false,
)

// Collection paths
object FirestorePaths {
    const val USERS = "users"
    const val BOOKS = "books"
    val POSITIONS = "positions"
    val HIGHLIGHTS = "highlights"
    val NOTES = "notes"
    val BOOKMARKS = "bookmarks"
    val SESSIONS = "readingSessions"
    val CYCLES = "readingCycles"
    val SETTINGS = "settings"
    val TAGS = "tags"
    val COLLECTIONS = "collections"
    val SERIES = "series"
    val QUOTES = "quotes"
    val REVISIT = "revisitItems"
    val DEVICES = "devices"
    val SYNC_STATE = "syncState"
    val MANGA = "manga"
    val MANGA_CHAPTERS = "mangaChapters"
    val MANGA_NOTES = "mangaNotes"

    fun userBooks(uid: String) = "$USERS/$uid/$BOOKS"
    fun userBook(uid: String, bookId: String) = "$USERS/$uid/$BOOKS/$bookId"
    fun userPositions(uid: String, bookId: String) = "$USERS/$uid/$BOOKS/$bookId/$POSITIONS"
    fun userHighlights(uid: String, bookId: String) = "$USERS/$uid/$BOOKS/$bookId/$HIGHLIGHTS"
    fun userNotes(uid: String, bookId: String) = "$USERS/$uid/$BOOKS/$bookId/$NOTES"
    fun userBookmarks(uid: String, bookId: String) = "$USERS/$uid/$BOOKS/$bookId/$BOOKMARKS"
    fun userSessions(uid: String) = "$USERS/$uid/$SESSIONS"
    fun userCycles(uid: String, bookId: String) = "$USERS/$uid/$BOOKS/$bookId/$CYCLES"
    fun userSettings(uid: String) = "$USERS/$uid/$SETTINGS"
    fun userSettingsDocument(uid: String) = "${userSettings(uid)}/global"
    fun userTags(uid: String) = "$USERS/$uid/$TAGS"
    fun userCollections(uid: String) = "$USERS/$uid/$COLLECTIONS"
    fun userSeries(uid: String) = "$USERS/$uid/$SERIES"
    fun userQuotes(uid: String) = "$USERS/$uid/$QUOTES"
    fun userRevisit(uid: String) = "$USERS/$uid/$REVISIT"
    fun userDevices(uid: String) = "$USERS/$uid/$DEVICES"
    fun userSyncState(uid: String) = "$USERS/$uid/$SYNC_STATE"
    fun userManga(uid: String) = "$USERS/$uid/$MANGA"
    fun userMangaChapters(uid: String) = "$USERS/$uid/$MANGA_CHAPTERS"
    fun userMangaNotes(uid: String) = "$USERS/$uid/$MANGA_NOTES"

    // Storage paths
    const val STORAGE_BOOKS = "books"
    fun userBookFile(uid: String, bookId: String) = "$STORAGE_BOOKS/$uid/$bookId/book.epub"
    fun userBookCover(uid: String, bookId: String) = "$STORAGE_BOOKS/$uid/$bookId/cover.jpg"
}