package com.folio.reader.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * Desktop side of the ambient light film: AGSL is Android-only, so the field
 * keeps its static [folioField] wash and pools, byte-for-byte (Rule 1).
 */
@Composable
actual fun Modifier.folioAmbientShader(
    colorA: Color,
    colorB: Color,
    intensity: Float,
    enabled: Boolean,
): Modifier = this
