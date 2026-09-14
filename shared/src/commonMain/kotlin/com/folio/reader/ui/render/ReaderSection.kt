package com.folio.reader.ui.render

/**
 * One chapter's renderable content inside a reader document.
 *
 * Continuous layout scrolls a *window* of chapters in a single page document so
 * chapters flow into each other with no swap at the boundaries; each entry
 * becomes a `<section data-folio-spine=… data-folio-chapter=…>` wrapper in that
 * document. Single-section content (reflowed documents) uses exactly the same
 * shape with `spineIndex = -1`, so one bridge serves both.
 */
data class ReaderSection(
    val spineIndex: Int,
    val chapterId: String,
    val href: String,
    val html: String,
)

/**
 * A one-shot mutation of the chapter window already on screen. The host emits
 * these after the engine asked for an extension (or decided to trim); the
 * surface consumes each exactly once — they are appended/prepended to the live
 * DOM rather than reloading the document, which is what keeps the scroll
 * position untouched while the window grows.
 */
sealed interface WindowOp {
    val nonce: Long

    data class Append(val section: ReaderSection, override val nonce: Long) : WindowOp
    data class Prepend(val section: ReaderSection, override val nonce: Long) : WindowOp
    data class Trim(val fromSpine: Int, val toSpine: Int, override val nonce: Long) : WindowOp
}

/** How many chapters may be on screen at once before the far end is trimmed. */
const val READER_WINDOW_MAX_SECTIONS: Int = 7

/** How many chapters load around the anchor when a window is (re)built. */
const val READER_WINDOW_PRELOAD: Int = 1
