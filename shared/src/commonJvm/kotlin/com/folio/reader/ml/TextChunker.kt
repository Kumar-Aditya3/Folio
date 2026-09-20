package com.folio.reader.ml

import java.security.MessageDigest

/**
 * Splits chapter plain text into overlapping windows suitable for embedding.
 *
 * Design notes:
 *
 * - **Windows, not sentences.** A fixed word window with a fraction of it overlapping is
 *   what makes a passage retrievable regardless of where the author put the paragraph
 *   break. Sentence segmentation is deliberately avoided: EPUB plain text has no
 *   reliable sentence punctuation across the corpus, and a wrong split costs recall
 *   silently.
 * - **The window is a function of the model, not a constant.** Callers pass
 *   [EmbeddingModel.maxChunkWords], which is derived from that model's token ceiling. The old
 *   class-level default of 250 words was larger than MiniLM's 256-token limit (~176 words), so
 *   chunks were truncated by the tokenizer without any error. See [EmbeddingModel.maxChunkWords].
 * - **The window snaps back to a paragraph break when one is close.** A chunk that ends
 *   mid-sentence embeds slightly worse, so if a blank line falls inside the last
 *   [PARAGRAPH_SNAP_FRACTION] of the window we cut there instead.
 * - **Offsets are exact.** [Chunk.charStart]/[Chunk.charEnd] index the *input* string,
 *   so a hit deep-links back into the reader at the right character.
 * - **Ids are content-derived**, so re-chunking identical text yields identical ids and
 *   a re-import cannot orphan vectors.
 *
 * Fully deterministic and free of model or platform dependencies, which is what lets it
 * be tested without ONNX.
 */
object TextChunker {

    /**
     * Retained for the chunker's own tests, which chunk without a model in hand.
     *
     * **Production must not use this** — the window it names is a MiniLM-era number and is
     * larger than MiniLM's own token ceiling. Read [EmbeddingModel.maxChunkWords] instead.
     */
    const val DEFAULT_MAX_WORDS = 250
    const val DEFAULT_OVERLAP_WORDS = 50

    /** Cut at a paragraph break if one appears in the last this-fraction of a window. */
    private const val PARAGRAPH_SNAP_FRACTION = 0.15

    /**
     * Shorter windows are dropped: a 3-word tail embeds to noise and pollutes results.
     *
     * Not private because the backfill has to agree with it. A chapter under this many words
     * yields no chunks at all, so `chaptersMissingVectors` must not keep offering it as work
     * to do — otherwise the backfill can never finish, and the progress readout can never
     * reach its total. The query mirrors this number; see `JdbcChunkRepository`.
     *
     * **This is a floor on chapter size, and it is not the chunk window.** A chapter of 10
     * words yields exactly one 10-word chunk here; the number that decides how many chunks a
     * long chapter produces is [EmbeddingModel.maxChunkWords]. Conflating the two is how a
     * "min length" filter gets mistaken for the chunk size — the floor only ever *removes*
     * work, it can never reduce the number of chunks a chapter yields.
     */
    const val MIN_CHUNK_WORDS = 8

    private class Word(val start: Int, val end: Int)

    /**
     * @param text chapter plain text
     * @param bookId owning book
     * @param chapterId owning chapter
     * @param spineIndex chapter position, carried through so a hit can open the reader
     * @return chunks in reading order; empty when [text] has no usable content
     */
    fun chunk(
        text: String,
        bookId: String,
        chapterId: String,
        spineIndex: Int,
        maxWords: Int = DEFAULT_MAX_WORDS,
        overlapWords: Int = DEFAULT_OVERLAP_WORDS,
    ): List<Chunk> {
        require(maxWords > 0) { "maxWords must be positive" }
        require(overlapWords >= 0 && overlapWords < maxWords) {
            "overlapWords must be in 0..<maxWords (got $overlapWords of $maxWords)"
        }
        if (text.isBlank()) return emptyList()

        val words = scanWords(text)
        if (words.isEmpty()) return emptyList()

        // Long enough to be one chunk: keep the offsets tight around the real content.
        if (words.size <= maxWords) {
            return listOfNotNull(
                buildChunk(text, bookId, chapterId, spineIndex, 0, words[0].start, words.last().end, words.size)
            )
        }

        val stride = maxWords - overlapWords
        val out = ArrayList<Chunk>((words.size / stride) + 2)
        var startWord = 0
        var index = 0
        while (startWord < words.size) {
            var endWord = minOf(startWord + maxWords, words.size)
            if (endWord < words.size) endWord = snapToParagraph(text, words, startWord, endWord, maxWords)

            val wordCount = endWord - startWord
            // A short tail that only exists because of the stride is overlap noise, not
            // content; buildChunk rejects anything under MIN_CHUNK_WORDS.
            buildChunk(
                text, bookId, chapterId, spineIndex, index,
                words[startWord].start, words[endWord - 1].end, wordCount,
            )?.let { out.add(it); index++ }
            if (endWord >= words.size) break
            startWord += stride
            // Never let a snapped window fail to advance.
            if (startWord >= endWord) startWord = endWord
        }
        return out
    }

