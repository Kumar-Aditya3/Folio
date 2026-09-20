package com.folio.reader.importer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The entity half of B1: `&#8217;` reaching the reader as nine literal characters.
 *
 * The strings below are lifted verbatim from the device screenshot that reported this —
 * Malazan results showing `Part of growing up.&#8217;Henar eyed her` and
 * `Murillio require. Rallick&#8217;s plan centered on this` — so the fixture is the bug
 * rather than a paraphrase of it.
 *
 * Both halves matter and are tested separately: the *display* is what the reader saw, and
 * the *token* is why search was broken too. A fix that only cleaned the display would leave
 * `it's` unable to match text that was stored as `It&#8217;s`.
 */
class SearchIndexerEntityTest {

    @Test
    fun `numeric apostrophe reference is decoded`() {
        val text = SearchIndexer.extractPlainText("<p>Part of growing up.&#8217;Henar eyed her, an</p>")
        assertEquals("Part of growing up.’Henar eyed her, an", text)
        assertFalse(text.contains("&#8217;"), "the reference itself must not survive")
    }

    @Test
    fun `both curly quote references decode, including a leading one`() {
        val text = SearchIndexer.extractPlainText("<p>&#8216;Let&#8217;s see if I fully understand you</p>")
        assertEquals("‘Let’s see if I fully understand you", text)
    }

    @Test
    fun `a possessive reads as an apostrophe the reader could have typed`() {
        val text = SearchIndexer.extractPlainText("<p>Murillio require. Rallick&#8217;s plan centered on this</p>")
        // The load-bearing half: the *index* must now hold `Rallick’s` so that a query whose
        // words overlap it can match. Asserting the absence of the reference is not enough —
        // a fix that replaced it with a space would also pass that.
        assertTrue(text.contains("Rallick’s"), "expected the decoded possessive, got: $text")
    }

    @Test
    fun `named references decode too`() {
        val text = SearchIndexer.extractPlainText("<p>Tom &amp; Jerry &mdash; and &nbsp; a gap</p>")
        assertEquals("Tom & Jerry — and a gap", text)
    }

    @Test
    fun `decoded non-breaking space becomes a real space, not a lookalike`() {
        // Caught by this test rather than reasoned about: `&nbsp;` decodes to U+00A0, and
        // Kotlin's `\s` does *not* match it, so the whitespace collapse left the NBSP in place.
        // That is not cosmetic. `TextChunker` counts words with `split(" ")` — ASCII space —
        // and the SQL predicate mirroring it uses `replace(content, ' ', '')`. A chapter whose
        // title page is built from `&nbsp;` would be measured differently by the two, and a
        // disagreement between "how many words" and "how many chunks can exist" is what makes a
        // backfill unable to finish.
        val text = SearchIndexer.extractPlainText("<p>one&nbsp;two</p>")

        assertEquals("one two", text)
        assertFalse(text.contains("\u00A0"), "no non-breaking space may survive into the index")
        assertEquals(
            2,
            text.split(" ").size,
            "the chunker must count two words here, so the SQL predicate can agree",
        )
    }

    @Test
    fun `other unicode space separators normalise the same way`() {
        // The rest of the HTML5 space-like entities that `\s` misses.
        val text = SearchIndexer.extractPlainText("<p>a&thinsp;b&ensp;c&emsp;d</p>")
        assertEquals("a b c d", text)
    }

    @Test
    fun `entities decode after tags are stripped so literal markup survives`() {
        // The ordering rule. Decoding first would turn `&lt;div&gt;` into a real tag for the
        // stripper to eat, deleting the very text a coding book was teaching the reader to
        // search for. In this order it survives as the characters the reader typed.
        val text = SearchIndexer.extractPlainText("<p>Write &lt;div&gt; to open a division</p>")
        assertEquals("Write <div> to open a division", text)
    }

    @Test
    fun `tags are still stripped and whitespace still collapses`() {
        val text = SearchIndexer.extractPlainText(
            "<div><h1>Title</h1>\n\n   <p>Body   text</p><script>var x = 1;</script></div>"
        )
        assertEquals("Title Body text", text)
    }

    @Test
    fun `repair over already-decoded text is idempotent`() {
        // This is what makes the one-time rewrite in `repairIndexEntitiesOnce` safe to retry
        // after a mid-way failure: the pass reads text it previously wrote and must not change
        // it. A lone `&` is the case that would break a naive second decode — `&amp;` is gone,
        // and re-running must not invent a reference out of the bare ampersand.
        val once = SearchIndexer.extractPlainText("<p>Tom &amp; Jerry &#8217;quoted&#8217;</p>")
        val twice = SearchIndexer.extractPlainText(once)
        assertEquals(once, twice)
        assertEquals("Tom & Jerry ’quoted’", twice)
    }
}
