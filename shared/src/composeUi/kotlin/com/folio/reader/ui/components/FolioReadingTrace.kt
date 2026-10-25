@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)

package com.folio.reader.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.folio.reader.model.ReadingTrace
import com.folio.reader.model.ReadingTracePassage
import com.folio.reader.ui.theme.*
import kotlinx.coroutines.flow.Flow
import kotlin.math.ln

data class TraceSelection(val bookId: String, val title: String, val progress: Float? = null)
val LocalReadingTraces = compositionLocalOf<Map<String, ReadingTrace>> { emptyMap() }
val LocalOpenReadingTrace = compositionLocalOf<(TraceSelection) -> Unit> { {} }

/** One collector above navigation. Saved marks update Home, Library and reader together. */
@Composable
fun FolioReadingTraceProvider(
    traces: Flow<Map<String, ReadingTrace>>,
    onOpenPassage: (String, ReadingTracePassage) -> Unit,
    content: @Composable () -> Unit,
) {
    val snapshot by traces.collectAsState(initial = emptyMap())
    var selected by remember { mutableStateOf<TraceSelection?>(null) }
    CompositionLocalProvider(LocalReadingTraces provides snapshot,
        LocalOpenReadingTrace provides { selected = it }) { content() }
    selected?.let { selection ->
        val record = snapshot[selection.bookId] ?: ReadingTrace()
        val trace = record.copy(progress = selection.progress ?: record.progress)
        FolioDialog(true, { selected = null }, title = selection.title) {
            Text("Reading trace", style = FolioTheme.typography.labelMedium,
                color = FolioTheme.colors.onSurfaceVariant)
            ReadingTraceLine(trace, Modifier.fillMaxWidth().height(64.dp))
            Text(trace.summary, style = FolioTheme.typography.titleMedium)
            if (trace.minutes > 0) Text("${trace.minutes} minutes recorded",
                style = FolioTheme.typography.bodySmall, color = FolioTheme.colors.onSurfaceVariant)
            if (trace.passages.isEmpty()) Text("Saved passages will leave marks here as you read.",
                style = FolioTheme.typography.bodyMedium)
            else {
                Text("Saved passages · arranged by chapter", style = FolioTheme.typography.labelMedium,
                    color = FolioTheme.colors.accentAnnotation, modifier = Modifier.padding(top = 16.dp))
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 340.dp)) {
                    items(trace.passages, key = { it.id }) { passage ->
                        Row(Modifier.fillMaxWidth().clickable(role = Role.Button,
                            onClickLabel = "Read saved passage") {
                            selected = null
                            onOpenPassage(selection.bookId, passage)
                        }.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${passage.spineIndex + 1}".padStart(2, '0'),
                                fontFamily = FontFamily.Monospace,
                                style = FolioTheme.typography.labelMedium,
                                color = FolioTheme.colors.accentAnnotation, modifier = Modifier.width(32.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(passage.text, style = FolioTheme.typography.bodyMedium,
                                    maxLines = 3, overflow = TextOverflow.Ellipsis)
                                if (passage.hasNote) Text("With your note", style = FolioTheme.typography.labelSmall,
                                    color = FolioTheme.colors.onSurfaceVariant)
                            }
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, null,
                                modifier = Modifier.padding(start = 12.dp).size(18.dp))
                        }
                    }
                }
            }
            TextButton(onClick = { selected = null }, modifier = Modifier.align(Alignment.End)) { Text("Close") }
        }
    }
}

