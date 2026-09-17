package com.folio.reader

import com.folio.reader.model.Book
import com.folio.reader.ui.library.libraryShelfSeed
import com.folio.reader.ui.library.libraryShelfSeedFrom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The shelf's starting value, which decides whether a Library visit renders a
 * skeleton for a frame.
 *
 * The bug these guard: the shelf flow is a cold `combine`, so each composition
 * pushes a `null` before its first emission. Seeded with that, `LibraryContent`
 * took its loading branch for one frame — inside the tab cross-fade, which is what
 * showed the library arriving blank and then filling in.
 */
class LibraryShelfSeedTest {

    private fun book(id: String) =
        Book(id = id, title = "Title $id", epubHash = "hash-$id", epubFileSize = 1024L)

    @Test
    fun a_known_shelf_is_handed_to_the_first_frame() {
        val known = listOf(book("a"), book("b"))
        assertEquals(known, libraryShelfSeed(known), "a warm entry must not start empty")
    }

    @Test
    fun a_library_that_has_not_been_read_starts_unmeasured() {
        assertNull(
            libraryShelfSeed(emptyList()),
            "an unread library has nothing to hand over, so the skeleton is correct",
        )
    }

    @Test
    fun the_seed_is_the_known_books_unchanged() {
        val known = listOf(book("a"), book("b"), book("c"))
        // Not a copy, not a filtered subset: the shelf's own sort and filter land a
        // frame later, and reimplementing either here would be a second source of
        // truth that could disagree with the real flow.
        assertEquals(3, libraryShelfSeed(known)?.size)
        assertEquals(known.map { it.id }, libraryShelfSeed(known)?.map { it.id })
    }

    @Test
    fun one_known_book_is_enough_to_skip_the_skeleton() {
        // The common case on a real device: the library is not empty, so the shelf
        // resolves immediately and there is no loading frame to show.
        assertEquals(1, libraryShelfSeed(listOf(book("only")))?.size)
    }

    // ── the hoisted read ─────────────────────────────────────────────────────
    //
    // The seed's input is the host's app-lifetime read (`libraryBooks`). These
    // pin the two ways that wiring can silently go back to showing a skeleton
    // frame inside the Home→Library morph.

    @Test
    fun the_seed_is_read_without_anyone_collecting_the_flow() {
        // No collector anywhere in this test. This is the property that matters:
        // the seed is taken from the flow's *current* value, so a host that has
        // been keeping it hot since launch hands the shelf over on the very first
        // composition. A seed that needed collecting first would be null here —
        // and null is the skeleton, one frame of it, mid-morph.
        val hoisted = MutableStateFlow(listOf(book("a"), book("b")))
        assertEquals(2, libraryShelfSeedFrom(hoisted)?.size)
        assertEquals(listOf("a", "b"), libraryShelfSeedFrom(hoisted)?.map { it.id })
    }

    @Test
    fun a_host_that_has_not_read_the_library_yet_still_starts_unmeasured() {
        // Hoisted but empty — a genuinely empty library, or a launch that has not
        // finished its first read. The skeleton is the honest answer; what must
        // *not* happen is an empty shelf rendered as a real one.
        assertNull(libraryShelfSeedFrom(MutableStateFlow(emptyList())))
    }

    @Test
    fun a_host_that_does_not_hoist_the_read_degrades_to_the_cold_path() {
        // Desktop and tests pass no hoisted flow. That must mean "seed nothing"
        // and leave the screen on its own cold read — never a crash, and never a
        // shelf invented out of a missing read.
        assertNull(libraryShelfSeedFrom(null))
    }

    @Test
    fun a_later_read_does_not_retroactively_change_the_seed() {
        // The seed is a one-shot argument, not state: it is sampled before the
        // first frame and `collectAsState` keeps its own value afterwards. If
        // this ever became live state it would fight the shelf flow for control
        // of what the grid shows.
        val hoisted = MutableStateFlow(emptyList<Book>())
        val seed = libraryShelfSeedFrom(hoisted)
        hoisted.value = listOf(book("late"))
        assertNull(seed, "the seed sampled before the read must not change under it")
        assertEquals(1, libraryShelfSeedFrom(hoisted)?.size, "the next visit samples the new value")
    }
}
