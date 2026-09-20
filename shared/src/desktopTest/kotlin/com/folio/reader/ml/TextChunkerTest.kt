package com.folio.reader.ml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TextChunkerTest {

    private fun words(count: Int, prefix: String = "w", start: Int = 0): String =
        (start until start + count).joinToString(" ") { "$prefix$it" }

    private fun chunk(
        text: String,
        maxWords: Int = TextChunker.DEFAULT_MAX_WORDS,
        overlapWords: Int = TextChunker.DEFAULT_OVERLAP_WORDS,
    ) = TextChunker.chunk(text, "book-1", "chapter-1", 7, maxWords, overlapWords)

    @Test
    fun `empty and blank text produce no chunks`() {
        assertTrue(chunk("").isEmpty())
        assertTrue(chunk("   \n\n \t ").isEmpty())
    }

    @Test
    fun `text shorter than the minimum chunk size is dropped`() {
        // Seven words is below MIN_CHUNK_WORDS; embedding it would produce noise.
        assertTrue(chunk(words(7)).isEmpty())
        assertEquals(1, chunk(words(8)).size)
    }

    @Test
    fun `offsets index the original text exactly`() {
        val text = words(600)
        for (c in chunk(text)) {
            assertEquals(
                text.substring(c.charStart, c.charEnd),
                c.text,
                "charStart/charEnd must slice the source text back out",
            )
        }
    }

    @Test
    fun `short chapter becomes exactly one chunk with tight offsets`() {
        val text = "  ${words(20)}  "
        val chunks = chunk(text)
        assertEquals(1, chunks.size)
        assertEquals(0, chunks[0].chunkIndex)
        // Offsets tighten around the content, so leading whitespace is excluded.
        assertEquals("w0", chunks[0].text.substringBefore(' '))
        assertEquals("w19", chunks[0].text.substringAfterLast(' '))
    }

    @Test
    fun `long chapter slides with the configured overlap`() {
        val text = words(600)
        val chunks = chunk(text)
        assertEquals(3, chunks.size)

        assertEquals("w0", chunks[0].text.substringBefore(' '))
        assertEquals("w249", chunks[0].text.substringAfterLast(' '))
        assertEquals("w200", chunks[1].text.substringBefore(' '))
        assertEquals("w449", chunks[1].text.substringAfterLast(' '))
        assertEquals("w400", chunks[2].text.substringBefore(' '))
        assertEquals("w599", chunks[2].text.substringAfterLast(' '))

        assertEquals(listOf(0, 1, 2), chunks.map { it.chunkIndex })
    }

    @Test
    fun `consecutive chunks share exactly the overlap window`() {
        val text = words(600)
        val chunks = chunk(text)
        val first = chunks[0].text.split(' ')
        val second = chunks[1].text.split(' ')
        // 250-word window striding by 200 leaves 50 shared words.
        val shared = first.toSet().intersect(second.toSet())
        assertEquals(50, shared.size)
    }

    @Test
    fun `window snaps back to a paragraph break when one is nearby`() {
        // Paragraph break after word 229, which is inside the last 15% of the 250-word window.
        val text = words(230) + "\n\n" + words(70, prefix = "z")
        val chunks = chunk(text)
        assertTrue(chunks.isNotEmpty())
        val first = chunks[0]
        assertEquals("w229", first.text.substringAfterLast(' '))
        assertTrue(!first.text.contains('\n'), "snapped chunk must not straddle the paragraph break")
    }

    @Test
    fun `distant paragraph breaks do not shorten the window`() {
        // Break after word 40 — far outside the snap zone, so the window still ends at word 249.
        val text = words(40) + "\n\n" + words(460, prefix = "z")
        val first = chunk(text).first()
        assertTrue(first.text.endsWith("z209"), "window should run the full 250 words")
        // The paragraph break is inside the window rather than at its edge.
        assertTrue(first.text.contains("\n\n"))
    }

    @Test
    fun `chunking is deterministic`() {
        val text = words(600)
        val a = chunk(text)
        val b = chunk(text)
        assertEquals(a.map { it.id }, b.map { it.id })
        assertEquals(a.map { it.contentHash }, b.map { it.contentHash })
        assertEquals(a.map { it.charStart }, b.map { it.charStart })
    }

    @Test
    fun `ids are content and location derived`() {
        val text = words(600)
        val a = chunk(text).first()
        // Same text, different book or chapter: ids must not collide.
        assertNotEquals(a.id, TextChunker.chunk(text, "book-2", "chapter-1", 7).first().id)
        assertNotEquals(a.id, TextChunker.chunk(text, "book-1", "chapter-2", 7).first().id)
        // Chunk id is a function of book, chapter, offset and content hash alone.
        assertEquals(
            a.id,
            TextChunker.chunkId(a.bookId, a.chapterId, a.charStart, a.contentHash),
        )
        // Editing the text changes the hash, and therefore the id.
        val edited = text.replaceFirst("w0 ", "w0x ")
        assertNotEquals(a.id, chunk(edited).first().id)
    }

    @Test
    fun `content hash covers the chunk text`() {
        for (c in chunk(words(600))) {
            assertEquals(TextChunker.sha256(c.text), c.contentHash)
        }
    }

    @Test
    fun `spine index and book id are carried onto every chunk`() {
        for (c in chunk(words(600))) {
            assertEquals("book-1", c.bookId)
            assertEquals("chapter-1", c.chapterId)
            assertEquals(7, c.spineIndex)
        }
    }

    @Test
    fun `paragraph-only text still chunks`() {
        val text = words(300).replace(" ", "\n\n")
        val chunks = chunk(text)
        assertTrue(chunks.isNotEmpty())
        for (c in chunks) assertEquals(text.substring(c.charStart, c.charEnd), c.text)
    }

    @Test
    fun `overlap must be smaller than the window`() {
        val error = runCatching { chunk(words(300), maxWords = 50, overlapWords = 50) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }
}
