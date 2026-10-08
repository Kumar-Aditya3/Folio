package com.folio.reader.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import com.folio.reader.ui.components.FolioGlassPlate

/**
 * §16.2 fallback, desktop side. The lens is an AGSL pass over a replayed backdrop layer, and
 * AGSL is Android-only, so the receiver comes back untouched and the painted bevel + sheen
 * remain the whole edge treatment — Rule 1, desktop is byte-for-byte the pre-lens look.
 *
 * Desktop would also never reach the draw: its tree is un-provided, so
 * [com.folio.reader.ui.components.glassRoomPlate] hands every caller a null plate. The
 * actual is a no-op twice over, which is the intent — one stand-down, not two behaviours.
 */
@Composable
actual fun Modifier.folioGlassLens(
    plate: FolioGlassPlate?,
    shape: Shape,
    tint: Color,
    highlight: Color,
    lightX: Float,
    lightY: Float,
    thickness: Dp,
    refraction: Dp,
    dispersion: Float,
    specular: Float,
    depth: Float,
    intensity: Float,
    enabled: Boolean,
): Modifier = this
