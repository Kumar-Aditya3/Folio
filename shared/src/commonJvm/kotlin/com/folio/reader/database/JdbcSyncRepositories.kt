package com.folio.reader.database

import com.folio.reader.sync.SyncOperation
import com.folio.reader.sync.SyncQueueItem
import com.folio.reader.sync.SyncStatus
import com.folio.reader.sync.SyncState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

class JdbcSyncQueueRepository(private val db: Database) : SyncRepository {

    override suspend fun enqueueSync(
        entityType: String,
        entityId: String,
        operation: SyncOperation,
        payload: String
    ) {
        try {
            val itemId = "$entityType:$entityId"
            val item = SyncQueueItem(
                id = itemId,
                entityType = entityType,
                entityId = entityId,
                operation = operation,
                payload = payload,
                createdAt = Clock.System.now(),
                retryCount = 0,
                status = SyncStatus.PENDING
            )
            db.withConnection { conn ->
                conn.prepareStatement(
                    """
                    INSERT OR REPLACE INTO sync_queue
                        (id, entity_type, entity_id, operation, payload, created_at, retry_count, last_attempt_at, status)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent()
                ).use { stmt ->
                    stmt.setString(1, item.id)
                    stmt.setString(2, item.entityType)
                    stmt.setString(3, item.entityId)
                    stmt.setInt(4, item.operation.value)
                    stmt.setString(5, item.payload)
                    stmt.setLong(6, item.createdAt.toEpochMilliseconds())
                    stmt.setInt(7, item.retryCount)
                    stmt.setString(8, item.lastAttemptAt?.toEpochMilliseconds()?.toString())
                    stmt.setInt(9, item.status.value)
                    stmt.executeUpdate()
                }
            }
        } catch (e: Exception) {
            // Silently fail - sync queue is non-critical for core reading functionality
            // Log for debugging but don't crash the app
            e.printStackTrace()
        }
    }

    override suspend fun getPendingSync(limit: Int): List<SyncQueueItem> {
        return db.withConnection { conn ->
            val items = mutableListOf<SyncQueueItem>()
            conn.prepareStatement(
                "SELECT * FROM sync_queue WHERE status IN (0, 3) AND retry_count < 3 ORDER BY created_at LIMIT ?"
            ).use { stmt ->
                stmt.setInt(1, limit)
                stmt.executeQuery().use { rs ->
                    while (rs.next()) items.add(mapRow(rs))
                }
            }
            items
        }
    }

    override suspend fun getPendingSyncCount(): Int = db.withConnection { conn ->
        conn.prepareStatement(
            "SELECT COUNT(*) FROM sync_queue WHERE status IN (0, 3) AND retry_count < ?"
        ).use { stmt ->
            stmt.setInt(1, MAX_RETRY_COUNT)
            stmt.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
    }

    override suspend fun recoverStaleSyncing(before: Instant) {
        db.withConnection { conn ->
            conn.prepareStatement(
                "UPDATE sync_queue SET status = ? WHERE status = ? AND last_attempt_at < ?"
            ).use { stmt ->
                stmt.setInt(1, SyncStatus.ERROR.value)
                stmt.setInt(2, SyncStatus.SYNCING.value)
                stmt.setLong(3, before.toEpochMilliseconds())
                stmt.executeUpdate()
            }
        }
    }

    override suspend fun markSyncing(id: String) = updateStatus(id, SyncStatus.SYNCING)

    override suspend fun markSynced(id: String) {
        db.withConnection { conn ->
            conn.prepareStatement("DELETE FROM sync_queue WHERE id = ?").use { stmt ->
                stmt.setString(1, id)
                stmt.executeUpdate()
            }
        }
    }

    override suspend fun markError(id: String) {
        db.withConnection { conn ->
            conn.prepareStatement(
                """
                UPDATE sync_queue
                SET status = ?, retry_count = retry_count + 1, last_attempt_at = ?
                WHERE id = ?
                """.trimIndent()
            ).use { stmt ->
                stmt.setInt(1, SyncStatus.ERROR.value)
                stmt.setLong(2, Clock.System.now().toEpochMilliseconds())
                stmt.setString(3, id)
                stmt.executeUpdate()
            }
        }
    }

    override suspend fun clearSynced(before: Instant) {
        // Synced items are deleted immediately by markSynced; this clears errored
        // items that exhausted their retry budget before the cutoff.
        db.withConnection { conn ->
            conn.prepareStatement(
                "DELETE FROM sync_queue WHERE status = ? AND retry_count >= ? AND created_at < ?"
            ).use { stmt ->
                stmt.setInt(1, SyncStatus.ERROR.value)
                stmt.setInt(2, MAX_RETRY_COUNT)
                stmt.setLong(3, before.toEpochMilliseconds())
                stmt.executeUpdate()
            }
        }
    }

    override fun getSyncState(): Flow<SyncState> = flow {
        emit(
            db.getSettings(KEY_SYNC_STATE)?.let {
                runCatching {
                    kotlinx.serialization.json.Json.decodeFromString(SyncState.serializer(), it)
                }.getOrNull()
            } ?: SyncState()
        )
    }

    override suspend fun updateSyncState(state: SyncState) {
        val json = kotlinx.serialization.json.Json.encodeToString(SyncState.serializer(), state)
        db.setSettings(KEY_SYNC_STATE, json)
    }

    private suspend fun updateStatus(id: String, status: SyncStatus) {
        db.withConnection { conn ->
            conn.prepareStatement("UPDATE sync_queue SET status = ?, last_attempt_at = ? WHERE id = ?").use { stmt ->
                stmt.setInt(1, status.value)
                stmt.setLong(2, Clock.System.now().toEpochMilliseconds())
                stmt.setString(3, id)
                stmt.executeUpdate()
            }
        }
    }

    private fun mapRow(rs: java.sql.ResultSet): SyncQueueItem = SyncQueueItem(
        id = rs.getString("id"),
        entityType = rs.getString("entity_type"),
        entityId = rs.getString("entity_id"),
        operation = SyncOperation.fromValue(rs.getInt("operation")),
        payload = rs.getString("payload"),
        createdAt = Instant.fromEpochMilliseconds(rs.getLong("created_at")),
        retryCount = rs.getInt("retry_count"),
        lastAttemptAt = rs.getString("last_attempt_at")?.toLongOrNull()?.let { Instant.fromEpochMilliseconds(it) },
        status = SyncStatus.fromValue(rs.getInt("status"))
    )

    companion object {
        const val KEY_SYNC_STATE = "sync_state"
        const val MAX_RETRY_COUNT = 3
    }
}

class JdbcDeviceRepository(private val db: Database) : DeviceRepository {

    override suspend fun upsertDevice(device: Device) {
        db.withConnection { conn ->
            conn.prepareStatement(
                """
                INSERT OR REPLACE INTO devices
                    (id, name, platform, app_version, last_seen_at, is_current, is_active)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, device.id)
                stmt.setString(2, device.name)
                stmt.setString(3, device.platform)
                stmt.setString(4, device.appVersion)
                stmt.setLong(5, device.lastSeenAt.toEpochMilliseconds())
                stmt.setInt(6, if (device.isCurrent) 1 else 0)
                stmt.setInt(7, if (device.isActive) 1 else 0)
                stmt.executeUpdate()
            }
        }
    }

