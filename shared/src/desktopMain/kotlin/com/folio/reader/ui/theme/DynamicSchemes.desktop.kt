package com.folio.reader.ui.theme

import androidx.compose.runtime.Composable

// §16: desktop keeps Rule 1 — no wallpaper derivation, so the System pack is
// simply not offered there.
@Composable
actual fun rememberDynamicSchemes(): Pair<androidx.compose.material3.ColorScheme, androidx.compose.material3.ColorScheme>? =
    null
