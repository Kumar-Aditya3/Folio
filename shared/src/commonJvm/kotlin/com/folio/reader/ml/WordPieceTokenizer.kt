package com.folio.reader.ml

import java.io.File
import java.text.Normalizer

/**
 * BERT WordPiece tokenizer, implemented in Kotlin so the shipped embedder has no native
 * dependency.
 *
 * Why hand-rolled: `ai.djl.huggingface:tokenizers` wraps the reference Rust implementation
 * but ships natives only for linux/osx/win, and `ai.djl.android:tokenizer-native` is
 * stranded at 0.33.0 against a 0.38.0 API. Bundling an 18 MB jar that cannot load on the
 * target platform is worse than 200 lines of Kotlin that run identically on both.
 *
 * The risk of hand-rolling a tokenizer is silent drift from the reference, so
 * `TokenizerParityTest` (desktopTest) asserts id-for-id equality against
 * `ai.djl.huggingface:tokenizers` over real corpus text. If this file is changed, that
 * test is the gate.
 *
 * Mirrors the reference pipeline: `BertNormalizer` (clean_text, handle_chinese_chars,
 * lowercase + strip accents) -> `BertPreTokenizer` (whitespace split, punctuation
 * isolated) -> `WordPiece` (greedy longest-match, `##` continuation) -> `[CLS] .. [SEP]`.
 */
