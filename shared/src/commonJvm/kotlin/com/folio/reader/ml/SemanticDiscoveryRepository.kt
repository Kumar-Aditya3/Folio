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
    /**
     * Reads the pre-computed per-book broad genre (written by the backfill's genre pass) so the
     * roll-up can name each community. Null degrades gracefully: communities fall back to a
     * c-TF-IDF term or "Mixed", exactly as when no book has been classified yet. The roll-up never
     * *computes* genre — that would open ONNX during the roll-up, which is the documented OOM path.
     */
    private val genreRepository: com.folio.reader.database.GenreRepository? = null,
    /** [MlDispatchers.inference] for the embed/scan; the roll-up runs on [Dispatchers.Default]. */
    private val dispatcher: CoroutineDispatcher = MlDispatchers.inference,
    /**
     * Where the computed map is persisted so a cold app start does not recompute the multi-second
     * roll-up. Null disables the disk cache (the in-memory session cache still applies). Supplied
     * by the app graph as the models dir, alongside the ONNX model this feature already borrows.
     */
    private val cacheDir: File? = null,
) {
    val model: EmbeddingModel get() = semanticSearch.model

    // In-memory session cache backed by a fingerprint-keyed disk cache. The roll-up is a
    // multi-second, CPU-bound job that saturates the cores, so recomputing it on every cold start
    // both wastes battery and starves anything running alongside it — notably an EPUB open, which
    // then crawls. The [cacheDir] copy lets a cold start reuse the last map while the library is
    // unchanged; it is keyed by [fingerprint] (model id, chunk count, embedded-book count), so any
    // add/remove/reindex silently invalidates it and it is recomputed. Within a session the
    // in-memory copy short-circuits even the disk read.
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
     * persisted to [cacheDir] so a cold start reuses it. Recomputed only when the index fingerprint
     * — model id, chunk count, embedded-book count — changes. A partially backfilled library maps
     * whatever is embedded so far.
     */
    suspend fun atlas(): AtlasModel = atlasMutex.withLock {
        val fingerprint = fingerprint()
        cachedAtlas?.let { if (fingerprint == cachedFingerprint) return it }

        // Disk cache: a cold app start has an empty in-memory cache and would otherwise recompute
        // the whole roll-up — the multi-second CPU spike that makes an EPUB opened alongside it
        // crawl. Keyed by the same fingerprint, so it is served only while the library is unchanged
        // and silently recomputed (then overwritten) once a book is added, removed or reindexed.
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

        // Pre-computed broad genres for this model (written by the backfill's genre pass). A cheap
        // bulk read; empty when nothing has been classified yet. Read *before* the layout so the
        // force-directed layout can apply a mild same-genre attraction (books of a genre drift into
        // one cloud), and reused below to colour + label the map by genre. Never *computed* here —
        // that would open ONNX during the roll-up, the documented OOM path.
        val genresByBook: Map<String, String> = runCatching {
            genreRepository?.genresForModel(model.id)?.mapValues { it.value.genre } ?: emptyMap()
        }.getOrDefault(emptyMap())

        // Cooperative cancellation: the roll-up is a multi-second CPU loop with no suspension
        // points, so without this it would run to completion (burning battery, holding the mutex)
        // even after the reader left the Atlas mid-load. The lambda lets the pure code bail at
        // loop boundaries when this coroutine is cancelled.
        val job = currentCoroutineContext()[Job]
        val geometry = withContext(Dispatchers.Default) {
            AtlasRollup.compute(entries, bookGenre = genresByBook, shouldCancel = { job?.isActive == false })
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

        // Theme labels via c-TF-IDF (stage 1). Load the text of each cluster's centroid-nearest
        // chunks once (bounded — a handful per cluster), build one document per cluster, and score
        // terms by how distinctive they are across clusters. This is what makes a region read as
        // "leviathan · tide" instead of the first few words of one passage. Optionally re-ranked by
        // embedding proximity (stage 2) below.
        val clusterMembers: List<List<String>> = geometry.books.flatMap { g -> g.clusters.map { it.memberChunkIds } }
        val neededTexts = clusterMembers.flatten().distinct()
        val textById = withContext(dispatcher) {
            runCatching { chunkRepository.chunkTexts(neededTexts) }.getOrDefault(emptyMap())
        }
        val docs = clusterMembers.map { ids -> ids.mapNotNull { textById[it] } }
        val candidates = withContext(Dispatchers.Default) { ClusterLabeler.label(docs) }
        // NOTE: stage-2 embedding re-rank is intentionally NOT run here. Opening the ONNX session
        // during the roll-up — on top of the sampled vectors and the terrain bitmap already in
        // memory — pushed native memory over the edge and the OS hard-killed the app with no Java
        // trace. The pure c-TF-IDF labels are already distinctive; a lazy per-book re-rank can be
        // reintroduced later (only for the book in view) if it earns its keep.

        // Decorate the geometry with per-book display data (title, cover, read progress) + labels.
        val bookCache = HashMap<String, com.folio.reader.model.Book?>()
        var flat = 0
        val books = geometry.books.map { g ->
            val book = bookCache.getOrPut(g.bookId) { runCatching { bookRepository.getBook(g.bookId) }.getOrNull() }
            val genreName = genresByBook[g.bookId]
            AtlasBook(
                bookId = g.bookId,
                title = book?.title.orEmpty(),
                coverPath = book?.coverPath,
                readFraction = book?.normalizedProgress?.toFloat()?.coerceIn(0f, 1f) ?: 0f,
                x = g.x,
                y = g.y,
                communityId = g.communityId,
                genre = genreName?.let { GenreTaxonomy.byName(it)?.displayName ?: it },
                clusters = g.clusters.map { c ->
                    val meta = metaById[c.exemplarChunkId]
                    val phrases = candidates.getOrElse(flat) { emptyList() }
                    flat++
                    AtlasCluster(
                        x = c.x,
                        y = c.y,
                        mass = c.mass,
                        tightness = c.tightness,
                        exemplarChunkId = c.exemplarChunkId,
                        exemplarSpineIndex = meta?.spineIndex ?: -1,
                        exemplarFraction = meta?.let { fractionOf(it) } ?: 0f,
                        exemplarText = null,
                        label = ClusterLabeler.display(phrases),
                        labelCandidates = phrases,
                        rawCentroid = c.rawCentroid,
                    )
                },
            )
        }

        val communities = rollUpCommunities(geometry.books, books, genresByBook)
        val composition = libraryComposition(books)
        val genreRegions = buildGenreRegions(books)
        val result = AtlasModel(books, geometry.edges, communities, composition, genreRegions)
        cachedAtlas = result
        cachedFingerprint = fingerprint
        writeDiskCache(fingerprint, result)
        result
    }

    /**
     * Rolls each emergent community up to a display genre and colour.
     *
     * The name is the **plurality broad genre** among the community's member books (metadata- or
     * inference-derived, whichever the backfill stored). When no member has a genre, it falls back
     * to the most common c-TF-IDF phrase across the community's clusters, and finally to "Mixed" —
     * never inventing a confident label. Colour is the genre's stable hue slot, or a
     * community-derived slot past the genre range when there is no genre.
     */
    private fun rollUpCommunities(
        geometry: List<AtlasBookGeometry>,
        books: List<AtlasBook>,
        genresByBook: Map<String, String>,
    ): List<AtlasCommunity> {
        val geomById = geometry.associateBy { it.bookId }
        return books.groupBy { it.communityId }.entries.sortedBy { it.key }.map { (cid, members) ->
            // Plurality broad genre by enum name, deterministic on ties (taxonomy order).
            val genreVotes = members.mapNotNull { genresByBook[it.bookId] }
                .groupingBy { it }.eachCount()
            val pluralityGenre = genreVotes.entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }
                    .thenBy { GenreTaxonomy.byName(it.key)?.ordinal ?: Int.MAX_VALUE })
                .firstOrNull()?.key
                ?.let { GenreTaxonomy.byName(it) }

            val label: String
            val hue: Int
            if (pluralityGenre != null) {
                label = pluralityGenre.displayName
                hue = pluralityGenre.hueId
            } else {
                // Fallback: the most common distinctive phrase across the community's clusters.
                val phraseVotes = members.flatMap { it.clusters }.flatMap { it.labelCandidates }
                    .groupingBy { it }.eachCount()
                val topPhrase = phraseVotes.entries
                    .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                    .firstOrNull()?.key
                label = topPhrase?.let { ClusterLabeler.display(listOf(it)) }?.takeIf { it.isNotBlank() } ?: "Mixed"
                hue = BroadGenre.entries.size + cid
            }

            var cx = 0f; var cy = 0f
            members.forEach { cx += it.x; cy += it.y }
            val n = members.size.coerceAtLeast(1)
            val tightness = members.flatMap { geomById[it.bookId]?.clusters ?: emptyList() }
                .map { it.tightness }.average().let { if (it.isNaN()) 0f else it.toFloat() }

            AtlasCommunity(
                id = cid,
                genreLabel = label,
                colorHueId = hue,
                memberBookIds = members.map { it.bookId },
                centroidX = cx / n,
                centroidY = cy / n,
                size = members.size,
                tightness = tightness,
            )
        }
    }

    /** The library's genre composition, largest share first — the legend's "42% Sci-Fi · …". */
    private fun libraryComposition(books: List<AtlasBook>): List<GenreShare> {
        if (books.isEmpty()) return emptyList()
        val total = books.size
        return books.mapNotNull { it.genre }
            .groupingBy { it }.eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { GenreShare(it.key, it.value, it.value.toFloat() / total) }
    }

    /**
     * One region per broad genre with at least [MIN_REGION_BOOKS] confident books, placed at the
     * centroid of that genre's books. This is the genre-first overview labelling: exactly one label
     * per genre (no duplicate "Historical Fiction"), and only for genres that actually resolved
     * (unclassified books get no region and no label — never a c-TF-IDF character name). Largest
     * genre first so the renderer labels the biggest clouds when space is tight.
     */
    private fun buildGenreRegions(books: List<AtlasBook>): List<AtlasGenreRegion> {
        val byGenre = books.filter { !it.genre.isNullOrBlank() }.groupBy { it.genre!! }
        return byGenre.entries
            .mapNotNull { (genre, members) ->
                if (members.size < MIN_REGION_BOOKS) return@mapNotNull null
                var cx = 0f; var cy = 0f
                members.forEach { cx += it.x; cy += it.y }
                val n = members.size.coerceAtLeast(1)
                Triple(genre, members.size, Offsets(cx / n, cy / n))
            }
            .sortedWith(compareByDescending<Triple<String, Int, Offsets>> { it.second }.thenBy { it.first })
            .mapIndexed { i, (genre, size, c) ->
                AtlasGenreRegion(
                    id = i,
                    genre = genre,
                    colorHueId = GenreTaxonomy.byDisplayName(genre)?.hueId ?: genre.hashCode(),
                    centroidX = c.x,
                    centroidY = c.y,
                    size = size,
                )
            }
    }

    /** Tiny internal pair-of-floats to avoid pulling a Compose Offset into this module. */
    private data class Offsets(val x: Float, val y: Float)

    /**
     * Reads a previously computed map from [cacheDir], but only when it was computed for the same
     * [fingerprint] as the current library — a stale envelope (the library changed since) is
     * ignored so the caller recomputes. Best-effort: any IO or parse failure returns null.
     */
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

    /**
     * Persists [model] under [fingerprint] to [cacheDir] for the next cold start. Written to a temp
     * file then renamed so a kill mid-write cannot leave a half-written cache — and even if it did,
     * [readDiskCache] swallows a parse failure and recomputes. Best-effort: a failure just means the
     * next start recomputes.
     */
    private suspend fun writeDiskCache(fingerprint: String, model: AtlasModel) {
        val dir = cacheDir ?: return
        withContext(Dispatchers.IO) {
            runCatching {
                dir.mkdirs()
                val file = File(dir, ATLAS_CACHE_FILE)
                val tmp = File(dir, "$ATLAS_CACHE_FILE.tmp")
                tmp.writeText(cacheJson.encodeToString(AtlasCacheEnvelope.serializer(), AtlasCacheEnvelope(fingerprint, model)))
                if (!tmp.renameTo(file)) {
                    // Rename can fail if the destination exists on some filesystems; fall back to a
                    // direct overwrite, which is still guarded by readDiskCache's runCatching.
                    file.writeText(tmp.readText())
                    tmp.delete()
                }
            }
        }
    }

    /**
     * Stage 2 of labelling: reorder each cluster's c-TF-IDF candidate phrases by cosine proximity
     * to the cluster's *meaning*, embedding each candidate and the cluster's exemplar passage with
     * the same model. Distinctive-and-on-theme phrases rise; distinctive-but-off-theme ones fall.
     *
     * Bounded: it embeds only the (deduped) candidate phrases plus one exemplar text per cluster,
     * in a single batch. Best-effort — any failure returns the input order unchanged.
     */
    /**
     * Stage 2, done the safe way: re-rank the label candidates of **one book's** clusters by
     * embedding proximity to each cluster's meaning, and return refreshed display labels keyed by
     * `exemplarChunkId` (a stable per-cluster id).
     *
     * This is called lazily when the reader zooms into a book — never during the roll-up — so the
     * embedder session is opened (or reused, if Echoes already warmed it) only when the memory
     * picture is calm, and only that book's handful of short candidate phrases are embedded, in
     * small batches. The anchor is the cluster's **raw** centroid (candidate phrases embed into raw
     * model space; the whitened centroid used for layout is a different space and must not be used
     * here). Best-effort: any failure returns an empty map and the c-TF-IDF labels stand.
     */
    suspend fun refineBookLabels(book: AtlasBook): Map<String, String> = withContext(dispatcher) {
        val clusters = book.clusters.filter { it.labelCandidates.isNotEmpty() && it.rawCentroid.isNotEmpty() }
        if (clusters.isEmpty()) return@withContext emptyMap()

        // Dedup the (small) phrase pool for this one book, embed in small batches, and reuse.
        val pool = clusters.flatMap { it.labelCandidates }.distinct()
        val phraseVec = HashMap<String, FloatArray?>(pool.size)
        pool.chunked(RERANK_BATCH).forEach { batch ->
            val vecs = runCatching { semanticSearch.embedTexts(batch) }.getOrDefault(batch.map { null })
            batch.forEachIndexed { i, p -> phraseVec[p] = vecs.getOrNull(i) }
        }
        if (phraseVec.values.all { it == null }) return@withContext emptyMap()

        clusters.associate { c ->
            val anchor = c.rawCentroid
            val reordered = c.labelCandidates.sortedByDescending { p ->
                phraseVec[p]?.let { cosine(it, anchor) } ?: -2.0
            }
            c.exemplarChunkId to ClusterLabeler.display(reordered)
        }
    }

    private fun cosine(a: FloatArray, b: FloatArray): Double {
        var dot = 0.0; var na = 0.0; var nb = 0.0
        val n = minOf(a.size, b.size)
        for (i in 0 until n) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        val denom = kotlin.math.sqrt(na) * kotlin.math.sqrt(nb)
        return if (denom > 1e-9) dot / denom else 0.0
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
        // Genre rows are part of the key too: the map is now coloured, labelled and laid out by
        // genre, so when the backfill classifies more books (or a classifier-version bump clears and
        // re-derives them) the count changes and a stale genre-blind atlas_cache.json is recomputed
        // instead of served. Without this the first cached map — built before genres existed — would
        // stay grey and unlabelled forever. Cheap indexed COUNT; stable once the library is classified.
        val genres = runCatching { genreRepository?.genreCount(model.id) ?: 0 }.getOrDefault(0)
        // ATLAS_ALGO_VERSION is part of the key so a change to the roll-up maths (robust adjacency,
        // force-directed layout, communities, genre) invalidates a stale atlas_cache.json on the
        // first run rather than serving a map built by the old algorithm.
        return "${model.id}:$chunks:$books:$genres:$ATLAS_ALGO_VERSION"
    }

    companion object {
        /** The Home hero requires at least this many embedded books before a map reads as a map. */
        const val ATLAS_BOOK_THRESHOLD = 5

        /**
         * Bumped whenever the roll-up's structure changes, so a cached [atlas_cache.json] built by an
         * older algorithm is discarded via [fingerprint] rather than reused. v2 introduced the robust
         * similarity graph, force-directed layout, emergent communities and broad-genre labelling; v3
         * moved overview labelling to genre-first regions (one per genre, no c-TF-IDF fallback) and
         * added same-genre attraction to the layout.
         */
        const val ATLAS_ALGO_VERSION = 3

        /** Fewest confident books of one genre before it earns a labelled region on the overview. */
        const val MIN_REGION_BOOKS = 2

        /**
         * Hard ceiling on chunks fed to the roll-up. Whitening allocates an n×dims double matrix,
         * so this bounds the peak heap regardless of library size. 5000 × 384 doubles ≈ 15 MB of
         * working set; on a ~35-book library that is ~140 sampled chunks per book, ample for the
         * per-book k-means (k ≤ 8) and the cross-book adjacency to stay representative.
         */
        const val MAX_ROLLUP_CHUNKS = 5000

        /** Small embed batch for the lazy per-book label re-rank, bounding transient memory. */
        private const val RERANK_BATCH = 32

        /** Filename of the fingerprint-keyed roll-up cache, written under [cacheDir]. */
        private const val ATLAS_CACHE_FILE = "atlas_cache.json"
    }
}

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
    /** Emergent communities (kept for the nebula/bridge grouping); no longer the label source. */
    val communities: List<AtlasCommunity> = emptyList(),
    /** The library's genre composition, largest share first — drives the legend readout. */
    val composition: List<GenreShare> = emptyList(),
    /**
     * One region per broad genre that has enough confident books — the map's overview labels. Placed
     * at the centroid of that genre's books, so the same genre is one labelled cloud rather than the
     * duplicated, character-name-prone community labels it replaced.
     */
    val genreRegions: List<AtlasGenreRegion> = emptyList(),
)

