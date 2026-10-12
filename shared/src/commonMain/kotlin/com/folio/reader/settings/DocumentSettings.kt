package com.folio.reader.settings

import kotlinx.serialization.Serializable

/**
 * The reading settings a **document** (PDF, Word/DOCX and the other generated
 * reflowable formats) opens with — its own defaults, separate from the book
 * defaults.
 *
 * ## Why this exists
 *
 * Documents used to render through the *global book* row: the document reader
 * was handed `ReaderSettings` wholesale, so a Word file inherited whatever
 * typography, page theme, margins and layout the reader had last configured for
 * a novel. That is wrong twice over — a document is not a book, and the two
 * formats are read for different reasons — and it left the document reader with
 * no settings of its own at all: the only document-specific default was the page
 * mode, and there was no in-reader panel to change anything.
 *
 * [DocumentSettings] is the document half of the same idea [ReaderSettings] is
 * the book half of: a small, authored set of fields a document is shown with,
 * persisted in the global settings row and synced/backed up like everything else.
 *
 * ## Scope
 *
 * **Global, not per document.** Books take a copy of the defaults on first open
 * and own it from then on (`ReaderSettings.toBookSettings`); documents do not —
 * every document follows these defaults, exactly as they follow the global page
 * mode. That keeps a folder of imported Word files consistent, which is what a
 * reader wants from a format they did not choose the styling of.
 *
 * The field set mirrors the document-relevant half of [ReaderSettings]: the
 * typography, the page, and the page theme. The app-level comfort toggles
 * (eye protection, editorial styling, brightness) stay app-wide and live on the
 * book row; the PDF/reflowable page mode stays on
 * [ReaderSettings.documentReaderMode].
 *
 * **Reflowable documents only.** A PDF is a fixed page the renderer paints as
 * bitmaps: typeface, spacing, margins, text width, alignment, hyphenation and the
 * layout mode cannot touch it, so a PDF keeps the global reader row and the
 * in-reader document panel is not offered for it. Only
 * [ReaderSettings.documentReaderMode] — the page mode — applies to a PDF.
 */
@Serializable
data class DocumentSettings(
    val fontFamily: String = "Literata",
    val fontSize: Float = 18f,
    val fontWeight: Int = 400,
    val lineHeight: Float = 1.5f,
    val letterSpacing: Float = 0f,
    val wordSpacing: Float = 0f,
    val paragraphSpacing: Float = 1.0f,
    val margins: Margins = Margins(),
    val textWidth: TextWidth = TextWidth.MEDIUM,
    val alignment: TextAlignment = TextAlignment.JUSTIFIED,
    val hyphenation: Boolean = true,
    /** The page theme a document is painted in — the reader's own preset id. */
    val themeId: String = "paper",
    val customTheme: Theme? = null,
    /** How the reflowable surface lays the document out: scrolling or paged. */
    val layoutMode: LayoutMode = LayoutMode.CONTINUOUS,
    val showProgress: Boolean = true,
    val showClock: Boolean = false,
    /** Index into the active theme's highlight palette used for new highlights. */
    val highlightColorIndex: Int = 0
)

/**
 * The document's settings expressed as a [ReaderSettings], so the rendering
 * surfaces — which speak `ReaderSettings` — need no second dialect. Every field
 * the document does not own is taken from [base] (the caller passes the global
 * book row), which is how the app-level comfort toggles and the ambient knobs
 * keep working over a document exactly as they do over a book.
 */
fun DocumentSettings.toReaderSettings(base: ReaderSettings = ReaderSettings()): ReaderSettings = base.copy(
    fontFamily = fontFamily,
    fontSize = fontSize,
    fontWeight = fontWeight,
    lineHeight = lineHeight,
    letterSpacing = letterSpacing,
    wordSpacing = wordSpacing,
    paragraphSpacing = paragraphSpacing,
    margins = margins,
    textWidth = textWidth,
    alignment = alignment,
    hyphenation = hyphenation,
    themeId = themeId,
    customTheme = customTheme,
    layoutMode = layoutMode,
    showProgress = showProgress,
    showClock = showClock,
    highlightColorIndex = highlightColorIndex
)

/**
 * This document row with the document-owned fields replaced by [reader]'s. Used
 * when a settings surface speaks `ReaderSettings` (the reader's own panels) and
 * its result has to be folded back into the document row without dragging the
 * book row's unrelated fields along.
 */
fun DocumentSettings.withFrom(reader: ReaderSettings): DocumentSettings = copy(
    fontFamily = reader.fontFamily,
    fontSize = reader.fontSize,
    fontWeight = reader.fontWeight,
    lineHeight = reader.lineHeight,
    letterSpacing = reader.letterSpacing,
    wordSpacing = reader.wordSpacing,
    paragraphSpacing = reader.paragraphSpacing,
    margins = reader.margins,
    textWidth = reader.textWidth,
    alignment = reader.alignment,
    hyphenation = reader.hyphenation,
    themeId = reader.themeId,
    customTheme = reader.customTheme,
    layoutMode = reader.layoutMode,
    showProgress = reader.showProgress,
    showClock = reader.showClock,
    highlightColorIndex = reader.highlightColorIndex
)
