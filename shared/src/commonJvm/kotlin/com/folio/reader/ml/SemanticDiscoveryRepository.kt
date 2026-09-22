package com.folio.reader.ml

import com.folio.reader.database.BookRepository
import com.folio.reader.database.ChunkMeta
import com.folio.reader.database.ChunkRepository
import com.folio.reader.database.IndexProgress
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Data owner for the two semantic-discovery features — **Echoes** (cross-book resonant
 * passages) and **Atlas** (a cartographic map of the library by topic).
 *
 * It deliberately sits *beside* [SemanticSearchRepository] and reuses it rather than standing
 * up a second embedder or a second resident index: an ONNX session is tens to hundreds of MB
 * and the whole point of the search repo's design is that exactly one is held while a semantic
 * surface is open. Echoes is a "more like this" query with one extra filter, and Atlas reads
 * the same stored vectors, so both borrow the search repo's embedder, its `preload`d index and
 * its `hasIndex` gate. This class only adds what those two features need on top: the current-book
 * exclusion for Echoes, and the roll-up + readiness for Atlas.
 *
 * Injected alongside the search repository from [EmbeddingModelSelection], so it is torn down
 * and rebuilt on a model swap in lockstep with everything else keyed on `model_id`.
 */
class SemanticDiscoveryRepository(
    private val semanticSearch: SemanticSearchRepository,
    private val chunkRepository: ChunkRepository,
    private val bookRepository: BookRepository,
    /** [MlDispatchers.inference] for the embed/scan; the roll-up runs on [Dispatchers.Default]. */
    private val dispatcher: CoroutineDispatcher = MlDispatchers.inference,
    /**
     * Where the computed map is persisted so a cold app start does not recompute it. Null disables
     * the disk cache (the in-memory one still applies). Supplied by the app graph.
     */
    private val cacheDir: File? = null,
) {
    val model: EmbeddingModel get() = semanticSearch.model

    private val atlasMutex = Mutex()
    private var cachedFingerprint: String? = null
    private var cachedAtlas: AtlasModel? = null
    private val cacheJson = Json { ignoreUnknownKeys = true }

    // ---- Echoes --------------------------------------------------------------------------

    /** True when there is a model and vectors to answer from — the same gate search uses. */
    suspend fun canEcho(): Boolean = semanticSearch.hasIndex()

    /**
     * Resonant passages from *other* books for a selected passage.
     *
     * Wraps [SemanticSearchRepository.moreLikeThis], excluding the open book so a result is
     * always a genuine cross-book echo, and honours the same [SemanticSearchRepository.MIN_SIMILARITY]
     * floor — an empty list here means "no echoes found", a result the panel states honestly,
     * not "the index is missing" (which [canEcho] answers separately).
     *
     * Titles and cover paths are resolved per distinct book (a batch of echoes usually draws
     * from a handful of books), so the panel can label and tint each card without a lookup per hit.
     */
    suspend fun echoes(
        selectedText: String,
        currentBookId: String,
        excludeChunkId: String? = null,
        limit: Int = 8,
    ): List<EchoHit> = withContext(dispatcher) {
        if (selectedText.isBlank()) return@withContext emptyList()
        val hits = semanticSearch.moreLikeThis(
            text = selectedText,
            excludeChunkId = excludeChunkId,
            limit = limit,
            excludeBookId = currentBookId,
        )
        if (hits.isEmpty()) return@withContext emptyList()

        val resolved = HashMap<String, com.folio.reader.model.Book?>()
        hits.map { hit ->
            val book = resolved.getOrPut(hit.bookId) { runCatching { bookRepository.getBook(hit.bookId) }.getOrNull() }
            EchoHit(
                bookId = hit.bookId,
                bookTitle = book?.title.orEmpty(),
                coverPath = book?.coverPath,
                chapterId = hit.chapterId,
                spineIndex = hit.spineIndex,
                charStart = hit.charStart,
                chapterFraction = hit.chapterFraction,
                snippet = hit.snippet,
                score = hit.score,
                chunkId = hit.chunkId,
            )
        }
    }

    // ---- Atlas ---------------------------------------------------------------------------

    /**
     * Whether the library is worth mapping, and how far along the embedding backfill is.
     *
     * Cheap by design — it reads [IndexProgress] and a `COUNT(DISTINCT book_id)`, never a vector —
     * so the Home hero can consult it on composition without paying the roll-up cost.
     */
    suspend fun atlasReadiness(): AtlasReadiness = withContext(Dispatchers.IO) {
        // On IO, not the inference dispatcher: a heavy roll-up load can occupy inference, and the
        // readiness check must never queue behind it (that made the Home hero blink out while an
        // Atlas load ran). progress() is the expensive call here (it scans chapter text), so it is
        // only paid on the Atlas screen itself — the Home hero uses [atlasHeroEligible] instead.
        val progress = runCatching { chunkRepository.progress(model.id) }.getOrNull()
            ?: return@withContext AtlasReadiness.Unavailable
        if (progress.totalChapters <= 0) return@withContext AtlasReadiness.Unavailable
        val embedded = runCatching { chunkRepository.embeddedBookCount(model.id) }.getOrDefault(0)
        when {
            embedded >= ATLAS_BOOK_THRESHOLD ->
                AtlasReadiness.Ready(forming = !progress.isComplete, fraction = progress.fraction)
            // Below the threshold but the backfill is still running: more books may yet arrive.
            !progress.isComplete -> AtlasReadiness.Forming(progress.fraction)
            // The library is fully indexed and simply does not hold enough embedded books.
            else -> AtlasReadiness.TooFewBooks(embedded, ATLAS_BOOK_THRESHOLD)
        }
    }

    /**
     * The Home hero's gate: is the library worth mapping? Deliberately the *cheapest* possible
     * signal — one `COUNT(DISTINCT book_id)` on IO, no `progress()` content scan, no inference
     * dispatcher — so Home never waits on it and it cannot blink out while an Atlas load runs.
     */
    suspend fun atlasHeroEligible(): Boolean = withContext(Dispatchers.IO) {
        runCatching { chunkRepository.embeddedBookCount(model.id) >= ATLAS_BOOK_THRESHOLD }.getOrDefault(false)
    }

    /**
     * The library's topic map. Computed on [Dispatchers.Default] (the roll-up is CPU-bound and
     * must not sit on the latency-sensitive inference pool), cached in memory for the session and
     * recomputed only when the index fingerprint — model id, chunk count, embedded-book count —
     * changes. A partially backfilled library maps whatever is embedded so far.
     */
    suspend fun atlas(): AtlasModel = atlasMutex.withLock {
        val fingerprint = fingerprint()
        cachedAtlas?.let { if (fingerprint == cachedFingerprint) return it }

        // Disk cache: a cold app start (empty in-memory cache) recomputes the whole roll-up
        // otherwise. Keyed by the same fingerprint, so it is used only while the library is
        // unchanged and silently ignored (then overwritten) once a book is added/removed/reindexed.
        readDiskCache(fingerprint)?.let {
            cachedAtlas = it
            cachedFingerprint = fingerprint
            return it
        }

        // Bound the roll-up's memory *at the source*. Loading a whole large library as floats
        // (~64 MB for 35k chunks) and then whitening it (an n×dims double working matrix on top)
        // overran the phone heap and crashed. The sampled loader streams the rows and never
        // materialises more than [MAX_ROLLUP_CHUNKS], with a per-book stride so every book still
        // appears on the map — capping the peak regardless of library size while keeping the layout
        // representative. Post-load subsampling could not help: the 64 MB was already allocated.
        val entries = withContext(Dispatchers.IO) {
            runCatching { chunkRepository.loadVectorMetadataSampled(model.id, model.dims, MAX_ROLLUP_CHUNKS) }
                .getOrDefault(emptyList())
        }
        if (entries.isEmpty()) {
            val empty = AtlasModel(emptyList(), emptyList())
            cachedAtlas = empty
            cachedFingerprint = fingerprint
            return empty
        }

        // Cooperative cancellation: the roll-up is a multi-second CPU loop with no suspension
        // points, so without this it would run to completion (burning battery, holding the mutex)
        // even after the reader left the Atlas mid-load. The lambda lets the pure code bail at
        // loop boundaries when this coroutine is cancelled.
        val job = currentCoroutineContext()[Job]
        val geometry = withContext(Dispatchers.Default) {
            AtlasRollup.compute(entries, shouldCancel = { job?.isActive == false })
        }

        // Index the loaded metadata so each cluster's exemplar can be turned into a reader
        // deep-link: its spine, and its position within the chapter as a fraction (the same
        // charEnd-of-chapter stand-in the search repo uses, so a tap lands on the passage).
        val metaById = HashMap<String, ChunkMeta>(entries.size)
        val chapterMaxCharEnd = HashMap<String, Int>()
        for ((meta, _) in entries) {
            metaById[meta.id] = meta
            val key = meta.bookId + "\u0000" + meta.chapterId
            chapterMaxCharEnd[key] = maxOf(chapterMaxCharEnd[key] ?: 0, meta.charEnd)
        }
        fun fractionOf(meta: ChunkMeta): Float {
            val length = chapterMaxCharEnd[meta.bookId + "\u0000" + meta.chapterId] ?: meta.charEnd
            return if (length > 0) (meta.charStart.toFloat() / length).coerceIn(0f, 1f) else 0f
        }

        // Decorate the geometry with per-book display data (title, cover, read progress).
        val bookCache = HashMap<String, com.folio.reader.model.Book?>()
        val books = geometry.books.map { g ->
            val book = bookCache.getOrPut(g.bookId) { runCatching { bookRepository.getBook(g.bookId) }.getOrNull() }
            AtlasBook(
                bookId = g.bookId,
                title = book?.title.orEmpty(),
                coverPath = book?.coverPath,
                readFraction = book?.normalizedProgress?.toFloat()?.coerceIn(0f, 1f) ?: 0f,
                clusters = g.clusters.map { c ->
                    val meta = metaById[c.exemplarChunkId]
                    AtlasCluster(
                        x = c.x,
                        y = c.y,
                        mass = c.mass,
                        tightness = c.tightness,
                        exemplarChunkId = c.exemplarChunkId,
                        exemplarSpineIndex = meta?.spineIndex ?: -1,
                        exemplarFraction = meta?.let { fractionOf(it) } ?: 0f,
                        exemplarText = null,
                    )
                },
            )
        }
        val result = AtlasModel(books, geometry.edges)
        cachedAtlas = result
        cachedFingerprint = fingerprint
        writeDiskCache(fingerprint, result)
        result
    }

    private suspend fun readDiskCache(fingerprint: String): AtlasModel? {
        val file = cacheDir?.let { File(it, ATLAS_CACHE_FILE) } ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                if (!file.exists()) return@runCatching null
                val envelope = cacheJson.decodeFromString(AtlasCacheEnvelope.serializer(), file.readText())
                if (envelope.fingerprint == fingerprint) envelope.model else null
            }.getOrNull()
        }
    }

    private suspend fun writeDiskCache(fingerprint: String, model: AtlasModel) {
        val file = cacheDir?.let { File(it, ATLAS_CACHE_FILE) } ?: return
        withContext(Dispatchers.IO) {
            runCatching {
                file.parentFile?.mkdirs()
                file.writeText(cacheJson.encodeToString(AtlasCacheEnvelope.serializer(), AtlasCacheEnvelope(fingerprint, model)))
            }
        }
    }

    /**
     * Warms the shared embedder session and preloads the resident index so the first Echoes tap
     * pays for neither a cold ONNX session nor an index build. Safe to call repeatedly (both are
     * cached) and never throws — a missing model just leaves the session null. Called when the
     * reader detects a selection, so warming overlaps the moment before the reader taps Echoes.
     */
    suspend fun warm() = kotlinx.coroutines.coroutineScope {
        // Overlap the two independent one-time costs — the ONNX session init and the resident-index
        // build — instead of paying them back to back, so the first Echoes tap resolves sooner.
        val session = launch { runCatching { semanticSearch.warm() } }
        val index = launch { runCatching { semanticSearch.preload() } }
        session.join(); index.join()
    }

    /** Releases the embedder session (its ~50 MB native footprint); keeps the small index resident. */
    suspend fun releaseEmbedder() {
        runCatching { semanticSearch.releaseEmbedder() }
    }

    /**
     * Passage text for a handful of exemplar chunks, loaded lazily for the clusters the reader
     * actually zooms into — the roll-up itself carries only chunk ids, never the ~170 MB of text.
     */
    suspend fun exemplarTexts(chunkIds: Collection<String>): Map<String, String> = withContext(dispatcher) {
        if (chunkIds.isEmpty()) emptyMap()
        else runCatching { chunkRepository.chunkTexts(chunkIds) }.getOrDefault(emptyMap())
    }

    private suspend fun fingerprint(): String {
        val chunks = runCatching { chunkRepository.chunkCount(model.id) }.getOrDefault(0)
        val books = runCatching { chunkRepository.embeddedBookCount(model.id) }.getOrDefault(0)
        return "${model.id}:$chunks:$books"
    }

    companion object {
        /** The Home hero requires at least this many embedded books before a map reads as a map. */
        const val ATLAS_BOOK_THRESHOLD = 5

        /**
         * Hard ceiling on chunks fed to the roll-up. Whitening allocates an n×dims double matrix,
         * so this bounds the peak heap regardless of library size. 5000 × 384 doubles ≈ 15 MB of
         * working set; on a ~35-book library that is ~140 sampled chunks per book, ample for the
         * per-book k-means (k ≤ 8) and the cross-book adjacency to stay representative.
         */
        const val MAX_ROLLUP_CHUNKS = 5000

        private const val ATLAS_CACHE_FILE = "atlas_cache.json"
    }
}

