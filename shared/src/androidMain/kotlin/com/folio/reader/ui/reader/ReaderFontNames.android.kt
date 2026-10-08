package com.folio.reader.ui.reader

/**
 * Android WebView font reality: the three generic families, plus whatever the
 * user imported (@font-face is declared per import in the reader surface).
 * Bundled/system-desktop names are absent — no @font-face declares them here.
 */
actual fun platformBaseReaderFonts(): List<String> =
    listOf("Serif", "Sans Serif", "Monospace")
