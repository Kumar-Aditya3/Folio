package com.folio.reader.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * Atlas atmosphere — an *animated* caustic sheen drawn over the Atlas canvas, the map's answer to
 * [folioAmbientShader]. It is the shader **enhancement** layer only: the Compose canvas underneath
 * carries the real cartography (regions lit by reading progress, coastlines ragged by cluster
 * tightness, borders between books), so this must be able to vanish with nothing lost.
 *
 * **Contract (Rule 19 — every effect declares its floor and its fallback):**
 *
 * | Condition | Result |
 * |---|---|
 * | API ≥ 33, [enabled] on, motion on | animated AGSL caustic sheen over the map |
 * | API < 33, compile fails, [enabled] off, or reduce-motion | receiver unchanged — the canvas stands alone |
 * | Desktop / previews / tests | receiver unchanged, byte-for-byte |
 *
 * @param sea the deep-water tint the caustics ride over (usually the theme's tertiary/sunken hue).
 * @param light the caustic highlight colour (usually the atmosphere's rim light).
 * @param intensity overall ceiling on the sheen's alpha; kept small so map labels stay legible.
 * @param enabled the liquid-glass capability gate — pass `LocalGlassCapabilities.current.specular`.
 */
@Composable
expect fun Modifier.folioAtlasShader(
    sea: Color,
    light: Color,
    intensity: Float = 0.12f,
    enabled: Boolean = true,
): Modifier
