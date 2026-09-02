package com.folio.reader.statistics

import com.folio.reader.model.BookStatus

/**
 * What kind of entity a stats-exclusion row targets (§11.2). Stored as its name
 * in the `stats_exclusions.scope` column. `BOOK_STATUS` rows carry the enum
 * name as their target id, so "exclude every DNF book" is one row that keeps
 * working as more books are abandoned.
 */
enum class Scope { BOOK, BOOK_TAG, BOOK_COLLECTION, BOOK_SERIES, BOOK_STATUS, MANGA, MANGA_CATEGORY, MANGA_SOURCE }

/**
 * The one resolver for stats exclusions (§11.2) — the single place that answers
 * "does this title count toward the statistics?".
 *
 * Resolution rule (v1, deliberately one-way): an entity is excluded if it is
 * listed directly **or** any group it belongs to is listed. There is no
 * per-entity "include anyway" override — two-way overrides are what make these
 * systems unexplainable; a user who needs one removes the group exclusion.
 *
 * The same instance feeds [StatisticsViewModel], Home and the manga statistics,
 * so the three surfaces can never drift apart on this question.
 */
class StatsScope(private val excluded: Set<Pair<Scope, String>>) {

    /** Target ids indexed by scope so the common empty-exclusions case is one map miss. */
    private val idsByScope: Map<Scope, Set<String>> =
        excluded.groupBy({ it.first }, { it.second }).mapValues { (_, ids) -> ids.toSet() }

    private fun isExcluded(scope: Scope, targetId: String): Boolean =
        idsByScope[scope]?.contains(targetId) == true

    /**
     * True when the book counts toward the statistics. A book is excluded when
     * listed directly, when any of its tags or collections is listed, when its
     * series is listed (which removes every book sharing that seriesId), or
     * when its status is listed.
     */
    fun includesBook(
        bookId: String,
        tagIds: Set<String>,
        collectionIds: Set<String>,
        seriesId: String?,
        status: BookStatus
    ): Boolean {
        if (isExcluded(Scope.BOOK, bookId)) return false
        if (tagIds.any { isExcluded(Scope.BOOK_TAG, it) }) return false
        if (collectionIds.any { isExcluded(Scope.BOOK_COLLECTION, it) }) return false
        if (seriesId != null && isExcluded(Scope.BOOK_SERIES, seriesId)) return false
        if (isExcluded(Scope.BOOK_STATUS, status.name)) return false
        return true
    }

    /**
     * True when the manga counts toward the statistics, under the same one-way
     * rule: listed directly, in an excluded category, or from an excluded source.
     */
    fun includesManga(
        mangaId: String,
        categoryIds: Set<String>,
        sourceId: Long
    ): Boolean {
        if (isExcluded(Scope.MANGA, mangaId)) return false
        if (categoryIds.any { isExcluded(Scope.MANGA_CATEGORY, it) }) return false
        if (isExcluded(Scope.MANGA_SOURCE, sourceId.toString())) return false
        return true
    }
}
