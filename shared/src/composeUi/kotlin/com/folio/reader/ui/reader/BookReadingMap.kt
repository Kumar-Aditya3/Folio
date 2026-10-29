package com.folio.reader.ui.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.folio.reader.model.BookTocRow
import com.folio.reader.model.Chapter
import com.folio.reader.model.ReadingTrace
import com.folio.reader.model.activeTocIndex
import com.folio.reader.ui.components.folioSeekGesture
import com.folio.reader.ui.components.pageBlockBandFraction
import com.folio.reader.ui.components.pageBlockSeekTarget
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.LocalMotion
import com.folio.reader.ui.theme.MotionKey
import com.folio.reader.ui.theme.rememberMotionAnimation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.jsoup.Jsoup

data class ReaderPassagePreview(val text: String, val paragraphIndex: Int? = null)

/** Text-only peeks keep the live browser, its layout and its persisted position untouched. */
internal fun readerPassagePreview(html: String, fraction: Float, paragraph: Int? = null): ReaderPassagePreview? {
    if (html.isBlank()) return null
    val body = Jsoup.parse(html).body()
    body.select("script,style,noscript,template").remove()
    val paragraphs = body.select("p")
    val source = if (paragraphs.isNotEmpty()) paragraphs else body.children()
    val blocks = source.mapIndexedNotNull { index, element ->
        element.text().trim().takeIf { it.isNotBlank() }?.let { index to it }
    }
    if (blocks.isEmpty()) return null
    val safeFraction = if (fraction.isFinite()) fraction.coerceIn(0f, 1f) else 0f
    val target = (blocks.sumOf { it.second.length.toLong() + 1L } * safeFraction).toLong()
    var offset = 0L
    val selected = if (paragraph != null) blocks.indexOfFirst { it.first >= paragraph }.let {
        if (it < 0) blocks.lastIndex else it
    } else blocks.indexOfFirst { (_, text) ->
        offset += text.length + 1L
        offset > target
    }.let { if (it < 0) blocks.lastIndex else it }
    val excerpt = blocks.drop(selected).take(3).joinToString("\n\n") { it.second }
    val text = if (excerpt.length <= 600) excerpt else excerpt.take(597).trimEnd() + "..."
    return ReaderPassagePreview(text, blocks[selected].first.takeIf { paragraphs.isNotEmpty() })
}

private data class MapTarget(val chapter: Int, val fraction: Float, val paragraph: Int? = null)