    override suspend fun getDevice(deviceId: String): Device? {
        return db.withConnection { conn ->
            conn.prepareStatement("SELECT * FROM devices WHERE id = ?").use { stmt ->
                stmt.setString(1, deviceId)
                stmt.executeQuery().use { rs -> if (rs.next()) mapRow(rs) else null }
            }
        }
    }

    override fun getAllDevices(): Flow<List<Device>> = flow {
        val devices = db.withConnection { conn ->
            val out = mutableListOf<Device>()
            conn.prepareStatement("SELECT * FROM devices ORDER BY last_seen_at DESC").use { stmt ->
                stmt.executeQuery().use { rs ->
                    while (rs.next()) out.add(mapRow(rs))
                }
            }
            out
        }
        emit(devices)
    }

    override suspend fun deactivateDevice(deviceId: String) {
        db.withConnection { conn ->
            conn.prepareStatement("UPDATE devices SET is_active = 0 WHERE id = ?").use { stmt ->
                stmt.setString(1, deviceId)
                stmt.executeUpdate()
            }
        }
    }

    private fun mapRow(rs: java.sql.ResultSet): Device = Device(
        id = rs.getString("id"),
        name = rs.getString("name"),
        platform = rs.getString("platform"),
        appVersion = rs.getString("app_version"),
        lastSeenAt = Instant.fromEpochMilliseconds(rs.getLong("last_seen_at")),
        isCurrent = rs.getInt("is_current") == 1,
        isActive = rs.getInt("is_active") == 1
    )
}
