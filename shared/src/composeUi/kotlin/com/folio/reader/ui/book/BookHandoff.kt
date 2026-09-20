package com.folio.reader.ui.book

import com.folio.reader.model.Book

/**
 * A one-slot hand-off from a tapped cover to the book-detail screen.
 *
 * The §17 cover morph needs the *destination* copy of the cover/title to be
 * composed while the 450ms flight is running — a shared element with a leaving
 * copy but no arriving copy has nothing to fly to, so the thumbnail just recedes
 * with the outgoing page and the header pops in un-animated once the DB read
 * lands. But the detail route only receives a `bookId`, and reading the row back
 * is asynchronous, so the header cannot exist on the first frames.
 *
 * The list already holds the whole [Book] at the moment of the tap. Stashing it
 * here lets [BookDetailViewModel.loadBook] seed its state synchronously and paint
 * the real cover/title/author immediately, so the morph has a target for the
 * whole flight. The authoritative row still loads right after and replaces the
 * seed; if the seed is absent (deep link, process death) the screen simply falls
 * back to its loading state as before.
 *
 * Single-slot on purpose: only the most recently tapped book matters, and
 * [take] clears it so nothing is retained.
 */
object BookHandoff {
    @Volatile
    private var pending: Book? = null

    fun offer(book: Book) {
        pending = book
    }

    fun take(bookId: String): Book? {
        val held = pending
        return if (held != null && held.id == bookId) {
            pending = null
            held
        } else {
            null
        }
    }
}
