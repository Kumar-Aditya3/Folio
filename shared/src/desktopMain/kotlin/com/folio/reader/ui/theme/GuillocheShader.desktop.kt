package com.folio.reader.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * Desktop side of the guilloche plate: AGSL is Android-only, so the field keeps its
 * static [folioField] wash and pools, byte-for-byte (Rule 19). Desktop publishes no
 * plate and no quiet regions, and nothing here allocates a shader or a clock.
 *
 * The fallback is deliberately the *pre-existing look* rather than a cheaper Canvas
 * reimplementation of the weave: a second implementation is a second source of truth for
 * the same pattern, and it would have to be kept in agreement with the shader forever.
 * The plate is a layer over a field that already reads complete, so "draw nothing" is the
 * correct and complete answer.
 */
@Composable
actual fun Modifier.folioGuilloche(
    packId: String,
    fieldTop: Color,
    fieldBottom: Color,
    fieldSourceX: Float,
    fieldSourceY: Float,
    isDark: Boolean,
    intensity: Float,
    enabled: Boolean,
): Modifier = this
