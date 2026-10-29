package com.folio.reader.database

import com.folio.reader.model.Document
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first

class ThumbnailDocumentRepository(
    private val database: Database,
    private val delegate: DocumentRepository
) : DocumentRepository by delegate {
    override fun observeDocuments() =
        delegate.observeDocuments().map { documents ->
            buildList {
                for (document in documents) {
                    add(document.withPersistedThumbnail())
                }
            }
        }

    override suspend fun getDocument(documentId: String) =
        delegate.getDocument(documentId)?.withPersistedThumbnail()

    override suspend fun getDocumentByHash(contentHash: String) =
        delegate.getDocumentByHash(contentHash)?.withPersistedThumbnail()

    override fun searchDocuments(query: String) =
        delegate.searchDocuments(query).map { documents ->
            buildList {
                for (document in documents) {
                    add(document.withPersistedThumbnail())
                }
            }
        }

    override suspend fun upsertDocument(document: Document) {
        delegate.upsertDocument(document)
        database.withConnection { connection ->
            connection.prepareStatement(
                "UPDATE documents SET thumbnail_path = ? WHERE id = ?"
            ).use { statement ->
                statement.setString(1, document.thumbnailPath)
                statement.setString(2, document.id)
                statement.executeUpdate()
            }
        }
    }

    override suspend fun deleteDocument(documentId: String) {
        database.withConnection { connection ->
            connection.prepareStatement(
                "DELETE FROM document_category_map WHERE document_id = ?"
            ).use { statement ->
                statement.setString(1, documentId)
                statement.executeUpdate()
            }
        }
        delegate.deleteDocument(documentId)
    }

    /** Change artwork without overwriting live reading progress or metadata. */
    suspend fun repairThumbnails(importer: com.folio.reader.importer.DocumentImporter,
        generatedPath: (String) -> String) {
        val documents = observeDocuments().first()
        for (document in documents) {
            if (document.thumbnailPath?.let { java.io.File(it).let { file -> file.isFile && file.length() > 0 } } == true) continue
            val source = if (document.format == com.folio.reader.model.DocumentFormat.PDF)
                document.localPath ?: continue else generatedPath(document.id)
            // A fresh path also invalidates a view that already tried the missing file.
            val path = importer.generateThumbnail(document.id, source, "preview-${java.util.UUID.randomUUID()}.png") ?: continue
            database.withConnection { connection ->
                connection.prepareStatement("UPDATE documents SET thumbnail_path = ? WHERE id = ?").use {
                    it.setString(1, path); it.setString(2, document.id); it.executeUpdate()
                }
            }
            database.bumpDocumentData()
            kotlinx.coroutines.yield()
        }
    }

    private suspend fun Document.withPersistedThumbnail(): Document =
        copy(
            thumbnailPath = database.withConnection { connection ->
                connection.prepareStatement(
                    "SELECT thumbnail_path FROM documents WHERE id = ?"
                ).use { statement ->
                    statement.setString(1, id)
                    statement.executeQuery().use { result ->
                        if (result.next()) result.getString(1) else null
                    }
                }
            }
        )
}
