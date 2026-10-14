package com.folio.reader.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import com.folio.reader.ui.components.folioPressable
import com.folio.reader.ui.components.rememberFolioInteraction
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.readerVeilAlpha
import com.folio.reader.ui.theme.FolioTokens
import com.folio.reader.ui.theme.rememberMotionEnabled
import kotlinx.coroutines.delay
import com.folio.reader.ui.theme.MotionKey
import com.folio.reader.ui.theme.rememberMotionAnimation
import com.folio.reader.ui.theme.rememberMotionSpec

/**
 * End-of-chapter summary (§5.3): floats above the bottom bar, fades in and out on
 * [FolioTokens.motionStandard], auto-dismisses after [FolioTokens.chipAutoDismiss],
 * and dismisses on tap. Only the pill itself is tappable, so it never stands
 * between the reader and the page.
 */
@Composable
internal fun ReaderChapterChipHost(
    chip: String?,
    onDismiss: () -> Unit,
    aboveBar: Boolean,
    modifier: Modifier = Modifier,
) {
    // The chip's own row: it fades and rises on one spec, and its dwell is the table's timer row
    // rather than the raw token — so the chip's life and its motion are stated in one place.
    val chipSpec = rememberMotionAnimation<Float>(MotionKey.EndOfChapterChip)
    val chipSlide = rememberMotionAnimation<IntOffset>(MotionKey.EndOfChapterChip)
    val chipDwellMs = rememberMotionSpec(MotionKey.ChipDwell).dwellMs
    // The last text must survive the exit fade — a nulled chip must not blank the
    // pill while it is still fading out.
    var lastText by remember { mutableStateOf("") }
    LaunchedEffect(chip) {
        if (chip != null) {
            lastText = chip
            delay(chipDwellMs)
            onDismiss()
        }
    }
    val motion = rememberMotionEnabled()
    val interaction = rememberFolioInteraction()
    // §16: the chip is a reader overlay, so its plate answers to the in-reader-controls
    // slider too — otherwise it stayed opaque while every other overlay thinned out.
    val veil = FolioTheme.readerVeilAlpha
    AnimatedVisibility(
        visible = chip != null,
        modifier = modifier,
        // The fade carries the small rise on the table's own spec, which shortens under
        // reduce-motion instead of switching the rise off — a shorter lift still says "here".
        enter = fadeIn(chipSpec) + slideInVertically(chipSlide) { it / 3 },
        exit = fadeOut(chipSpec) + slideOutVertically(chipSlide) { it / 3 },
    ) {
        Box(Modifier.padding(bottom = if (aboveBar) FolioTokens.barHeight else FolioTokens.space3)) {
            Text(
                text = lastText,
                style = FolioTheme.typography.bodySmall,
                color = FolioTheme.colors.onSurface,
                modifier = Modifier
                    .folioPressable(interaction)
                    .background(FolioTheme.colors.surfaceVariant.copy(alpha = veil), RoundedCornerShape(FolioTokens.radiusChip))
                    .clickable(interactionSource = interaction, indication = null, onClick = onDismiss)
                    .padding(horizontal = FolioTokens.space2, vertical = FolioTokens.space1)
            )
        }
    }
}
