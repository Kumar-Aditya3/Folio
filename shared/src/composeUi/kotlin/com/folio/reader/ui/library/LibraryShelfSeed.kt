package com.folio.reader.ui.library

import com.folio.reader.model.Book

/**
 * The value the shelf's `collectAsState` starts from, given what the screen already
 * knows about the library and about the shelf it is about to show.
 *
 * `null` means "not measured yet" and drives the skeleton. The shelf flow is a cold
 * `combine`, so every composition starts it fresh and it pushes a `null` through
 * before its first real emission — one frame of loading state rendered in the middle
 * of the tab cross-fade, which is what reads as the library arriving blank and then
 * filling in.
 *
 * [settledShelfIds] is the membership that has actually landed for the current
 * selection (`LibraryViewModel.settledShelfIds()`). It is what makes the seed *the
 * shelf* rather than *the library*: the first version of this helper handed over the
 * unfiltered [knownBooks] and trusted the host's gate to hide it, so an open gate
 * painted every collection's books for a frame. A value that has to be hidden is not
 * a fix, so the unfiltered list is no longer an available answer here either.
 *
 * `null` shelf ids means the selection has not settled: the skeleton is honest, and it
 * is what the gate would hold anyway. An empty [knownBooks] against a non-empty shelf
 * means the library read itself has not landed — also the skeleton, which is how hosts
 * that do not hoist the read (desktop, tests) degrade to their own cold read exactly as
 * before.
 */
fun libraryShelfSeed(knownBooks: List<Book>, settledShelfIds: Set<String>?): List<Book>? = when {
    settledShelfIds == null -> null
    knownBooks.isEmpty() && settledShelfIds.isNotEmpty() -> null
    else -> knownBooks.filter { it.id in settledShelfIds }
}

/**
 * The same decision, taken from the host's hoisted read when there is one.
 *
 * [knownBooks] is `FolioNavModelImpl.libraryBooks` — collected for the app's whole
 * lifetime, so by the time the Library is reached it already holds the shelf.
 * Read as a `StateFlow` and not a bare `Flow` on purpose: the seed is the argument
 * to `collectAsState`, which reads it once at subscription time, and it has to be
 * right on the destination's *first* composition. `collectAsState` cannot deliver
 * a flow's value before the frame after that, so seeding from a collected value
 * would put one frame of skeleton inside the morph — the very thing
 * [libraryShelfSeed] exists to prevent.
 *
 * Null [knownBooks] covers hosts that do not hoist the read: the screen then falls
 * back to its own cold read and starts on the skeleton.
 */
fun libraryShelfSeedFrom(
    knownBooks: kotlinx.coroutines.flow.StateFlow<List<Book>>?,
    settledShelfIds: Set<String>?,
): List<Book>? = libraryShelfSeed(knownBooks?.value ?: emptyList(), settledShelfIds)