@Composable
internal fun BookReadingMap(
    bookId: String,
    chapters: List<Chapter>,
    entries: List<BookTocRow>,
    stops: List<Float>,
    currentChapter: Int,
    currentFraction: Float,
    returnFraction: Float?,
    trace: ReadingTrace,
    onPreviewChapter: suspend (Int, Float, Int?) -> ReaderPassagePreview?,
    onRead: (Int, Float, Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    if (chapters.isEmpty()) return
    val initial = pageBlockSeekTarget(stops, currentFraction)
    val activeRow = entries.activeTocIndex(currentChapter, initial.chapterFraction)
    var target by remember(bookId) { mutableStateOf(MapTarget(initial.chapterIndex, initial.chapterFraction)) }
    var expandedRow by remember(bookId) { mutableStateOf<Int?>(null) }
    // Row taps preserve manual browsing; only the map explicitly requests a new list position.
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (activeRow - 1).coerceAtLeast(0))
    var passage by remember(bookId, target, expandedRow) { mutableStateOf<ReaderPassagePreview?>(null) }
    var loading by remember(bookId, target, expandedRow) { mutableStateOf(expandedRow != null) }
    var committed by remember { mutableStateOf(false) }
    val previewProvider by rememberUpdatedState(onPreviewChapter)
    LaunchedEffect(bookId, target, expandedRow) {
        passage = null
        if (expandedRow == null) { loading = false; return@LaunchedEffect }
        loading = true
        try {
            if (target.paragraph == null) delay(100)
            passage = previewProvider(target.chapter, target.fraction, target.paragraph)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            passage = null
        } finally {
            loading = false
        }
    }
    val previewFraction = pageBlockBandFraction(stops, target.chapter, target.chapter, target.fraction)
    val colors = FolioTheme.colors
    val longest = chapters.maxOfOrNull { it.wordCount.coerceAtLeast(1L) } ?: 1L
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val selectFraction: (Float) -> Unit = { fraction ->
        val landing = pageBlockSeekTarget(stops, fraction)
        target = MapTarget(landing.chapterIndex, landing.chapterFraction)
        expandedRow = entries.activeTocIndex(landing.chapterIndex, landing.chapterFraction).takeIf { it >= 0 }
        // Apply the jump with the next measure, together with the new preview drawer.
        expandedRow?.let { listState.requestScrollToItem(it) }
    }
    com.folio.reader.ui.components.FolioBackHandler(expandedRow != null) { expandedRow = null }
    com.folio.reader.ui.components.FolioBackHandler(expandedRow == null && !LocalReaderWorkspace.current, onBack = onDismiss)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val short = maxHeight < 300.dp
        val showMap = maxHeight >= 200.dp
        val density = LocalDensity.current
        var headerHeight by remember { mutableStateOf(0f) }
        var headerOffset by remember(bookId) { mutableStateOf(0f) }
        val headerScroll = remember(bookId) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    // Programmatic TOC following must never move the map or its legend.
                    if (source != NestedScrollSource.UserInput) return Offset.Zero
                    val before = headerOffset
                    headerOffset = (before + available.y).coerceIn(-headerHeight, 0f)
                    return Offset(0f, headerOffset - before)
                }
            }
        }
        Column(Modifier.fillMaxSize().nestedScroll(headerScroll)) {
            Box(Modifier.fillMaxWidth().height(with(density) { (headerHeight + headerOffset).coerceAtLeast(0f).toDp() }).clipToBounds()) {
            Column(Modifier.fillMaxWidth().wrapContentHeight(unbounded = true, align = Alignment.Top)
                .offset(y = with(density) { headerOffset.toDp() })
                .onSizeChanged { headerHeight = it.height.toFloat() }) {
            if (showMap) Canvas(Modifier.fillMaxWidth().height(if (short) 48.dp else 64.dp).padding(horizontal = 20.dp)
                .folioSeekGesture(true, rtl, onPreview = { it?.let(selectFraction) }, onCommit = selectFraction)
                .semantics {
                    contentDescription = "Book map. Preview only; use Read here to change your place. Saved marks use chapter locations."
                    progressBarRangeInfo = ProgressBarRangeInfo(previewFraction, 0f..1f)
                    setProgress { selectFraction(it.coerceIn(0f, 1f)); true }
                }) {
                fun x(fraction: Float) = size.width * if (rtl) 1f - fraction.coerceIn(0f, 1f) else fraction.coerceIn(0f, 1f)
                val top = 12.dp.toPx()
                val height = 24.dp.toPx()
                for (index in chapters.indices) {
                    val from = x(stops.getOrElse(index) { 0f })
                    val to = x(stops.getOrElse(index + 1) { 1f })
                    val left = minOf(from, to)
                    val width = (kotlin.math.abs(to - from) - 1.dp.toPx()).coerceAtLeast(1f)
                    drawRoundRect(if (index <= currentChapter) colors.accentProgress.copy(alpha = .28f)
                        else colors.onSurface.copy(alpha = .09f), Offset(left, top), Size(width, height), CornerRadius(2.dp.toPx()))
                    if (index == target.chapter) drawRoundRect(colors.primary, Offset(left, top), Size(width, height),
                        CornerRadius(2.dp.toPx()), style = Stroke(1.5.dp.toPx()))
                }
                trace.passages.takeLast(40).forEach {
                    drawCircle(colors.accentAnnotation, if (it.hasNote) 3.dp.toPx() else 2.dp.toPx(),
                        Offset(x(it.fraction), top + height + 7.dp.toPx()))
                }
                drawLine(colors.accentProgress, Offset(x(currentFraction), top - 4.dp.toPx()),
                    Offset(x(currentFraction), top + height + 4.dp.toPx()), 2.dp.toPx())
                drawCircle(colors.primary, 3.dp.toPx(), Offset(x(previewFraction), top - 5.dp.toPx()))
                returnFraction?.let { drawCircle(colors.onSurfaceVariant, 4.dp.toPx(),
                    Offset(x(it), top + height / 2), style = Stroke(1.5.dp.toPx())) }
            }
            if (!short) ReaderLocationLabel(if (returnFraction == null) "HERE / PREVIEW / SAVED" else "HERE / PREVIEW / SAVED / RETURN",
                Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            }
            }
            LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(vertical = 4.dp)) {
                itemsIndexed(entries, key = { index, row -> "$index:${row.chapterIndex}:${row.paragraph}" }) { index, row ->
                    val chapter = chapters.getOrNull(row.chapterIndex)
                    val selected = expandedRow == index
                    Column(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().clickable(role = Role.Button) {
                        target = MapTarget(row.chapterIndex, row.fraction, row.paragraph)
                        expandedRow = if (selected) null else index
                    }.background(if (selected) colors.primary.copy(alpha = .08f) else colors.surface.copy(alpha = 0f))
                        .semantics { stateDescription = if (selected) "Preview expanded" else "Preview collapsed" }
                        .padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(row.title, Modifier.weight(1f), style = FolioTheme.typography.titleSmall,
                                color = if (selected) colors.primary else colors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (index == activeRow) ReaderLocationLabel("HERE", Modifier.padding(start = 8.dp))
                        }
                        val ratio = (chapter?.wordCount?.coerceAtLeast(1L)?.toFloat() ?: 1f) / longest
                        Box(Modifier.fillMaxWidth().height(2.dp).background(colors.onSurface.copy(alpha = .06f))) {
                            Box(Modifier.fillMaxWidth(ratio.coerceIn(.02f, 1f)).fillMaxHeight()
                                .background(if (selected) colors.primary else colors.accentProgress.copy(alpha = .45f)))
                        }
                    }
                    ReaderChapterPreviewDrawer(selected, passage, loading, !committed && !loading,
                        onCancel = { if (expandedRow == index) expandedRow = null },
                        onRead = {
                            if (expandedRow == index && !committed && !loading) {
                                committed = true
                                onRead(target.chapter, target.fraction, passage?.paragraphIndex ?: target.paragraph)
                            }
                        })
                    }
                }
            }
        }
    }
}

