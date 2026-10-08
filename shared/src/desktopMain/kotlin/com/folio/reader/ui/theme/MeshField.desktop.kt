package com.folio.reader.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * Desktop/Skiko side of the per-theme field. Mirage is AGSL, which is Android-only, so the field
 * draws nothing here and [folioThemeFieldSupported] is false — [FolioThemeBackground]'s fallback
 * ramp-plus-bloom field is then the complete ground plane, byte-for-byte as before (Rule 19).
 */
@Composable
actual fun FolioThemeField(
    kind: FolioFieldKind,
    top: Color,
    bottom: Color,
    pools: List<Color>,
    accents: List<Color>,
    isDark: Boolean,
    animate: Boolean,
    modifier: Modifier,
) {
    // Intentionally empty: the fallback painted underneath stands alone.
}

actual fun folioThemeFieldSupported(): Boolean = false
