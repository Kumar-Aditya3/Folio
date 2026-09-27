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
    /**
     * Fixed-layout (pre-paginated) marker. When true the section is a rigid page
     * (a Calibre/Kobo/EPUB3 `rendition:layout` pre-paginated page) that must be
     * scaled to fit rather than reflowed. The assembler emits this as
     * `data-folio-fxl` on the `<section>` so the rendering JS can size the page.
     * Defaults keep every existing constructor and reflowable path unchanged.
     */
    val isFixedLayout: Boolean = false,
    /** Intrinsic page width in CSS pixels from the chapter viewport meta; 0 when unknown. */
    val fxlWidth: Int = 0,
    /** Intrinsic page height in CSS pixels from the chapter viewport meta; 0 when unknown. */
    val fxlHeight: Int = 0,
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
const val READER_WINDOW_MAX_SECTIONS: Int = 11

/** How many chapters load around the anchor when a window is (re)built. */
const val READER_WINDOW_PRELOAD: Int = 3
