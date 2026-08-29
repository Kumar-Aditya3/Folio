package com.folio.reader.model

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlin.math.abs
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

fun ReadingPosition.spotLocator(): String =
    contentLocator.ifBlank { "/$spineIndex/f${(chapterProgress * 1000).roundToInt()}" }

/** The per-mille chapter fraction of a locator step ("f423"), if it has one. */
private fun String.fractionStep(): Float? {
    val step = substringAfterLast('/').substringBefore(':').trimEnd(')')
    if (!step.startsWith("f")) return null
    return step.substring(1).toIntOrNull()?.div(1000f)
}

/** Fraction a locator without a paragraph step points at, as 0..1 of the chapter. */
fun String.locatorFraction(): Float? = fractionStep()?.takeIf { it in 0f..1f }

/**
 * Whether two locators describe the same place. Fractions are rounded and the
 * reader keeps moving between taps, so near-equal fractions count as a match.
 */
fun locatorsMatch(a: String?, b: String?): Boolean {
    if (a == null || b == null) return a == b
    if (a == b) return true
    val fa = a.fractionStep() ?: return false
    val fb = b.fractionStep() ?: return false
    return abs(fa - fb) <= 0.005f
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

    companion object {
        /**
         * Sessions started before this instant recorded the gap between opening and
         * closing a book rather than reading time, so their durations are unusable —
         * the desktop database held single "sessions" of 45 hours. Anything older is
         * discarded locally and refused on sync. 2026-08-29T11:45:00Z.
         */
        val FIRST_MEASURED_SESSION: Instant = Instant.fromEpochMilliseconds(1_788_003_900_000L)
    }

    /**
     * Closes the session with [totalActiveMs] — milliseconds of demonstrable reading,
     * measured by the reader — as its final length.
     *
     * Wall-clock is deliberately not consulted. The span between opening a book and
     * closing it contains every phone call, every backgrounded hour and every night
     * the app was simply left on, and counting that as reading is what made the
     * statistic worthless.
     */
    fun end(
        endPos: ReadingPosition,
        endProgress: Double,
        wordsRead: Long,
        totalActiveMs: Long
    ): ReadingSession {
        return copy(
            endedAt = Clock.System.now(),
            durationMs = totalActiveMs,
            endPosition = endPos,
            endProgress = endProgress,
            wordsRead = wordsRead,
            isActive = false
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