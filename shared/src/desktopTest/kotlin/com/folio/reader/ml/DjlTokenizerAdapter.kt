package com.folio.reader.ml

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import java.nio.file.Path

/**
 * [Tokenizer] backed by the reference Rust implementation, via DJL.
 *
 * Test-only, for two reasons:
 * 1. [TokenizerParityTest] uses it to prove the hand-rolled [WordPieceTokenizer] has not
 *    drifted from the reference on real corpus prose.
 * 2. The Phase 0b comparison needs the SentencePiece/Unigram path the multilingual model
 *    requires, which is exactly the piece not worth hand-rolling.
 *
 * It cannot ship: the DJL jar carries natives for linux/osx/win but none for Android.
 */
class DjlTokenizerAdapter private constructor(
    private val delegate: HuggingFaceTokenizer,
) : Tokenizer {

    /**
     * The reference binding exposes no vocabulary size, so this reports -1 rather than
     * pretending. Callers that need the count should read the vocabulary file.
     */
    override val vocabSize: Int get() = -1

    override fun encode(text: String, maxLength: Int): IntArray {
        val ids = delegate.encode(text).ids
        if (ids.size <= maxLength) return IntArray(ids.size) { ids[it].toInt() }
        return IntArray(maxLength) { ids[it].toInt() }
    }

    override fun encodeBatch(texts: List<String>, maxLength: Int, padToLongest: Boolean): Tokenizer.EncodedBatch {
        val encoded = texts.map { delegate.encode(it) }
        val lengths = encoded.map { minOf(it.ids.size, maxLength) }
        val width = if (padToLongest) (lengths.maxOrNull() ?: 0) else 0
        val inputIds = LongArray(texts.size * width)
        val attention = LongArray(texts.size * width)
        val types = LongArray(texts.size * width)
        encoded.forEachIndexed { row, encoding ->
            val take = lengths[row]
            for (col in 0 until take) {
                inputIds[row * width + col] = encoding.ids[col]
                attention[row * width + col] = encoding.attentionMask.getOrElse(col) { 1L }
                types[row * width + col] = encoding.typeIds.getOrElse(col) { 0L }
            }
        }
        return Tokenizer.EncodedBatch(inputIds, attention, types, width, texts.size)
    }

    fun close() = delegate.close()

    companion object {
        /**
         * @param tokenizerJson a HuggingFace `tokenizer.json`; DJL reads the full pipeline
         *   (normalizer, pre-tokenizer, model, post-processor) out of it
         */
        fun fromTokenizerJson(tokenizerJson: java.io.File): DjlTokenizerAdapter =
            DjlTokenizerAdapter(HuggingFaceTokenizer.newInstance(Path.of(tokenizerJson.absolutePath)))
    }
}
