package com.folio.reader.ml

/**
 * Semantic-search domain types.
 *
 * These live in `commonMain` on purpose: they contain no ONNX, no file IO and no
 * platform types, which is what lets the retrieval layer be unit-tested on desktop
 * with a fake [Embedder] and no model on disk. The ONNX session factories are the
 * only platform-specific piece, and they are `expect`/`actual` — `onnxruntime-android`
 * and the desktop `onnxruntime` are different artifacts, so the engine cannot live in
 * `commonJvm`.
 */

/** How a model's weights are stored. Affects both file size and accuracy. */
enum class EmbeddingQuantization { FLOAT32, INT8, UINT8 }

/**
 * How a sentence vector is read out of the encoder's last hidden state.
 *
 * This is **not** a tuning knob and it is not interchangeable between models: it is part
 * of the training recipe. Feeding the wrong one produces vectors of the right shape and
 * the right norm that retrieve markedly worse, with no error anywhere — which is why it
 * is a declared property of the model rather than a choice made at the call site.
 *
 * - [MEAN] — attention-masked mean over the token axis. `all-MiniLM-L6-v2` and the e5
 *   family are trained this way.
 * - [CLS] — the leading `[CLS]` token's hidden state, with no pooling at all.
 *   `snowflake-arctic-embed` (all sizes) is trained this way: its model card instructs
 *   `model(**tok)[0][:, 0]`.
 */
enum class PoolingStrategy { MEAN, CLS }

/**
 * Everything needed to locate, download and correctly call one embedding model.
 *
 * [dims] and [id] are load-bearing: they are written onto every vector row so a
 * vector produced by one model can never be compared against another's.
 */
data class EmbeddingModel(
    val id: String,
    val displayName: String,
    val dims: Int,
    val quantization: EmbeddingQuantization,
    /** File name under the models directory, e.g. `all-MiniLM-L6-v2-int8.onnx`. */
    val fileName: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val vocabFileName: String,
    val vocabUrl: String,
    val maxTokens: Int,
    /**
     * How to read a sentence vector out of the encoder.
     *
     * Defaults to [PoolingStrategy.MEAN] because that is what the models already in the
     * catalog use; a model trained on CLS must say so explicitly, or it will be silently
     * mis-pooled. See [PoolingStrategy] for why this cannot be inferred.
     */
    val pooling: PoolingStrategy = PoolingStrategy.MEAN,
    /** e5-family and arctic models are trained with these prefixes; MiniLM has none. */
    val queryPrefix: String = "",
    val passagePrefix: String = "",
    /** True when the model expects `token_type_ids` alongside `input_ids`/`attention_mask`. */
    val usesTokenTypeIds: Boolean = true,
    /**
     * Overrides the token-derived [maxChunkWords] when set.
     *
     * The derived window fills the model's whole token budget, which maximises the *context* per
     * vector but also dilutes it: a 350-word Arctic chunk averages many sentences into one point,
     * so a query about one idea matches a blurred average. On-corpus measurement (`malazan-probe`)
     * found a ~150-word window retrieves the *specific* passage more sharply for narrative prose,
     * at the cost of more chunks. This is that override — a retrieval-quality choice, not a token
     * limit, so it is stated per model rather than derived.
     */
    val chunkWordsOverride: Int? = null,
) {
    val vocabFile: String get() = vocabFileName

    /**
     * Words a chunk may contain before it must be split, derived from [maxTokens].
     *
     * This is **derived rather than configured** on purpose. It used to be
     * `TextChunker.DEFAULT_MAX_WORDS = 250`, a plain constant set when MiniLM was the only
     * model — and 250 English words is roughly 330–360 WordPiece tokens, which is *over*
     * MiniLM's 256-token ceiling. Chunks were therefore silently truncated: the tokenizer
     * cut the tail off most passages, so a "250-word" chunk embedded only its first ~180
     * words. Nothing reported this, because truncation is not an error — the vector is
     * simply computed from less text than the reader believes, and recall drops.
     *
     * For Arctic-S (`maxTokens = 512`) the same 250-word default is *under* the ceiling, so
     * it wastes context instead of truncating — the opposite failure, and the reason the
     * right chunk size genuinely depends on the model rather than on a house style.
     *
     * English prose runs about **1.3–1.45 tokens per word**, so [TOKENS_PER_WORD] = 1.45 is
     * the conservative end. The `-`4 accounts for the `[CLS]`/`[SEP]` frame the tokenizer
     * adds, so the result is the number of real words that safely fit.
     *
     * Measured against the corpus: MiniLM's 256-token ceiling yields **~176 words**, i.e.
     * ~62 chunks for a typical 11 000-word chapter; Arctic-S's 512 yields **~350 words**, and
     * roughly **100 chunks per chapter** at the 150-word window the retrieval measurements
     * preferred. Both are far from the old flat 250.
     */
    val maxChunkWords: Int get() =
        chunkWordsOverride ?: ((maxTokens - 4) / TOKENS_PER_WORD).toInt().coerceAtLeast(1)

    private companion object {
        /** Conservative tokens-per-word for English prose: 1.45 (range 1.3–1.45). */
        const val TOKENS_PER_WORD = 1.45
    }
}

