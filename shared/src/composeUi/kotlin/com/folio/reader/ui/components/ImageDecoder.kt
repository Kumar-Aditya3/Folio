package com.folio.reader.ui.components

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.graphics.ImageBitmap

/**
 * Decodes a cover/thumbnail image. [targetWidthPx] guides subsampling so a large
 * source (for example a CBZ first page) is not decoded at full resolution for a
 * small cell; 0 or negative keeps the platform's default cover cap. Covers render
 * in small cells, so [COVER_TARGET_WIDTH_PX] is the usual value passed by shelves
 * and grids. Full-bleed content images (inline EPUB art) pass 0 to keep quality.
 */
expect fun decodeCoverImage(bytes: ByteArray, targetWidthPx: Int = 0): ImageBitmap?

/** Typical decoded width for a cover/thumbnail cell; keeps peak bitmap memory low. */
const val COVER_TARGET_WIDTH_PX = 512

/**
 * Decodes a reading page sized for display rather than at source resolution.
 * [targetWidthPx] guides subsampling; 0 or negative decodes at full size.
 * Full-resolution webtoon strips can be tens of thousands of pixels tall and
 * dominate memory/GC when decoded raw, which is what made scrolling laggy.
 */
expect fun decodePageImage(bytes: ByteArray, targetWidthPx: Int): ImageBitmap?

/**
 * Loads an installed system font by family name (e.g. "Georgia", "Literata",
 * "Segoe UI"). Returns null when the platform doesn't have it — callers fall
 * back to the generic serif/sans/mono mapping.
 */
expect fun systemFontFamily(name: String): FontFamily?
