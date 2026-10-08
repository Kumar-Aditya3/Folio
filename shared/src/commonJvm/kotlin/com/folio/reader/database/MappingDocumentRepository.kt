package com.folio.reader.database

import com.folio.reader.model.Document
import kotlinx.coroutines.flow.map

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