/** Retrieval is asymmetric: a short query is embedded differently from a 250-word passage. */
enum class EmbedKind { QUERY, PASSAGE }

/**
 * Identifies the chunking that produced a model's vectors, so a changed window can be detected.
 *
 * A vector index is only meaningful for the recipe that built it: chunk boundaries decide which
 * text each vector represents. `model_id` captures the *model* but not the window, and the window
 * changed — from a flat 250 words to [EmbeddingModel.maxChunkWords] — without any model changing.
 * Stored per model and compared on startup; a mismatch means the whole index is stale.
 *
 * Deliberately a **string built from the parameters**, not a version number that someone must
 * remember to bump. A version would be silently wrong the first time a window changed without the
 * bump, which is exactly the bug this exists to catch. The value is stable for a given window size,
 * so re-indexing with the same recipe is idempotent.
 */
fun chunkingRecipe(maxWords: Int, overlapWords: Int): String = "words=$maxWords;overlap=$overlapWords"

/**
 * Turns text into unit-normalised vectors.
 *
 * Implementations are **not** thread-safe and are expensive to construct, so callers
 * must hold one per search session and close it on exit. Never call [embed] from the
 * UI thread or from a `LaunchedEffect` — see `ML_PLAN.md`'s cross-cutting rules.
 */
interface Embedder : AutoCloseable {
    val model: EmbeddingModel

    /** Embeds a batch; the result is index-aligned with [texts]. */
    suspend fun embed(texts: List<String>, kind: EmbedKind = EmbedKind.PASSAGE): List<FloatArray>

    override fun close() {}
}

/**
 * One embedded unit of a chapter.
 *
 * [charStart]/[charEnd] are offsets into the chapter's **plain text** (the same string
 * `SearchIndexer.extractPlainText` produces), so a hit can deep-link back into the
 * reader. [contentHash] covers [text] only: it is what tells a stale vector from a
 * fresh one after a re-import, and it is deliberately independent of chunk position.
 */
data class Chunk(
    val id: String,
    val bookId: String,
    val chapterId: String,
    val spineIndex: Int,
    val chunkIndex: Int,
    val charStart: Int,
    val charEnd: Int,
    val text: String,
    val contentHash: String,
)

/** A scored chunk id. [score] is cosine similarity in [-1, 1] for [VectorIndex] hits. */
data class VectorHit(val chunkId: String, val score: Float)

/**
 * Nearest-neighbour search over chunk vectors.
 *
 * Brute force is the correct implementation at this project's scale: 40 000 chunks x
 * 384 dims is ~15 M multiply-adds, i.e. 10-20 ms single-threaded. An ANN index only
 * earns its complexity past ~200 000 chunks.
 */
interface VectorIndex {
    val dims: Int
    val size: Int

    fun add(chunkId: String, vector: FloatArray)

    /** Replaces the whole index in one shot — cheaper than N [add] calls at load time. */
    fun addAll(entries: List<Pair<String, FloatArray>>) {
        entries.forEach { (id, vector) -> add(id, vector) }
    }

    fun search(
        query: FloatArray,
        limit: Int,
        filter: ((chunkId: String) -> Boolean)? = null,
    ): List<VectorHit>

    fun clear()
}

/** L2-normalises in place and returns the same array, so callers can chain. */
fun FloatArray.l2Normalize(): FloatArray {
    var sum = 0.0
    for (v in this) sum += v.toDouble() * v.toDouble()
    val norm = kotlin.math.sqrt(sum)
    if (norm > 1e-12) {
        val inv = (1.0 / norm).toFloat()
        for (i in indices) this[i] = this[i] * inv
    }
    return this
}

/**
 * Dot product of two already-normalised vectors, which equals cosine similarity.
 * Falls back to a real cosine when either side is not unit length.
 */
fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
    require(a.size == b.size) { "dimension mismatch: ${a.size} vs ${b.size}" }
    var dot = 0.0
    var na = 0.0
    var nb = 0.0
    for (i in a.indices) {
        dot += a[i].toDouble() * b[i].toDouble()
        na += a[i].toDouble() * a[i].toDouble()
        nb += b[i].toDouble() * b[i].toDouble()
    }
    val denom = kotlin.math.sqrt(na) * kotlin.math.sqrt(nb)
    return if (denom < 1e-12) 0f else (dot / denom).toFloat()
}
