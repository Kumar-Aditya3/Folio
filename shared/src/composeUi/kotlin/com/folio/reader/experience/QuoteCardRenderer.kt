package com.folio.reader.experience

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.*
import com.folio.reader.ui.theme.FontTheme
import com.folio.reader.ui.theme.UiFonts
import com.folio.reader.ui.components.legibleOn
import com.folio.reader.ui.components.drawFolioLogoMark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

interface ExportCoordinator {
    suspend fun images(pages: List<QuoteCardPage>, options: QuoteCardOptions, palette: CardPalette,
        covers: Map<String, ImageBitmap> = emptyMap()): List<ExportFile>
}

class CanvasExportCoordinator(private val resolver: FontFamily.Resolver) : ExportCoordinator {
    override suspend fun images(pages: List<QuoteCardPage>, options: QuoteCardOptions, palette: CardPalette,
        covers: Map<String, ImageBitmap>): List<ExportFile> = withContext(Dispatchers.Default) {
        val measurer = TextMeasurer(resolver, Density(1f), LayoutDirection.Ltr)
        val stamp = System.currentTimeMillis()
        pages.mapIndexed { index, page ->
            coroutineContext.ensureActive()
            val image = renderQuoteCard(page, options, measurer, palette, covers[page.source.id])
            ExportFile("Folio-$stamp-${(index + 1).toString().padStart(3, '0')}.png", encodeCardPng(image))
        }
    }
}

data class CardPalette(val paper: Color, val ink: Color, val accent: Color, val field: Color) {
    /** Theme-coloured source text, guarded against the actual exported paper. */
    val quoteInk: Color get() = legibleOn(accent, paper, ink)
    companion object { val Print = CardPalette(Color(0xfffffdf8), Color(0xff27251f), Color(0xff78664c), Color(0xffece5d5)) }
}

private fun permittedFont(options: QuoteCardOptions) = FontTheme.byId(options.fontThemeId).takeIf { it in listOf(FontTheme.CLASSIC, FontTheme.LITERARY, FontTheme.SOFT) } ?: FontTheme.LITERARY
fun exportQuoteStyle(options: QuoteCardOptions = QuoteCardOptions()) = TextStyle(fontFamily = UiFonts.display(permittedFont(options), 400), fontSize = 54.sp, lineHeight = 75.sp)
fun exportNoteStyle(options: QuoteCardOptions = QuoteCardOptions()) = TextStyle(fontFamily = UiFonts.text(permittedFont(options)), fontSize = 38.sp, lineHeight = 55.sp)

// Fixed, seeded marks keep the material identical in the preview and in every exported image.
private val paperFibres = Random(8417).let { random -> List(900) {
    Offset(random.nextFloat(), random.nextFloat())
} }

private fun DrawScope.paperTexture(p: CardPalette, height: Float, quiet: Boolean) {
    drawPoints(paperFibres.map { Offset(it.x * 1080f, it.y * height) }, PointMode.Points,
        p.ink.copy(alpha = if (quiet) .035f else .07f), strokeWidth = 1.5f, cap = StrokeCap.Round)
    for (i in 0..20) {
        val y = 23f + i * height / 21
        drawLine(p.accent.copy(alpha = .025f), Offset(0f, y), Offset(1080f, y + 5f), .8f)
    }
}

private fun DrawScope.folioFlower(center: Offset, color: Color, radius: Float = 20f) {
    repeat(8) { petal ->
        val angle = petal * kotlin.math.PI.toFloat() / 4
        val end = center + Offset(cos(angle), sin(angle)) * radius
        drawLine(color, center, end, 1.4f, cap = StrokeCap.Round)
        drawCircle(color, 2.2f, end)
    }
    drawCircle(color, 4f, center)
}

private fun DrawScope.bookplateFrame(p: CardPalette, height: Float) {
    val color = p.accent.copy(alpha = .52f)
    drawRect(color, Offset(48f, 48f), Size(984f, height - 96f), style = Stroke(1.4f))
    drawRect(p.accent.copy(alpha = .18f), Offset(57f, 57f), Size(966f, height - 114f), style = Stroke(.8f))
    for (x in listOf(48f, 1032f)) for (y in listOf(48f, height - 48)) {
        drawCircle(p.paper, 12f, Offset(x, y))
        folioFlower(Offset(x, y), color, 10f)
    }
    // A quiet printer's ornament, rather than a second decorative surface.
    folioFlower(Offset(540f, 166f), p.accent, 17f)
    drawLine(color, Offset(400f, 166f), Offset(493f, 166f), 1f)
    drawLine(color, Offset(587f, 166f), Offset(680f, 166f), 1f)
}

