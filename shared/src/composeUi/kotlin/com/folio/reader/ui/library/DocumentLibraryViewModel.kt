package com.folio.reader.ui.library

import com.folio.reader.database.DocumentCategoryRepository
import com.folio.reader.database.DocumentRepository
import com.folio.reader.database.SettingsRepository
import com.folio.reader.model.Document
import com.folio.reader.model.DocumentCategory
import com.folio.reader.model.DocumentFormat
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class DocumentViewMode { GRID, LIST }

enum class DocumentSortBy(val label: String) {
    TITLE("Title"),
    LAST_OPENED("Last opened"),
    IMPORT_DATE("Import date"),
    SIZE("Size"),
    FORMAT("Format"),
    PROGRESS("Progress")
}

data class DocumentLibraryItem(
    val document: Document,
    val isLocalFileMissing: Boolean
)

data class DocumentLibraryState(
    val items: List<DocumentLibraryItem> = emptyList(),
    val query: String = "",
    val viewMode: DocumentViewMode = DocumentViewMode.GRID,
    val sortBy: DocumentSortBy = DocumentSortBy.LAST_OPENED,
    val sortAscending: Boolean = false,
    val formatFilter: DocumentFormat? = null,
    val isLoading: Boolean = true,
    val isImporting: Boolean = false,
    val errorMessage: String? = null
)

private data class DocumentDisplaySettings(
    val viewMode: DocumentViewMode,
    val sortBy: DocumentSortBy,
    val sortAscending: Boolean,
    val formatFilter: DocumentFormat?
)

private const val KEY_LIBRARY_CATEGORY = "document.library.category"

class DocumentLibraryViewModel(
    private val repository: DocumentRepository,
    private val categoryRepository: DocumentCategoryRepository,
    private val settingsRepository: SettingsRepository,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val fileExists: (String) -> Boolean = { File(it).isFile }
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    val query = MutableStateFlow("")
    val viewMode = MutableStateFlow(DocumentViewMode.GRID)
    val sortBy = MutableStateFlow(DocumentSortBy.LAST_OPENED)
    val sortAscending = MutableStateFlow(false)
    val formatFilter = MutableStateFlow<DocumentFormat?>(null)
    val isImporting = MutableStateFlow(false)
    val selectedIds = MutableStateFlow<Set<String>>(emptySet())
    val isSelectionMode = MutableStateFlow(false)
    val selectedCategoryId = MutableStateFlow<String?>(null)
    val bulkPickerInitial = MutableStateFlow<Set<String>?>(null)
    val categories: StateFlow<List<DocumentCategory>> = categoryRepository.observeCategories()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())
    private val reload = MutableStateFlow(0)

    private val categoryMembership: StateFlow<Set<String>?> = selectedCategoryId
        .flatMapLatest { id ->
            if (id == null) flowOf<Set<String>?>(null)
            else categoryRepository.observeDocumentIdsInCategory(id).map { it as Set<String>? }
        }
        .stateIn(scope, SharingStarted.Eagerly, null)

    private val documents: Flow<Result<List<Document>>> = combine(query, reload) { value, _ -> value }
        .flatMapLatest { value ->
            if (value.isBlank()) repository.observeDocuments()
            else repository.searchDocuments(value.trim())
        }
        .map { Result.success(it) }
        .catch { emit(Result.failure(it)) }

    private val filteredDocuments = combine(documents, categoryMembership) { result, membership ->
        result.map { source ->
            if (membership == null) source else source.filter { it.id in membership }
        }
    }

    private val displaySettings = combine(
        viewMode,
        sortBy,
        sortAscending,
        formatFilter
    ) { viewMode, sortBy, ascending, formatFilter ->
        DocumentDisplaySettings(viewMode, sortBy, ascending, formatFilter)
    }

    val state: StateFlow<DocumentLibraryState> = combine(
        filteredDocuments,
        query,
        displaySettings,
        isImporting
    ) { result, query, settings, importing ->
        result.fold(
            onSuccess = { source ->
                val matching = filterDocuments(source, query, settings.formatFilter)
                DocumentLibraryState(
                    items = sortDocuments(matching, settings.sortBy, settings.sortAscending).map { document ->
                        DocumentLibraryItem(
                            document = document,
                            isLocalFileMissing = document.localPath.isNullOrBlank() ||
                                !fileExists(document.localPath)
                        )
                    },
                    query = query,
                    viewMode = settings.viewMode,
                    sortBy = settings.sortBy,
                    sortAscending = settings.sortAscending,
                    formatFilter = settings.formatFilter,
                    isLoading = false,
                    isImporting = importing
                )
            },
            onFailure = { error ->
                DocumentLibraryState(
                    query = query,
                    viewMode = settings.viewMode,
                    sortBy = settings.sortBy,
                    sortAscending = settings.sortAscending,
                    formatFilter = settings.formatFilter,
                    isLoading = false,
                    isImporting = importing,
                    errorMessage = error.message ?: "Could not load documents"
                )
            }
        )
    }.stateIn(scope, SharingStarted.Eagerly, DocumentLibraryState())

    init {
        scope.launch {
            categoryRepository.ensureSeeded()
        }
        scope.launch {
            categories.collect { list ->
                val current = selectedCategoryId.value
                if (list.none { it.id == current }) {
                    val remembered = if (current == null) settingsRepository.getRaw(KEY_LIBRARY_CATEGORY) else null
                    val target = list.firstOrNull { it.id == remembered }
                        ?: list.firstOrNull { it.id == DocumentCategory.MAIN_ID }
                        ?: list.firstOrNull()
                    target?.let { selectCategory(it.id) }
                }
            }
        }
    }

    fun selectCategory(categoryId: String) {
        selectedCategoryId.value = categoryId
        scope.launch { settingsRepository.setRaw(KEY_LIBRARY_CATEGORY, categoryId) }
    }

    suspend fun createCategory(name: String): String? =
        runCatching { categoryRepository.create(name).id }.getOrNull()

    fun renameCategory(id: String, name: String) {
        scope.launch { categoryRepository.rename(id, name) }
    }

    fun deleteCategory(id: String) {
        scope.launch {
            if (categoryRepository.delete(id) && selectedCategoryId.value == id) {
                categoryRepository.defaultCategory()?.let { selectCategory(it.id) }
            }
        }
    }

    suspend fun categoriesFor(documentId: String): Set<String> =
        categoryRepository.categoriesFor(documentId)

    fun setCategoriesFor(documentId: String, categoryIds: Set<String>) {
        scope.launch {
            val target = categoryIds.ifEmpty {
                categoryRepository.defaultCategory()?.let { setOf(it.id) } ?: return@launch
            }
            categoryRepository.assign(documentId, target)
        }
    }

    fun requestBulkCategories() {
        val ids = selectedIds.value
        if (ids.isEmpty()) return
        scope.launch {
            val assignments = ids.map { categoryRepository.categoriesFor(it) }
            bulkPickerInitial.value = sharedCategoryIds(assignments)
        }
    }

    fun closeBulkPicker() {
        bulkPickerInitial.value = null
    }

    fun applyBulkCategories(categoryIds: Set<String>) {
        val ids = selectedIds.value
        if (ids.isEmpty()) return
        scope.launch {
            val target = categoryIds.ifEmpty {
                categoryRepository.defaultCategory()?.let { setOf(it.id) } ?: return@launch
            }
            ids.forEach { categoryRepository.assign(it, target) }
        }
    }

    fun toggleSelection(documentId: String) {
        val next = if (documentId in selectedIds.value) {
            selectedIds.value - documentId
        } else {
            selectedIds.value + documentId
        }
        selectedIds.value = next
        isSelectionMode.value = next.isNotEmpty()
    }

    fun clearSelection() {
        selectedIds.value = emptySet()
        isSelectionMode.value = false
    }

    fun setQuery(value: String) {
        query.value = value
    }

    fun setViewMode(value: DocumentViewMode) {
        viewMode.value = value
    }

    fun setSort(value: DocumentSortBy) {
        sortBy.value = value
    }

    fun setSortAscending(value: Boolean) {
        sortAscending.value = value
    }

    fun toggleSortDirection() {
        sortAscending.value = !sortAscending.value
    }

    fun setFormatFilter(value: DocumentFormat?) {
        formatFilter.value = value
    }

    fun setImporting(value: Boolean) {
        isImporting.value = value
    }

    fun retry() {
        reload.value += 1
    }

    suspend fun rename(document: Document, title: String) {
        val trimmed = title.trim()
        if (trimmed.isNotEmpty() && trimmed != document.title) {
            repository.upsertDocument(document.copy(title = trimmed))
        }
    }

    override fun close() {
        scope.cancel()
    }
}

