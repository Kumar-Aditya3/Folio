package com.folio.reader.ui.atlas

import com.folio.reader.ml.AtlasModel
import com.folio.reader.ml.AtlasReadiness
import com.folio.reader.ml.SemanticDiscoveryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Holds the Atlas map for the life of a visit.
 *
 * The roll-up is a few seconds on a large library, so it is run once on open behind a skeleton
 * and cached in the [SemanticDiscoveryRepository] for the session; this view model just drives
 * the screen through Loading → (Forming | TooFewBooks | Unavailable | Map). Exemplar passage
 * text is loaded lazily when the reader zooms into a book, never for the whole library at once.
 */
class AtlasViewModel(
    private val discovery: SemanticDiscoveryRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow<AtlasUiState>(AtlasUiState.Loading)
    val state: StateFlow<AtlasUiState> = _state.asStateFlow()

    private val _exemplarTexts = MutableStateFlow<Map<String, String>>(emptyMap())
    val exemplarTexts: StateFlow<Map<String, String>> = _exemplarTexts.asStateFlow()

    fun load() {
        _state.value = AtlasUiState.Loading
        scope.launch {
            when (val readiness = runCatching { discovery.atlasReadiness() }.getOrDefault(AtlasReadiness.Unavailable)) {
                is AtlasReadiness.Unavailable -> _state.value = AtlasUiState.Unavailable
                is AtlasReadiness.TooFewBooks -> _state.value = AtlasUiState.TooFewBooks(readiness.count, readiness.threshold)
                is AtlasReadiness.Forming -> {
                    // Below the threshold and still filling: map what exists, and say it is forming.
                    val model = runCatching { discovery.atlas() }.getOrNull()
                    _state.value = if (model == null || model.books.isEmpty()) {
                        AtlasUiState.Forming(readiness.fraction)
                    } else {
                        AtlasUiState.Map(model, forming = true, fraction = readiness.fraction)
                    }
                }
                is AtlasReadiness.Ready -> {
                    val model = runCatching { discovery.atlas() }.getOrNull()
                    _state.value = if (model == null || model.books.isEmpty()) {
                        AtlasUiState.Unavailable
                    } else {
                        AtlasUiState.Map(model, forming = readiness.forming, fraction = readiness.fraction)
                    }
                }
            }
        }
    }

    /** Loads exemplar passage text for the clusters of one book, when the reader zooms into it. */
    fun loadExemplars(chunkIds: Collection<String>) {
        val missing = chunkIds.filter { it !in _exemplarTexts.value }
        if (missing.isEmpty()) return
        scope.launch {
            val fetched = runCatching { discovery.exemplarTexts(missing) }.getOrDefault(emptyMap())
            if (fetched.isNotEmpty()) _exemplarTexts.value = _exemplarTexts.value + fetched
        }
    }

    fun dispose() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }
}

/** The Atlas screen's states — each a different sentence, per the plan's cold-start handling. */
sealed interface AtlasUiState {
    data object Loading : AtlasUiState
    data class Forming(val fraction: Float) : AtlasUiState
    data class TooFewBooks(val count: Int, val threshold: Int) : AtlasUiState
    data object Unavailable : AtlasUiState

    /** The map. [forming] is true while the backfill is still adding books to it. */
    data class Map(val model: AtlasModel, val forming: Boolean, val fraction: Float) : AtlasUiState
}