private fun DrawScope.atmosphereMaterial(p: CardPalette, height: Float, inkFriendly: Boolean) {
    val tint = if (inkFriendly) .08f else .38f
    drawRect(Brush.linearGradient(listOf(lerp(p.field, p.accent, tint), p.field, lerp(p.paper, p.accent, tint)),
        start = Offset(0f, 0f), end = Offset(1080f, height)), size = Size(1080f, height))
    drawCircle(Brush.radialGradient(listOf(p.accent.copy(alpha = tint), Color.Transparent),
        center = Offset(960f, 70f), radius = 700f), 700f, Offset(960f, 70f))
    // Engraved contour lines run around the paper; they never cross the quote.
    repeat(16) { index ->
        val shift = index * 29f
        val contour = Path().apply {
            moveTo(-160f + shift, -70f)
            cubicTo(70f + shift, height * .22f, -230f + shift, height * .66f, 170f + shift, height + 80f)
        }
        drawPath(contour, p.ink.copy(alpha = if (inkFriendly) .05f else .09f), style = Stroke(1.3f))
    }
    val paperOffset = Offset(64f, 206f)
    val paperSize = Size(952f, height - 270f)
    drawRoundRect(p.ink.copy(alpha = .045f), paperOffset + Offset(0f, 16f), paperSize, androidx.compose.ui.geometry.CornerRadius(9f))
    drawRoundRect(p.ink.copy(alpha = .04f), paperOffset + Offset(0f, 7f), paperSize, androidx.compose.ui.geometry.CornerRadius(9f))
    drawRoundRect(lerp(p.paper, p.accent, .12f), paperOffset + Offset(10f, -12f), paperSize, androidx.compose.ui.geometry.CornerRadius(9f))
    drawRoundRect(p.paper, paperOffset, paperSize, androidx.compose.ui.geometry.CornerRadius(9f))
    drawRoundRect(p.accent.copy(alpha = .24f), paperOffset, paperSize, androidx.compose.ui.geometry.CornerRadius(9f), style = Stroke(1f))
    drawRect(Brush.verticalGradient(listOf(p.accent.copy(alpha = .075f), Color.Transparent), 206f, 500f),
        topLeft = paperOffset, size = Size(952f, 294f.coerceAtMost(height - 270f)))
}

private fun DrawScope.notebookMaterial(p: CardPalette, height: Float, inkFriendly: Boolean) {
    drawRect(Brush.horizontalGradient(listOf(lerp(p.paper, p.accent, .1f), p.paper, p.paper)), size = Size(1080f, height))
    drawRect(p.accent.copy(alpha = if (inkFriendly) .06f else .14f), size = Size(66f, height))
    drawLine(p.accent.copy(alpha = .34f), Offset(98f, 0f), Offset(98f, height), 1.5f)
    drawLine(p.accent.copy(alpha = .13f), Offset(105f, 0f), Offset(105f, height), 1f)
    var y = 237f
    while (y < height - 285f) {
        drawLine(p.ink.copy(alpha = .045f), Offset(125f, y), Offset(990f, y), 1f)
        y += 55f
    }
    // A folded corner and a ribbon are physical details, with no fabricated handwriting.
    val corner = Path().apply { moveTo(1008f, 0f); lineTo(1080f, 72f); lineTo(1008f, 72f); close() }
    drawPath(corner, lerp(p.paper, p.accent, .19f))
    drawLine(p.accent.copy(alpha = .3f), Offset(1008f, 0f), Offset(1008f, 72f), 1f)
    val ribbon = Path().apply { moveTo(900f, 0f); lineTo(944f, 0f); lineTo(944f, 150f); lineTo(922f, 133f); lineTo(900f, 150f); close() }
    drawPath(ribbon, p.accent.copy(alpha = if (inkFriendly) .18f else .55f))
}

/** A small engraved device occupies spare space without ever sitting beneath the writing. */
private fun DrawScope.readingDevice(center: Offset, p: CardPalette, template: CardTemplate) {
    val ink = p.accent.copy(alpha = .62f)
    if (template == CardTemplate.ATMOSPHERE) {
        drawCircle(p.accent.copy(alpha = .12f), 42f, center + Offset(46f, -21f))
        drawCircle(ink.copy(alpha = .36f), 42f, center + Offset(46f, -21f), style = Stroke(1f))
        repeat(5) { i ->
            val wave = Path().apply {
                moveTo(center.x - 125f, center.y + 5f + i * 11f)
                cubicTo(center.x - 35f, center.y - 40f + i * 11f, center.x + 30f,
                    center.y + 55f + i * 6f, center.x + 125f, center.y + 5f + i * 11f)
            }
            drawPath(wave, ink.copy(alpha = .5f - i * .065f), style = Stroke(1.4f))
        }
    } else {
        // Two botanical branches, like a printer's device or a sketch in the margin.
        for (side in listOf(-1f, 1f)) {
            val stem = Path().apply {
                moveTo(center.x, center.y + 32f)
                cubicTo(center.x + side * 24, center.y + 20, center.x + side * 80,
                    center.y + 10, center.x + side * 112, center.y - 38)
            }
            drawPath(stem, ink, style = Stroke(1.5f))
            repeat(4) { leaf ->
                val root = center + Offset(side * (26 + leaf * 21f), 23 - leaf * 12f)
                val tip = root + Offset(side * 6, -34f)
                val leafPath = Path().apply {
                    moveTo(root.x, root.y)
                    cubicTo(root.x - side * 14, root.y - 18, tip.x - side * 10, tip.y + 5, tip.x, tip.y)
                    cubicTo(tip.x + side * 11, tip.y + 12, root.x + side * 13, root.y - 3, root.x, root.y)
                    close()
                }
                drawPath(leafPath, p.accent.copy(alpha = .06f))
                drawPath(leafPath, ink, style = Stroke(1.2f))
            }
        }
        folioFlower(center + Offset(0f, 28f), ink, 6f)
    }
}

