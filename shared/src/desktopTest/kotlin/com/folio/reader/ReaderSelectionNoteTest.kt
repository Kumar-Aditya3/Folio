package com.folio.reader

import com.folio.reader.database.*
import com.folio.reader.model.*
import com.folio.reader.platform.DesktopPlatform
import com.folio.reader.settings.ReaderSettings
import com.folio.reader.ui.reader.ReaderAnnotations
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import kotlin.test.*

class ReaderSelectionNoteTest {
    @Test fun selectionNoteBelongsToItsSelectedChapterAndKeepsTheChosenInk() = runBlocking<Unit> {
        val root = kotlin.io.path.createTempDirectory("folio-selection-note-").toFile()
        val db = Database(DesktopPlatform(root).fileSystem.getDatabasePath())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val books = JdbcBookRepository(db)
            books.insertBook(Book("book", "Small Worlds", epubHash = "book", epubFileSize = 1), false)
            val highlights = JdbcHighlightRepository(db)
            val notes = JdbcNoteRepository(db)
            val annotations = ReaderAnnotations(JdbcBookmarkRepository(db), highlights, notes, scope,
                MutableStateFlow(ReadingPosition("book", "test", "anchor", 0, "/0/1:0")),
                { "book" }, { "test" }, { ReaderSettings() })
            annotations.startCollecting("book")
            val saved = CompletableDeferred<String>()
            val ink = 0xffbad7a4.toInt()
            annotations.addHighlight("/2/4:0", "/2/4:end", "There was still time.",
                customColor = ink, chapterId = "selected", spineIndex = 2, onSaved = {
                    annotations.setHighlightNote(it, "Come back to this idea.")
                    saved.complete(it)
                })
            val id = withTimeout(5_000) { saved.await() }
            val highlight = withTimeout(5_000) {
                var found = highlights.getHighlight(id)
                while (found?.noteId == null) { delay(10); found = highlights.getHighlight(id) }
                requireNotNull(found)
            }
            val note = withTimeout(5_000) {
                var found = notes.getNote(requireNotNull(highlight.noteId))
                while (found == null) { delay(10); found = notes.getNote(requireNotNull(highlight.noteId)) }
                found
            }
            assertEquals("selected", highlight.chapterId)
            assertEquals(2, highlight.spineIndex)
            assertEquals(ink, highlight.effectiveColor)
            assertEquals("selected", note.chapterId)
            assertEquals("/2/4:0", note.locator)
            assertEquals("Come back to this idea.", note.content)
        } finally {
            scope.cancel()
            db.close()
            val temporaryRoot = File(System.getProperty("java.io.tmpdir")).canonicalPath + File.separator
            check(root.canonicalPath.startsWith(temporaryRoot) && root.name.startsWith("folio-selection-note-"))
            root.deleteRecursively()
        }
    }
}
