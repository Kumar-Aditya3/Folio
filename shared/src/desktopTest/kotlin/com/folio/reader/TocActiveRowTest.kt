package com.folio.reader

import com.folio.reader.model.BookTocRow
import com.folio.reader.model.activeTocIndex
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which Contents row lights while reading, asserted with the real fractions from Ship of Magic — a
 * heading-less book where several rows share one spine file, so the within-chapter fraction is the
 * only thing that tells them apart. Each stored fraction is that row's paragraph ordinal over the
 * file's `<p>` count, exactly as the parser emits it.
 */
class TocActiveRowTest {

    private val rows = listOf(
        BookTocRow("Chapter Twenty", 6, 779, 779f / 1086f),
        BookTocRow("Chapter Twenty-One", 7, 716, 716f / 1087f),
        BookTocRow("Chapter Twenty-Two", 7, 941, 941f / 1087f),
        BookTocRow("Chapter Twenty-Three", 8, 110, 110f / 1086f),
        BookTocRow("Chapter Twenty-Four", 8, 385, 385f / 1086f),
        BookTocRow("Chapter Twenty-Five", 8, 600, 600f / 1086f),
        BookTocRow("Chapter Twenty-Six", 8, 871, 871f / 1086f),
        BookTocRow("Chapter Twenty-Seven", 9, 73, 73f / 1086f),
    )

    private fun litAt(chapter: Int, fraction: Float): String {
        val i = rows.activeTocIndex(chapter, fraction)
        return rows.getOrNull(i)?.title ?: "none($i)"
    }

    @Test
    fun `a reported fraction a hair below its own row still lights that row`() {
        // Measured on device: the tapped row stores 304/1086 = 0.2799 and the page reported 0.2741.
        val chapter9 = listOf(
            BookTocRow("Chapter Twenty-Seven", 9, 73, 73f / 1086f),
            BookTocRow("Chapter Twenty-Eight", 9, 304, 304f / 1086f),
            BookTocRow("Chapter Twenty-Nine", 9, 569, 569f / 1086f),
        )
        assertEquals(1, chapter9.activeTocIndex(9, 0.2741f))
    }

    @Test
    fun `the last row of a chapter lights when the page is a hair short of it`() {
        assertEquals(6, rows.activeTocIndex(8, 0.797f), "lit ${litAt(8, 0.797f)}")
    }

    @Test
    fun `a row whose heading has not been reached cannot light`() {
        // 0.45 is inside Twenty-Four (0.3545..0.5525); the slack must not reach Twenty-Five.
        assertEquals(4, rows.activeTocIndex(8, 0.45f), "lit ${litAt(8, 0.45f)}")
    }

    @Test
    fun `a chapter with no reported fraction yet falls back to the previous chapter's last row`() {
        // Documents why ReaderScreen seeds the tapped row's fraction: goToChapter reseeds
        // chapterProgress to 0.0 for a jump, and this fallback is what lit "Twenty-Two" when the
        // reader had tapped "Twenty-Four".
        assertEquals(2, rows.activeTocIndex(8, 0.0f), "lit ${litAt(8, 0.0f)}")
    }

    @Test
    fun `an ordinary one-row-per-chapter contents is unchanged`() {
        val plain = (0..4).map { BookTocRow("C$it", it, 0, 0f) }
        assertEquals(3, plain.activeTocIndex(3, 0.5f))
        assertEquals(1, plain.activeTocIndex(1, 0f))
    }
}