internal fun sharedCategoryIds(assignments: List<Set<String>>): Set<String> =
    assignments.reduceOrNull { shared, categoryIds -> shared.intersect(categoryIds) } ?: emptySet()

internal fun filterDocuments(
    documents: List<Document>,
    query: String,
    formatFilter: DocumentFormat? = null
): List<Document> {
    val term = query.trim()
    return documents.filter { document ->
        (formatFilter == null || document.format == formatFilter) &&
            (term.isEmpty() ||
                document.title.contains(term, ignoreCase = true) ||
                document.originalFilename.contains(term, ignoreCase = true) ||
                document.author?.contains(term, ignoreCase = true) == true ||
                document.description?.contains(term, ignoreCase = true) == true ||
                document.format.name.contains(term, ignoreCase = true) ||
                document.mimeType.contains(term, ignoreCase = true))
    }
}

internal fun sortDocuments(
    documents: List<Document>,
    sortBy: DocumentSortBy,
    ascending: Boolean
): List<Document> {
    val comparator = when (sortBy) {
        DocumentSortBy.TITLE -> compareBy<Document> { it.title.lowercase() }
        DocumentSortBy.LAST_OPENED -> compareBy { it.lastOpenedAt?.toEpochMilliseconds() ?: Long.MIN_VALUE }
        DocumentSortBy.IMPORT_DATE -> compareBy { it.importedAt.toEpochMilliseconds() }
        DocumentSortBy.SIZE -> compareBy { it.byteSize }
        DocumentSortBy.FORMAT -> compareBy<Document> { it.format.name }.thenBy { it.title.lowercase() }
        DocumentSortBy.PROGRESS -> compareBy { it.normalizedProgress }
    }
    return documents.sortedWith(if (ascending) comparator else comparator.reversed())
}
