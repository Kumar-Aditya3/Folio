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
