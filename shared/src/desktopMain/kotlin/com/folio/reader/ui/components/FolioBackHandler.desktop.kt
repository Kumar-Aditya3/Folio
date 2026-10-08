package com.folio.reader.ui.components

import androidx.compose.runtime.Composable

/** No system back on desktop; Escape handling stays where it already lives. */
@Composable
actual fun FolioBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // Intentionally inert.
}
