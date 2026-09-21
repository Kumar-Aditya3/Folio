package com.folio.reader.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * Desktop side of the Atlas caustic sheen: AGSL is Android-only, so the Compose canvas keeps its
 * static cartography, byte-for-byte (Rule 1).
 */
@Composable
actual fun Modifier.folioAtlasShader(
    sea: Color,
    light: Color,
    intensity: Float,
    enabled: Boolean,
): Modifier = this
