package com.folio.reader.ml

/**
 * Encodes text into model input ids.
 *
 * Extracted as an interface so [OnnxEmbedder] is tokenizer-agnostic: the shipped path uses
 * [WordPieceTokenizer], while the Phase 0b comparison drives the SentencePiece multilingual
 * model through the reference Rust tokenizer on desktop. Without this seam the benchmark
 * could not compare the two model families at all.
 */
interface Tokenizer {

    val vocabSize: Int

    /** Token ids including the `[CLS]`/`[SEP]` frame, truncated to [maxLength]. */
    fun encode(text: String, maxLength: Int): IntArray

    /** Pads a batch to the longest sequence so one forward pass can serve it. */
    fun encodeBatch(texts: List<String>, maxLength: Int, padToLongest: Boolean = true): EncodedBatch

    data class EncodedBatch(
        val inputIds: LongArray,
        val attentionMask: LongArray,
        val tokenTypeIds: LongArray,
        val sequenceLength: Int,
        val batchSize: Int,
    )
}
