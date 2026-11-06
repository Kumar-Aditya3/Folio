package com.folio.reader.ui.home

import com.folio.reader.manga.MangaDetail
import com.folio.reader.manga.MangaEntry
import com.folio.reader.model.Book
import com.folio.reader.model.BookStatus

/** Shared normalization keeps punctuation, casing and empty metadata from creating false links. */
internal fun recommendationKey(value: String): String = value.lowercase()
    .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

private val unknownCreators = setOf("", "unknown", "unknown author", "unknown artist", "n a", "none", "anonymous")
private fun creators(values: List<String>): Set<String> = values
    .flatMap { it.split(Regex("[;&]+")) }
    .map(::recommendationKey).filterNot { it in unknownCreators }.toSet()

internal fun recommendationReason(anchor: Book?, candidate: Book): String? = when {
    anchor == null || anchor.id == candidate.id -> null
    !anchor.seriesId.isNullOrBlank() && anchor.seriesId == candidate.seriesId -> "Same series"
    creators(anchor.authors).intersect(creators(candidate.authors)).isNotEmpty() -> "By the same author"
    else -> null
}

/** Next volumes precede other works by the author; repository order never determines the picks. */
internal fun rankBookRecommendations(anchor: Book?, books: List<Book>, limit: Int = 6): List<Book> {
    if (anchor == null) return emptyList()
    return books.asSequence()
        .filter { it.status == BookStatus.UNREAD && it.id != anchor.id }
        .filter { recommendationReason(anchor, it) != null }
        .filterNot { !anchor.seriesId.isNullOrBlank() && it.seriesId == anchor.seriesId && anchor.seriesNumber != null &&
            it.seriesNumber != null && it.seriesNumber <= anchor.seriesNumber }
        .sortedWith(compareBy<Book> {
            if (!anchor.seriesId.isNullOrBlank() && it.seriesId == anchor.seriesId) 0 else 1
        }.thenBy { if (it.seriesId == anchor.seriesId) it.seriesNumber ?: Double.MAX_VALUE else Double.MAX_VALUE }
            .thenBy { recommendationKey(it.displayTitle) }.thenBy { it.id })
        .distinctBy { it.epubHash.takeIf(String::isNotBlank) ?: it.id }
        .take(limit).toList()
}

internal data class MangaAffinity(val score: Double, val reason: String)

// One broad label alone says little about taste. A creator, two genres, or a specific genre does.
private val broadGenres = setOf("action", "adventure", "comedy", "drama", "fantasy", "romance")
private val nonContentGenres = setOf("shounen", "shonen", "shoujo", "shojo", "seinen", "josei",
    "manga", "manhwa", "manhua", "webtoon", "webtoons", "ongoing", "completed", "unknown")

internal fun mangaAffinity(candidate: MangaDetail, recentReads: List<MangaEntry>): MangaAffinity? {
    val candidateCreators = creators(listOfNotNull(candidate.author, candidate.artist))
    val genres = candidate.genres.associateBy(::recommendationKey).filterKeys { it.isNotBlank() && it !in nonContentGenres }
    return recentReads.take(5).mapIndexedNotNull { index, anchor ->
        val sharedCreators = candidateCreators.intersect(creators(listOfNotNull(anchor.author, anchor.artist)))
        val anchorGenres = anchor.genres.map(::recommendationKey).filter { it.isNotBlank() && it !in nonContentGenres }.toSet()
        val shared = genres.keys.intersect(anchorGenres)
        if (sharedCreators.isEmpty() && shared.size < 2 && shared.none { it !in broadGenres }) return@mapIndexedNotNull null
        val overlap = shared.size.toDouble() / (genres.keys + anchorGenres).size.coerceAtLeast(1)
        val score = (if (sharedCreators.isNotEmpty()) 120.0 else 0.0) + overlap * 60 + shared.size * 8
        val reason = if (sharedCreators.isNotEmpty()) "Shares a creator with ${anchor.title}"
            else "Like ${anchor.title} · ${shared.sorted().take(2).joinToString(", ") { genres.getValue(it) }}"
        MangaAffinity(score * (1.0 - index * .08), reason)
    }.maxByOrNull { it.score }
}
