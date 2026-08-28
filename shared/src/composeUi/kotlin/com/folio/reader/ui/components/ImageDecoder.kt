package com.folio.reader.ui.components

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.graphics.ImageBitmap

expect fun decodeCoverImage(bytes: ByteArray): ImageBitmap?

/**
 * Loads an installed system font by family name (e.g. "Georgia", "Literata",
 * "Segoe UI"). Returns null when the platform doesn't have it — callers fall
 * back to the generic serif/sans/mono mapping.
 */
expect fun systemFontFamily(name: String): FontFamily?
