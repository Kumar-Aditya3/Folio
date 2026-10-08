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

/**
 * Ceiling on the chapter HTML a *rebuilt* window may hold, measured on loaded HTML. It bounds the
 * cold build a Contents jump pays for, where the WebView must parse and lay out the whole document
 * before the jump can land. [READER_WINDOW_PRELOAD] either side is a *count*, and a count is blind to
 * size: measured across the repro books, a mid-book window of 7 chapters came to 931 KB / 3,901
 * paragraphs for a book of 200 KB tales and 1.28 MB / 5,431 paragraphs for a pdf-split book whose 12
 * files each hold several chapters, against 148-246 KB / 500-660 paragraphs for the books that
 * behaved. Books whose chapters are a few KB never reach this ceiling, so their windows stay exactly
 * as they were. Growth while scrolling is deliberately not capped here: it arrives as appends into
 * the live document, and [READER_WINDOW_MAX_SECTIONS] alone governs it.
 */
const val READER_WINDOW_MAX_CHARS: Int = 400_000
