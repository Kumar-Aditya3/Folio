package com.folio.reader.ui.reader

/**
 * The base reader font names the current platform's renderer can actually
 * resolve, before the user's imported faces are appended. Android's WebView
 * only ships the three generic families — every bundled/system-desktop name
 * silently fell back to the same serif, which is why half the picker read as
 * placeholders that "did nothing" when tapped.
 */
expect fun platformBaseReaderFonts(): List<String>