/** On-disk envelope: the map plus the fingerprint it was computed for. */
@Serializable
private data class AtlasCacheEnvelope(val fingerprint: String, val model: AtlasModel)

/**
 * A cross-book resonant passage.
 *
 * [coverPath] is carried rather than a resolved colour because the accent is derived from the
 * decoded cover bitmap in the Compose layer (`rememberCoverAccent`), which this module cannot
 * see — so the "land fragment" card tints itself from this path at render time.
 */
data class EchoHit(
    val bookId: String,
    val bookTitle: String,
    val coverPath: String?,
    val chapterId: String,
    val spineIndex: Int,
    val charStart: Int,
    val chapterFraction: Float,
    val snippet: String,
    val score: Float,
    val chunkId: String?,
)

/** The whole library as a map: books with topic regions, joined by shared borders. */
@Serializable
data class AtlasModel(
    val books: List<AtlasBook>,
    val edges: List<AtlasEdge>,
)

@Serializable
data class AtlasBook(
    val bookId: String,
    val title: String,
    val coverPath: String?,
    /** 0..1 reading progress; the renderer lights read regions and fogs unread ones. */
    val readFraction: Float,
    val clusters: List<AtlasCluster>,
)

@Serializable
data class AtlasCluster(
    val x: Float,
    val y: Float,
    val mass: Float,
    val tightness: Float,
    val exemplarChunkId: String,
    /** The exemplar chunk's spine, for a reader deep-link; -1 when unknown. */
    val exemplarSpineIndex: Int,
    /** The exemplar's position within its chapter, in [0, 1], for the deep-link seek. */
    val exemplarFraction: Float,
    /** Filled lazily via [SemanticDiscoveryRepository.exemplarTexts] for zoomed/visible clusters. */
    val exemplarText: String?,
)

/**
 * Whether the Atlas can be shown, modelled rather than collapsed to a boolean because each
 * state deserves a different sentence: an unindexed library is an instruction, a small one is a
 * fact, and a backfilling one is a "come back soon".
 */
sealed interface AtlasReadiness {
    /** Enough embedded books to map. [forming] is true while the backfill is still running. */
    data class Ready(val forming: Boolean, val fraction: Float) : AtlasReadiness

    /** Below the threshold, but the backfill is still adding books. */
    data class Forming(val fraction: Float) : AtlasReadiness

    /** Fully indexed and genuinely too few embedded books to read as a map. */
    data class TooFewBooks(val count: Int, val threshold: Int) : AtlasReadiness

    /** No searchable library, or no model/vectors at all. */
    data object Unavailable : AtlasReadiness
}
