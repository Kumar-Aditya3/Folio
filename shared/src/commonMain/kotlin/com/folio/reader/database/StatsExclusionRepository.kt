package com.folio.reader.database

import com.folio.reader.statistics.Scope
import kotlinx.coroutines.flow.Flow

/**
 * §11.2 stats exclusions: the user's list of library entities kept out of the
 * statistics (Statistics tab, Home, manga stats all consume the same set via
 * `StatsScope`). Backed by the single `stats_exclusions` table.
 */
interface StatsExclusionRepository {
    fun observeExclusions(): Flow<Set<Pair<Scope, String>>>
    suspend fun add(scope: Scope, targetId: String)
    suspend fun remove(scope: Scope, targetId: String)
}
