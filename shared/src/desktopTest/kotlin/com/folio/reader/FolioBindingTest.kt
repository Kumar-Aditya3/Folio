package com.folio.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import com.folio.reader.ui.components.bindingPalette
import kotlin.test.Test
import kotlin.test.assertTrue

class FolioBindingTest {
    @Test
    fun ivoryTextRetainsContrastAcrossEveryDyeIncludingWeave() {
        val ink = Color(0xFFFFF2D8)
        // Consecutive single-character hashes visit every palette.
        for (title in listOf("A", "B", "C", "D", "E", "F", "polygenelubricants", "")) {
            val palette = bindingPalette(title)
            for (base in listOf(palette.cloth, palette.light)) {
                val background = Color.White.copy(alpha = 0.025f).compositeOver(base)
                for (foreground in listOf(ink, ink.copy(alpha = 0.85f).compositeOver(background))) {
                    val contrast = (foreground.luminance() + 0.05f) / (background.luminance() + 0.05f)
                    assertTrue(contrast >= 4.5f, "$title: contrast $contrast")
                }
            }
        }
    }
}
