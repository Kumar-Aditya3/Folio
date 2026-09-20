package com.folio.reader.ml

import com.folio.reader.database.ChunkMeta
import com.folio.reader.database.ChunkRepository
import com.folio.reader.database.SearchRepository
import com.folio.reader.database.SearchResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Which retrieval strategy the search screen is using.
 *
 * All three are offered in the running app rather than argued about, so the comparison is
 * something the reader can make directly on their own library.
 */
enum class SearchMode(val label: String) {
    /** FTS5 BM25 only — today's behaviour, and the baseline. */
    FULL_TEXT("Exact"),

    /** Embedding cosine only. Finds paraphrases; misses rare proper nouns. */
    SEMANTIC("Meaning"),

    /** BM25 + cosine fused with RRF. */
    HYBRID("Best"),
}

/** A single search hit. Carries enough to deep-link into the reader. */
data class SemanticHit(
    val bookId: String,
    val chapterId: String,
    val spineIndex: Int,
    val title: String,
    val snippet: String,
    val score: Float,
    val charStart: Int,
    /**
     * Where this hit sits in its chapter, as a fraction in [0, 1] of the chapter's plain text.
     *
     * `charStart` alone cannot be turned into a scroll position without the chapter's length, and
     * that length is not on the hit — so the fraction is precomputed here (from the loaded index)
     * and carried through to the reader, which seeks to it. 0 means "chapter top", which is also
     * the honest fallback for a pure BM25 hit that has no chunk offset.
     */
    val chapterFraction: Float = 0f,
    /** Present for semantic hits; null for pure BM25 hits. */
    val chunkId: String? = null,
)

/**
 * Hybrid retrieval over the reader's own library.
 *
 * Fusion happens at chapter granularity, because that is what the app's FTS5 index and its
 * search UI already work at: BM25 ranks chapters directly, embeddings rank chunks which are
 * then collapsed to their owning chapter (best chunk wins). Fusing at a single granularity
 * is what makes the two rankings comparable.
 *
 * RRF is used rather than a score blend because BM25's scale is unbounded and
 * corpus-dependent while cosine is bounded in [-1, 1] — any weighted sum would need
 * per-library tuning, and would silently drift as the library grows.
 *
 * The index is a preloaded flat matrix, not a per-query database read: the plan requires
 * the scan to be ready when the query arrives, not built when it does. [preload] is
 * therefore called when the search screen opens, and [release] when it closes.
 */
