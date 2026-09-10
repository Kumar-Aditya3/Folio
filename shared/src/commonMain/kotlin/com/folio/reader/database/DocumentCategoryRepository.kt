package com.folio.reader.database

import com.folio.reader.model.DocumentCategory
import kotlinx.coroutines.flow.Flow

interface DocumentCategoryRepository {
    suspend fun create(name: String): DocumentCategory
    suspend fun rename(id: String, name: String)
    suspend fun delete(id: String): Boolean
    fun observeCategories(): Flow<List<DocumentCategory>>
    suspend fun get(id: String): DocumentCategory?
    suspend fun defaultCategory(): DocumentCategory?
    suspend fun assign(documentId: String, categoryIds: Set<String>)
    suspend fun categoriesFor(documentId: String): Set<String>
    fun observeCategoriesFor(documentId: String): Flow<Set<String>>
    fun observeDocumentIdsInCategory(categoryId: String): Flow<Set<String>>
    suspend fun documentIdsInCategory(categoryId: String): Set<String>
    suspend fun ensureMembership(documentId: String)
    suspend fun ensureSeeded()
}