internal fun DrawScope.drawQuoteCard(page: QuoteCardPage, options: QuoteCardOptions, measurer: TextMeasurer, palette: CardPalette,
    cover: ImageBitmap? = null, layout: QuoteCardLayout = layoutQuoteCard(page, options, measurer)) {
    val p = if (options.inkFriendly) CardPalette.Print else palette
    val width = 1080f; val height = options.format.height.toFloat()
    drawRect(p.paper, size = Size(width, height))
    when (options.template) {
        CardTemplate.EDITORIAL -> bookplateFrame(p, height)
        CardTemplate.ATMOSPHERE -> atmosphereMaterial(p, height, options.inkFriendly)
        CardTemplate.MARGINALIA -> notebookMaterial(p, height, options.inkFriendly)
    }
    paperTexture(p, height, options.inkFriendly)
    val x = layout.frame.normalArea.left
    val small = TextStyle(fontFamily = UiFonts.text(permittedFont(options)), fontSize = 25.sp, lineHeight = 35.sp, color = p.ink)
    if (page.source.isPersonalNote || (page.note != null && page.text.isEmpty())) {
        val heading = measurer.measure("NOTES", small.copy(fontSize = 20.sp, letterSpacing = 3.5.sp))
        drawText(heading, topLeft = Offset(if (options.template == CardTemplate.EDITORIAL) (width - heading.size.width) / 2 else x, 99f))
    }
    if (options.template != CardTemplate.EDITORIAL) {
        if (options.template == CardTemplate.MARGINALIA) drawLine(p.accent, Offset(x, 168f), Offset(x + 82f, 168f), 2f)
        else folioFlower(Offset(922f, 115f), p.ink.copy(alpha = .7f), 22f)
    }
    val noteOnly = page.text.isEmpty() && page.note != null
    val sourceInk = if (options.inkFriendly || noteOnly || page.source.isPersonalNote) p.ink else p.quoteInk
    val bodyY = layout.bodyPosition.y
    if (!noteOnly && !page.source.isPersonalNote && layout.contentArea.top >= 250f) {
        val quotation = measurer.measure("\u201c", exportQuoteStyle(options).copy(fontSize = 170.sp, lineHeight = 170.sp, color = p.accent.copy(alpha = .22f)))
        drawText(quotation, topLeft = Offset(layout.bodyPosition.x - 9f, bodyY - 116f))
    }
    drawText(layout.body, color = sourceInk, topLeft = layout.bodyPosition)
    layout.notePosition?.let { position ->
        val labelY = position.y - 42f
        drawLine(p.accent.copy(alpha = .5f), Offset(position.x, labelY + 13), Offset(position.x + 29, labelY + 13), 1.5f)
        drawText(measurer.measure("MY NOTE", small.copy(fontSize = 19.sp, lineHeight = 26.sp, letterSpacing = 2.sp,
            color = legibleOn(p.accent, p.paper, p.ink))), topLeft = Offset(position.x + 44, labelY))
        drawText(layout.note!!, color = p.ink, topLeft = position)
    }
    val footerTop = layout.frame.footerRule
    val spare = footerTop - layout.contentBottom
    if (spare > 195f) readingDevice(Offset(if (options.template == CardTemplate.MARGINALIA) 790f else 540f,
        layout.contentBottom + spare * .53f), p, options.template)
    drawLine(p.accent.copy(alpha = .42f), Offset(x, footerTop), Offset(width - 116f, footerTop), 1.2f)
    if (options.cover && cover != null) {
        drawImage(cover, dstOffset = IntOffset(x.toInt(), layout.frame.coverPosition.y.toInt()), dstSize = IntSize(60, 84))
    }
    layout.frame.attribution.forEach { drawText(it.text, color = p.ink, topLeft = it.position) }
    val brand = layout.frame.brandIcon
    drawFolioLogoMark(brand.topLeft, brand.width, if (options.inkFriendly) p.ink else p.quoteInk)
    val imprint = small.copy(fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 2.sp, color = p.ink.copy(alpha = .65f))
    if (page.total > 1) {
        val pageMark = "${page.number.toString().padStart(2, '0')} / ${page.total.toString().padStart(2, '0')}"
        val imprintEnd = measurer.measure(pageMark, imprint)
        drawText(imprintEnd, topLeft = Offset(width - 116f - imprintEnd.size.width, brand.top + (brand.height - imprintEnd.size.height) / 2f))
    }
}

fun renderQuoteCard(page: QuoteCardPage, options: QuoteCardOptions, measurer: TextMeasurer, palette: CardPalette,
    cover: ImageBitmap? = null): ImageBitmap {
    val image = ImageBitmap(1080, options.format.height)
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(1080f, options.format.height.toFloat())) {
        drawQuoteCard(page, options, measurer, palette, cover)
    }
    return image
}
