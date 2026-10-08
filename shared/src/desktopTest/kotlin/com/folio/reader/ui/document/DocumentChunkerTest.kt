package com.folio.reader.ui.document

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DocumentChunkerTest {
    @Test
    fun emptyBodyFallsBackToOneWholeDocumentChunk() {
        val html = "<html><head><title>T</title></head><body>Document</body></html>"
        val chunks = DocumentChunker.chunk(html)
        assertEquals(1, chunks.size)
        assertEquals(0, chunks[0].spineIndex)
        assertEquals("document-chunk-0", chunks[0].chapterId)
        assertEquals("generated/index.html", chunks[0].href)
        // Fallback carries the whole document verbatim (today's behaviour).
        assertEquals(html, chunks[0].html)
    }

    @Test
    fun blankInputFallsBackToOneChunk() {
        val chunks = DocumentChunker.chunk("")
        assertEquals(1, chunks.size)
        assertEquals("document-chunk-0", chunks[0].chapterId)
    }

    @Test
    fun manyParagraphsSplitIntoContiguousMonotonicChunks() {
        val body = (0 until 40).joinToString("") { "<p>token$it ${"word ".repeat(40)}</p>" }
        val chunks = DocumentChunker.chunk("<html><head></head><body>$body</body></html>")

        // The document overruns the text/element budgets, so it must window.
        assertTrue(chunks.size >= 2, "expected the document to split into multiple chunks")
        // spineIndex is unique, contiguous and monotonic (0..N-1).
        assertEquals(chunks.indices.toList(), chunks.map { it.spineIndex })
        // chapterId is unique and matches the spine.
        assertEquals(chunks.indices.map { "document-chunk-$it" }, chunks.map { it.chapterId })
        // href is stable so relative resources still resolve.
        assertTrue(chunks.all { it.href == "generated/index.html" })
        // Every chunk is a standalone document.
        assertTrue(chunks.all { it.html.startsWith("<!DOCTYPE html><html><head>") })
        // No content is dropped: every paragraph token survives somewhere.
        val combined = chunks.joinToString("") { it.html }
        assertTrue((0 until 40).all { "token$it" in combined })
    }

    @Test
    fun consecutiveMediaElementsStartFreshChunks() {
        val body = "<p>lead</p><img src=\"assets/image-1.png\"><img src=\"assets/image-2.png\">"
        val chunks = DocumentChunker.chunk("<html><head></head><body>$body</body></html>")
        // <p>+first image group, then the second image opens a new chunk.
        assertEquals(2, chunks.size)
        assertTrue("image-1.png" in chunks[0].html)
        assertTrue("image-2.png" in chunks[1].html)
    }
}