class SemanticSearchRepository(
    private val searchRepository: SearchRepository,
    private val chunkRepository: ChunkRepository,
    private val embedderFactory: EmbedderFactory,
    /**
     * [MlDispatchers.inference], not the generic pool. Query embedding and the index scan are
     * latency-sensitive — the reader is waiting — and sharing a pool with the backfill is what
     * made a search stutter while indexing. See [MlDispatchers].
     */
    private val dispatcher: CoroutineDispatcher = MlDispatchers.inference,
) {
    val model: EmbeddingModel get() = embedderFactory.model

    private val loadMutex = Mutex()
    private var index: VectorIndex? = null
    private var chunkById: Map<String, ChunkMeta> = emptyMap()

    /**
     * A cached embedder session, reused across queries.
     *
     * Opening an ONNX session parses the model and builds XNNPACK's packed weights — tens of
     * milliseconds to a few hundred, paid on top of the ~15-40 ms embed itself. Doing that on
     * *every* query (the old `create()`/`close()` per call) put that cost on the reader's
     * critical path for each keystroke-triggered search. Holding one session for the life of the
     * search screen turns it into a one-time cost.
     *
     * Guarded by [embedderMutex] because [Embedder] is explicitly not thread-safe (see its doc)
     * and the inference pool has parallelism 2, so two searches can overlap. Embedding is short,
     * so serialising it costs nothing the reader can feel.
     *
     * It is **not** kept resident the way [index] is: a MiniLM session is ~40-60 MB (the
     * multilingual one ~150-200 MB), which is exactly the resident footprint that gets the app
     * killed in the background. [releaseEmbedder] drops it when the search screen leaves, while
     * the small int8 [index] stays for a fast reopen.
     */
    private val embedderMutex = Mutex()
    private var cachedEmbedder: Embedder? = null

    /**
     * Embeds one string through the cached session, creating it on first use.
     *
     * Returns null when the model is not on disk (the normal state for a reader who has not
     * downloaded it) or when embedding fails — callers already treat an empty result as "no
     * semantic hits" and fall back to lexical search.
     */
    private suspend fun embedOne(text: String, kind: EmbedKind): FloatArray? = embedderMutex.withLock {
        val embedder = cachedEmbedder ?: embedderFactory.create()?.also { cachedEmbedder = it } ?: return null
        runCatching { embedder.embed(listOf(text), kind).firstOrNull() }.getOrElse {
            // A session that threw may be in a bad state; drop it so the next call rebuilds.
            runCatching { embedder.close() }
            cachedEmbedder = null
            null
        }
    }

    /**
     * Warms the cached embedder session ahead of the first query.
     *
     * Constructing an ONNX session parses the model and builds XNNPACK's packed weights — the
     * one-time cost that otherwise lands on the first keystroke-triggered search, on top of the
     * embed and scan the reader is already waiting for. Called alongside [preload] when the search
     * surface opens, so both the vector index *and* the session are ready before the reader types.
     *
     * A single embed of a trivial string forces the session open and the graph to compile; the
     * result is discarded. Safe to call repeatedly (a warm session is reused) and never throws —
     * a missing model just leaves the session null and the first query falls back as before.
     */
    suspend fun warm(): Unit = withContext(dispatcher) {
        embedderMutex.withLock {
            if (cachedEmbedder != null) return@withLock
            val embedder = embedderFactory.create()?.also { cachedEmbedder = it } ?: return@withLock
            runCatching { embedder.embed(listOf("."), EmbedKind.QUERY) }
        }
    }

    /**
     * Closes the cached embedder session, freeing its native memory.
     *
     * Called when the search surface leaves (the screen's `onDispose`, the rail closing). The
     * index is deliberately *not* released here — it is small and staying resident is what makes
     * a reopen fast — but the embedder session is large and must not outlive the interaction.
     */
    suspend fun releaseEmbedder() = embedderMutex.withLock {
        cachedEmbedder?.let { runCatching { it.close() } }
        cachedEmbedder = null
    }

    /**
     * Which scope the in-memory index currently holds: a book id, or null for the whole library.
     * Sentinel [NO_SCOPE] means nothing is loaded. Tracked so [preload] can tell "already have the
     * right scope" from "have a different one and must rebuild": in-book search loads one book,
     * cross-book search loads everything, and switching between them must not reuse the wrong index.
     */
    private var loadedScope: String? = NO_SCOPE

    /**
     * Highest `charEnd` seen for each chapter, keyed by [chapterKey]. Stands in for the chapter's
     * plain-text length so a hit's [SemanticHit.chapterFraction] can be computed without a second
     * read: the last chunk of a chapter ends at (or just before) the chapter's end, so its
     * `charEnd` is the length to within one chunk — accurate enough to seek by.
     */
    private var chapterMaxCharEnd: Map<String, Int> = emptyMap()

    /** How many chunks are currently loaded; 0 means [preload] has not run or found nothing. */
    val loadedChunks: Int get() = index?.size ?: 0

    val isLoaded: Boolean get() = loadedChunks > 0

    /**
     * True when there is something to search: either the index is in memory, or the model
     * is on disk and vectors exist for it.
     *
     * Separate from [isLoaded] because the panel often asks before [preload] has run, and
     * "the model is missing" and "the model is there but the library is not indexed" need
     * different sentences in the UI.
     */
    suspend fun hasIndex(): Boolean = withContext(dispatcher) {
        if (isLoaded) return@withContext true
        runCatching { chunkRepository.chunkCount(model.id) > 0 }.getOrDefault(false)
    }

    /**
     * Loads the active model's vectors into an in-memory index, scoped to [bookId] when given.
     *
     * Safe to call repeatedly; a call whose scope already matches what is loaded is a no-op.
     * A different scope rebuilds the index. Callers should invoke this when the search screen
     * opens so the first keystroke does not pay for it, passing the book id for in-book search.
     *
     * @param bookId restrict the loaded index to one book; null loads the whole library. In-book
     *   search must pass the open book: loading the whole library there is what allocated a
     *   ~150 MB float array for a large Arctic index and hit the heap limit. The index is now
     *   int8 ([QuantizedCosineIndex]) and sized to the exact row count, and scoping keeps the
     *   in-book case tiny regardless of library size.
     */
    suspend fun preload(bookId: String? = null): Boolean = withContext(dispatcher) {
        if (isLoaded && loadedScope == bookId) return@withContext true
        loadMutex.withLock {
            if (isLoaded && loadedScope == bookId) return@withContext true
            // Never propagate a load failure. Callers launch this bare on a composition scope
            // (`SearchScreen`'s DisposableEffect, `LibraryScreen`'s LaunchedEffect via the rail),
            // where an uncaught exception is not "semantic search is unavailable" — it is an app
            // crash. A database error in `loadVectors`, or the index dimension backstop firing on
            // a stale row, must read as "nothing loaded" here; the search path already treats a
            // false return as "index not ready" and answers from the lexical side.
            runCatching {
                val entries = chunkRepository.loadVectorMetadata(model.id, model.dims, bookId)
                if (entries.isEmpty()) {
                    // Release any prior scope so a book with no vectors does not keep answering
                    // from the previously-loaded book's index.
                    index?.clear()
                    index = null
                    chunkById = emptyMap()
                    chapterMaxCharEnd = emptyMap()
                    loadedScope = NO_SCOPE
                    return@runCatching false
                }
                // Size to the exact row count: the flat store otherwise grows by doubling, and the
                // transient over-allocation on a bulk load is exactly what overran the heap.
                val built = QuantizedCosineIndex(model.dims, initialCapacity = entries.size)
                built.addAll(entries.map { it.first.id to it.second })
                index = built
                chunkById = entries.associate { it.first.id to it.first }
                chapterMaxCharEnd = entries
                    .groupingBy { chapterKey(it.first.bookId, it.first.chapterId) }
                    .fold(0) { acc, entry -> maxOf(acc, entry.first.charEnd) }
                loadedScope = bookId
                true
            }.getOrElse {
                // Leave the index unset so a later call can retry rather than caching the failure.
                index = null
                chunkById = emptyMap()
                chapterMaxCharEnd = emptyMap()
                loadedScope = NO_SCOPE
                false
            }
        }
    }

    /**
     * Drops the index and its chunk metadata.
     *
     * Called when leaving the search screen. At 40 000 chunks this releases ~61 MB of
     * vectors plus the chunk text, which is the difference between being evicted in the
     * background and not.
     */
    suspend fun release() = withContext(dispatcher) {
        releaseEmbedder()
        loadMutex.withLock {
            index?.clear()
            index = null
            chunkById = emptyMap()
            chapterMaxCharEnd = emptyMap()
            loadedScope = NO_SCOPE
        }
    }

    /**
     * @param bookId restrict to one book; null searches the whole library
     */
    suspend fun search(
        query: String,
        mode: SearchMode,
        bookId: String? = null,
        limit: Int = DEFAULT_LIMIT,
    ): List<SemanticHit> = withContext(dispatcher) {
        if (query.isBlank()) return@withContext emptyList()

        val bm25 = if (mode == SearchMode.SEMANTIC) {
            emptyList()
        } else {
            runCatching {
                if (bookId != null) {
                    searchRepository.searchInBook(bookId, query).first()
                } else {
                    searchRepository.search(query).first()
                }
            }.getOrElse { emptyList() }
        }

        if (mode == SearchMode.FULL_TEXT) {
            return@withContext bm25.take(limit).map { it.toHit(score = 0f) }
        }

        // The semantic side is wrapped exactly as the BM25 side above, and for the same reason:
        // an embedder or index failure (a missing model file surfacing at `embed` time, an ONNX
        // error, a dimension mismatch against a stale index) must degrade to "no semantic hits",
        // not throw out of `search`. Without this guard a thrown `semanticRank` failed the whole
        // call — the UI caught it as empty — so *both* Meaning and Best showed "no strong match"
        // while Exact kept working, and in HYBRID the BM25 hits already in hand were discarded
        // with it. The floor/margin returning an empty list is a legitimate answer and is handled
        // below; this only catches the failure that is not one.
        val semantic = runCatching {
            semanticRank(query, bookId, candidateLimit = maxOf(limit * 4, SEMANTIC_CANDIDATES))
        }.getOrElse { emptyList() }

        if (mode == SearchMode.SEMANTIC) {
            // Collapse to one hit per chapter, keeping its best-scoring chunk. `semanticRank`
            // returns chunk-level hits in descending score, so several chunks of the same chapter
            // can otherwise fill the list and crowd out other books — the HYBRID path already
            // fuses at chapter granularity, so this makes SEMANTIC agree with it and diversifies
            // the results the reader sees.
            return@withContext semantic
                .distinctBy { chapterKey(it.bookId, it.chapterId) }
                .take(limit)
        }

        // HYBRID: fuse chapter rankings so both sides are comparable.
        //
        // The fusion key is (bookId, chapterId), never chapterId alone. `chapter_id` is the
        // EPUB manifest identifier, which is book-local — two books routinely both have a
        // `ch1` — so keying on it alone collapsed them into a single candidate, and the
        // `associateBy` lookups below would then resolve a hit to whichever book happened to
        // win the collision. That is a wrong-book result, not a ranking wobble.
        //
        // The two rankings are still fused by *rank*, not score — RRF's whole point, and the
        // reason a BM25 hit needs no cosine at all — so the floor is applied to the semantic
        // side only, before this point. A passage that is a strong lexical match is a strong
        // answer even when the embedder does not recognise it, and `semanticRank` has already
        // dropped the ones that were neither.
        //
        // The two lists are **not** weighted equally, and that is a measurement rather than a
        // preference. Equal weights are the textbook default and they cost a third of the semantic
        // ranker's paraphrase recall on the real corpus (29% against 43%) — see
        // [RrfFusion.DEFAULT_LEXICAL_WEIGHT] for the table. The short version: RRF compares ranks,
        // and rank 1 of a lexical list is not the equal of rank 1 of a semantic one for a vague
        // query, because BM25 always returns *something* while the embedder's top hit had to earn
        // its place.
        val bm25ByChapter = bm25.associateBy { chapterKey(it.bookId, it.chapterId) }
        val semanticByChapter = semantic.associateBy { chapterKey(it.bookId, it.chapterId) }
        val fused = RrfFusion.fusePair(
            bm25.map { chapterKey(it.bookId, it.chapterId) }.distinct(),
            semantic.map { chapterKey(it.bookId, it.chapterId) }.distinct(),
            limit = limit,
        )

        fused.mapNotNull { hit ->
            val key = hit.chunkId
            val bm = bm25ByChapter[key]
            val semanticHit = semanticByChapter[key]
            when {
                // Both sides matched the same chapter. Keep the BM25 snippet — it is centred on
                // the matched term, which is easier to recognise than an arbitrary chunk window —
                // but carry the semantic hit's `charStart`/`chunkId` so the result still deep-links
                // to the matched passage rather than to the top of the chapter. A `SearchResult`
                // has no character offset (`toHit` sets -1), so without this the precise anchor the
                // semantic side already computed would be thrown away whenever BM25 also matched.
                bm != null && semanticHit != null ->
                    bm.toHit(hit.score).copy(
                        charStart = semanticHit.charStart,
                        chapterFraction = semanticHit.chapterFraction,
                        chunkId = semanticHit.chunkId,
                    )
                bm != null -> bm.toHit(hit.score)
                else -> semanticHit?.copy(score = hit.score)
            }
        }
    }

    /**
     * True when semantic search has a model, an index, and therefore *can* answer — which is
     * a different question from whether a given query matched anything.
     *
     * Added for the relevance floor. Before it, "no results" and "not indexed" were
     * indistinguishable on this path, so the UI could not say which had happened; the reader
     * either saw noise (everything scored as a hit) or nothing at all. Now the three states
     * are separate: the model is missing, the library is not indexed, or the query genuinely
     * has no strong match — and only the caller knows which sentence each deserves.
     */
    suspend fun canAnswer(): Boolean = hasIndex()

    /**
     * "More like this": nearest neighbours of a passage the reader already has.
     *
     * Returns an empty list in two situations that callers must be able to tell apart —
     * "the model is not installed" and "nothing is indexed for it" both land here — which is
     * why [hasIndex] exists: a UI that shows "no similar passages" when the index was never
     * built is telling the reader a result that is not one.
     *
     * The relevance floor applies here too. "More like this" is a semantic query like any
     * other, and returning the eight least-unrelated passages when the library holds nothing
     * comparable is the same noise as a nonsense query "matching". [MIN_SIMILARITY] is the
     * floor; the margin is deliberately *not* applied, because this is a browse list rather
     * than an answer and a lone good neighbour is a legitimate one.
     */
    suspend fun moreLikeThis(text: String, excludeChunkId: String? = null, limit: Int = 8): List<SemanticHit> =
        withContext(dispatcher) {
            val vector = embedOne(text, EmbedKind.PASSAGE) ?: return@withContext emptyList()
            if (!preload()) return@withContext emptyList()
            val current = index ?: return@withContext emptyList()
            val picked = current.search(vector, limit + 1)
                .filter { it.chunkId != excludeChunkId && it.score >= MIN_SIMILARITY }
                .take(limit)
            val texts = runCatching { chunkRepository.chunkTexts(picked.map { it.chunkId }) }
                .getOrDefault(emptyMap())
            picked
                .mapNotNull { vectorHit ->
                    chunkById[vectorHit.chunkId]?.let { chunk ->
                        SemanticHit(
                            bookId = chunk.bookId,
                            chapterId = chunk.chapterId,
                            spineIndex = chunk.spineIndex,
                            title = "",
                            snippet = snippetOf(texts[vectorHit.chunkId] ?: ""),
                            score = vectorHit.score,
                            charStart = chunk.charStart,
                            chapterFraction = fractionFor(chunk),
                            chunkId = chunk.id,
                        )
                    }
                }
        }

    private suspend fun semanticRank(
        query: String,
        bookId: String?,
        candidateLimit: Int,
    ): List<SemanticHit> {
        // Scope the load to the queried book. In-book search then holds only that book's vectors,
        // and cross-book search (bookId null) loads the whole int8 index.
        if (!preload(bookId)) return emptyList()
        val current = index ?: return emptyList()
        val vector = embedOne(query, EmbedKind.QUERY) ?: return emptyList()

        // With a book-scoped index the whole index is already the wanted book, so the per-hit
        // filter is only needed for the whole-library scope.
        val filter: ((String) -> Boolean)? = if (bookId != null && loadedScope != bookId) {
            { chunkId -> chunkById[chunkId]?.bookId == bookId }
        } else {
            null
        }
        val ranked = current.search(vector, candidateLimit, filter)

        // The relevance floor, and the margin that turns a ranking into an answer.
        //
        // `search` returns the nearest `candidateLimit` chunks *unconditionally* — in
        // embedding space there is always a nearest chunk, and MiniLM puts all English prose
        // in a narrow cone, so unrelated passages score ~0.1-0.2 rather than 0. Without a
        // floor every query "succeeds": on the device the nonsense query
        // `motorcycle elevator refrigerator cryptocurrency` returned three confident hits
        // from a fantasy library. The reader is then shown noise as if it were a result, and
        // — worse — the UI can never honestly say "no match" on this path.
        //
        // The floor is the same concept [ZeroShotTagger] already carries, deliberately reused
        // rather than reinvented: same model, same cosine, same 0.1-0.2 unrelated-prose band,
        // so the same 0.35 threshold is the right side of it. See that class for why the
        // number is what it is.
        //
        // The margin is where this differs from the tagger, and it is not an afterthought:
        // for tagging, "several equally mediocre tags" means *no confident answer*, and the
        // accepted set is still useful. For search, an answer *is* the set, and a margin over
        // the second-best would silently discard the 3rd..20th hits of an entirely good query
        // — every passage about the same subject scores alike. So [MIN_MARGIN_OVER_FLOOR]
        // is a margin over the *floor* rather than over the runner-up: the leader must be
        // meaningfully better than noise, not meaningfully better than its own siblings.
        // Below it the query has a nearest neighbour and nothing more, and "no strong
        // matches" is the honest answer.
        val aboveFloor = ranked.filter { it.score >= MIN_SIMILARITY }
        if (aboveFloor.isEmpty()) return emptyList()
        val leader = aboveFloor.first().score
        if (leader - MIN_SIMILARITY < MIN_MARGIN_OVER_FLOOR) return emptyList()

        // A second gate, for CLS-pooled models only, because the absolute floor above cannot
        // stand alone for them.
        //
        // On the measured Arctic-embed-S (CLS) the cosine tracks how *fluent and long* a query is,
        // not how topically close it is, so a confidently-worded off-topic query scores as high as
        // a real match (nonsense ~0.62–0.72 against a genuine 0.645) — the two overlap, and no
        // absolute floor can separate them. What still separates them is *shape*: a real match
        // stands out from its own candidate neighbourhood, while fluent nonsense sits in a tight
        // high band where the leader barely leads. `z` measures exactly that — the leader in units
        // of the candidate spread — and is invariant to the whole distribution shifting up.
        //
        // Scoped to [PoolingStrategy.CLS] deliberately: the mean-pooled models (MiniLM, e5) are
        // well separated by the absolute floor, which is calibrated for them, so applying this to
        // them would only risk rejecting a good query whose hits happen to cluster. It leaves the
        // default model's path exactly as it was. [MIN_STANDOUT_Z] is a conservative heuristic, not
        // a fully calibrated constant — see its doc.
        if (model.pooling == PoolingStrategy.CLS) {
            val scores = ranked.map { it.score.toDouble() }
            if (scores.size >= MIN_CANDIDATES_FOR_STANDOUT) {
                val mean = scores.average()
                val variance = scores.sumOf { (it - mean) * (it - mean) } / scores.size
                val sd = kotlin.math.sqrt(variance)
                val z = if (sd > 1e-9) (leader - mean) / sd else 0.0
                if (z < MIN_STANDOUT_Z) return emptyList()
            }
        }

        // Fetch passage text only for the hits that survived the floor and gates — the index
        // itself holds no text (see [ChunkRepository.loadVectorMetadata]), so this is the one
        // place it is read, for at most `candidateLimit` ids rather than the whole library.
        val texts = runCatching { chunkRepository.chunkTexts(aboveFloor.map { it.chunkId }) }
            .getOrDefault(emptyMap())
        return aboveFloor.mapNotNull { hit ->
            chunkById[hit.chunkId]?.let { chunk ->
                SemanticHit(
                    bookId = chunk.bookId,
                    chapterId = chunk.chapterId,
                    spineIndex = chunk.spineIndex,
                    title = "",
                    snippet = snippetOf(texts[hit.chunkId] ?: ""),
                    score = hit.score,
                    charStart = chunk.charStart,
                    chapterFraction = fractionFor(chunk),
                    chunkId = chunk.id,
                )
            }
        }
    }

    /**
     * A chunk's start as a fraction of its chapter's plain-text length, in [0, 1].
     *
     * Uses [chapterMaxCharEnd] as the chapter length, falling back to the chunk's own `charEnd`
     * when the chapter is not in the map (it always is once [preload] has run). The reader seeks
     * to this fraction, which lands the passage rather than the chapter top.
     */
    private fun fractionFor(chunk: ChunkMeta): Float {
        val length = chapterMaxCharEnd[chapterKey(chunk.bookId, chunk.chapterId)] ?: chunk.charEnd
        return if (length > 0) (chunk.charStart.toFloat() / length).coerceIn(0f, 1f) else 0f
    }

    /**
     * A fusion key that is unique across the library.
     *
     * `chapter_id` on its own is not: it is the EPUB manifest identifier, so it is only unique
     * *within* a book. NUL is the separator because it cannot occur in an EPUB id or a book id,
     * so the key cannot be forged by an id that happens to contain the separator.
     */
    private fun chapterKey(bookId: String, chapterId: String): String = "$bookId\u0000$chapterId"

    /**
     * A short window from the start of the chunk. The chunker already cut on a paragraph
     * break when one was nearby, so the opening words are a fair summary of the passage.
     */
    private fun snippetOf(text: String): String =
        if (text.length <= SNIPPET_CHARS) text else text.take(SNIPPET_CHARS).substringBeforeLast(' ') + "…"

    private fun SearchResult.toHit(score: Float) = SemanticHit(
        bookId = bookId,
        chapterId = chapterId,
        spineIndex = spineIndex,
        title = title,
        snippet = context,
        score = score,
        charStart = -1,
    )

    companion object {
        const val DEFAULT_LIMIT = 20
        private const val SEMANTIC_CANDIDATES = 60
        private const val SNIPPET_CHARS = 180

        /**
         * Sentinel for [loadedScope] meaning "nothing loaded", distinct from `null` which is the
         * legitimate whole-library scope. A plain nullable could not tell the two apart.
         */
        private const val NO_SCOPE = "\u0000none"

        /**
         * Cosine below which a passage is not a match at all.
         *
         * Same number and same reasoning as [ZeroShotTagger.DEFAULT_THRESHOLD], and reused
         * rather than retuned because it is the same measurement: MiniLM scores *unrelated*
         * English prose at ~0.1-0.2, so anything under this band is the model's bias toward
         * the centre of its embedding cone rather than similarity to the query. See that
         * class's doc for the full argument.
         *
         * Kept as its own constant rather than pointing at the tagger's, because the two
         * calibrate the same phenomenon on different *tasks* — a threshold on a tag is a
         * decision to propose a word, a threshold here is a decision to show a passage. If
         * one is ever retuned the other must not silently move with it.
         */
        const val MIN_SIMILARITY = 0.35f

        /**
         * How far the best match must clear [MIN_SIMILARITY] to count as an answer.
         *
         * A margin over the *floor*, not over the runner-up: a good query's top twenty hits
         * are all about the same thing and score within a few points of each other, so a
         * runner-up margin would throw away hits 3..20 of a perfectly good search. This asks
         * only whether the leader is meaningfully better than noise. A tenth of a point is
         * above the run-to-run wobble of the same query on the same index, and far below the
         * gap a real match shows.
         */
        const val MIN_MARGIN_OVER_FLOOR = 0.10f

        /**
         * How far the leader must stand out from its candidate neighbourhood, in standard
         * deviations, for a **CLS-pooled** model's result set to count as an answer.
         *
         * This is the relative half of the floor, applied only where the absolute floor cannot
         * stand alone (see [semanticRank]). It is a conservative heuristic rather than a fully
         * calibrated constant: the on-device measurement established that Arctic's absolute cosine
         * does not separate real matches from fluent nonsense, and that the leader's z-score does,
         * but not the exact cut. One standard deviation is the mild, standard "stands out" bar; it
         * is set where a genuine top hit clears it comfortably while a tight high band of
         * indistinguishable candidates (the nonsense signature) does not. Revisit with a proper
         * positive/negative z sweep before trusting it beyond Arctic-S.
         */
        const val MIN_STANDOUT_Z = 1.0

        /**
         * Below this many candidates the standout test is skipped: a z-score over a handful of
         * points is noise, and a genuinely small library should not have its results suppressed by
         * a statistic it cannot support. The absolute floor and margin still apply.
         */
        private const val MIN_CANDIDATES_FOR_STANDOUT = 12
    }
}
