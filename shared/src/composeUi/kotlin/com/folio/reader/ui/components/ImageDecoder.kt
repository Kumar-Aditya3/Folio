package com.folio.reader.ui.components

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.graphics.ImageBitmap

expect fun decodeCoverImage(bytes: ByteArray): ImageBitmap?

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
