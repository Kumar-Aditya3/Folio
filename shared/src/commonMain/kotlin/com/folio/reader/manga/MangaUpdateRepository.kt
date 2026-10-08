package com.folio.reader.manga

import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Instant

/** One row of manga_update_state: the per-manga new-chapter badge. */
data class MangaUpdateState(
    val mangaId: String,
    val lastCheckedAt: Instant,
    /** Accumulates until the reader clears it; a check finding zero new chapters leaves it unchanged. */
    val newChapterCount: Int,
    val lastError: String? = null,
)

/** Totals for one update-check run. Counts reflect what this run discovered, not the stored badge. */
data class MangaUpdateRunResult(
    val mangaChecked: Int,
    val seriesWithNewChapters: Int,
    val newChaptersTotal: Int,
)

/** One Home "New chapters" row (§11.4): a library manga with new_chapter_count > 0. */
data class MangaNewChapterBadge(
    val mangaId: String,
    val title: String,
    val sourceId: Long,
    val thumbnailUrl: String?,
    val coverPath: String?,
    val newChapterCount: Int,
    val lastCheckedAt: Instant,
)

/**
 * Background manga chapter update checks (§11.3). Runs are rate-limited and capped by
 * the implementation; per-manga failures never propagate, they land in
 * [MangaUpdateState.lastError].
 */
interface MangaUpdateRepository {

    /** One pass over the in-library manga: fetch remote chapter lists, diff by url, replace chapters. */
    suspend fun runUpdateCheck(): MangaUpdateRunResult

    fun observeUpdateStates(): Flow<List<MangaUpdateState>>

    /** Resets the badge once the reader has shown the new chapters. */
    suspend fun clearNewChapters(mangaId: String)

    /**
     * Up to 6 library manga with new chapters, newest check first. Joins the library
     * table (orphaned rows for removed manga never surface) and resolves the
     * §11.2/§12.9 manga exclusions so excluded titles never reach Home.
     */
    suspend fun getNewChapterBadges(
        exclusions: Set<Pair<com.folio.reader.statistics.Scope, String>> = emptySet()
    ): List<MangaNewChapterBadge>
}
