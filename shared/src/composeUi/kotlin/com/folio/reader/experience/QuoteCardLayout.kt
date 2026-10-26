package com.folio.reader.experience

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import kotlin.math.max

internal data class CardAttribution(val text: TextLayoutResult, val position: Offset)

internal data class QuoteCardFrame(
    val normalArea: Rect,
    val compactArea: Rect,
    val footerRule: Float,
    val attribution: List<CardAttribution>,
    val coverPosition: Offset,
    val brandIcon: Rect,
)

internal data class QuoteCardLayout(
    val frame: QuoteCardFrame,
    val body: TextLayoutResult,
    val note: TextLayoutResult?,
    val bodyPosition: Offset,
    val notePosition: Offset?,
    val contentArea: Rect,
    val fontSize: Float,
    val fits: Boolean,
) {
    val contentBottom: Float get() = notePosition?.let { it.y + note!!.size.height }
        ?: (bodyPosition.y + body.size.height)
}

/** Footer and branding reserve independent rows before any passage is fitted. */
internal fun quoteCardFrame(source: ExportSource, options: QuoteCardOptions, measurer: TextMeasurer): QuoteCardFrame {
    val height = options.format.height.toFloat()
    val left = if (options.template == CardTemplate.MARGINALIA) 140f else 116f
    val right = 964f
    val brand = Rect(left, height - 116f, left + 40f, height - 76f)
    val footerLeft = left + if (options.cover) 82f else 0f
    val fields = listOfNotNull(source.title.takeIf { options.title && it.isNotBlank() },
        source.author.takeIf { options.author && it.isNotBlank() }, source.chapter.takeIf { options.chapter && it.isNotBlank() })
    fun measureFooter(font: Int) = fields.map { measurer.measure(it, exportNoteStyle(options).copy(
        fontSize = font.sp, lineHeight = (font * 1.32f).sp),
        constraints = Constraints(maxWidth = (right - footerLeft).toInt())) }
    var footer = measureFooter(24)
    for (font in 23 downTo 18) {
        if (footer.sumOf { it.size.height + 4 } <= height * .2f) break
        footer = measureFooter(font)
    }
    val footerHeight = max(if (options.cover) 84 else 0, footer.sumOf { it.size.height + 4 }.let { if (it > 0) it - 4 else 0 })
    var y = brand.top - 26f - footerHeight
    val coverPosition = Offset(left, y)
    val rule = if (footerHeight > 0) y - 22f else brand.top - 28f
    val attribution = footer.map { layout -> CardAttribution(layout, Offset(footerLeft, y)).also { y += layout.size.height + 4 } }
    val bottom = rule - 32f
    val compactLeft = if (options.template == CardTemplate.MARGINALIA) 132f else 100f
    return QuoteCardFrame(Rect(left, 250f, right, bottom), Rect(compactLeft, 222f, 980f, bottom),
        rule, attribution, coverPosition, brand)
}

private const val MIN_QUOTE_SIZE = 30
private const val NOTE_GAP = 82

private fun measureCardAtSize(page: QuoteCardPage, options: QuoteCardOptions, measurer: TextMeasurer,
    frame: QuoteCardFrame, font: Int, compact: Boolean): QuoteCardLayout {
    val area = if (compact) frame.compactArea else frame.normalArea
    val noteOnly = page.text.isEmpty() && page.note != null
    val noteFont = max(26f, font * 38f / 54f)
    val quoteStyle = if (compact) exportQuoteStyle(options).copy(fontSize = font.sp, lineHeight = (font * 1.28f).sp) else exportQuoteStyle(options)
    val noteStyle = if (compact) exportNoteStyle(options).copy(fontSize = noteFont.sp, lineHeight = (noteFont * 1.34f).sp) else exportNoteStyle(options)
    val constraints = Constraints(maxWidth = area.width.toInt())
    val body = measurer.measure(if (noteOnly) page.note.orEmpty() else page.text, if (noteOnly) noteStyle else quoteStyle, constraints = constraints)
    val note = page.note?.takeIf { !noteOnly && it.isNotBlank() }?.let { measurer.measure(it, noteStyle, constraints = constraints) }
    val blockHeight = body.size.height + (note?.let { it.size.height + NOTE_GAP } ?: 0)
    val extra = (area.height - blockHeight).coerceAtLeast(0f)
    val top = area.top + extra * if (options.template == CardTemplate.MARGINALIA) .2f else .42f
    return QuoteCardLayout(frame, body, note, Offset(area.left, top), note?.let { Offset(area.left, top + body.size.height + NOTE_GAP) },
        area, if (noteOnly) noteFont else font.toFloat(), blockHeight <= area.height)
}

/** Keep generous typography when it fits; tighten margins/leading and fit type before splitting. */
internal fun layoutQuoteCard(page: QuoteCardPage, options: QuoteCardOptions, measurer: TextMeasurer,
    frame: QuoteCardFrame = quoteCardFrame(page.source, options, measurer), minimumOnly: Boolean = false): QuoteCardLayout {
    if (!minimumOnly) {
        val generous = measureCardAtSize(page, options, measurer, frame, 54, compact = false)
        if (generous.fits) return generous
    }
    var best = measureCardAtSize(page, options, measurer, frame, MIN_QUOTE_SIZE, compact = true)
    if (minimumOnly || !best.fits) return best
    var low = MIN_QUOTE_SIZE + 1; var high = 54
    while (low <= high) {
        val font = (low + high) / 2
        val candidate = measureCardAtSize(page, options, measurer, frame, font, compact = true)
        if (candidate.fits) { best = candidate; low = font + 1 } else high = font - 1
    }
    return best
}

fun paginateQuoteCards(sources: List<ExportSource>, options: QuoteCardOptions, measurer: TextMeasurer): List<QuoteCardPage> {
    val pages = sources.flatMap { source ->
        val frame = quoteCardFrame(source, options, measurer)
        val note = source.note?.takeIf { options.note && it.isNotBlank() }
        val complete = QuoteCardPage(source, source.text, note)
        if (layoutQuoteCard(complete, options, measurer, frame).fits) return@flatMap listOf(complete)
        val quotes = paginateExportText(source.text) {
            layoutQuoteCard(QuoteCardPage(source, it), options, measurer, frame, minimumOnly = true).fits
        }.map { QuoteCardPage(source, it) }.toMutableList()
        if (note != null) {
            val last = quotes.lastOrNull()?.copy(note = note)
            if (last != null && layoutQuoteCard(last, options, measurer, frame).fits) quotes[quotes.lastIndex] = last
            else quotes += paginateExportText(note) {
                layoutQuoteCard(QuoteCardPage(source, "", it), options, measurer, frame, minimumOnly = true).fits
            }.map { QuoteCardPage(source, "", it) }
        }
        quotes
    }
    return pages.mapIndexed { index, page -> page.copy(number = index + 1, total = pages.size) }
}
