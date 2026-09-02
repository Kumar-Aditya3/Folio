package com.folio.reader.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens
import kotlinx.coroutines.delay

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
    // The last text must survive the exit fade — a nulled chip must not blank the
    // pill while it is still fading out.
    var lastText by remember { mutableStateOf("") }
    LaunchedEffect(chip) {
        if (chip != null) {
            lastText = chip
            delay(FolioTokens.chipAutoDismiss)
            onDismiss()
        }
    }
    AnimatedVisibility(
        visible = chip != null,
        modifier = modifier,
        enter = fadeIn(tween(FolioTokens.motionStandard.toInt())),
        exit = fadeOut(tween(FolioTokens.motionStandard.toInt())),
    ) {
        Box(Modifier.padding(bottom = if (aboveBar) FolioTokens.barHeight else FolioTokens.space3)) {
            Text(
                text = lastText,
                style = FolioTheme.typography.bodySmall,
                color = FolioTheme.colors.onSurface,
                modifier = Modifier
                    .background(FolioTheme.colors.surfaceVariant, RoundedCornerShape(FolioTokens.radiusChip))
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = FolioTokens.space2, vertical = FolioTokens.space1)
            )
        }
    }
}
