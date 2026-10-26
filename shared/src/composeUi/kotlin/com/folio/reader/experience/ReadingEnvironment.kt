package com.folio.reader.experience

import androidx.compose.runtime.*
import com.folio.reader.database.*
import com.folio.reader.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ReadingEnvironment(val settings: SettingsRepository, val books: BookRepository, val highlights: HighlightRepository,
    val notes: NoteRepository, val sessions: ReadingSessionRepository, val scope: kotlinx.coroutines.CoroutineScope) {
    val repository = ReadingExperienceRepository(settings)
    private var cachedSound: Pair<String, SoundscapeController>? = null
    fun soundFor(bookId: String, create: () -> SoundscapeController): SoundscapeController {
        cachedSound?.takeIf { it.first == bookId }?.let { return it.second }
        cachedSound?.second?.close()
        return create().also { cachedSound = bookId to it }
    }
    fun closeAudio() { cachedSound?.second?.close(); cachedSound = null }
    var exports by mutableStateOf<List<ExportSource>?>(null)
    var exportAnchor by mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)
    var journal by mutableStateOf<JournalSnapshot?>(null)
    var error by mutableStateOf<String?>(null)
    suspend fun snapshot(bookId: String): JournalSnapshot {
        val book = books.getBook(bookId) ?: error("This book is no longer available")
        val chapters = books.getChaptersForBook(bookId)
        val hs = highlights.getHighlightsForBook(bookId).first().filterNot { it.isDeleted }
        val ns = notes.getNotesForBook(bookId).first().filterNot { it.isDeleted }
        return JournalSnapshot(book, chapters, annotationExportSources(book, chapters, hs, ns), sessions.getSessionsForBook(bookId).first())
    }
    suspend fun openJournal(bookId: String) { journal = snapshot(bookId) }
    fun perform(action: suspend ReadingEnvironment.() -> Unit) { scope.launch {
        try { action() } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { error = "Could not save this change. Please try again." }
    } }
}
data class JournalSnapshot(val book: Book, val chapters: List<Chapter>, val sources: List<ExportSource>, val sessions: List<ReadingSession>)
val LocalReadingEnvironment = staticCompositionLocalOf<ReadingEnvironment?> { null }

@Composable
fun ReadingEnvironmentProvider(settings: SettingsRepository, books: BookRepository, highlights: HighlightRepository,
    notes: NoteRepository, sessions: ReadingSessionRepository, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val environment = remember(settings) { ReadingEnvironment(settings, books, highlights, notes, sessions, scope) }
    DisposableEffect(environment) { onDispose { environment.closeAudio() } }
    LaunchedEffect(environment) {
        try { environment.repository.refresh() } catch (e: Exception) { environment.error = "Could not load reading preferences." }
        // Library changes also remove dependent companion data. Existing note/quote rows stay owned by their repositories.
        books.getAllBooks().collect { current ->
            val ids = current.mapTo(mutableSetOf()) { it.id }
            val data = environment.repository.state.value
            val staleBooks = (data.returns.map { it.bookId } + data.reviews.map { it.source.bookId } + data.reflections.map { it.bookId }).filterNot { it in ids }.distinct()
            for (id in staleBooks) environment.repository.removeBook(id)
        }
    }
    LaunchedEffect(environment.repository) { environment.repository.observe() }
    CompositionLocalProvider(LocalReadingEnvironment provides environment) {
        content()
        ReadingExportHost(environment)
    }
}

fun annotationExportSources(book: Book, chapters: List<Chapter>, highlights: List<Highlight>, notes: List<Note>): List<ExportSource> {
    val ns = notes.filterNot { it.isDeleted }.associateBy { it.id }
    fun chapter(id: String?, spine: Int?) = chapters.firstOrNull { it.id == id || it.spineIndex == spine }?.title.orEmpty()
    val sources = highlights.filterNot { it.isDeleted }.map { h -> ExportSource(h.id, book.id, book.displayTitle, book.displayAuthor,
        h.selectedText, chapter(h.chapterId, h.spineIndex), h.chapterId, h.spineIndex, h.startLocator, h.noteId?.let { ns[it]?.content }, book.coverPath) }
    val owned = highlights.mapNotNull { it.noteId }.toSet()
    return (sources + notes.filter { !it.isDeleted && it.id !in owned }.map { n -> ExportSource(n.id, book.id, book.displayTitle, book.displayAuthor,
        n.content, chapter(n.chapterId, n.spineIndex), n.chapterId, n.spineIndex, n.locator, coverPath = book.coverPath, isPersonalNote = true) })
        .sortedWith(compareBy<ExportSource> { it.spineIndex ?: Int.MAX_VALUE }.thenBy { exportPassageOrder(it.locator) }.thenBy { it.id })
}