    private fun buildChunk(
        text: String,
        bookId: String,
        chapterId: String,
        spineIndex: Int,
        chunkIndex: Int,
        charStart: Int,
        charEnd: Int,
        wordCount: Int,
    ): Chunk? {
        if (wordCount < MIN_CHUNK_WORDS) return null
        val slice = text.substring(charStart, charEnd)
        val hash = sha256(slice)
        return Chunk(
            id = chunkId(bookId, chapterId, charStart, hash),
            bookId = bookId,
            chapterId = chapterId,
            spineIndex = spineIndex,
            chunkIndex = chunkIndex,
            charStart = charStart,
            charEnd = charEnd,
            text = slice,
            contentHash = hash,
        )
    }

    /**
     * Pulls the window end back to the nearest paragraph break, when there is one in the
     * last [PARAGRAPH_SNAP_FRACTION] of the window. Only ever shortens the window, and
     * never below [MIN_CHUNK_WORDS].
     */
    private fun snapToParagraph(
        text: String,
        words: List<Word>,
        startWord: Int,
        endWord: Int,
        maxWords: Int,
    ): Int {
        val floor = endWord - maxOf(1, (maxWords * PARAGRAPH_SNAP_FRACTION).toInt())
        for (i in endWord - 1 downTo maxOf(floor, startWord + MIN_CHUNK_WORDS)) {
            val gapStart = words[i].end
            val gapEnd = words[i + 1].start
            if (containsParagraphBreak(text, gapStart, gapEnd)) return i + 1
        }
        return endWord
    }

    /** A blank line between two words means the author ended a paragraph. */
    private fun containsParagraphBreak(text: String, from: Int, to: Int): Boolean {
        var newlines = 0
        for (i in from until to) {
            if (text[i] == '\n') newlines++
            if (newlines >= 2) return true
        }
        return false
    }

    /** Whitespace-delimited word spans. Punctuation stays attached — the tokenizer handles it. */
    private fun scanWords(text: String): List<Word> {
        val out = ArrayList<Word>()
        var i = 0
        while (i < text.length) {
            while (i < text.length && text[i].isWhitespace()) i++
            if (i >= text.length) break
            val start = i
            while (i < text.length && !text[i].isWhitespace()) i++
            out.add(Word(start, i))
        }
        return out
    }

    /**
     * Stable, content-derived id. Two runs over identical text produce identical ids, and
     * the offset keeps chunks distinct even when two windows happen to share their text.
     */
    fun chunkId(bookId: String, chapterId: String, charStart: Int, contentHash: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(bookId.toByteArray(Charsets.UTF_8))
        digest.update(0)
        digest.update(chapterId.toByteArray(Charsets.UTF_8))
        digest.update(0)
        digest.update(charStart.toString().toByteArray(Charsets.UTF_8))
        digest.update(0)
        digest.update(contentHash.toByteArray(Charsets.UTF_8))
        val bytes = digest.digest()
        val sb = StringBuilder(32)
        for (i in 0 until 16) sb.append("%02x".format(bytes[i]))
        return sb.toString()
    }

    fun sha256(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return buildString(64) { for (b in bytes) append("%02x".format(b)) }
    }
}
