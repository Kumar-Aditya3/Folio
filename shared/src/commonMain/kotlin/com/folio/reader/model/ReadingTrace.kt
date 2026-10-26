package com.folio.reader.model

import kotlin.math.max
import kotlin.math.min

/** A reading record, never generated from a title hash or an invented activity score. */
data class ReadingTraceSpan(val from: Float, val to: Float, val minutes: Float)

data class ReadingTracePassage(
    val id: String,
    val chapterId: String,
    val spineIndex: Int,
    /** Chapter-relative placement, not a claim about exact word position. */
    val fraction: Float,
    val text: String,
    val hasNote: Boolean,
)

data class ReadingTrace(
    val progress: Float = 0f,
    val spans: List<ReadingTraceSpan> = emptyList(),
    val passages: List<ReadingTracePassage> = emptyList(),
    val sessionCount: Int = 0,
    val minutes: Long = 0,
) {
    val summary: String get() = buildList {
        if (sessionCount > 0) add("$sessionCount ${if (sessionCount == 1) "sitting" else "sittings"}")
        if (passages.isNotEmpty()) add("${passages.size} ${if (passages.size == 1) "passage" else "passages"}")
    }.joinToString(" · ").ifBlank { "Your reading trace starts here" }
}

fun readingTrace(
    progress: Float,
    chapterCount: Int,
    sessions: List<ReadingSession>,
    highlights: List<Highlight>,
    chapters: List<Chapter> = emptyList(),
): ReadingTrace {
    val ordered = chapters.sortedBy { it.spineIndex }
    val total = ordered.sumOf { it.wordCount.coerceAtLeast(0L) }
    var offset = 0.0
    val bands = ordered.associate { chapter ->
        val weight = if (total > 0) chapter.wordCount.coerceAtLeast(0L).toDouble() / total else 1.0 / ordered.size.coerceAtLeast(1)
        (chapter.id to (offset.toFloat() to weight.toFloat())).also { offset += weight }
    }
    val measured = sessions.distinctBy { it.id }.filter {
        it.startedAt >= ReadingSession.FIRST_MEASURED_SESSION && it.durationMs > 0 &&
            it.startProgress.isFinite() && it.endProgress.isFinite()
    }.sortedBy { it.startedAt }
    val spans = measured.mapNotNull {
        val from = min(it.startProgress, it.endProgress).toFloat().coerceIn(0f, 1f)
        val to = max(it.startProgress, it.endProgress).toFloat().coerceIn(0f, 1f)
        if (to - from <= .0001f) null else ReadingTraceSpan(from, to, it.durationMs / 60_000f)
    }.takeLast(48)
    val passages = highlights.filterNot { it.isDeleted }.distinctBy { it.id }
        .sortedWith(compareBy({ it.spineIndex }, { it.startLocator }, { it.id })).map {
            val local = it.startLocator.locatorFraction() ?: .5f
            val band = bands[it.chapterId]
            ReadingTracePassage(it.id, it.chapterId, it.spineIndex,
                (if (band != null) band.first + band.second * local
                else (it.spineIndex.coerceAtLeast(0) + local) / chapterCount.coerceAtLeast(1)).coerceIn(0f, 1f),
                it.selectedText, it.noteId != null)
        }
    return ReadingTrace(if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f,
        spans, passages, measured.distinctBy { it.activity?.sittingId ?: it.id }.size, measured.sumOf { it.durationMs } / 60_000)
}
