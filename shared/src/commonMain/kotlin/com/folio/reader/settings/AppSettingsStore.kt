package com.folio.reader.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The app's live view of the global settings row.
 *
 * The row itself is only ever read on demand: `getGlobalSettings()` is a suspend
 * that returns a snapshot, so anything built on it — `flow { emit(get()) }` in
 * particular — fires exactly once and then goes quiet forever. That is how
 * [ReaderSettings.homeCoverTint], [ReaderSettings.ambientColor] and
 * [ReaderSettings.dailyGoalMinutes] all ended up needing an app restart to apply:
 * Home held its own private copy of a row the rest of the app was already watching.
 *
 * The app root already had the right idea — `FolioNavModelImpl.globalSettings` is
 * Compose state, patched optimistically on save and reconciled with the merged row
 * the repository returns. This is the same value on the side of the seam where a
 * ViewModel can reach it, because a ViewModel cannot read a `mutableStateOf`.
 *
 * **One writer.** Publish only from the property that owns the Compose state, never
 * alongside it. Two places that both have to remember to update is how this class
 * would become a second stale copy of the thing it exists to replace.
 */
class AppSettingsStore {
    private val _state = MutableStateFlow(ReaderSettings())

    /** Emits the current row immediately on collection, then every change. */
    val state: StateFlow<ReaderSettings> = _state.asStateFlow()

    /** The row right now, for callers that are not observing. */
    val current: ReaderSettings get() = _state.value

    fun publish(settings: ReaderSettings) {
        _state.value = settings
    }
}
