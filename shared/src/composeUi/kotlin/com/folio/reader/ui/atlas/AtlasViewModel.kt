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

    // Per-cluster display labels, keyed by exemplarChunkId. Baked synchronously from the model's
    // per-book theme/genre when a map is shown, so headings render right the first time instead of
    // flashing older labels while an async pass catches up.
    private val _refinedLabels = MutableStateFlow<Map<String, String>>(emptyMap())
    val refinedLabels: StateFlow<Map<String, String>> = _refinedLabels.asStateFlow()
    private var labeledModel: AtlasModel? = null

    fun load() {
        scope.launch {
            // Instant open: show the last computed map (in-memory or on disk) on the first frame,
            // even if a book was added/removed since, so opening the Atlas never blocks behind the
            // multi-second roll-up. Only fall back to the skeleton when nothing has ever been
            // computed. The fresh map is then reconciled in below and swapped in when ready.
            val stale = runCatching { discovery.lastComputedAtlas() }.getOrNull()
            val haveStale = stale != null && stale.books.isNotEmpty()
            if (haveStale) {
                bakeLabels(stale!!)
                _state.value = AtlasUiState.Map(stale, forming = false, fraction = 1f)
            } else {
                _state.value = AtlasUiState.Loading
            }

            when (val readiness = runCatching { discovery.atlasReadiness() }.getOrDefault(AtlasReadiness.Unavailable)) {
                is AtlasReadiness.Unavailable -> if (!haveStale) _state.value = AtlasUiState.Unavailable
                is AtlasReadiness.TooFewBooks -> if (!haveStale) _state.value = AtlasUiState.TooFewBooks(readiness.count, readiness.threshold)
                is AtlasReadiness.Forming -> {
                    // Below the threshold and still filling: map what exists, and say it is forming.
                    val model = runCatching { discovery.atlas() }.getOrNull()
                    if (model != null && model.books.isNotEmpty()) {
                        bakeLabels(model)
                        _state.value = AtlasUiState.Map(model, forming = true, fraction = readiness.fraction)
                    } else if (!haveStale) {
                        _state.value = AtlasUiState.Forming(readiness.fraction)
                    }
                }
                is AtlasReadiness.Ready -> {
                    val model = runCatching { discovery.atlas() }.getOrNull()
                    if (model != null && model.books.isNotEmpty()) {
                        bakeLabels(model)
                        _state.value = AtlasUiState.Map(model, forming = readiness.forming, fraction = readiness.fraction)
                    } else if (!haveStale) {
                        _state.value = AtlasUiState.Unavailable
                    }
                }
            }
        }
    }

    /**
     * Fills [refinedLabels] from the map's **baked** per-book labels (dominant theme, else broad
     * genre), synchronously, once per model instance. Because the labels already live on the model
     * — computed in the roll-up / classification pass, not here — there is no async pass to flash a
     * stale set first, which is exactly the "flash of old categories" this replaced.
     */
    private fun bakeLabels(model: AtlasModel) {
        if (labeledModel === model) return
        labeledModel = model
        _refinedLabels.value = buildMap {
            model.books.forEach { b ->
                val label = b.theme?.takeIf { it.isNotBlank() } ?: b.genre?.takeIf { it.isNotBlank() }
                if (label != null) b.clusters.forEach { put(it.exemplarChunkId, label) }
            }
        }
    }

    /** No-op: cluster labels are baked into the model now, so there is nothing to refine on zoom. */
    fun refineVisibleBooks(books: List<com.folio.reader.ml.AtlasBook>) = Unit

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
