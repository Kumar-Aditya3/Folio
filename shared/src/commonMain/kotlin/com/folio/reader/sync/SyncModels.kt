package com.folio.reader.sync

import com.folio.reader.model.Book
import com.folio.reader.model.Bookmark
import com.folio.reader.model.Collection
import com.folio.reader.model.Highlight
import com.folio.reader.model.Note
import com.folio.reader.model.Quote
import com.folio.reader.model.ReadingCycle
import com.folio.reader.model.ReadingPosition
import com.folio.reader.model.ReadingSession
import com.folio.reader.model.RevisitItem
import com.folio.reader.model.Series
import com.folio.reader.model.Tag
import com.folio.reader.settings.ReaderSettings
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
sealed class SyncEntity {
    @Serializable
    data class BookEntity(
        val book: Book,
        val operation: SyncOperation
    ) : SyncEntity()

    @Serializable
    data class PositionEntity(
        val position: ReadingPosition,
        val operation: SyncOperation
    ) : SyncEntity()

    @Serializable
    data class HighlightEntity(
        val highlight: Highlight,
        val operation: SyncOperation
    ) : SyncEntity()

    @Serializable
    data class NoteEntity(
        val note: Note,
        val operation: SyncOperation
    ) : SyncEntity()

    @Serializable
    data class BookmarkEntity(
        val bookmark: Bookmark,
        val operation: SyncOperation
    ) : SyncEntity()

    @Serializable
    data class SessionEntity(
        val session: ReadingSession,
        val operation: SyncOperation
    ) : SyncEntity()

    @Serializable
    data class CycleEntity(
        val cycle: ReadingCycle,
        val operation: SyncOperation
    ) : SyncEntity()

    @Serializable
    data class SettingsEntity(
        val settings: ReaderSettings,
        val operation: SyncOperation
    ) : SyncEntity()

    @Serializable
    data class TagEntity(
        val tag: Tag,
        val operation: SyncOperation
    ) : SyncEntity()

    @Serializable
    data class CollectionEntity(
        val collection: Collection,
        val operation: SyncOperation
    ) : SyncEntity()

    @Serializable
    data class SeriesEntity(
        val series: Series,
        val operation: SyncOperation
    ) : SyncEntity()

    @Serializable
    data class QuoteEntity(
        val quote: Quote,
        val operation: SyncOperation
    ) : SyncEntity()

    @Serializable
    data class RevisitEntity(
        val revisit: RevisitItem,
        val operation: SyncOperation
    ) : SyncEntity()
}

enum class SyncOperation(val value: Int) {
    CREATE(0),
    UPDATE(1),
    DELETE(2),
    UPSERT(1);

    companion object {
        fun fromValue(value: Int): SyncOperation = values().firstOrNull { it.value == value } ?: CREATE
        fun fromString(name: String): SyncOperation = runCatching { valueOf(name) }.getOrDefault(UPDATE)
    }
}

@Serializable
data class SyncQueueItem(
    val id: String,
    val entityType: String, // "book", "position", "highlight", etc.
    val entityId: String,
    val operation: SyncOperation,
    val payload: String, // JSON
    val createdAt: Instant = Clock.System.now(),
    val retryCount: Int = 0,
    val lastAttemptAt: Instant? = null,
    val status: SyncStatus = SyncStatus.PENDING
)

enum class SyncStatus(val value: Int) {
    PENDING(0),
    SYNCING(1),
    SYNCED(2),
    ERROR(3);

    companion object {
        fun fromValue(value: Int): SyncStatus = values().firstOrNull { it.value == value } ?: PENDING
    }
}

@Serializable
data class SyncResult(
    val success: Boolean,
    val syncedCount: Int = 0,
    val errorCount: Int = 0,
    val errors: List<String> = emptyList(),
    val conflicts: List<SyncConflict> = emptyList(),
    val lastSyncAt: Instant = Clock.System.now()
)

@Serializable
data class SyncConflict(
    val entityType: String,
    val entityId: String,
    val localVersion: String, // JSON
    val remoteVersion: String, // JSON
    val conflictType: ConflictType,
    val resolution: ConflictResolution? = null
)

enum class ConflictType {
    SCALAR_FIELD,      // last-write-wins
    INDEPENDENT_ENTITY, // merge
    READING_POSITION,   // latest updatedAt + user prompt
    SETTINGS,           // last-write-wins per device
    COLLECTION          // merge
}

enum class ConflictResolution {
    LOCAL_WINS,
    REMOTE_WINS,
    MERGE,
    USER_CHOICE,
    KEEP_BOTH
}

@Serializable
data class BookStorageProgress(
    val bookId: String,
    val percent: Float = 0f,
    val isUpload: Boolean = false,
    val startedAt: Instant? = null,
    val error: String? = null
)

@Serializable
data class SyncState(
    val lastFullSyncAt: Instant? = null,
    val lastIncrementalSyncAt: Instant? = null,
    val pendingUploadCount: Int = 0,
    val pendingDownloadCount: Int = 0,
    val isSyncing: Boolean = false,
    val lastError: String? = null,
    val isConfigured: Boolean = true,
    val deviceStates: Map<String, DeviceSyncState> = emptyMap(),
    val storageProgress: Map<String, BookStorageProgress> = emptyMap()
)

@Serializable
data class DeviceSyncState(
    val deviceId: String,
    val lastSyncedAt: Instant? = null,
    val pendingCount: Int = 0,
    val hasErrors: Boolean = false
)

@Serializable
data class SyncConfig(
    val autoSync: Boolean = true,
    val syncIntervalMinutes: Int = 10,
    val wifiOnly: Boolean = true,
    val syncAnnotations: Boolean = true,
    val syncPositions: Boolean = true,
    val syncSettings: Boolean = true,
    val syncBooks: Boolean = true, // EPUB files
    val maxRetries: Int = 3,
    val retryDelaySeconds: Int = 30
)
