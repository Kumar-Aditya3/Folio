package com.folio.reader.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape

/**
 * §13.7 fallback, desktop side: AGSL is Android-only, so neither the film nor the
 * gel draws here and the hero keeps the §13.4 static mesh + sheen. Rule 1 — desktop
 * is byte-for-byte the pre-shader look, the press focal included: desktop's tree is
 * un-provided, so it gets [com.folio.reader.ui.components.GlassCapabilities.None]
 * and every glass term — this film and the Canvas-level bloom in
 * [com.folio.reader.ui.components.folioGlassPress] alike — stands down together.
 * Desktop also publishes no [LocalFolioBackdrop], so the gel would have no room to
 * refract even where a shader existed.
 */
@Composable
actual fun Modifier.folioLiquidGlass(
    accent: Color,
    highlight: Color,
    lightX: Float,
    lightY: Float,
    intensity: Float,
    enabled: Boolean,
    refractBackdrop: Boolean,
    focal: FolioPressFocal?,
    shape: Shape,
): Modifier = this
