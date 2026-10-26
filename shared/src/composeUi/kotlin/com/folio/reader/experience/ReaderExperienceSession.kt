package com.folio.reader.experience

import androidx.compose.runtime.*
import com.folio.reader.model.*
import com.folio.reader.settings.ReaderSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

class ReaderExperienceSession(val environment: ReadingEnvironment?, val sound: SoundscapeController) {
    var position: ReadingPosition? = null
    var chapters: List<Chapter> = emptyList()
    var chapterIndex = 0
    var title = ""
    var author by mutableStateOf("")
    var cover: String? = null
    var sources: List<ExportSource> = emptyList()
    var eligible = false
    var targetRemainingMs by mutableStateOf<Long?>(null)
    var targetFinished by mutableStateOf(false)
    var timeEstimate by mutableStateOf<ReadingTimeEstimate?>(null)
    var history by mutableStateOf<List<ReadingSession>>(emptyList())
    var returnCard by mutableStateOf<ReadingReturnContext?>(null)
    var showReminder by mutableStateOf(false)
    var showSequence by mutableStateOf(false)
    var reflectionEditor by mutableStateOf(false)
    var latestExcerpt = ""
    fun exportSelection(chapterId: String, paragraph: Int, text: String) {
        val pos = position ?: return
        val chapter = chapters.firstOrNull { it.id == chapterId }
        environment?.exports = listOf(ExportSource("selection", pos.bookId, title, author, text, chapter?.title.orEmpty(),
            chapterId, chapter?.spineIndex, "/${chapter?.spineIndex ?: pos.spineIndex}/$paragraph:0", coverPath = cover))
    }
    fun export(id: String) { sources.firstOrNull { it.id == id }?.let { environment?.exports = listOf(it) } }
    fun review(id: String) { sources.firstOrNull { it.id == id }?.let { source -> environment?.perform { repository.schedule(source, System.currentTimeMillis()) } } }
    fun journal() { position?.bookId?.let { environment?.perform { openJournal(it) } } }
    fun saveContext() {
        val pos = position ?: return
        val env = environment ?: return
        val chapter = chapters.getOrNull(chapterIndex)?.title.orEmpty()
        val excerpt = latestExcerpt
        env.perform {
            val existing = repository.state.value.returns.firstOrNull { it.bookId == pos.bookId }
            repository.saveReturn(ReadingReturnContext(pos.bookId, pos.copy(previousPosition = null), chapter, excerpt, existing?.reminder.orEmpty(), System.currentTimeMillis(), existing?.reminderNoteId))
        }
    }
}

val LocalReaderExperience = staticCompositionLocalOf<ReaderExperienceSession?> { null }

@Composable
fun rememberReaderExperienceSession(title: String, cover: String?, position: ReadingPosition?, chapters: List<Chapter>,
    chapterIndex: Int, settings: ReaderSettings, highlights: List<Highlight>, notes: List<Note>): ReaderExperienceSession {
    val env = LocalReadingEnvironment.current
    val platform = rememberExperiencePlatform()
    val sound = remember(platform, position?.bookId) {
        val id = position?.bookId
        if (id != null && env != null) env.soundFor(id) { platform.soundscapeController() } else platform.soundscapeController()
    }
    val session = remember(sound) { ReaderExperienceSession(env, sound) }
    val author = session.author
    val sourceSnapshot = remember(title, cover, author, chapters, highlights, notes, position?.bookId) {
        val id = position?.bookId.orEmpty()
        val book = Book(id = id, title = title, authors = listOf(author), epubFileSize = 0, epubHash = "")
        annotationExportSources(book, chapters, highlights, notes).map { it.copy(coverPath = cover) }
    }
    SideEffect {
        session.position = position; session.title = title; session.cover = cover
        session.chapters = chapters; session.chapterIndex = chapterIndex; session.sources = sourceSnapshot
    }
    LaunchedEffect(settings.soundscape) { sound.restore(settings.soundscape) }
    LaunchedEffect(session, position?.bookId) {
        if (env != null && position != null) {
            env.repository.refresh()
            session.author = env.books.getBook(position.bookId)?.displayAuthor.orEmpty()
            sound.setVolume(env.repository.volume())
            session.history = env.sessions.observeSessionsSince(kotlinx.datetime.Instant.fromEpochMilliseconds(0)).first()
            val context = env.repository.state.value.returns.firstOrNull { it.bookId == position.bookId }
            if (context != null && System.currentTimeMillis() - context.lastLeftAt >= ReviewSchedule.DAY_MS) session.returnCard = context
        }
    }
    key(session) {
        ReaderForegroundEffect { foreground ->
            session.eligible = foreground; sound.setEligible(foreground)
            if (!foreground) session.saveContext()
        }
    }
    DisposableEffect(session) { onDispose {
        session.saveContext()
        if (position?.bookId != null && env != null) sound.releaseOutput() else sound.close()
    } }
    LaunchedEffect(session, session.targetRemainingMs != null) {
        if (session.targetRemainingMs == null) return@LaunchedEffect
        var last = System.nanoTime()
        while (true) {
            delay(250)
            val now = System.nanoTime()
            val elapsed = ((now - last) / 1_000_000).coerceIn(0, 1000); last = now
            if (session.eligible) session.targetRemainingMs?.let { remaining ->
                val value = (remaining - elapsed).coerceAtLeast(0)
                session.targetRemainingMs = value
                if (value == 0L) { session.targetRemainingMs = null; session.targetFinished = true }
            }
        }
    }
    return session
}