private data class ChapterDrawerPreview(val passage: ReaderPassagePreview?, val loading: Boolean)

@Composable
internal fun ReaderChapterPreviewDrawer(expanded: Boolean, passage: ReaderPassagePreview?, loading: Boolean,
    canRead: Boolean, onCancel: () -> Unit, onRead: () -> Unit) {
    var last by remember { mutableStateOf(ChapterDrawerPreview(passage, loading)) }
    if (expanded) last = ChapterDrawerPreview(passage, loading)
    val body: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().then(if (expanded) Modifier else Modifier.clearAndSetSemantics { })
            .pointerInput(expanded) {
                if (!expanded) awaitPointerEventScope {
                    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            }.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.fillMaxWidth().height(.75.dp).background(FolioTheme.colors.outlineVariant.copy(alpha = .5f)))
            ReaderLocationLabel("PREVIEW")
            Text(if (last.loading) "Loading this passage..." else last.passage?.text ?: "No text preview for this chapter. You can still open it.",
                style = FolioTheme.typography.bodyLarge, color = FolioTheme.colors.onSurface,
                maxLines = 5, overflow = TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onCancel, enabled = expanded, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
                TextButton(onClick = onRead, enabled = expanded && canRead, modifier = Modifier.heightIn(min = 48.dp)) { Text("Read here") }
            }
        }
    }
    if (LocalMotion.current.reduced) {
        if (expanded) body()
    } else {
        val resize = rememberMotionAnimation<IntSize>(MotionKey.ReaderSidePanels)
        val fade = rememberMotionAnimation<Float>(MotionKey.ReaderSidePanels)
        AnimatedVisibility(expanded, enter = expandVertically(resize, expandFrom = Alignment.Top) + fadeIn(fade),
            exit = shrinkVertically(resize, shrinkTowards = Alignment.Top) + fadeOut(fade)) { body() }
    }
}
