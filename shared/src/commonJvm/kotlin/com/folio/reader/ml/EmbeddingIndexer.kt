package com.folio.reader.ml

import com.folio.reader.database.ChunkRepository
import com.folio.reader.database.ChapterRef
import com.folio.reader.database.ChapterIndexEntry
import com.folio.reader.importer.SearchIndexer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Creates embedders on demand. Returns null when the model is not on disk, which is the
 * normal state for a reader who has not opted into the download — callers must treat that
 * as "semantic search unavailable", not as an error.
 */
interface EmbedderFactory {
    val model: EmbeddingModel

    /**
     * @param threads intra-op threads for the session, or null for the factory's own default.
     *   The backfill passes a smaller number than an interactive search would: it is minutes
     *   of sustained CPU that nobody is waiting on, and it must not take the cores the reader
     *   is using. See [MlDispatchers].
     * @param sweep true for a long pass over many thousands of *distinct* inputs — the backfill
     *   and the importer. It turns the ONNX CPU memory arena **off**, which is required rather
     *   than merely preferable: on a sweep the arena grows with the number of distinct shapes it
     *   has seen and is never returned to the OS until the session closes, so a single backfill
     *   run accumulates hundreds of megabytes of native memory the Android GC cannot see. See
     *   [EmbeddingBackfillWorker] for the device evidence. Interactive callers (search, the
     *   taggers) leave it false: they embed a handful of same-shaped batches, where one arena is
     *   a straight win and closing the session at the end of the interaction frees it anyway.
     *
     * Defaulted so test fakes do not have to restate it, and so a new implementation that
     * ignores it is merely wasteful rather than a compile break in every fake.
     */
    suspend fun create(threads: Int? = null, sweep: Boolean = false): Embedder?
}

/**
 * Intra-op threads for an embedding session.
 *
 * This is the single biggest lever on embedding time, and the default of 2 that used to live
 * here was a placeholder nobody had measured. Reading `/proc/<pid>/stat` while the backfill
 * ran on the MT6897 test device showed only ~1.6 of 8 cores busy — 95 CPU-seconds to advance
 * 22 chapters, which is exactly what a 2-thread session looks like.
 *
 * The counts now live in [MlDispatchers], which owns the whole ML footprint in one place: a
 * session's threads are only half the story, because the pool the session runs *on* is the
 * other half, and sizing those independently is how the backfill ended up with four ONNX
 * threads inside a four-worker `Dispatchers.Default` on an 8-core phone. See that file.
 */
internal fun defaultEmbedThreads(): Int = MlDispatchers.sessionThreads

/**
 * Builds an [OnnxEmbedder] from files under the models directory.
 *
 * The session is created per use and closed afterwards rather than held open: a resident
 * MiniLM session is ~40-60 MB and the multilingual one ~150-200 MB, which is enough to get
 * the app killed in the background on a mid-range phone if it is never released.
 */
