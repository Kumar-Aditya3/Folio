package com.folio.reader.database

import com.folio.reader.statistics.Scope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * JDBC-backed stats exclusions (§11.2): one row per (scope, target_id) in the
 * `stats_exclusions` table created by [Database]'s schema init. Exclusions are
 * a local preference, not synced library data — nothing here touches the sync
 * queue.
 */
class JdbcStatsExclusionRepository(private val db: Database) : StatsExclusionRepository {

    override fun observeExclusions(): Flow<Set<Pair<Scope, String>>> = flow {
        emit(
            db.withConnection { conn ->
                conn.prepareStatement("SELECT scope, target_id FROM stats_exclusions").use { stmt ->
                    stmt.executeQuery().use { rs ->
                        val out = mutableSetOf<Pair<Scope, String>>()
                        while (rs.next()) {
                            // Tolerate a row written by a newer build with a scope this
                            // build does not know; dropping it beats crashing the stats tab.
                            val scope = runCatching { Scope.valueOf(rs.getString("scope")) }.getOrNull()
                            val targetId = rs.getString("target_id")
                            if (scope != null && targetId != null) out.add(scope to targetId)
                        }
                        out.toSet()
                    }
                }
            }
        )
    }

    override suspend fun add(scope: Scope, targetId: String) {
        db.withConnection { conn ->
            conn.prepareStatement(
                "INSERT OR IGNORE INTO stats_exclusions (scope, target_id) VALUES (?, ?)"
            ).use { stmt ->
                stmt.setString(1, scope.name)
                stmt.setString(2, targetId)
                stmt.executeUpdate()
            }
        }
    }

    override suspend fun remove(scope: Scope, targetId: String) {
        db.withConnection { conn ->
            conn.prepareStatement(
                "DELETE FROM stats_exclusions WHERE scope = ? AND target_id = ?"
            ).use { stmt ->
                stmt.setString(1, scope.name)
                stmt.setString(2, targetId)
                stmt.executeUpdate()
            }
        }
    }
}
