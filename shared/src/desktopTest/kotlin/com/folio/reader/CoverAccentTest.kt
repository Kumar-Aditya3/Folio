package com.folio.reader

import androidx.compose.ui.graphics.Color
import com.folio.reader.ui.components.guardCoverContrast
import com.folio.reader.ui.components.rgbToHsv
import com.folio.reader.ui.components.sampleCoverAccent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * §13.3 cover-derived hero accent: the pure sampling and contrast-guard math.
 * The composable side only feeds these ARGB pixels from a 32×32 downsample of
 * the already-cached bitmap; these tests pin the bucketing and the
 * non-negotiable 4.5:1 guard.
 */
class CoverAccentTest {

    private fun argb(r: Int, g: Int, b: Int): Int =
        (255 shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun hsvConversionBasics() {
        val red = rgbToHsv(argb(255, 0, 0))
        assertEquals(0f, red.first, 0.5f)
        assertEquals(1f, red.second, 0.001f)
        assertEquals(1f, red.third, 0.001f)

        val white = rgbToHsv(argb(255, 255, 255))
        assertEquals(0f, white.second, 0.001f)
        assertEquals(1f, white.third, 0.001f)

        val black = rgbToHsv(argb(0, 0, 0))
        assertEquals(0f, black.third, 0.001f)
    }

    @Test
    fun modalSaturatedBucketWins() {
        // Mostly red, some blue, a few greys — the modal qualifying hue is red.
        val pixels = IntArray(32 * 32) { i ->
            when {
                i < 500 -> argb(220, 30, 30)   // red, qualifies
                i < 600 -> argb(30, 30, 220)   // blue, qualifies
                i < 650 -> argb(128, 128, 128) // grey — saturation ≤ 0.25, filtered
                else -> argb(250, 250, 245)    // paper white — value > 0.9, filtered
            }
        }
        val accent = sampleCoverAccent(pixels)
        assertNotNull(accent)
        val hsv = rgbToHsv(
            argb(
                (accent.red * 255f).toInt(),
                (accent.green * 255f).toInt(),
                (accent.blue * 255f).toInt(),
            )
        )
        assertTrue(hsv.first < 30f || hsv.first > 330f, "expected the red bin, got hue ${hsv.first}")
    }

    @Test
    fun whiteAndBlackCoversYieldNothing() {
        val white = IntArray(32 * 32) { argb(255, 255, 255) }
        assertNull(sampleCoverAccent(white))
        val black = IntArray(32 * 32) { argb(10, 10, 10) }
        assertNull(sampleCoverAccent(black))
    }

    @Test
    fun guardAlwaysReturnsLegibleColor() {
        val lightSurface = Color(0xFFFAF7F2)
        val darkSurface = Color(0xFF101418)
        val fallback = Color(0xFF4D8FC7)
        val suspects = listOf(
            Color.White, Color.Black, Color(0xFF808080),
            Color(0xFFF5F5F0), Color(0xFF111111), Color(0xFFB0B0B0),
        )
        for (surface in listOf(lightSurface, darkSurface)) {
            for (suspect in suspects) {
                val guarded = guardCoverContrast(suspect, surface, fallback)
                val ratio = contrastRatio(guarded, surface)
                assertTrue(ratio >= 4.5, "guard left ${guarded} at $ratio:1 on $surface")
            }
        }
    }

    private fun contrastRatio(a: Color, b: Color): Double {
        fun luminance(c: Color): Double {
            fun channel(v: Float): Double {
                val d = v.toDouble()
                return if (d <= 0.04045) d / 12.92 else Math.pow((d + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
        }
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }
}