/**
 * One genre region on the map: a single label per broad genre, at the centroid of that genre's
 * confident books. Replaces per-community labelling for the overview — one label per genre means no
 * duplicate "Historical Fiction", and being genre-derived (not c-TF-IDF) means no character names.
 */
@Serializable
data class AtlasGenreRegion(
    /** Stable index among the model's regions (largest first) — the renderer's layout key. */
    val id: Int,
    /** Broad-genre display name shown as the region heading. */
    val genre: String,
    /** [BroadGenre.hueId] (or a name hash fallback) so the region's colour matches its stars. */
    val colorHueId: Int,
    val centroidX: Float,
    val centroidY: Float,
    val size: Int,
)

/**
 * One emergent community — a nebula on the map — named by the plurality broad genre of its member
 * books. [colorHueId] is a stable palette slot (a [BroadGenre.hueId] when a genre resolved, or a
 * community-derived slot past the genre range otherwise) so the renderer colours it consistently.
 */
@Serializable
data class AtlasCommunity(
    val id: Int,
    val genreLabel: String,
    val colorHueId: Int,
    val memberBookIds: List<String>,
    val centroidX: Float,
    val centroidY: Float,
    val size: Int,
    val tightness: Float,
)

/** One genre's share of the library, for the composition readout ("42% Science Fiction"). */
@Serializable
data class GenreShare(
    val genre: String,
    val count: Int,
    val fraction: Float,
)

/** On-disk envelope: the map plus the fingerprint it was computed for. */
@Serializable
private data class AtlasCacheEnvelope(val fingerprint: String, val model: AtlasModel)

@Serializable
data class AtlasBook(
    val bookId: String,
    val title: String,
    val coverPath: String?,
    /** 0..1 reading progress; the renderer lights read regions and fogs unread ones. */
    val readFraction: Float,
    /** The book's macro position in roughly [-1, 1] on each axis, from the force-directed layout. */
    val x: Float = 0f,
    val y: Float = 0f,
    /** The emergent community this book belongs to; the renderer colours the star by it. */
    val communityId: Int = 0,
    /** Broad genre display name (e.g. "Science Fiction"), or null when unclassified. */
    val genre: String? = null,
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
    /** The c-TF-IDF theme label, e.g. "Leviathan · Tide". Precomputed; refined lazily on zoom. */
    val label: String = "",
    /** The ranked candidate phrases behind [label], used by the lazy stage-2 re-rank. */
    val labelCandidates: List<String> = emptyList(),
    /** Raw (un-whitened) unit centroid — the anchor for the stage-2 re-rank. */
    val rawCentroid: FloatArray = FloatArray(0),
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
