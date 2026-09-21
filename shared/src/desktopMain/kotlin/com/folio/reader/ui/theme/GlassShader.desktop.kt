package com.folio.reader.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * §13.7 fallback, desktop side: AGSL is Android-only, so the film never draws
 * here and the hero keeps the §13.4 static mesh + sheen. Rule 1 — desktop is
 * byte-for-byte the pre-shader look.
 */
@Composable
actual fun Modifier.folioLiquidGlass(
    accent: Color,
    highlight: Color,
    lightX: Float,
    lightY: Float,
    intensity: Float,
    enabled: Boolean,
): Modifier = this
