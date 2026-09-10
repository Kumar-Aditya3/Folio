package com.folio.reader.database

import com.folio.reader.model.DocumentCategory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import java.sql.ResultSet
import java.util.UUID

class JdbcDocumentCategoryRepository(
    private val db: Database
) : DocumentCategoryRepository {
    override suspend fun create(name: String): DocumentCategory {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty())
        val category = DocumentCategory(
            id = UUID.randomUUID().toString(),
            name = trimmed,
            sortOrder = nextSortOrder()
        )
        insert(category)
        db.bumpDocumentData()
        return category
    }

    override suspend fun rename(id: String, name: String) {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty())
        db.withConnection { connection ->
            connection.prepareStatement(
                "UPDATE document_categories SET name = ?, updated_at = ? WHERE id = ?"
            ).use { statement ->
                statement.setString(1, trimmed)
                statement.setLong(2, Clock.System.now().toEpochMilliseconds())
                statement.setString(3, id)
                statement.executeUpdate()
            }
        }
        db.bumpDocumentData()
    }

    override suspend fun delete(id: String): Boolean {
        if (get(id) == null) return false
        if (id == DocumentCategory.MAIN_ID && countCategories() <= 1) return false
        val affectedDocumentIds = documentIdsInCategory(id)
        db.withTransaction { connection ->
            connection.prepareStatement(
                "DELETE FROM document_category_map WHERE category_id = ?"
            ).use { statement ->
                statement.setString(1, id)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                "DELETE FROM document_categories WHERE id = ?"
            ).use { statement ->
                statement.setString(1, id)
                statement.executeUpdate()
            }
        }
        db.bumpDocumentData()
        affectedDocumentIds.forEach { ensureMembership(it) }
        return true
    }

    override fun observeCategories(): Flow<List<DocumentCategory>> =
        db.documentDataRevision.map {
            db.withConnection { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "SELECT id, name, sort_order, updated_at FROM document_categories " +
                            "ORDER BY sort_order, name"
                    ).use { result ->
                        buildList {
                            while (result.next()) add(result.toDocumentCategory())
                        }
                    }
                }
            }
        }

    override suspend fun get(id: String): DocumentCategory? =
        db.withConnection { connection ->
            connection.prepareStatement(
                "SELECT id, name, sort_order, updated_at FROM document_categories WHERE id = ?"
            ).use { statement ->
                statement.setString(1, id)
                statement.executeQuery().use { result ->
                    if (result.next()) result.toDocumentCategory() else null
                }
            }
        }

    override suspend fun defaultCategory(): DocumentCategory? {
        get(DocumentCategory.MAIN_ID)?.let { return it }
        return db.withConnection { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT id, name, sort_order, updated_at FROM document_categories " +
                        "ORDER BY sort_order, name LIMIT 1"
                ).use { result ->
                    if (result.next()) result.toDocumentCategory() else null
                }
            }
        }
    }

    override suspend fun assign(
        documentId: String,
        categoryIds: Set<String>
    ) {
        db.withTransaction { connection ->
            connection.prepareStatement(
                "DELETE FROM document_category_map WHERE document_id = ?"
            ).use { statement ->
                statement.setString(1, documentId)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                "INSERT INTO document_category_map (document_id, category_id) VALUES (?, ?)"
            ).use { statement ->
                categoryIds.forEach { categoryId ->
                    statement.setString(1, documentId)
                    statement.setString(2, categoryId)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
        }
        db.bumpDocumentData()
    }

    override suspend fun categoriesFor(documentId: String): Set<String> =
        db.withConnection { connection ->
            connection.prepareStatement(
                "SELECT category_id FROM document_category_map WHERE document_id = ?"
            ).use { statement ->
                statement.setString(1, documentId)
                statement.executeQuery().use { result ->
                    buildSet {
                        while (result.next()) add(result.getString(1))
                    }
                }
            }
        }

    override fun observeCategoriesFor(documentId: String): Flow<Set<String>> =
        db.documentDataRevision.map { categoriesFor(documentId) }

    override fun observeDocumentIdsInCategory(
        categoryId: String
    ): Flow<Set<String>> =
        db.documentDataRevision.map { documentIdsInCategory(categoryId) }

    override suspend fun documentIdsInCategory(categoryId: String): Set<String> =
        db.withConnection { connection ->
            connection.prepareStatement(
                "SELECT document_id FROM document_category_map WHERE category_id = ?"
            ).use { statement ->
                statement.setString(1, categoryId)
                statement.executeQuery().use { result ->
                    buildSet {
                        while (result.next()) add(result.getString(1))
                    }
                }
            }
        }

    override suspend fun ensureMembership(documentId: String) {
        if (categoriesFor(documentId).isNotEmpty()) return
        val target = defaultCategory() ?: run {
            ensureSeeded()
            defaultCategory()
        } ?: return
        assign(documentId, setOf(target.id))
    }

    override suspend fun ensureSeeded() {
        if (countCategories() == 0) {
            insert(
                DocumentCategory(
                    id = DocumentCategory.MAIN_ID,
                    name = DocumentCategory.MAIN_NAME
                )
            )
            db.bumpDocumentData()
        }
        val orphaned = db.withConnection { connection ->
            connection.prepareStatement(
                "SELECT d.id FROM documents d WHERE NOT EXISTS " +
                    "(SELECT 1 FROM document_category_map cm WHERE cm.document_id = d.id)"
            ).use { statement ->
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) add(result.getString(1))
                    }
                }
            }
        }
        orphaned.forEach { ensureMembership(it) }
    }

    private suspend fun nextSortOrder(): Int = db.withConnection { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT COALESCE(MAX(sort_order), -1) + 1 FROM document_categories"
            ).use { result ->
                if (result.next()) result.getInt(1) else 0
            }
        }
    }

    private suspend fun countCategories(): Int = db.withConnection { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT COUNT(*) FROM document_categories"
            ).use { result ->
                if (result.next()) result.getInt(1) else 0
            }
        }
    }

    private suspend fun insert(category: DocumentCategory) {
        db.withConnection { connection ->
            connection.prepareStatement(
                "INSERT OR REPLACE INTO document_categories " +
                    "(id, name, sort_order, updated_at) VALUES (?, ?, ?, ?)"
            ).use { statement ->
                statement.setString(1, category.id)
                statement.setString(2, category.name)
                statement.setInt(3, category.sortOrder)
                statement.setLong(4, category.updatedAt.toEpochMilliseconds())
                statement.executeUpdate()
            }
        }
    }

    private fun ResultSet.toDocumentCategory() = DocumentCategory(
        id = getString("id"),
        name = getString("name"),
        sortOrder = getInt("sort_order"),
        updatedAt = Instant.fromEpochMilliseconds(getLong("updated_at"))
    )
}