/** A progress axis engraved by actual sessions, with saved-passage diamonds at chapter locations. */
@Composable
fun ReadingTraceLine(trace: ReadingTrace, modifier: Modifier = Modifier, onSeek: ((Float) -> Unit)? = null,
    sharedId: String? = null, onPreview: ((Float?) -> Unit)? = null) {
    val colors = FolioTheme.colors
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    var preview by remember { mutableStateOf<Float?>(null) }
    val progress by animateFloatAsState(preview ?: trace.progress.coerceIn(0f, 1f),
        if (preview != null) androidx.compose.animation.core.snap() else rememberMotionAnimation(MotionKey.ProgressBars),
        label = "readingTraceProgress")
    val pulse = remember { Animatable(1f) }
    val spec = rememberMotionSpec(MotionKey.ShelfFadeSwap)
    val enabled = rememberMotionEnabled()
    var previousIds by remember { mutableStateOf<Set<String>?>(null) }
    var pulseIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    val ids = trace.passages.map { it.id }.toSet()
    LaunchedEffect(ids, enabled) {
        val previous = previousIds
        previousIds = ids
        pulseIds = if (previous == null) emptySet() else ids - previous
        if (previous != null && (ids - previous).isNotEmpty() && enabled && spec.durationMs > 0 && spec.intensity > 0f) {
            pulse.snapTo(0f)
            pulse.animateTo(1f, spec.toAnimationSpec())
        } else pulse.snapTo(1f)
    }
    val seek = if (onSeek == null) Modifier else Modifier.folioSeekGesture(true, rtl,
        onPreview = { preview = it; onPreview?.invoke(it) }, onCommit = onSeek).semantics {
        progressBarRangeInfo = ProgressBarRangeInfo(trace.progress, 0f..1f)
        setProgress { onSeek(it.coerceIn(0f, 1f)); true }
    }
    val shared = if (sharedId.isNullOrBlank()) Modifier else Modifier.sharedElementOrNoop(FolioSharedKeys.readingTrace(sharedId))
    Canvas(modifier.then(shared).then(seek).semantics {
        contentDescription = "${(trace.progress * 100).toInt()}% read. ${trace.summary}. Passage marks use chapter locations."
    }) {
        val inset = 4.dp.toPx()
        val usable = (size.width - inset * 2).coerceAtLeast(1f)
        fun x(f: Float) = inset + usable * if (rtl) 1f - f.coerceIn(0f, 1f) else f.coerceIn(0f, 1f)
        val y = size.height * .64f
        drawLine(colors.onSurface.copy(alpha = .16f), Offset(x(0f), y), Offset(x(1f), y), 1.dp.toPx(), StrokeCap.Round)
        drawLine(colors.accentProgress.copy(alpha = .8f), Offset(x(0f), y), Offset(x(progress), y), 1.8.dp.toPx(), StrokeCap.Round)
        val peak = ln(1f + (trace.spans.maxOfOrNull { it.minutes } ?: 1f).coerceAtLeast(1f))
        trace.spans.forEachIndexed { index, span ->
            val rise = size.height * (.12f + .36f * ln(1f + span.minutes.coerceAtLeast(0f)) / peak)
            val lift = rise * (.6f + (index % 3) * .2f)
            val from = x(span.from); val to = x(span.to)
            val contour = Path().apply {
                moveTo(from, y)
                cubicTo(from, y - lift, to, y - lift, to, y)
            }
            drawPath(contour, colors.accentProgress.copy(alpha = .36f), style = Stroke(.85.dp.toPx()))
            val wash = Path().apply { addPath(contour); lineTo(from, y); close() }
            drawPath(wash, colors.accentProgress.copy(alpha = .045f))
            drawLine(colors.accentProgress.copy(alpha = .65f), Offset(to, y - 3.dp.toPx()), Offset(to, y + 3.dp.toPx()), .9.dp.toPx())
        }
        trace.passages.takeLast(40).forEach { passage ->
            val px = x(passage.fraction)
            val r = if (passage.hasNote) 3.4.dp.toPx() else 2.6.dp.toPx()
            val mark = Path().apply { moveTo(px, y - r); lineTo(px + r, y); lineTo(px, y + r); lineTo(px - r, y); close() }
            drawPath(mark, colors.surface)
            drawPath(mark, colors.accentAnnotation, style = Stroke(1.4.dp.toPx()))
            if (passage.id in pulseIds && pulse.value < 1f) drawCircle(colors.accentAnnotation.copy(alpha = (1f - pulse.value) * .4f),
                r + pulse.value * 9.dp.toPx(), Offset(px, y), style = Stroke(1.dp.toPx()))
        }
        drawLine(colors.primary, Offset(x(progress), y - 6.dp.toPx()), Offset(x(progress), y + 6.dp.toPx()), 2.dp.toPx(), StrokeCap.Round)
    }
}

@Composable
fun FolioReadingTrace(bookId: String, title: String, progress: Float, modifier: Modifier = Modifier,
    compact: Boolean = false) {
    val safeProgress = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
    val data = (LocalReadingTraces.current[bookId] ?: ReadingTrace()).copy(progress = safeProgress)
    val open = LocalOpenReadingTrace.current
    val interaction = remember { MutableInteractionSource() }
    Column(modifier.fillMaxWidth().heightIn(min = 60.dp).folioPressable(interaction, scaleTo = .994f)
        .clickable(role = Role.Button, interactionSource = interaction, indication = null,
        onClickLabel = "Explore reading trace and saved passages") { open(TraceSelection(bookId, title, safeProgress)) }
        .padding(vertical = 4.dp)) {
        ReadingTraceLine(data, Modifier.fillMaxWidth().height(if (compact) 32.dp else 44.dp), sharedId = bookId)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            if (!compact) Text("YOUR TRACE", style = FolioTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
                color = FolioTheme.colors.onSurfaceVariant)
            val caption = buildList {
                if (data.sessionCount > 0) add("${data.sessionCount} ${if (data.sessionCount == 1) "sitting" else "sittings"}")
                if (data.passages.isNotEmpty()) add("${data.passages.size} saved")
            }.joinToString(" · ").ifBlank { "Open to explore" }
            Text(caption,
                style = FolioTheme.typography.labelSmall, color = FolioTheme.colors.onSurfaceVariant,
                maxLines = if (compact) 2 else 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = if (compact) 0.dp else 12.dp))
        }
    }
}

/** The same marks on the physical fore-edge. Artwork remains unobscured apart from this narrow edge. */
@Composable
fun FolioTraceSpine(bookId: String, progress: Float, modifier: Modifier = Modifier) {
    val trace = LocalReadingTraces.current[bookId] ?: return
    if (trace.spans.isEmpty() && trace.passages.isEmpty()) return
    val colors = FolioTheme.colors
    Canvas(modifier.width(8.dp).fillMaxHeight().semantics { contentDescription = trace.summary }) {
        val x = size.width - 2.dp.toPx()
        drawLine(colors.surface.copy(alpha = .68f), Offset(x, size.height * .05f), Offset(x, size.height * .95f), 2.dp.toPx())
        trace.spans.forEach {
            val y = size.height * (.05f + it.to * .9f)
            drawLine(colors.accentProgress, Offset(x - 3.dp.toPx(), y), Offset(x, y), 1.dp.toPx())
        }
        trace.passages.takeLast(40).forEach {
            val y = size.height * (.05f + it.fraction * .9f)
            drawCircle(colors.accentAnnotation, 2.dp.toPx(), Offset(x - 1.dp.toPx(), y))
        }
    }
}
