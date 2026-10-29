package com.folio.reader.platform

import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import kotlin.math.min

suspend fun renderDesktopDocumentThumbnail(
    path: String,
    targetWidthPx: Int,
    targetHeightPx: Int
): ByteArray? = withContext(Dispatchers.IO) {
    if (!path.endsWith(".pdf", ignoreCase = true)) {
        val preview = com.folio.reader.importer.documentPreviewText(path)
        val image = java.awt.image.BufferedImage(targetWidthPx, targetHeightPx, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            graphics.color = java.awt.Color(249, 246, 239)
            graphics.fillRect(0, 0, targetWidthPx, targetHeightPx)
            graphics.color = java.awt.Color(47, 44, 39)
            val margin = targetWidthPx * .085f
            val width = targetWidthPx - margin * 2
            graphics.clipRect(margin.toInt(), margin.toInt(), width.toInt(), (targetHeightPx - margin * 2).toInt())
            var y = margin
            fun text(value: String, bold: Boolean, maxLines: Int = Int.MAX_VALUE) {
                val font = java.awt.Font("Serif", if (bold) java.awt.Font.BOLD else java.awt.Font.PLAIN,
                    (targetWidthPx * if (bold) .056f else .034f).toInt().coerceAtLeast(1))
                var lines = 0
                for (paragraph in value.split('\n')) {
                    if (paragraph.isBlank()) { y += font.size * .6f; continue }
                    val attributed = java.text.AttributedString(paragraph).apply { addAttribute(java.awt.font.TextAttribute.FONT, font) }
                    val measurer = java.awt.font.LineBreakMeasurer(attributed.iterator, graphics.fontRenderContext)
                    while (measurer.position < paragraph.length && lines < maxLines && y < targetHeightPx - margin) {
                        val layout = measurer.nextLayout(width)
                        y += layout.ascent
                        layout.draw(graphics, margin, y)
                        y += (layout.descent + layout.leading + font.size * .35f)
                        lines++
                    }
                    if (lines >= maxLines || y >= targetHeightPx - margin) break
                }
            }
            text(preview.title, true, 3)
            y += margin * .7f
            text(preview.body, false)
            return@withContext ByteArrayOutputStream().use { ImageIO.write(image, "png", it); it.toByteArray() }
        } finally { graphics.dispose(); image.flush() }
    }
    Loader.loadPDF(File(path)).use { document ->
        if (document.numberOfPages == 0) return@withContext null
        val page = document.getPage(0)
        val crop = page.cropBox
        val rotated = page.rotation == 90 || page.rotation == 270
        val naturalWidth = if (rotated) crop.height else crop.width
        val naturalHeight = if (rotated) crop.width else crop.height
        val scale = min(
            targetWidthPx / naturalWidth.coerceAtLeast(1f),
            targetHeightPx / naturalHeight.coerceAtLeast(1f)
        ).coerceAtLeast(0.05f)
        val rendered = PDFRenderer(document).renderImage(0, scale, ImageType.RGB)
        ByteArrayOutputStream().use { output ->
            ImageIO.write(rendered, "png", output)
            output.toByteArray()
        }
    }
}
