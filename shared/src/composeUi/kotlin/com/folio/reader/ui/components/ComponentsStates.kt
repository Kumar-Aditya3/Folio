package com.folio.reader.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

@Composable
fun ProgressRing(
    progress: Float,
    modifier: Modifier = Modifier.size(48.dp),
    strokeWidth: Float = 4f,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant
) {
    // §13.5: the sweep grows from zero once per instance — rememberSaveable, so a
    // lazy-list item scrolled away and back does not replay it, and live progress
    // changes draw through immediately once the entry has played. Rule 19 is that
    // same seam's job: with system animations off it resolves to 1f on the first
    // read, so the ring draws its final static arc rather than skipping the value.
    //
    // Held as a State and read inside the Canvas below rather than unwrapped in
    // composition, the way BookCover's reveal and Stats' GoalDial already do it:
    // `rememberEntryProgress` returns the value, which makes every frame of the
    // sweep recompose this ring and whoever placed it, while `rememberEntryState`
    // is the draw-phase form — same ramp, one drawing pass per frame.
    val entry: State<Float> = rememberEntryState()
    Canvas(modifier = modifier.progressSemantics(progress.coerceIn(0f, 1f))) {
        val strokePx = strokeWidth.dp.toPx()
        val diameter = minOf(size.width, size.height) - strokePx
        val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
        val arcSize = Size(diameter, diameter)

        drawArc(
            color = trackColor,
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = strokePx, cap = StrokeCap.Round)
        )

        drawArc(
            color = color,
            startAngle = -90f,
            sweepAngle = 360f * progress.coerceIn(0f, 1f) * entry.value,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = strokePx, cap = StrokeCap.Round)
        )
    }
}

@Composable
fun LoadingPlaceholder(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            modifier = Modifier.size(24.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/**
 * The one blank-state layout: icon, headline, optional body, optional action.
 * Every screen renders its empty condition through this so "nothing here"
 * always looks and reads the same.
 */
@Composable
fun EmptyState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    headline: String,
    body: String? = null,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null
) {
    // A blank state should feel intentional, not unfinished: a gentle one-shot
    // fade + rise on first composition (draw-phase, and instant under reduce-motion
    // via rememberEntryState).
    val reveal = rememberEntryState(headline)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = reveal.value
                translationY = (1f - reveal.value) * 8.dp.toPx()
            }
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = com.folio.reader.ui.theme.FolioTheme.colors.onSurfaceVariant,
            modifier = Modifier.size(44.dp)
        )
        Text(
            headline,
            style = com.folio.reader.ui.theme.FolioTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        if (body != null) {
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = com.folio.reader.ui.theme.FolioTheme.colors.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
        action?.invoke()
    }
}
