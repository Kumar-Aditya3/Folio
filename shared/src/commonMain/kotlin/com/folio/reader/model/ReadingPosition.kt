package com.folio.reader.model

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

@Serializable
data class ReadingPosition(
    val bookId: String,
    val deviceId: String,
    val chapterId: String,
    val spineIndex: Int,
    val contentLocator: String, // EPUB CFI
    val characterOffset: Int = 0,
    val paragraphIndex: Int = 0,
    val normalizedProgress: Double = 0.0, // 0.0-1.0 book progress
    val chapterProgress: Double = 0.0, // 0.0-1.0 chapter progress
    val scrollOffset: Double = 0.0,
    val updatedAt: Instant = Clock.System.now(),
    val previousPosition: ReadingPosition? = null // For conflict recovery
) {
    fun withUpdatedLocator(
        newLocator: String,
        newChapterId: String,
        newSpineIndex: Int,
        newCharOffset: Int = 0,
        newParaIndex: Int = 0,
        newChapterProgress: Double = 0.0,
        newNormalizedProgress: Double = 0.0,
        newScrollOffset: Double = 0.0
    ): ReadingPosition {
        return copy(
            chapterId = newChapterId,
            spineIndex = newSpineIndex,
            contentLocator = newLocator,
            characterOffset = newCharOffset,
            paragraphIndex = newParaIndex,
            chapterProgress = newChapterProgress,
            normalizedProgress = newNormalizedProgress,
            scrollOffset = newScrollOffset,
            updatedAt = Clock.System.now(),
            previousPosition = this
        )
    }

    fun withProgress(
        newNormalizedProgress: Double,
        newChapterProgress: Double,
        newScrollOffset: Double = scrollOffset
    ): ReadingPosition {
        return copy(
            normalizedProgress = newNormalizedProgress.coerceIn(0.0, 1.0),
            chapterProgress = newChapterProgress.coerceIn(0.0, 1.0),
            scrollOffset = newScrollOffset,
            updatedAt = Clock.System.now()
        )
    }

    val progressPercent: Int
        get() = (normalizedProgress * 100).roundToInt()

    val chapterProgressPercent: Int
        get() = (chapterProgress * 100).roundToInt()
}

@Serializable
data class ReadingSession(
    val id: String,
    val bookId: String,
    val cycleId: String?,
    val deviceId: String,
    val startedAt: Instant,
    val endedAt: Instant? = null,
    val durationMs: Long = 0,
    val startPosition: ReadingPosition,
    val endPosition: ReadingPosition? = null,
    val startProgress: Double = 0.0,
    val endProgress: Double = 0.0,
    val wordsRead: Long = 0,
    val isActive: Boolean = true
) {
    val durationMinutes: Double
        get() = durationMs / 60000.0

    val wordsPerMinute: Double?
        get() {
            if (durationMinutes <= 0) return null
            return wordsRead / durationMinutes
        }

    fun end(
        endPos: ReadingPosition,
        endProgress: Double,
        wordsRead: Long
    ): ReadingSession {
        val now = Clock.System.now()
        return copy(
            endedAt = now,
            durationMs = (now.toEpochMilliseconds() - startedAt.toEpochMilliseconds()),
            endPosition = endPos,
            endProgress = endProgress,
            wordsRead = wordsRead,
            isActive = false
        )
    }

    fun pause(): ReadingSession {
        val now = Clock.System.now()
        return copy(
            endedAt = now,
            durationMs = (now.toEpochMilliseconds() - startedAt.toEpochMilliseconds()),
            isActive = false
        )
    }

    fun resume(newStartPosition: ReadingPosition): ReadingSession {
        return copy(
            endedAt = null,
            startPosition = newStartPosition,
            startProgress = newStartPosition.normalizedProgress,
            isActive = true
        )
    }
}

@Serializable
data class ReadingCycle(
    val id: String,
    val bookId: String,
    val cycleNumber: Int,
    val startedAt: Instant,
    val finishedAt: Instant? = null,
    val totalDurationMs: Long = 0,
    val sessionCount: Int = 0,
    val finalProgress: Double = 0.0
) {
    val isCompleted: Boolean
        get() = finishedAt != null

    val durationHours: Double
        get() = totalDurationMs / 3_600_000.0

    fun finish(finishedAt: Instant, finalProgress: Double): ReadingCycle {
        return copy(
            finishedAt = finishedAt,
            finalProgress = finalProgress
        )
    }

    fun addSession(session: ReadingSession): ReadingCycle {
        return copy(
            totalDurationMs = totalDurationMs + session.durationMs,
            sessionCount = sessionCount + 1
        )
    }
}