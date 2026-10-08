package com.folio.reader.ml

/**
 * The one genuinely platform-specific piece of the embedding stack.
 *
 * `onnxruntime-android` and the desktop `onnxruntime` are different artifacts with
 * different native loading, so the session cannot live in `commonJvm` — it has to be
 * `expect`/`actual`. Everything above this line (chunking, pooling, cosine, RRF, the
 * tokenizer) is shared and testable without a model.
 */
interface OnnxModel : AutoCloseable {
    /** Names of the graph's inputs, used to decide whether `token_type_ids` is wanted. */
    val inputNames: Set<String>

    /**
     * Runs one batch.
     *
     * @param inputIds row-major `[batchSize, seqLength]`
     * @param attentionMask row-major `[batchSize, seqLength]`, 1 for real tokens
     * @param tokenTypeIds row-major `[batchSize, seqLength]`, or null when the graph has no such input
     * @return last hidden state flattened row-major `[batchSize, seqLength, hidden]`, plus the
     *   hidden size; or an already-pooled `[batchSize, hidden]` when the export pre-pools
     */
    fun run(
        inputIds: LongArray,
        attentionMask: LongArray,
        tokenTypeIds: LongArray?,
        batchSize: Int,
        seqLength: Int,
    ): OnnxOutput

    override fun close()
}

/**
 * @param values flattened activations
 * @param batchSize rows
 * @param seqLength tokens per row for `[batch, seq, hidden]`; 1 when the model already pooled
 * @param hiddenSize vector width
 */
data class OnnxOutput(
    val values: FloatArray,
    val batchSize: Int,
    val seqLength: Int,
    val hiddenSize: Int,
) {
    val isPooled: Boolean get() = seqLength == 1
}

/** Raised when the native runtime cannot be loaded or the model file is unusable. */
class OnnxUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Loads a model from disk.
 *
 * @param modelPath absolute path to the `.onnx` file
 * @param threads `intra_op_num_threads`. Deliberately has no default here: it belongs to the
 *   factory that owns the policy (`defaultEmbedThreads`), and a second default in this
 *   signature is how the placeholder value of 2 survived unmeasured.
 * @param useXnnpack Android only: XNNPACK vs NNAPI. Ignored on desktop.
 * @param enableCpuMemArena keep the ORT CPU memory arena between runs. Default true, and the app
 *   now leaves it at the default on **every** path including a backfill sweep.
 *
 *   This parameter has been the subject of two separate errors and the current answer is the
 *   third. It was first requested off through `addConfigEntry("session.use_cpu_mem_arena", "0")`,
 *   a key that does not exist in `libonnxruntime.so` — ORT stores unknown config entries without
 *   complaint, so the call "succeeded" and the arena was on for the life of the feature while the
 *   code, the parameter name and the comments all said otherwise. It was then disabled *for real*
 *   through the typed `setCPUArenaAllocator(false)`, which is when it became clear that an arena is
 *   not what was costing the memory: the sweep's peak came from a forward pass 62 rows wide, one
 *   whole chapter, because the chunker grouped by chapter and only checked the batch size after
 *   adding a group. Bounding the pass at `EmbeddingIndexer.batch` rows — 5 for Arctic-S, derived
 *   from the model's token ceiling — took a single pass from 4.38 GB to 661 MB and held it flat
 *   across 60+ passes on device.
 *
 *   The arena's own contribution was never isolated from that fix, so this parameter is left at the
 *   ORT default rather than tuned on a confounded measurement. What is measured is that an arena
 *   plus *bucketed* widths is cheap: `WordPieceTokenizer` rounds every batch to a padding bucket, so
 *   a sweep presents a handful of distinct shapes and the arena is reused instead of accumulating.
 */
expect fun openOnnxModel(
    modelPath: String,
    threads: Int,
    useXnnpack: Boolean = true,
    enableCpuMemArena: Boolean = true,
): OnnxModel
