package com.folio.reader.ui.reader

/**
 * Desktop (JCEF on Windows/Linux/macOS) resolves system families and the
 * installed platform faces, so the reader offers the full editorial set.
 */
actual fun platformBaseReaderFonts(): List<String> =
    listOf(
        "Literata", "Merriweather", "Georgia", "EB Garamond", "Lora",
        "Open Sans", "Inter", "Noto Serif", "Serif", "Sans Serif", "Monospace"
    )
