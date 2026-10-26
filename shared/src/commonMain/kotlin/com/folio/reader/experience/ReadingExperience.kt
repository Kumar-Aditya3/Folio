package com.folio.reader.experience

import com.folio.reader.model.Chapter
import com.folio.reader.model.ReadingPosition
import com.folio.reader.model.ReadingSession
import com.folio.reader.settings.BookReaderSettings
import kotlinx.serialization.Serializable

@Serializable
enum class Soundscape(val title: String) { OFF("Off"), RAIN("Rain"), CAFE("Café"), FIREPLACE("Fireplace") }

@Serializable
data class SoundscapePreferences(
    val scene: Soundscape = Soundscape.OFF,
    val primaryLevel: Float = 1f,
    val secondary: Soundscape = Soundscape.OFF,
    val secondaryLevel: Float = .35f,
    val timerMinutes: Int = 0,
    val responsiveAtmosphere: Boolean = true,
) {
    fun normalized() = copy(primaryLevel = primaryLevel.coerceIn(0f, 1f),
        secondary = secondary.takeUnless { it == scene } ?: Soundscape.OFF,
        secondaryLevel = secondaryLevel.coerceIn(0f, 1f), timerMinutes = timerMinutes.coerceIn(0, 180))
}

@Serializable
data class ExportSource(
    val id: String,
    val bookId: String,
    val title: String,
    val author: String = "",
    val text: String,
    val chapter: String = "",
    val chapterId: String? = null,
    val spineIndex: Int? = null,
    val locator: String? = null,
    val note: String? = null,
    val coverPath: String? = null,
    val isPersonalNote: Boolean = false,
    val isReflection: Boolean = false,
)

@Serializable
data class ReadingPreset(val id: String, val name: String, val settings: BookReaderSettings)

@Serializable
data class ReadingReturnContext(
    val bookId: String,
    val position: ReadingPosition,
    val chapter: String,
    val excerpt: String = "",
    val reminder: String = "",
    val lastLeftAt: Long,
    val reminderNoteId: String? = null,
)

data class ReflectionInvitation(val bookId: String, val chapterId: String, val chapter: String,
    val sessionId: String, val position: ReadingPosition)

@Serializable
data class ReadingReflection(val noteId: String, val bookId: String, val chapterId: String, val sessionId: String)

@Serializable
data class ReviewSchedule(val source: ExportSource, val dueAt: Long, val intervalIndex: Int = 0) {
    fun remember(now: Long): ReviewSchedule {
        val next = (intervalIndex + 1).coerceAtMost(INTERVALS.lastIndex)
        return copy(intervalIndex = next, dueAt = now + INTERVALS[next] * DAY_MS)
    }
    fun again(now: Long) = copy(intervalIndex = 0, dueAt = now + DAY_MS)
    fun snooze(now: Long) = copy(dueAt = now + DAY_MS)
    companion object {
        val INTERVALS = listOf(1, 3, 7, 14, 30)
        const val DAY_MS = 86_400_000L
    }
}

/** Defaulted additions travel through the existing settings backup/sync transaction. */
@Serializable
data class ReadingExperienceData(
    val presets: List<ReadingPreset> = emptyList(),
    val returns: List<ReadingReturnContext> = emptyList(),
    val reflections: List<ReadingReflection> = emptyList(),
    val reviews: List<ReviewSchedule> = emptyList(),
    val chapterReflections: Boolean = false,
)

data class ReadingTimeEstimate(val wordsPerMinute: Int, val personal: Boolean, val message: String)

fun estimateReadingTime(sessions: List<ReadingSession>, bookId: String, chapters: List<Chapter>,
    currentIndex: Int, fraction: Double, minutes: Int): ReadingTimeEstimate {
    val eligible = sessions.filter { it.durationMs >= 30_000 && it.wordsRead > 0 &&
        (it.wordsPerMinute ?: 0.0) in 40.0..700.0 }.sortedByDescending { it.startedAt }.take(20)
    val book = eligible.filter { it.bookId == bookId }
    val measured = if (book.sumOf { it.durationMs } >= 600_000) book else eligible
    val rate = if (measured.isEmpty()) 220 else
        (measured.sumOf { it.wordsRead } * 60_000.0 / measured.sumOf { it.durationMs }).toInt().coerceIn(40, 700)
    val remaining = ((chapters.getOrNull(currentIndex)?.wordCount ?: 0L) * (1 - fraction.coerceIn(0.0, 1.0))).toLong()
    val chapterMinutes = kotlin.math.ceil(remaining.toDouble() / rate).toInt().coerceAtLeast(1)
    val message = if (remaining > 0 && chapterMinutes <= minutes)
        "The end of this chapter is about $chapterMinutes min away."
    else if (remaining > 0) "About ${rate * minutes} words; this chapter ends in ~$chapterMinutes min."
    else "About ${rate * minutes} words at ${if (measured.isEmpty()) "an estimated" else "your"} $rate words/min."
    return ReadingTimeEstimate(rate, measured.isNotEmpty(), message)
}