class OnnxEmbedderFactory(
    override val model: EmbeddingModel,
    private val modelsDir: File,
    private val threads: Int = defaultEmbedThreads(),
    private val useXnnpack: Boolean = true,
) : EmbedderFactory {

    override suspend fun create(threads: Int?, sweep: Boolean): Embedder? {
        val modelFile = File(modelsDir, model.fileName)
        val vocabFile = File(modelsDir, model.vocabFileName)
        if (!modelFile.isFile || !vocabFile.isFile) return null
        return OnnxEmbedder.wordPiece(
            model,
            modelFile,
            vocabFile,
            threads ?: this.threads,
            // **The sweep runs on the plain CPU execution provider, not XNNPACK.**
            //
            // XNNPACK is the right default for interactive embedding and was chosen for it, but on a
            // sweep it costs roughly 2 GB of native memory per session — and the cost is
            // *batch-independent*, which is what identifies it as weight/graph work rather than
            // activations. Measured on the MT6897 test device, `dumpsys meminfo` Native Heap while
            // the backfill ran:
            //
            //   t=6s   84 MB      (before the first session)
            //   t=12s  96 MB
            //   t=18s  1,559 MB   (session constructed)
            //   t=48s  process killed by lowmemorykiller
            //
            // The batch-independence is the diagnostic: batch 32 -> 16 -> 8 moved this number not at
            // all, and neither did the arena setting in either direction. Only session *construction*
            // scales that way, and XNNPACK repacks weights into its own layout per delegated
            // subgraph, so a 12-layer encoder can hold many copies of a model that is only 34 MB on
            // disk. See `OnnxModel.android.kt`.
            useXnnpack = useXnnpack && !sweep,
            // The arena stays **on** for a sweep, and that is a reversal.
            //
            // It used to be `!sweep`, on the reasoning that a sweep sees thousands of distinct
            // shapes and an arena grows with shape count. The reasoning was right; the remedy was
            // wrong, and it was also a no-op for the feature's whole life (the arena was disabled
            // through a config key that does not exist — see `OnnxModel.android.kt`). When the typed
            // setter finally made it real, peak native memory went *up*, because the arena is how ORT
            // reuses buffers between nodes and removing it made every node in a 12-layer encoder
            // allocate its own intermediates at once.
            //
            // The accumulation is a shape problem, and it is fixed at the shape: `encodeBatch` now
            // rounds every batch's width up to `WordPieceTokenizer.PAD_BUCKET`, so the sweep presents
            // at most eight distinct shapes instead of hundreds and the arena allocates for each
            // once. With the shape set bounded, reuse is free and the arena is what keeps the peak
            // small.
            //
            // The arena was never isolated from the fix that actually mattered, though — bounding
            // the pass at `batch` rows, which took one pass from 4.38 GB to 661 MB on device. So the
            // parameter is left at ORT's default and the *batch* is treated as the memory lever. See
            // `OnnxModel.kt`'s `enableCpuMemArena` doc for the full sequence.
            enableCpuMemArena = true,
        )
    }
}

/**
 * Turns chapter text into stored chunk vectors.
 *
 * Deliberately model-agnostic: everything it needs comes from [EmbedderFactory], so the
 * whole pipeline is exercisable in tests with [FakeEmbedder] and no ONNX at all.
 *
 * Every write is keyed by `model_id` and each chunk carries a `content_hash`, so a model
 * swap or a re-import is a well-defined amount of rework rather than a silent quality
 * change.
 */
