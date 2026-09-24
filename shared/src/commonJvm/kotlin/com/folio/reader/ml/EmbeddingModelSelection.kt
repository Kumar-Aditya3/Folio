package com.folio.reader.ml

import com.folio.reader.database.ChunkRepository
import com.folio.reader.database.SearchRepository
import com.folio.reader.database.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which embedding model the app is currently using, and the services built on it.
 *
 * ### Why this exists rather than five `val`s in the graph
 *
 * The model used to be a single `val` resolved at graph construction, with the embedder
 * factory, indexer, semantic repository and tagger all built from it in the same breath.
 * That is fine while the choice is fixed and wrong the moment it is not: the indexer and
 * the searcher must never disagree about the model, because every vector row is keyed by
 * `model_id`. An indexer writing MiniLM vectors while the searcher loads Arctic vectors
 * does not fail — it returns nothing, silently, and the reader concludes the feature is
 * broken. Keeping all of them behind one [selection] makes that disagreement structurally
 * impossible: [adopt] swaps every derived service together or none of them.
 *
 * ### Lifecycle
 *
 * [start] reads the stored choice and builds against it; [adopt] re-reads and rebuilds.
 * Both are safe to call repeatedly. The graph calls [start] once, off the main thread,
 * and the settings screen calls [adopt] after writing a new choice.
 *
 * @param chunkRepository shared across models — chunk rows are not model-specific, only the
 *   vectors are, and it carries the per-model progress readout the settings screen observes.
 */
class EmbeddingModelSelection(
    private val settingsRepository: SettingsRepository,
    private val searchRepository: SearchRepository,
    private val modelsDir: java.io.File,
    private val chunkRepository: ChunkRepository,
    /**
     * Shared, model-independent — the Atlas/Echoes discovery repo resolves book titles, covers
     * and reading progress through it. Kept behind this holder like the other derived services
     * so a model swap tears the discovery repo down with the searcher it borrows from.
     */
    private val bookRepository: com.folio.reader.database.BookRepository,
    /**
     * Optional store for per-book broad genre + parsed subjects. When present, the discovery repo
     * reads genres to name Atlas communities and a [GenreClassificationService] is offered for the
     * backfill/import to populate it. Null keeps the Atlas working with "Mixed" community names.
     */
    private val genreRepository: com.folio.reader.database.GenreRepository? = null,
    threads: Int = defaultEmbedThreads(),
    useXnnpack: Boolean = true,
) {
    private val buildFactory: (EmbeddingModel) -> OnnxEmbedderFactory = { model ->
        OnnxEmbedderFactory(
            model = model,
            modelsDir = modelsDir,
            threads = threads,
            useXnnpack = useXnnpack,
        )
    }

    private val _model = MutableStateFlow(EmbeddingModelCatalog.default)

    /** The model in force. Emits on [adopt], so the UI can follow a change. */
    val model: StateFlow<EmbeddingModel> = _model.asStateFlow()

    /**
     * Cut from the same factory as [model] so a reader of the flow always sees a matched
     * pair. Rebuilt by [adopt]; never mutated in place.
     */
    private var _embedderFactory: OnnxEmbedderFactory = buildFactory(_model.value)
    private var _indexer: EmbeddingIndexer? = null
    private var _semantic: SemanticSearchRepository? = null
    private var _tagger: ZeroShotTagger? = null
    private var _discovery: SemanticDiscoveryRepository? = null
    private var _genreClassifier: GenreClassifier? = null
    private var _genreClassification: GenreClassificationService? = null

    val embedderFactory: OnnxEmbedderFactory get() = _embedderFactory

    /** The import/backfill path. Null until [start] or [adopt] has resolved a model. */
    val indexer: EmbeddingIndexer
        get() = _indexer ?: EmbeddingIndexer(chunkRepository, _embedderFactory).also { _indexer = it }

    val semanticSearch: SemanticSearchRepository
        get() = _semantic ?: SemanticSearchRepository(
            searchRepository = searchRepository,
            chunkRepository = chunkRepository,
            embedderFactory = _embedderFactory,
        ).also { _semantic = it }

    val tagger: ZeroShotTagger
        get() = _tagger ?: ZeroShotTagger(_embedderFactory).also { _tagger = it }

    /**
     * Atlas + Echoes. Reuses [semanticSearch] rather than opening a second embedder, so it must
     * be rebuilt whenever the searcher is (an [adopt] nulls both).
     */
    val discovery: SemanticDiscoveryRepository
        get() = _discovery ?: SemanticDiscoveryRepository(
            semanticSearch = semanticSearch,
            chunkRepository = chunkRepository,
            bookRepository = bookRepository,
            genreRepository = genreRepository,
            cacheDir = modelsDir,
        ).also { _discovery = it }

    /** Genre inference engine (taxonomy + zero-shot), rebuilt on a model swap like the tagger. */
    val genreClassifier: GenreClassifier
        get() = _genreClassifier ?: GenreClassifier(_embedderFactory).also { _genreClassifier = it }

    /**
     * The backfill/import genre pass. Null when no [genreRepository] was supplied (there is nowhere
     * to persist), so callers no-op rather than classify into the void.
     */
    val genreClassification: GenreClassificationService?
        get() = genreRepository?.let { repo ->
            _genreClassification ?: GenreClassificationService(genreClassifier, chunkRepository, repo)
                .also { _genreClassification = it }
        }

    /**
     * Reads the stored choice and adopts it. Returns the model now in force.
     *
     * Reads through [settingsRepository], so it is a suspend call — an unreadable settings
     * row falls back to the catalog default rather than propagating, because a model choice
     * is not worth failing app start over.
     */
    suspend fun start(): EmbeddingModel {
        val stored = runCatching { settingsRepository.getRaw(EmbeddingModelCatalog.SELECTED_MODEL_KEY) }
            .getOrNull()
        return adopt(EmbeddingModelCatalog.resolve(stored))
    }

    /**
     * Switches to [model] and rebuilds everything derived from it.
     *
     * Also called with the model already in force, where it is a no-op, so callers do not
     * have to compare first.
     */
    suspend fun adopt(model: EmbeddingModel): EmbeddingModel {
        // Rebuild unconditionally, even when [model] is already in force.
        //
        // This used to early-return on `_model.value == model && _indexer != null`, on the
        // reasoning that adopting the same model again is a no-op. It is not: the derived
        // services are built lazily and capture values that can change without the *model*
        // changing — the chunk window reads `maxChunkWords` off the model, so when the
        // derivation itself is fixed, a selection that is already loaded must be torn down
        // for the fix to take effect. With the early return, `start()` on a stored Arctic
        // choice rebuilt nothing and the indexer stayed on the old chunk size.
        //
        // The cost is that a redundant adopt drops the cached indexer/semantic/tagger and
        // rebuilds them on next use. Those are cheap to construct (the ONNX session inside
        // them is what is expensive, and it is created per call and closed) — see
        // [MlSessionCensus] for what makes that claim checkable.
        _model.value = model
        _embedderFactory = buildFactory(model)
        _indexer = null
        _semantic = null
        _tagger = null
        _discovery = null
        _genreClassifier = null
        _genreClassification = null
        return model
    }

    /**
     * Persists [model] as the choice for future launches.
     *
     * Deliberately does **not** adopt it: the settings screen writes and then adopts, so an
     * adopt that throws cannot leave the stored value and the in-force model disagreeing.
     */
    suspend fun persist(model: EmbeddingModel) {
        settingsRepository.setRaw(EmbeddingModelCatalog.SELECTED_MODEL_KEY, model.id)
    }
}