class WordPieceTokenizer(
    private val vocab: Map<String, Int>,
    private val clsToken: String = "[CLS]",
    private val sepToken: String = "[SEP]",
    private val unkToken: String = "[UNK]",
    private val padToken: String = "[PAD]",
    private val maxInputCharsPerWord: Int = 100,
) : Tokenizer {

    override val vocabSize: Int get() = vocab.size

    val clsId: Int = vocab[clsToken] ?: 101
    val sepId: Int = vocab[sepToken] ?: 102
    val unkId: Int = vocab[unkToken] ?: 100
    val padId: Int = vocab[padToken] ?: 0

    /** Longest token in the vocabulary; bounds the greedy match window. */
    private val maxTokenLength: Int = vocab.keys.maxOfOrNull { it.length } ?: 1

    /**
     * Full encode, including the `[CLS]`/`[SEP]` frame the sentence-transformer models expect.
     * Truncates to [maxLength] including both special tokens.
     */
    override fun encode(text: String, maxLength: Int): IntArray {
        val ids = ArrayList<Int>(64)
        ids.add(clsId)
        for (piece in preTokenize(normalize(text))) {
            if (ids.size >= maxLength - 1) break
            for (id in wordPiece(piece)) {
                if (ids.size >= maxLength - 1) break
                ids.add(id)
            }
        }
        ids.add(sepId)
        return ids.toIntArray()
    }

    /** [encode] plus the `attention_mask` (and `token_type_ids`, which are all zero). */
    override fun encodeBatch(texts: List<String>, maxLength: Int, padToLongest: Boolean): Tokenizer.EncodedBatch {
        val encoded = texts.map { encode(it, maxLength) }
        val longest = if (padToLongest) (encoded.maxOfOrNull { it.size } ?: 0) else 0
        // **Bucketed, not exact.** Padding to the batch's own longest sequence looks tidier and is
        // what this did, but it hands the ONNX session a *different* shape for almost every batch —
        // a sweep over thousands of chunks produces hundreds of distinct widths. ONNX Runtime keys
        // its CPU memory arena on allocation shape, so every new width is a new permanent
        // allocation: the arena grows for the whole run and is not returned before `close()`. That
        // is the accumulation behind the 1.6 GB native heap this feature kept being killed for.
        //
        // Rounding up to [PAD_BUCKET] collapses those hundreds of widths into at most
        // `maxLength / PAD_BUCKET` — eight, for a 512-token ceiling — so the arena allocates for
        // each bucket once and then reuses. The cost is at most one bucket of wasted compute per
        // sequence, and the chunker already produces near-maximal chunks (its window is derived
        // from the token ceiling), so most sequences round up by a handful of tokens.
        val width = if (padToLongest) bucketWidth(longest, maxLength) else 0
        val batch = encoded.size
        val inputIds = LongArray(batch * width)
        val attention = LongArray(batch * width)
        val tokenTypes = LongArray(batch * width)
        encoded.forEachIndexed { row, ids ->
            for (col in 0 until width) {
                val value = if (col < ids.size) ids[col] else padId
                inputIds[row * width + col] = value.toLong()
                attention[row * width + col] = if (col < ids.size) 1L else 0L
            }
        }
        return Tokenizer.EncodedBatch(inputIds, attention, tokenTypes, width, batch)
    }

    /** [longest] rounded up to the next [PAD_BUCKET], never past [maxLength]. See [encodeBatch]. */
    internal fun bucketWidth(longest: Int, maxLength: Int): Int {
        if (longest <= 0) return 0
        val bucketed = ((longest + PAD_BUCKET - 1) / PAD_BUCKET) * PAD_BUCKET
        return bucketed.coerceAtMost(maxLength)
    }

    // ---------- BertNormalizer ----------

    internal fun normalize(text: String): String {
        val cleaned = StringBuilder(text.length)
        for (ch in text) {
            if (ch.code == 0 || ch.code == 0xFFFD) continue
            if (isControl(ch) && ch != '\t' && ch != '\n' && ch != '\r') continue
            cleaned.append(if (isUnicodeWhitespace(ch)) ' ' else ch)
        }
        val cjkSpaced = StringBuilder(cleaned.length + 8)
        for (ch in cleaned) {
            if (isCjk(ch)) {
                cjkSpaced.append(' ').append(ch).append(' ')
            } else {
                cjkSpaced.append(ch)
            }
        }
        // strip_accents defaults to `lowercase` in BertNormalizer, and this model sets
        // lowercase=true, so both run: lowercase, decompose, drop non-spacing marks, recompose.
        val lowered = cjkSpaced.toString().lowercase()
        val decomposed = Normalizer.normalize(lowered, Normalizer.Form.NFD)
        val stripped = StringBuilder(decomposed.length)
        for (ch in decomposed) {
            val type = Character.getType(ch)
            if (type == Character.NON_SPACING_MARK.toInt() || type == Character.COMBINING_SPACING_MARK.toInt()) continue
            stripped.append(ch)
        }
        return Normalizer.normalize(stripped, Normalizer.Form.NFC)
    }

    // ---------- BertPreTokenizer ----------

    internal fun preTokenize(normalized: String): List<String> {
        val out = ArrayList<String>(32)
        val current = StringBuilder()
        for (ch in normalized) {
            when {
                ch == ' ' -> {
                    if (current.isNotEmpty()) { out.add(current.toString()); current.clear() }
                }
                isPunctuation(ch) -> {
                    if (current.isNotEmpty()) { out.add(current.toString()); current.clear() }
                    out.add(ch.toString())
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) out.add(current.toString())
        return out
    }

    // ---------- WordPiece ----------

    internal fun wordPiece(piece: String): List<Int> {
        if (piece.isEmpty()) return emptyList()
        if (piece.length > maxInputCharsPerWord) return listOf(unkId)

        val chars = piece.toCharArray()
        val out = ArrayList<Int>(4)
        var start = 0
        while (start < chars.size) {
            var end = chars.size
            var matched: Int? = null
            while (start < end) {
                val length = end - start
                // No token is longer than maxTokenLength, so there is nothing to match past it.
                if (length > maxTokenLength + 2) { end--; continue }
                val substring = if (start > 0) "##" + String(chars, start, length) else String(chars, start, length)
                val id = vocab[substring]
                if (id != null) { matched = id; break }
                end--
            }
            if (matched == null) return listOf(unkId)
            out.add(matched)
            start = end
        }
        return out
    }

    private fun isControl(ch: Char): Boolean = Character.isISOControl(ch)

    private fun isUnicodeWhitespace(ch: Char): Boolean =
        ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r' ||
            ch == '\u000B' || ch == '\u000C' || ch == '\u0085' || ch == '\u00A0' ||
            ch == '\u1680' || (ch in '\u2000'..'\u200A') || ch == '\u2028' ||
            ch == '\u2029' || ch == '\u202F' || ch == '\u205F' || ch == '\u3000'

    private fun isCjk(ch: Char): Boolean {
        val c = ch.code
        return (c in 0x4E00..0x9FFF) || (c in 0x3400..0x4DBF) ||
            (c in 0xF900..0xFAFF) || (c in 0x20000..0x2A6DF) ||
            (c in 0x2A700..0x2B73F) || (c in 0x2B740..0x2B81F) ||
            (c in 0x2B820..0x2CEAF) || (c in 0x2F800..0x2FA1F)
    }

    /**
     * ASCII punctuation plus Unicode punctuation **and symbols**.
     *
     * The reference implementation's `is_bert_punc` is Unicode-aware, and the symbol
     * categories matter: U+2026 (`…`) and U+2019 (`’`) both appear constantly in this
     * corpus, and treating either as a word character silently changes tokenization.
     * The exact category set was derived by sweeping the reference tokenizer over
     * U+0021-U+2FFF and observing which code points it emits as standalone tokens;
     * `TokenizerParityTest` keeps it honest against real prose.
     */
    private fun isPunctuation(ch: Char): Boolean {
        if (ch in '!'..'/' || ch in ':'..'@' || ch in '['..'`' || ch in '{'..'~') return true
        return when (Character.getType(ch)) {
            Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(),
            Character.END_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
            Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt(),
            Character.MATH_SYMBOL.toInt(),
            Character.CURRENCY_SYMBOL.toInt(),
            Character.MODIFIER_SYMBOL.toInt(),
            Character.OTHER_SYMBOL.toInt(),
            -> true
            else -> false
        }
    }

    companion object {
        /**
         * Sequence widths are rounded up to a multiple of this before padding.
         *
         * 64 is a compromise between two costs. Larger means fewer distinct shapes for the ONNX
         * arena to hold, but more wasted compute on every sequence; smaller means tighter packing
         * but more shapes. At 64 a 512-token model has eight buckets and a 256-token model four, so
         * the arena's shape set is bounded and tiny, while the padding waste stays inside one
         * bucket. See [encodeBatch] for why the shape count is what matters.
         */
        const val PAD_BUCKET = 64

        /** Reads a HuggingFace `vocab.txt`: one token per line, line number is the id. */
        fun fromVocabFile(file: File): WordPieceTokenizer {
            val vocab = HashMap<String, Int>(40000)
            var index = 0
            file.forEachLine { line ->
                // Tokens never contain a newline, but the final line may be empty.
                if (line.isNotEmpty() || index == 0) vocab[line] = index
                index++
            }
            return WordPieceTokenizer(vocab)
        }

        fun fromVocabLines(lines: List<String>): WordPieceTokenizer {
            val vocab = HashMap<String, Int>(lines.size * 2)
            lines.forEachIndexed { i, token -> vocab[token] = i }
            return WordPieceTokenizer(vocab)
        }
    }
}
