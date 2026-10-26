package com.folio.reader.experience

import com.folio.reader.database.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Atomic patches preserve concurrent typography, library and cloud settings writes. */
class ReadingExperienceRepository(private val settings: SettingsRepository) {
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(ReadingExperienceData())
    val state = mutable.asStateFlow()
    suspend fun observe() { settings.observeReadingExperience().collect { mutable.value = it } }
    suspend fun refresh() = mutex.withLock { mutable.value = settings.getGlobalSettings().readingExperience }
    suspend fun update(transform: (ReadingExperienceData) -> ReadingExperienceData) = mutex.withLock {
        mutable.value = settings.mergeGlobalSettings { current ->
            current.copy(readingExperience = transform(current.readingExperience))
        }.readingExperience
    }
    suspend fun savePreset(preset: ReadingPreset) = update { it.copy(presets = it.presets.filterNot { p -> p.id == preset.id } + preset) }
    suspend fun removePreset(id: String) = update { it.copy(presets = it.presets.filterNot { p -> p.id == id }) }
    suspend fun saveReturn(context: ReadingReturnContext) = update { data -> data.copy(
        returns = data.returns.filterNot { it.bookId == context.bookId } + context) }
    suspend fun schedule(source: ExportSource, now: Long) = update { data -> data.copy(
        reviews = data.reviews.filterNot { it.source.id == source.id } + ReviewSchedule(source, now + ReviewSchedule.DAY_MS)) }
    suspend fun review(id: String, action: String, now: Long) = update { data -> data.copy(reviews = data.reviews.mapNotNull {
        if (it.source.id != id) it else when (action) {
            "remember" -> it.remember(now); "again" -> it.again(now); "snooze" -> it.snooze(now); else -> null
        }
    }) }
    suspend fun removeBook(id: String) = update { data -> data.copy(returns = data.returns.filterNot { it.bookId == id },
        reflections = data.reflections.filterNot { it.bookId == id }, reviews = data.reviews.filterNot { it.source.bookId == id }) }
    suspend fun removeSource(id: String) = update { it.copy(reviews = it.reviews.filterNot { r -> r.source.id == id },
        reflections = it.reflections.filterNot { r -> r.noteId == id }) }
    suspend fun volume(): Float = settings.getRaw("reading_sound_volume")?.toFloatOrNull()?.coerceIn(0f, 1f) ?: .35f
    suspend fun saveVolume(value: Float) = settings.setRaw("reading_sound_volume", value.coerceIn(0f, 1f).toString())
}

fun mergeReadingExperience(current: ReadingExperienceData, incoming: ReadingExperienceData) = incoming.copy(
    presets = (current.presets + incoming.presets).associateBy { it.id }.values.toList(),
    returns = (current.returns + incoming.returns).groupBy { it.bookId }.values.map { entries -> entries.maxBy { it.lastLeftAt } },
    reflections = (current.reflections + incoming.reflections).associateBy { it.noteId }.values.toList(),
    reviews = (current.reviews + incoming.reviews).associateBy { it.source.id }.values.toList(),
)

suspend fun pruneReadingExperience(db: com.folio.reader.database.Database, bookId: String? = null, sourceId: String? = null, emitSyncEvent: Boolean = true) {
    com.folio.reader.database.JdbcSettingsRepository(db).mergeGlobalSettings(emitSyncEvent) { settings ->
        val data = settings.readingExperience
        settings.copy(readingExperience = data.copy(
            returns = data.returns.filterNot { it.bookId == bookId }.map { if (it.reminderNoteId == sourceId) it.copy(reminder = "", reminderNoteId = null) else it },
            reflections = data.reflections.filterNot { it.bookId == bookId || it.noteId == sourceId },
            reviews = data.reviews.filterNot { it.source.bookId == bookId || it.source.id == sourceId }))
    }
}
