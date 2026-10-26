package com.folio.reader.database

import kotlinx.datetime.Clock

/** Reorder existing rows atomically; stale/deleted ids cannot recreate categories. */
internal suspend fun Database.reorderCategoryRows(table: String, requestedIds: List<String>): List<String> {
    require(table in setOf("collections", "manga_categories", "document_categories"))
    return withTransaction { connection ->
        val current = connection.createStatement().use { statement ->
            statement.executeQuery("SELECT id FROM $table ORDER BY sort_order, name").use { result ->
                buildList { while (result.next()) add(result.getString(1)) }
            }
        }
        val existing = current.toSet()
        val requested = requestedIds.distinct().filter { it in existing }
        val ordered = requested + current.filterNot { it in requested }
        if (ordered == current) return@withTransaction emptyList()
        val now = Clock.System.now().toEpochMilliseconds()
        connection.prepareStatement("UPDATE $table SET sort_order = ?, updated_at = ? WHERE id = ?").use { statement ->
            ordered.forEachIndexed { index, id ->
                statement.setInt(1, index)
                statement.setLong(2, now)
                statement.setString(3, id)
                statement.addBatch()
            }
            statement.executeBatch()
        }
        ordered
    }
}
