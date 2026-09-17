package com.folio.reader.ui.library

import com.folio.reader.model.Book

/**
 * The value the shelf's `collectAsState` starts from, given what the screen already
 * knows about the library.
 *
 * `null` means "not measured yet" and drives the skeleton. The shelf flow is a cold
 * `combine`, so every composition starts it fresh and it pushes a `null` through
 * before its first real emission — one frame of loading state rendered in the middle
 * of the tab cross-fade, which is what reads as the library arriving blank and then
 * filling in.
 *
 * [knownBooks] is the screen's own unfiltered read (seeded `emptyList`, sharing the
 * repository with the shelf query). When it has data the shelf resolves in the same
 * frame batch, so starting from `null` would buy a flicker and nothing else. Starting
 * from the unfiltered list instead hands the first frame a *complete* shelf; the sort
 * and filter land a frame later, which is invisible where an empty page is not.
 *
 * When [knownBooks] is empty this returns `null` — that is a genuine cold read and the
 * skeleton is the honest answer.
 *
 * Extracted from the composable so the policy is testable without a UI host, and so
 * there is one place to read the reasoning.
 */
fun libraryShelfSeed(knownBooks: List<Book>): List<Book>? =
    knownBooks.takeIf { it.isNotEmpty() }

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
 * Null covers hosts that do not hoist the read (desktop, tests): the screen then
 * falls back to its own cold read and starts on the skeleton, exactly as it did
 * before the hoist existed.
 */
fun libraryShelfSeedFrom(knownBooks: kotlinx.coroutines.flow.StateFlow<List<Book>>?): List<Book>? =
    libraryShelfSeed(knownBooks?.value ?: emptyList())
