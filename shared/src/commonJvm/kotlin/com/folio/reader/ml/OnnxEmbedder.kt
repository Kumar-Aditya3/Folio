package com.folio.reader.ml

import kotlinx.coroutines.withContext
import java.io.File

/**
 * Sentence-transformer inference: tokenize -> ONNX -> pool -> L2 normalise.
 *
 * Pooling follows [EmbeddingModel.pooling], because it is part of each model's training
 * recipe rather than a free choice: `all-MiniLM-L6-v2` and the e5 family are trained with
 * an attention-masked mean, `snowflake-arctic-embed` with the leading `[CLS]` token. Using
 * the wrong one degrades retrieval noticeably and silently — the vectors are still the
 * right length and still unit-norm, so nothing fails.
 *
 * Session construction is lazy: the first [embed] loads the model. A 113 MB multilingual
 * model costs ~150-200 MB of RSS once resident, which is enough to get background apps
 * killed on a mid-range phone, so callers must [close] when the search screen goes away.
 */
class OnnxEmbedder(
    private val descriptor: EmbeddingModel,
    private val modelFile: File,
    private val tokenizer: Tokenizer,
    private val threads: Int = 2,
    private val useXnnpack: Boolean = true,
    private val maxTokens: Int = descriptor.maxTokens,
    /**
     * Keep the ONNX CPU memory arena across calls. On by default — the app embeds in
     * same-shaped batches, so one arena is pure win. Turn it **off** for a long sweep over
     * many thousands of distinct inputs, where the arena only accumulates: a benchmark run
     * here reached tens of GB of native RSS against a sub-GB Java heap, none of it
     * reclaimable by GC.
     *
     * @see openOnnxModel for the underlying setting.
     */
    private val enableCpuMemArena: Boolean = true,
) : Embedder {

    override val model: EmbeddingModel get() = descriptor

    private var session: OnnxModel? = null

    /** Wall-clock milliseconds spent constructing the ONNX session, for Phase 0a. */
    var sessionInitMillis: Long = -1
        private set

    /** Number of forward passes run, so a benchmark can warm up before it measures. */
    var runCount: Int = 0
        private set

    /**
     * Runs on [MlDispatchers.inference] unconditionally, rather than trusting the caller.
     *
     * Every caller today already wraps this in the same dispatcher, so the hop is usually a
     * no-op — `withContext` returns immediately when the dispatcher matches. It is kept
     * because the alternative is a future call site that forgets, and the failure mode of
     * forgetting is a multi-threaded ONNX session on the composition dispatcher, which is
     * both a jank source and (on desktop) a `Dispatchers.Main` that is a single Swing thread.
     */
    override suspend fun embed(texts: List<String>, kind: EmbedKind): List<FloatArray> =
        withContext(MlDispatchers.inference) {
            if (texts.isEmpty()) return@withContext emptyList()
            val sess = ensureSession()

            val prefix = when (kind) {
                EmbedKind.QUERY -> descriptor.queryPrefix
                EmbedKind.PASSAGE -> descriptor.passagePrefix
            }
            val prepared = texts.map { if (prefix.isEmpty()) it else prefix + it }
            val batch = tokenizer.encodeBatch(prepared, maxTokens)

            val output = sess.run(
                inputIds = batch.inputIds,
                attentionMask = batch.attentionMask,
                // Feed `token_type_ids` whenever the graph declares that input — which is a
                // different question from `descriptor.usesTokenTypeIds`. That flag describes the
                // *tokenizer*: XLM-R produces no meaningful segment ids, so it is false for
                // multilingual-e5. But both exported graphs still declare the input, and ORT
                // refuses to run without it ("Input Missing Input: token_type_ids" raised by the
                // token_type_embeddings Gather node). So the decision follows the graph, and both
                // tokenizers already emit an all-zero array of the right size when they have none.
                tokenTypeIds = if (sess.inputNames.contains("token_type_ids")) batch.tokenTypeIds else null,
                batchSize = batch.batchSize,
                seqLength = batch.sequenceLength,
            )
            runCount++

            val pooled = if (output.isPooled) {
                // Export already pooled: copy each row out so callers cannot alias the buffer.
                (0 until output.batchSize).map { row ->
                    output.values.copyOfRange(
                        row * output.hiddenSize,
                        (row + 1) * output.hiddenSize,
                    )
                }
            } else {
                when (descriptor.pooling) {
                    PoolingStrategy.MEAN -> meanPool(output, batch.attentionMask, batch.sequenceLength)
                    PoolingStrategy.CLS -> clsPool(output, batch.sequenceLength)
                }
            }
            pooled.forEach { it.l2Normalize() }
            pooled
        }

    /** One-shot convenience. The caller still owns the lifecycle — this does not close anything. */
    suspend fun embedOne(text: String, kind: EmbedKind = EmbedKind.PASSAGE): FloatArray =
        embed(listOf(text), kind).first()

    private fun ensureSession(): OnnxModel {
        session?.let { return it }
        check(modelFile.exists()) { "Embedding model missing: ${modelFile.absolutePath}" }
        val started = System.currentTimeMillis()
        val opened = openOnnxModel(modelFile.absolutePath, threads, useXnnpack, enableCpuMemArena)
        sessionInitMillis = System.currentTimeMillis() - started
        session = opened
        // Counted here rather than in the constructor because the session is lazy: this is the
        // moment ~40 MB of native memory is actually committed, and a leaked handle is a leak
        // from this line onward. See [MlSessionCensus].
        MlSessionCensus.opened()
        return opened
    }

    /**
     * Attention-masked mean over the token axis.
     *
     * Padding positions must be excluded, not merely zero-weighted: including them shifts
     * the centroid toward whatever the model emits for `[PAD]`, which on a short query
     * against a long passage is a large error.
     */
    private fun meanPool(
        output: OnnxOutput,
        attentionMask: LongArray,
        seqLength: Int,
    ): List<FloatArray> {
        val hidden = output.hiddenSize
        return (0 until output.batchSize).map { row ->
            val acc = FloatArray(hidden)
            var tokens = 0
            for (t in 0 until seqLength) {
                if (attentionMask[row * seqLength + t] == 0L) continue
                tokens++
                val base = (row * seqLength + t) * hidden
                for (h in 0 until hidden) acc[h] += output.values[base + h]
            }
            if (tokens > 0) {
                val inv = 1f / tokens
                for (h in 0 until hidden) acc[h] *= inv
            }
            acc
        }
    }

    /**
     * The leading `[CLS]` token's hidden state, taken verbatim.
     *
     * No attention mask is needed and none is passed: position 0 is the first token of
     * every sequence by construction — the tokenizer always emits `[CLS]` there and no
     * model in the catalog prepends anything else — so it can never be a padding position.
     * Reading it straight out (rather than, say, mean-pooling over an empty mask) is also
     * what the Arctic model card prescribes: `model(**tok)[0][:, 0]`.
     *
     * [OnnxOutput.values] is laid out `[batch, seq, hidden]`, so the CLS vector for row
     * `r` is the first `hiddenSize` floats of that row's block.
     */
    private fun clsPool(
        output: OnnxOutput,
        seqLength: Int,
    ): List<FloatArray> {
        val hidden = output.hiddenSize
        val stride = seqLength * hidden
        return (0 until output.batchSize).map { row ->
            output.values.copyOfRange(row * stride, row * stride + hidden)
        }
    }

    override fun close() {
        // Decremented only when a session was actually open, so a double `close()` cannot drive
        // the census negative and mask a real leak as a smaller one.
        session?.let {
            runCatching { it.close() }
            MlSessionCensus.closed()
        }
        session = null
    }

    companion object {
        /**
         * The shipped path: BERT WordPiece built from the model's `vocab.txt`.
         * Kept as a factory so callers never construct a tokenizer inline and end up
         * mismatching it against the model.
         */
        fun wordPiece(
            descriptor: EmbeddingModel,
            modelFile: File,
            vocabFile: File,
            threads: Int = defaultEmbedThreads(),
            useXnnpack: Boolean = true,
            enableCpuMemArena: Boolean = true,
        ): OnnxEmbedder {
            check(vocabFile.exists()) { "Tokenizer vocab missing: ${vocabFile.absolutePath}" }
            return OnnxEmbedder(
                descriptor = descriptor,
                modelFile = modelFile,
                tokenizer = WordPieceTokenizer.fromVocabFile(vocabFile),
                threads = threads,
                useXnnpack = useXnnpack,
                enableCpuMemArena = enableCpuMemArena,
            )
        }
    }
}
