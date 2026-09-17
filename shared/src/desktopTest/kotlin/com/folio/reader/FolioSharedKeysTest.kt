package com.folio.reader

import com.folio.reader.ui.components.FolioSharedKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * §13.6 shared-element keys.
 *
 * The registry pairs a source and a destination by key equality, so these
 * functions are the whole contract between the two sides of a morph. Two classes
 * of bug live here and neither is visible in a screenshot:
 *
 *  - **collision** — two different elements resolving to one key. The registry has
 *    no answer for two live copies of a key in one scope; it leaves one stranded
 *    on whichever screen is fading out.
 *  - **instability** — a key that depends on anything but the item's id (a title,
 *    a list index, a `hashCode()`). The destination computes it from a different
 *    screen's data, so the two sides disagree and no morph happens at all.
 *
 * Both are cheap to rule out here and very expensive to notice on a device.
 */
class FolioSharedKeysTest {

    private val a = "book-1"
    private val b = "book-2"

    @Test
    fun coverKeysAreUniquePerItemAndPerLibrary() {
        val all = listOf(
            FolioSharedKeys.bookCover(a),
            FolioSharedKeys.bookCover(b),
            FolioSharedKeys.mangaCover(a),
            FolioSharedKeys.mangaCover(b),
            FolioSharedKeys.documentCover(a),
            FolioSharedKeys.documentCover(b),
        )
        assertEquals(
            all.size,
            all.toSet().size,
            "every (library, id) pair must resolve to its own key, got $all",
        )
        // The critical case: a book and a manga that happen to share an id must
        // not collide, or opening one would fly the other's cover.
        assertNotEquals(FolioSharedKeys.bookCover(a), FolioSharedKeys.mangaCover(a))
        assertNotEquals(FolioSharedKeys.bookCover(a), FolioSharedKeys.documentCover(a))
    }

    @Test
    fun textKeysAreDistinctFromTheirCoversAndFromEachOther() {
        // A title paired against a cover would morph the wrong element: the cover
        // box would be driven by the title's bounds. BookDetailHeader attaches both
        // from the same book id, one call apart.
        assertNotEquals(FolioSharedKeys.bookCover(a), FolioSharedKeys.bookTitle(a))
        assertNotEquals(FolioSharedKeys.bookCover(a), FolioSharedKeys.bookAuthor(a))
        assertNotEquals(FolioSharedKeys.bookTitle(a), FolioSharedKeys.bookAuthor(a))
        assertNotEquals(FolioSharedKeys.mangaCover(a), FolioSharedKeys.mangaTitle(a))
        assertNotEquals(FolioSharedKeys.documentCover(a), FolioSharedKeys.documentTitle(a))

        // And a book's title must not pair with a manga's, even at equal ids.
        assertNotEquals(FolioSharedKeys.bookTitle(a), FolioSharedKeys.mangaTitle(a))
        assertNotEquals(FolioSharedKeys.bookTitle(a), FolioSharedKeys.documentTitle(a))
    }

    @Test
    fun keysAreStableAndDependOnlyOnTheItemId() {
        // Same input, same output — the source and destination compute these
        // independently on opposite sides of a navigation.
        for (id in listOf(a, b, "", "id with spaces", "id:with:colons")) {
            assertEquals(FolioSharedKeys.bookCover(id), FolioSharedKeys.bookCover(id))
            assertEquals(FolioSharedKeys.bookTitle(id), FolioSharedKeys.bookTitle(id))
            assertEquals(FolioSharedKeys.mangaCover(id), FolioSharedKeys.mangaCover(id))
        }
        // Deterministic across the two sides of a morph even when titles collide:
        // a book whose title is another book's *id* still keys on its own id.
        assertNotEquals(FolioSharedKeys.bookTitle(a), FolioSharedKeys.bookTitle(b))
        val titleAsId = FolioSharedKeys.bookTitle(b)
        assertTrue(
            titleAsId.endsWith(b),
            "the key must be derived from the id, not from any display string",
        )
    }

    @Test
    fun keysAreNamespacedSoAPrefixIsNotMistakenForAnotherItemsKey() {
        // "book-1" and "book-12" are different books; a naive concatenation would
        // still separate them because of the separator, and this pins that.
        assertNotEquals(FolioSharedKeys.bookCover("book-1"), FolioSharedKeys.bookCover("book-12"))
        assertNotEquals(FolioSharedKeys.bookTitle("1"), FolioSharedKeys.bookTitle("12"))
    }

    // ── In-content morph keys ───────────────────────────────────────────────
    //
    // These pair two states of one screen rather than two destinations. The
    // collision risk is higher here, not lower: the page and the annotations
    // panel are composed *at the same time* while a morph runs, so a highlight
    // key that collides with a quote key would put two live copies of one key in
    // one scope — the case the registry strands.

    @Test
    fun contentMorphKeysAreUniquePerItem() {
        val all = listOf(
            FolioSharedKeys.highlightToNote(a),
            FolioSharedKeys.highlightToNote(b),
        )
        assertEquals(
            all.size,
            all.toSet().size,
            "every (kind, id) pair must resolve to its own key, got $all",
        )
    }

    @Test
    fun contentMorphKeysDoNotCollideWithCoverKeys() {
        // A highlight id and a book id are both plain UUID strings in this app, so
        // nothing but the namespace stops `highlightToNote(bookId)` from pairing
        // with `bookCover(bookId)` — which would fly a cover across the reader.
        assertNotEquals(FolioSharedKeys.highlightToNote(a), FolioSharedKeys.bookCover(a))
        assertNotEquals(FolioSharedKeys.highlightToNote(a), FolioSharedKeys.mangaCover(a))
        assertNotEquals(FolioSharedKeys.highlightToNote(a), FolioSharedKeys.documentCover(a))
        assertNotEquals(FolioSharedKeys.highlightToNote(a), FolioSharedKeys.bookTitle(a))
        assertNotEquals(FolioSharedKeys.highlightToNote(a), FolioSharedKeys.bookAuthor(a))
        assertNotEquals(FolioSharedKeys.highlightToNote(a), FolioSharedKeys.mangaTitle(a))
    }

    @Test
    fun contentMorphKeysAreStable() {
        for (id in listOf(a, b, "", "id with spaces", "id:with:colons")) {
            assertEquals(FolioSharedKeys.highlightToNote(id), FolioSharedKeys.highlightToNote(id))
        }
        // Prefix safety: "h1" and "h12" are different highlights.
        assertNotEquals(FolioSharedKeys.highlightToNote("h1"), FolioSharedKeys.highlightToNote("h12"))
    }
}
