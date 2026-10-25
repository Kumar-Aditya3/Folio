package com.folio.reader.database

import com.folio.reader.model.ReadingSession
import com.folio.reader.model.Chapter
import com.folio.reader.model.ReadingTrace
import com.folio.reader.model.readingTrace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/** Bulk reads shared by the whole Android navigation tree. No per-cover queries or polling. */
class ReadingTraceRepository(private val db: Database) {
    val traces: Flow<Map<String, ReadingTrace>> = combine(
        db.bookDataRevision.map {
            val books = db.getAllBooks()
            val chapters = db.withConnection { connection ->
                connection.prepareStatement("SELECT id, book_id, spine_index, word_count FROM chapters ORDER BY book_id, spine_index").use { statement ->
                    statement.executeQuery().use { rows ->
                        buildList { while (rows.next()) add(Chapter(rows.getString("id"), rows.getString("book_id"),
                            "", "", rows.getInt("spine_index"), wordCount = rows.getLong("word_count"))) }
                    }
                }
            }
            books to chapters.groupBy { it.bookId }
        },
        JdbcReadingSessionRepository(db).observeSessionsSince(ReadingSession.FIRST_MEASURED_SESSION),
        db.annotationDataRevision.map {
            db.withConnection { connection ->
                connection.prepareStatement("SELECT h.*, n.id AS live_note_id FROM highlights h LEFT JOIN notes n ON n.id = h.note_id AND n.is_deleted = 0 WHERE h.is_deleted = 0 ORDER BY h.book_id, h.spine_index").use { statement ->
                    statement.executeQuery().use { rows ->
                        buildList { while (rows.next()) add(db.mapRowToHighlight(rows).copy(noteId = rows.getString("live_note_id"))) }
                    }
                }
            }
        },
    ) { bookData, sessions, highlights ->
        val (books, chapters) = bookData
        val byBook = sessions.filterNot { it.startPosition.contentLocator.startsWith("manga-page") }.groupBy { it.bookId }
        val marks = highlights.groupBy { it.bookId }
        books.associate { book ->
            book.id to readingTrace(book.normalizedProgress.toFloat(), book.chapterCount,
                byBook[book.id].orEmpty(), marks[book.id].orEmpty(), chapters[book.id].orEmpty())
        }
    }.catch { failure ->
        println("Reading trace unavailable: ${failure.message}")
        emit(emptyMap())
    }.distinctUntilChanged().flowOn(Dispatchers.IO)
}