class EmbeddingIndexer(
    private val chunkRepository: ChunkRepository,
    private val embedderFactory: EmbedderFactory,
    /**
     * [MlDispatchers.inference], not `Dispatchers.Default`. The indexer is the heaviest ML
     * consumer in the app, and putting it on the generic pool is what starved the UI during
     * the backfill — see [MlDispatchers].
     */
    private val dispatcher: CoroutineDispatcher = MlDispatchers.inference,
    /**
     * Chunk window in words. **Do not pass a constant here.**
     *
     * This defaulted to `TextChunker.DEFAULT_MAX_WORDS` (250), which is a *MiniLM-era* number:
     * `EmbeddingIndexer` is constructed from [EmbeddingModelSelection] with no model argument,
     * so the window was frozen at 250 for every model and could not follow a model switch.
     *
     * The default now reads the window off whichever model the factory is actually holding —
     * [EmbeddingModel.maxChunkWords], which is derived from that model's `maxTokens` and so
     * cannot exceed its token ceiling ([EmbeddingModel.maxChunkWords] explains why that matters).
     * Resolving it per instance rather than per class is what keeps the chunk size and the model
     * from drifting apart; they are a matched pair, like [EmbeddingModel.pooling].
     */
    private val maxWords: Int = embedderFactory.model.maxChunkWords,
    /**
     * Overlap in words, scaled down with the window so it always stays inside it.
     *
     * [TextChunker] requires `overlapWords < maxWords`, and the two are now genuinely independent:
     * the window comes from the model's token ceiling, so a model with a small ceiling produces a
     * window *narrower* than the fixed 50-word overlap. The fake test model (`maxTokens = 64`)
     * yields 41 words, which made the invariant throw — a real defect the fixed overlap had been
     * hiding, not a test artefact.
     *
     * Keeping the *proportion* rather than the absolute count is the point: overlap exists so a
     * passage straddling a boundary is still retrievable from one side, and that is a property of
     * the window, not of a word count. A quarter is [TextChunker.DEFAULT_OVERLAP_WORDS] against its
     * own 250-word default, so real models (173 and 350 words) get 43 and 87 words, and the
     * derivation stays honest for any ceiling.
     */
    private val overlapWords: Int = defaultOverlapWords(embedderFactory.model.maxChunkWords),
) {
    /**
     * The chunking this indexer writes, as a comparable string.
     *
     * Callers record it alongside the vectors so a later run can tell whether the index it is
     * looking at was built by the window now in force — `model_id` does not capture the window, and
     * a mismatch means the stored vectors describe different spans of text than the chunker would
     * produce today. See [chunkingRecipe].
     */
    val recipe: String = chunkingRecipe(maxWords, overlapWords)
    val model: EmbeddingModel get() = embedderFactory.model

    /**
     * Chunks per forward pass, derived from this model's token ceiling.
     *
     * ### This was pinned to 8 for one build, and pinning it did nothing
     *
     * The pin was an experiment: the derivation's constants had been calibrated from runs taken
     * while the ONNX arena was silently still on, so `batchFor` returned 2 for Arctic-S and looked
     * like the cause of both the crashes and the slowness. Pinning it to a known 8 produced **no
     * change at all** — because `batch` never reached the encoder. See [flush]: the sweep grouped
     * chunks per *chapter* and a chapter is ~44 chunks, so the pass ran at 44–62 rows whether this
     * number was 8 or 32. The pin measured nothing and has been removed.
     *
     * ### The real number, from one measured forward pass
     *
     * With in-process instrumentation (`OnnxMem` in `OnnxModel.android.kt`) a single pass at
     * `batch=62, seq=512` took whole-process PSS from 400 MB to **4380 MB** — a ~4 GB delta for one
     * `sess.run`, with the arena on and XNNPACK off. That is the quantity the system prices, and it
     * is what made the process the named abnormal consumer in ColorOS's watcher
     * (`abnormal_size: 1960252`). It also explains the slowness in the same breath: 4 GB does not
     * fit in the device's free memory, so the run spends its time in swap rather than in the encoder.
     *
     * Dividing that delta by `batch x seq^2` gives ~245 bytes per row per squared token, which is
     * the constant now in [BYTES_PER_ROW_PER_TOKEN_SQUARED]. This is a genuine activation cost and
     * it *is* batch-proportional — at `seq=512` the attention scores alone are `[B, 12, 512, 512]`
     * floats, i.e. 12.6 MB per row per layer. So the batch is the correct lever after all; it simply
     * was not connected.
     */
    val batch: Int = batchFor(embedderFactory.model.maxTokens)

    /**
     * Indexes one book's chapters in a single embedder session.
     *
     * Called from the import path, which is already off the UI thread — the plan's rule is
     * that no ML call may run on the composition dispatcher, and this never does.
     */
    suspend fun indexChapters(
        bookId: String,
        entries: List<ChapterIndexEntry>,
    ): IndexResult = withContext(dispatcher) {
        if (entries.isEmpty()) return@withContext IndexResult.SkippedNoModel
        // Importing a book is a sweep too: it embeds every chapter in one session, which is
        // the same accumulation the arena causes on the backfill path.
        val embedder = embedderFactory.create(sweep = true)
            ?: return@withContext IndexResult.SkippedNoModel
        try {
            var chunks = 0
            var chapters = 0
            // Groups are per chapter, so a failure can be attributed and the rest of the book kept.
            // The *forward pass* is bounded separately, inside [flush] — this accumulation is about
            // how much text is held at once, which is kilobytes, not about the encoder's footprint.
            val pending = ArrayList<Chunk>(batch)
            entries.forEach { entry ->
                val chapterChunks = chunkChapter(bookId, entry)
                if (chapterChunks.isEmpty()) return@forEach
                chapters++
                pending.addAll(chapterChunks)
                if (pending.size >= batch) {
                    chunks += flush(pending, embedder, batch)
                    pending.clear()
                }
            }
            chunks += flush(pending, embedder, batch)
            IndexResult.Indexed(chapters = chapters, chunks = chunks)
        } finally {
            runCatching { embedder.close() }
        }
    }

    /** Indexes a single chapter, e.g. after an in-place edit. */
    suspend fun indexChapter(bookId: String, entry: ChapterIndexEntry): IndexResult =
        indexChapters(bookId, listOf(entry))

    /**
     * One resumable slice of the library backfill.
     *
     * Picks up chapters that have no vectors for this model, so an interrupted run resumes
     * where it stopped instead of restarting. Returns how many chapters it processed so the
     * caller can decide whether to keep going.
     *
     * **Chunks are batched across chapter boundaries.** This used to call [flush] once per
     * chapter, so a typical chapter's four-to-six chunks went through the encoder as a batch of
     * four-to-six where thirty-two would fit. A forward pass costs about the same regardless of
     * batch size at this scale, so the sweep was issuing several times more passes than it
     * needed — which is why it had to hold ~310% CPU for so long, and therefore why it starved
     * the UI into ~9 fps.
     *
     * **A chapter that throws is skipped, not fatal.** The slice used to abort on the first
     * exception, which made a single unindexable chapter a permanent stall: it is never stored,
     * so `chaptersMissingVectors` selects it first on *every* subsequent slice, throws again,
     * and the index can never move past it. Observed on device as a frozen
     * "2322 of 2561 chapters indexed" with the worker re-running every 15 minutes forever.
     * Failures are counted and reported so the caller can tell "skipped one bad chapter" from
     * "the embedder is broken".
     *
     * Batching and per-chapter failure handling interact, which is why the flush takes **groups**
     * rather than a flat list. A batch carries chunks from many chapters; if one chapter's text
     * makes the encoder throw, a naive flush of the whole batch would lose every other chapter's
     * vectors with it, and would count them as succeeded because the exception is raised outside
     * the per-chapter `try`. So the batch is attributed per chapter and a failure falls back to
     * the chapters that did embed.
     */
    suspend fun backfillSlice(
        limit: Int,
        batchSize: Int? = null,
        threads: Int? = null,
        budgetMs: Long = Long.MAX_VALUE,
    ): BackfillSlice = withContext(dispatcher) {
        val chapters = chunkRepository.chaptersMissingVectors(model.id, limit)
        if (chapters.isEmpty()) return@withContext BackfillSlice(0, 0, complete = true)
        // The caller lowers this when the device is short of memory. It is the only lever that
        // actually reduces *peak* footprint: the slice length changes how long a run works, but the
        // batch changes how many rows are resident inside one forward pass, and peak native memory
        // is `batch x seq^2` — see [NATIVE_BATCH_BUDGET_BYTES]. `null` is the normal case and means
        // "use the batch derived for this model" ([batch]); a caller cannot raise it above that.
        val perPass = (batchSize ?: batch).coerceIn(1, batch)
        // `sweep = true` selects the plain CPU execution provider rather than XNNPACK, whose
        // per-subgraph weight repacking costs ~2 GB of native memory per session on a 12-layer
        // encoder — see [EmbeddingIndexerFactory.create]. It does **not** control the arena; that
        // stays on, because the pass widths are now bucketed so ORT's buffer reuse works. See
        // `OnnxModel.android.kt`.
        //
        // Threads are the caller's to choose. A sweep used to be pinned to
        // `MlDispatchers.backfillThreads` (2 of 8 cores) so it could not starve the reader — but the
        // worker now defers to the reader before every slice, so the state the cap was protecting
        // does not exist for the slices that run. `EmbeddingBackfillWorker` passes more threads when
        // it has confirmed the app is backgrounded, and the conservative two when it has not.
        val embedder = embedderFactory.create(threads ?: MlDispatchers.backfillThreads, sweep = true)
            ?: return@withContext BackfillSlice(0, 0, complete = true, modelMissing = true)

        try {
            var indexedChapters = 0
            var indexedChunks = 0
            var failedChapters = 0
            var firstError: Throwable? = null

            // One group per chapter, so a failure can be attributed and the rest of the batch
            // kept. The group count here is *not* the encoder's batch — a chapter arrives whole and
            // is ~44 chunks, so the pass is bounded inside [flush] instead. See the comment there
            // for why the bound had to move.
            var pending = ArrayList<List<Chunk>>(perPass)
            var pendingChunks = 0

            suspend fun drain() {
                if (pending.isEmpty()) return
                val outcome = flushGroups(pending, embedder, perPass)
                indexedChapters += outcome.storedGroups
                indexedChunks += outcome.storedChunks
                failedChapters += outcome.failedGroups
                if (firstError == null) firstError = outcome.firstError
                pending = ArrayList(perPass)
                pendingChunks = 0
            }

            val deadline = if (budgetMs == Long.MAX_VALUE) Long.MAX_VALUE
            else System.currentTimeMillis() + budgetMs

            for (ref in chapters) {
                // Stop between chapters once the budget is spent. Checked *between* chapters rather
                // than inside one, so a chapter is always stored whole — `storeChunks` deletes the
                // chapter's rows before inserting, so a chapter interrupted halfway would leave a
                // partial vector set that `chaptersMissingVectors` would never revisit.
                //
                // At least one chapter is always attempted, or a device slow enough to exceed the
                // budget on its first chapter would spin forever selecting the same work.
                if (indexedChapters + failedChapters > 0 && System.currentTimeMillis() >= deadline) {
                    break
                }
                val chunks = try {
                    chunkChapter(ref)
                } catch (error: Throwable) {
                    // Cancellation is not a chapter failure; let it unwind the slice.
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    failedChapters++
                    if (firstError == null) firstError = error
                    continue
                }
                // A chapter too short to chunk is not an error — the SQL predicate and the
                // chunker agree on that — but if one slips through it is skipped silently
                // rather than counted as a failure.
                if (chunks.isEmpty()) continue

                pending.add(chunks)
                pendingChunks += chunks.size
                if (pendingChunks >= perPass) drain()
            }
            drain()

            BackfillSlice(
                indexedChapters = indexedChapters,
                indexedChunks = indexedChunks,
                complete = chapters.size < limit,
                failedChapters = failedChapters,
                firstError = firstError,
            )
        } finally {
            runCatching { embedder.close() }
        }
    }

    /** What one batched flush managed to store. */
    private data class FlushOutcome(
        val storedGroups: Int,
        val storedChunks: Int,
        val failedGroups: Int,
        val firstError: Throwable?,
    )

    /**
     * Embeds a batch of per-chapter chunk groups, keeping whatever it can.
     *
     * Fast path first: one `embed` for the whole batch, which is the entire point of batching.
     * Only if that throws does it fall back to embedding group by group, so the cost of a
     * failure is paid once rather than on every batch. The fallback is what actually protects
     * the other chapters — the fast path's exception tells us *that* something failed, not which
     * chapter, so the batch has to be split to find out.
     */
    private suspend fun flushGroups(
        groups: List<List<Chunk>>,
        embedder: Embedder,
        maxRowsPerPass: Int,
    ): FlushOutcome {
        if (groups.isEmpty()) return FlushOutcome(0, 0, 0, null)

        val all = groups.flatten()
        val fast = runCatching { flush(all, embedder, maxRowsPerPass) }
        if (fast.isSuccess) {
            return FlushOutcome(storedGroups = groups.size, storedChunks = all.size, failedGroups = 0, firstError = null)
        }

        // Something in the batch is bad. Find out which, chapter by chapter.
        var storedGroups = 0
        var storedChunks = 0
        var failedGroups = 0
        var firstError: Throwable? = null
        for (group in groups) {
            try {
                storedChunks += flush(group, embedder, maxRowsPerPass)
                storedGroups++
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                failedGroups++
                if (firstError == null) firstError = error
            }
        }
        return FlushOutcome(storedGroups, storedChunks, failedGroups, firstError)
    }

    /** Removes a book's vectors, for a re-import or a delete. */
    suspend fun forgetBook(bookId: String) = chunkRepository.deleteChunksForBook(bookId)

    /**
     * Drops this model's vectors when they were built by a different chunking [recipe], and records
     * the recipe now in force.
     *
     * Vectors are keyed by `model_id`, so *switching models* is already lossless. A change to the
     * **chunk window** is not: the window is derived from the model's token ceiling and is not part
     * of the model id, so when the derivation moves (an app upgrade), the stored boundaries no
     * longer match what the chunker would produce. `chaptersMissingVectors` skips any chapter that
     * already has chunks, so a stale generation is never revisited — search would then serve two
     * generations of the same passage. Comparing the recorded recipe against [recipe] and deleting
     * on a mismatch is what prevents that.
     *
     * Shared by every entry point that builds an index — the Android backfill worker and the import
     * path (the desktop app's only index-building route) — so a window change invalidates a stale
     * index on both platforms, not only where a background worker happens to run.
     *
     * A missing record (a fresh install, or one predating the recipe table) records the current
     * recipe without deleting: a fresh install has nothing to drop, and wiping an existing library's
     * work on upgrade is a worse outcome than one stale generation surviving until the next re-index.
     *
     * @return a human-readable reason when a stale generation was cleared, or null when the index
     *   was already current (or freshly recorded).
     */
    suspend fun reconcileRecipe(): String? {
        val recorded = chunkRepository.chunkRecipe(model.id)
        if (recorded == null) {
            chunkRepository.recordChunkRecipe(model.id, recipe)
            return null
        }
        if (recorded == recipe) return null
        chunkRepository.deleteChunksForModel(model.id)
        chunkRepository.recordChunkRecipe(model.id, recipe)
        return "model ${model.id}: recipe was '$recorded', now '$recipe'"
    }

    private suspend fun flush(
        pending: List<Chunk>,
        embedder: Embedder,
        maxRowsPerPass: Int,
    ): Int {
        if (pending.isEmpty()) return 0
        // The forward pass is bounded **here**, not by how many chunks the caller accumulated.
        //
        // This is the fix for the crash, and the reason it took so long to find. Both callers
        // grouped chunks per *chapter* and drained once the accumulated count reached the batch —
        // but a chapter is ~44 chunks and arrives as one indivisible group, so the batch never
        // bound and every pass ran at 44–62 rows. Instrumented on device:
        //
        //   OnnxMem: run #1: pss=4380MB batch=62 seq=512 out=12189696 floats
        //
        // Pinning the batch to 8 changed nothing at all, which is how the defect presented from
        // the outside: a memory knob that measured as completely inert.
        //
        // Sub-batching at the *embedding* call rather than at the group level is deliberate.
        // `storeChunks` deletes a chapter's existing chunks before inserting the new ones, so a
        // chapter must reach storage in a single call or the second half would erase the first.
        // Splitting the pass keeps storage atomic while still bounding the encoder. Embedding is
        // per-chunk and independent, so the split costs only a little ORT call overhead.
        val vectors = ArrayList<FloatArray>(pending.size)
        for (pass in pending.chunked(maxRowsPerPass.coerceAtLeast(1))) {
            vectors += embedder.embed(pass.map { it.text }, EmbedKind.PASSAGE)
        }
        chunkRepository.storeChunks(pending, model.id, model.dims, vectors)
        return pending.size
    }

    private fun chunkChapter(bookId: String, entry: ChapterIndexEntry): List<Chunk> =
        TextChunker.chunk(
            text = entry.content,
            bookId = bookId,
            chapterId = entry.chapterId,
            spineIndex = entry.spineIndex,
            maxWords = maxWords,
            overlapWords = overlapWords,
        )

    private fun chunkChapter(ref: ChapterRef): List<Chunk> =
        TextChunker.chunk(
            text = ref.content,
            bookId = ref.bookId,
            chapterId = ref.chapterId,
            spineIndex = ref.spineIndex,
            maxWords = maxWords,
            overlapWords = overlapWords,
        )

    companion object {
        /**
         * Hard ceiling on chunks per forward pass, for both [indexChapters] and [backfillSlice].
         *
         * The batch a given model actually uses is [batch] = [batchFor]. This constant only stops a
         * model with a *small* ceiling from taking an enormous batch merely because it could afford
         * one.
         */
        const val MAX_BATCH = 32

        /**
         * Native memory one forward pass is allowed to hold.
         *
         * ### The dominant term is quadratic in the token window
         *
         * A transformer encoder's largest allocation is not the `[batch, seq, hidden]` output
         * buffer and not the tokenizer arrays — it is the **attention score matrix**, one per
         * layer, sized `batch x heads x seq x seq`. That is quadratic in the sequence length, and
         * the sequence length is the model's token window, which for Arctic-S is its full
         * 512-token ceiling. At `batch=62, seq=512` that single buffer is `62 x 12 x 512 x 512`
         * floats — 780 MB, per layer, before softmax and the value projection.
         *
         * ### Calibrated from one clean forward pass
         *
         * The numbers that used to be here (MiniLM B=32 → 819 MB, Arctic B=16 → 1532 MB, implying
         * `k ≈ 500`) were taken while the ONNX CPU memory arena was silently still on, so they are
         * arena high-water marks rather than the cost of a pass. Both were discarded.
         *
         * The replacement is a single instrumented `sess.run`, read from inside the process because
         * `/proc/<pid>/smaps` is unreadable for the shell UID on this device:
         *
         * ```
         * OnnxMem: session created: pss 287MB -> 400MB (delta 142MB; arena=true)
         * OnnxMem: run #1:          pss=4380MB batch=62 seq=512 out=12189696 floats
         * ```
         *
         * 4380 − 400 = **3980 MB for one pass at 62 rows of 512 tokens**, i.e.
         * `3980e6 / (62 x 512²)` ≈ **245 bytes per row per squared token**. Two facts make this the
         * right calibration where the old pair was not: it is a *single* pass rather than a whole
         * run's high-water mark, and the arithmetic reproduces the attention buffer exactly
         * (780 MB per layer at this batch, so ~4 GB across a 12-layer plan is the right order).
         *
         * ### Why 320 MB
         *
         * The measured failure is hard. At ~4 GB for a pass the device has ~11 MB free and ColorOS's
         * low-memory watcher names Folio as the abnormal consumer —
         *
         * ```
         * LOWMEM_WATCHER_EVENT: abnormal_pid: 30549, abnormal_size: 1960252,
         *                      total: 7612880, free: 11904, available: 880232
         * ```
         *
         * — recorded as `Process com.folio.reader has died: prcp FGS` with an **empty crash buffer**.
         * That is not a fault to catch in our code; it is the process being priced against every
         * other app and losing. The same overshoot also explains the slowness, because a 4 GB pass
         * does not fit and the encoder's time goes to swap rather than to arithmetic.
         *
         * 320 MB is under what the test device offers while merely busy (measured between 306 MB and
         * 2.8 GB available depending on what else is resident), so a pass fits in headroom the device
         * already has rather than competing for it. On Arctic-S this yields **5** rows per pass —
         * ~321 MB — and on MiniLM **20**.
         */
        const val NATIVE_BATCH_BUDGET_BYTES = 320L * 1024L * 1024L

        /**
         * Native peak per row, per squared token.
         *
         * Derived from the single instrumented pass described on [NATIVE_BATCH_BUDGET_BYTES]:
         * 3980 MB at 62 rows x 512 tokens. The scaling it encodes — quadratic in the window, linear
         * in the batch — is the shape of the attention buffer, which is why the term is a product of
         * squares rather than a lookup table per model.
         */
        const val BYTES_PER_ROW_PER_TOKEN_SQUARED = 245L

        /**
         * Chunks per forward pass for a model whose token ceiling is [maxTokens].
         *
         * Pure, so a test can pin the derivation without a model or a device. Never returns less
         * than 1: a model whose ceiling is so large that a single row exceeds the budget must
         * still be able to embed one row, or its index could never be built at all.
         *
         * **The one-row floor can exceed the budget, deliberately.** At
         * [BYTES_PER_ROW_PER_TOKEN_SQUARED] = 245 the crossover is ~1170 tokens, so any model wider
         * than that costs more than [NATIVE_BATCH_BUDGET_BYTES] for a single row. Returning 1 and
         * accepting the overshoot is the correct answer: returning 0 would mean "embed nothing",
         * and the backfill would select the same chapter on every slice forever. A model that wide
         * needs the *budget* raised, not the floor lowered — and no shipping model is close.
         */
        fun batchFor(maxTokens: Int): Int {
            if (maxTokens <= 0) return MAX_BATCH
            val perRow = BYTES_PER_ROW_PER_TOKEN_SQUARED * maxTokens.toLong() * maxTokens.toLong()
            if (perRow <= 0L) return MAX_BATCH
            return (NATIVE_BATCH_BUDGET_BYTES / perRow).coerceIn(1L, MAX_BATCH.toLong()).toInt()
        }

        /**
         * Overlap for a window of [maxWords], kept strictly inside it.
         *
         * A quarter of the window, matching [TextChunker.DEFAULT_OVERLAP_WORDS] (50) against
         * `DEFAULT_MAX_WORDS` (250). The `coerceAtLeast(1)` is what makes the `overlapWords <
         * maxWords` invariant hold for even a tiny window: a model declaring a very small
         * `maxTokens` produces a window of a few words, and a proportional overlap could round to
         * zero and lose the overlap entirely — which is still valid, but a one-word overlap is
         * never wrong and keeps a boundary-straddling passage retrievable.
         */
        fun defaultOverlapWords(maxWords: Int): Int =
            (maxWords / 4).coerceIn(1, (maxWords - 1).coerceAtLeast(1))

        /** Builds a [ChapterIndexEntry] from chapter HTML, reusing the FTS5 text extractor. */
        fun entry(chapterId: String, spineIndex: Int, title: String, html: String): ChapterIndexEntry =
            ChapterIndexEntry(
                chapterId = chapterId,
                spineIndex = spineIndex,
                title = title,
                content = SearchIndexer.extractPlainText(html),
            )
    }
}

sealed interface IndexResult {
    data class Indexed(val chapters: Int, val chunks: Int) : IndexResult

    /** The model file is not on disk; nothing was attempted. Not an error. */
    data object SkippedNoModel : IndexResult
}

data class BackfillSlice(
    val indexedChapters: Int,
    val indexedChunks: Int,
    val complete: Boolean,
    val modelMissing: Boolean = false,
    /**
     * Chapters that threw while being indexed. They are skipped so the slice can still make
     * progress; the caller decides whether that is worth retrying (a transient failure) or
     * worth reporting (a chapter that will never index).
     */
    val failedChapters: Int = 0,
    /** The first failure of the slice, kept so the caller can log a real cause. */
    val firstError: Throwable? = null,
)
