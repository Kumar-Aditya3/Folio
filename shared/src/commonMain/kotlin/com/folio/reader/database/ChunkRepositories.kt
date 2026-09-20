package com.folio.reader.database

import com.folio.reader.ml.Chunk
import kotlinx.coroutines.flow.Flow

/**
 * Persistence for chunked chapter text and its embeddings.
 *
 * Split out from [SearchRepository] because the two have genuinely different lifecycles:
 * FTS5 content is written once per import and is model-independent, whereas chunk vectors
 * are per-model and must be re-derivable after a model swap or a re-import.
 */
interface ChunkRepository {

    /**
     * Replaces the chunks for [chunks]' chapters and stores their [vectors] in one
     * transaction. [vectors] is index-aligned with [chunks].
     */
    suspend fun storeChunks(chunks: List<Chunk>, modelId: String, dims: Int, vectors: List<FloatArray>)

    /** Removes every chunk (and vector) belonging to [bookId], for any model. */
    suspend fun deleteChunksForBook(bookId: String)

    /** Removes chunks belonging to [bookId] for one model only. */
    suspend fun deleteChunksForBook(bookId: String, modelId: String)

    /** Removes every chunk produced by [modelId]; used when a model is swapped or removed. */
    suspend fun deleteChunksForModel(modelId: String)

    /**
     * Loads chunks and vectors for [modelId], for preloading a brute-force index.
     * Returns empty when [modelId]'s stored dimensionality does not match [dims], so a
     * model swap cannot silently mix incompatible vectors.
     *
     * [bookId] scopes the load to a single book when non-null. In-book search needs only the
     * open book's vectors, and loading the whole library there is what pushed a large library's
     * flat index over the heap limit — a full Arctic index of ~100k chunks is a ~150 MB
     * contiguous float array. Null keeps the whole-library behaviour for cross-book search.
     *
     * Prefer [loadVectorMetadata] for building an in-memory index: this variant also pulls every
     * chunk's `text`, which for a large library is ~170 MB of strings the index scan never reads.
     */
    suspend fun loadVectors(modelId: String, dims: Int, bookId: String? = null): List<Pair<Chunk, FloatArray>>

    /**
     * Like [loadVectors] but without the chunk `text`, for building the in-memory index.
     *
     * The scan needs the vector and enough to identify and deep-link a hit; it never needs the
     * passage text until a result is shown. Loading text for every one of a large library's ~100k
     * chunks was ~170 MB of string allocation and the bulk of the preload time — [chunkTexts]
     * fetches text for just the handful of hits that are actually returned.
     */
    suspend fun loadVectorMetadata(modelId: String, dims: Int, bookId: String? = null): List<Pair<ChunkMeta, FloatArray>>

    /** Chunk `text` for the given ids, for filling snippets of the hits actually shown. */
    suspend fun chunkTexts(ids: Collection<String>): Map<String, String>

    /**
     * Chunk ids already stored for a chapter, used to skip work that is already done.
     *
     * [bookId] is required rather than derived: `chapter_id` is the EPUB manifest identifier,
     * which is book-local (`ch1`, `html_0`) and collides freely across books, so a lookup
     * keyed on it alone can return another book's chunks.
     */
    suspend fun indexedChunkIds(bookId: String, chapterId: String, modelId: String): Set<String>

    suspend fun chunkCount(modelId: String): Int

    /**
     * Every model id that currently has stored chunks, with its chunk count. Used by the storage
     * screen to offer clearing the vectors of models the reader has switched away from.
     */
    suspend fun modelChunkCounts(): Map<String, Int>

    /** Total chapters in the library that have searchable text. */
    suspend fun searchableChapterCount(): Int

    suspend fun indexedChapterCount(modelId: String): Int

    /**
     * Chapters that still need embedding for [modelId], in a stable order so a chunked,
     * resumable backfill makes progress rather than re-scanning the same head each run.
     */
    suspend fun chaptersMissingVectors(modelId: String, limit: Int): List<ChapterRef>

    /** Observable progress for the "index your library" UI. */
    fun observeProgress(modelId: String): Flow<IndexProgress>

    suspend fun progress(modelId: String): IndexProgress

    /**
     * The chunking recipe that produced [modelId]'s vectors, or null when it has none.
     *
     * A vector index is only valid for the recipe that built it: chunk boundaries decide which text
     * each vector represents, so a different window means a different index. `model_id` alone does
     * not capture that — the window lives on the model but is not part of its id — so it is recorded
     * and compared separately. See [ChunkingRecipe].
     */
    suspend fun chunkRecipe(modelId: String): String?

    /** Records [recipe] as the producer of [modelId]'s vectors. Idempotent. */
    suspend fun recordChunkRecipe(modelId: String, recipe: String)
}

/**
 * A chunk's identity and position without its text.
 *
 * Everything the index scan and a hit's deep-link need — where the chunk is and which chapter it
 * belongs to — but not the passage itself, which is fetched by [ChunkRepository.chunkTexts] only
 * for the hits that are shown. Keeping text out of the resident index is what lets a large
 * library's vectors stay in memory without the ~170 MB of chunk strings alongside them.
 */
data class ChunkMeta(
    val id: String,
    val bookId: String,
    val chapterId: String,
    val spineIndex: Int,
    val charStart: Int,
    val charEnd: Int,
)

/** A chapter's searchable text, as stored by the FTS5 index. */
data class ChapterRef(
    val bookId: String,
    val chapterId: String,
    val spineIndex: Int,
    val title: String,
    val content: String,
)

data class IndexProgress(
    val indexedChapters: Int,
    val totalChapters: Int,
    val indexedChunks: Int,
    val modelId: String,
) {
    val fraction: Float
        get() = if (totalChapters <= 0) 0f else (indexedChapters.toFloat() / totalChapters).coerceIn(0f, 1f)

    val isComplete: Boolean get() = totalChapters > 0 && indexedChapters >= totalChapters

    /** True when the library has searchable text but nothing has been embedded yet. */
    val hasNothingIndexed: Boolean get() = indexedChunks == 0 && totalChapters > 0
}
