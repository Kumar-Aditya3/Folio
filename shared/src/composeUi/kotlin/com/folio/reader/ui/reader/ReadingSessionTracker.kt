package com.folio.reader.ui.reader

import com.folio.reader.database.ReadingSessionRepository
import com.folio.reader.model.Chapter
import com.folio.reader.model.ReadingPosition
import com.folio.reader.model.ReadingSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.Clock
import kotlin.time.TimeSource

/** Small stateful guard shared by browser and Compose scroll surfaces. */
internal class ChapterEndGuard {
    private var handledChapter: String? = null

    fun accept(chapterId: String): Boolean {
        if (handledChapter == chapterId) return false
        handledChapter = chapterId
        return true
    }

    fun reset() {
        handledChapter = null
    }
}

/**
 * The engagement clock and the persisted session rows for the open book. The
 * clock measures one open span; this tracker owns the span accounting and
 * creates, resumes and ends the [ReadingSession] backing it.
 */
internal class ReadingSessionTracker(
    private val sessionRepository: ReadingSessionRepository,
    private val sessionState: MutableStateFlow<ReadingSession?>,
    private val bookTotalWords: () -> Long
) {
    val chapterEndGuard = ChapterEndGuard()
    val chapterStartGuard = ChapterEndGuard()

    /** Chapter-share accounting: where this chapter began on the clock and in the book. */
    private var chapterBaseActiveMs = 0L
    private var chapterBaseProgress = 0.0

    /** Called on every chapter entry so the finished chapter's time and words are measurable. */
    fun markChapterEntry(progress: Double) {
        chapterBaseActiveMs = activeMs()
        chapterBaseProgress = progress
    }

    /** Active reading time (ms) and credited words since the last [markChapterEntry]. */
    fun chapterShare(currentProgress: Double): Pair<Long, Long> {
        val ms = (activeMs() - chapterBaseActiveMs).coerceAtLeast(0L)
        val words = ((currentProgress - chapterBaseProgress).coerceAtLeast(0.0) * bookTotalWords()).toLong()
        return ms to words
    }

    /**
     * Engagement clock. [activeSpanMs] grows only in the intervals between reading
     * events that are close enough together to be plausible, so a book left open while
     * the reader walks away stops counting. [bankedSpanMs] carries time already
     * measured for a session reopened from an earlier run.
     */
    private var activeSpanMs = 0L
    private var bankedSpanMs = 0L
    private var lastActivityAt: TimeSource.Monotonic.ValueTimeMark? = null

    /** Words credited to this session, advanced a readable pace at a time. */
    private var creditedWords = 0L
    private var wordsBaseProgress = 0.0
    private var wordsBaseActiveMs = 0L

    /** Reading time measured for the open session so far, in milliseconds. */
    fun activeMs(): Long = bankedSpanMs + activeSpanMs

    /**
     * Called by everything that proves the reader is in the book: a scroll tick, a page
     * turn, a chapter change. The interval since the previous event counts as reading
     * only while it stays under [IDLE_GRACE_MS]; a longer gap was the app sitting
     * idle, and banking it is what turned reading time into elapsed time.
     */
    fun markReadingActivity() {
        val now = TimeSource.Monotonic.markNow()
        val previous = lastActivityAt
        lastActivityAt = now
        if (previous == null) return
        val gapMs = (now - previous).inWholeMilliseconds
        if (gapMs in 1 until IDLE_GRACE_MS) activeSpanMs += gapMs
    }

    /**
     * Advances the session's word count by whatever could plausibly have been read
     * since the last measurement. Progress also moves on scrubs, search hits and
     * chapter skips, and travelling through a book is not reading it, so the credit is
     * clamped to [MAX_CREDIBLE_WPM] over the active time elapsed.
     */
    fun creditedWordsFor(normalized: Double, totalActiveMs: Long): Long {
        val totalWords = bookTotalWords()
        val advance = (normalized - wordsBaseProgress).coerceAtLeast(0.0)
        wordsBaseProgress = normalized
        if (advance <= 0.0 || totalWords <= 0L) return creditedWords
        val elapsedMinutes = ((totalActiveMs - wordsBaseActiveMs).coerceAtLeast(0L)) / 60_000.0
        wordsBaseActiveMs = totalActiveMs
        val candidate = advance * totalWords
        val ceiling = elapsedMinutes * MAX_CREDIBLE_WPM
        creditedWords += minOf(candidate, ceiling).toLong()
        return creditedWords
    }

    /**
     * Loads or creates the session for a book being opened. The engagement clock
     * measures one open span, so it starts from zero every time a book is opened.
     * [startIndex] is the unclamped start chapter the position restore computed.
     */
    suspend fun openSession(
        bookId: String,
        deviceId: String,
        chapters: List<Chapter>,
        startIndex: Int,
        position: ReadingPosition?
    ) {
        activeSpanMs = 0L
        bankedSpanMs = 0L
        lastActivityAt = null
        val activeSession = runCatching { sessionRepository.getActiveSession(bookId) }.getOrNull()
        val resumable = activeSession?.takeIf {
            it.isActive &&
                Clock.System.now().toEpochMilliseconds() - it.startedAt.toEpochMilliseconds() < STALE_SESSION_MS
        }
        if (resumable != null) {
            sessionState.value = resumable
            bankedSpanMs = resumable.durationMs
            creditedWords = resumable.wordsRead
            wordsBaseProgress = resumable.endProgress.takeIf { it > 0.0 } ?: resumable.startProgress
        } else {
            // An orphan left active by a crash or a killed process must not be
            // adopted: its stored total was frozen at the last flush and is already
            // correct, it only needs closing.
            activeSession?.let { orphan ->
                runCatching {
                    sessionRepository.updateSession(
                        orphan.copy(isActive = false, endedAt = Clock.System.now())
                    )
                }
            }
            val startPosition = position ?: ReadingPosition(
                bookId = bookId,
                deviceId = deviceId,
                chapterId = chapters.getOrNull(startIndex)?.id ?: "",
                spineIndex = chapters.getOrNull(startIndex)?.spineIndex ?: 0,
                contentLocator = ""
            )
            val newSession = ReadingSession(
                id = java.util.UUID.randomUUID().toString(),
                bookId = bookId,
                cycleId = null,
                deviceId = deviceId,
                startedAt = Clock.System.now(),
                startPosition = startPosition,
                startProgress = startPosition.normalizedProgress
            )
            runCatching { sessionRepository.insertSession(newSession) }
            sessionState.value = newSession
            creditedWords = 0L
            wordsBaseProgress = startPosition.normalizedProgress
        }
        wordsBaseActiveMs = activeMs()
        markChapterEntry(position?.normalizedProgress ?: 0.0)
    }

    /** Ends the active session with its measured reading time. */
    suspend fun endActiveSession(session: ReadingSession?, position: ReadingPosition?) {
        if (session == null || !session.isActive) return
        markReadingActivity()
        val totalMs = activeMs()
        val ended = session.end(
            endPos = position ?: session.startPosition,
            endProgress = position?.normalizedProgress ?: session.startProgress,
            wordsRead = creditedWordsFor(
                position?.normalizedProgress ?: session.endProgress, totalMs
            ),
            totalActiveMs = totalMs
        )
        sessionRepository.updateSession(ended)
        sessionState.value = ended
    }

    private companion object {
        /**
         * A longer silence between reading events means the reader stopped: a page
         * takes well under this to get through, and anything past it was the app
         * sitting unattended.
         */
        const val IDLE_GRACE_MS = 120_000L

        /** An active session older than this was abandoned, not paused. */
        const val STALE_SESSION_MS = 6L * 60L * 60L * 1000L

        /** Above this, progress is movement through the book rather than reading it. */
        const val MAX_CREDIBLE_WPM = 800.0
    }
}
