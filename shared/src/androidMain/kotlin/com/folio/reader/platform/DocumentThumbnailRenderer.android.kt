package com.folio.reader.platform

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

suspend fun renderAndroidDocumentThumbnail(
    path: String,
    targetWidthPx: Int,
    targetHeightPx: Int
): ByteArray? = withContext(Dispatchers.IO) {
    if (!path.endsWith(".pdf", ignoreCase = true)) {
        val preview = com.folio.reader.importer.documentPreviewText(path)
        val bitmap = Bitmap.createBitmap(targetWidthPx, targetHeightPx, Bitmap.Config.ARGB_8888)
        try {
            val canvas = android.graphics.Canvas(bitmap)
            canvas.drawColor(Color.rgb(249, 246, 239))
            val margin = targetWidthPx * .085f
            val paint = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(47, 44, 39)
                textSize = targetWidthPx * .056f
                typeface = android.graphics.Typeface.create("serif", android.graphics.Typeface.BOLD)
            }
            val width = (targetWidthPx - margin * 2).roundToInt()
            canvas.save()
            canvas.clipRect(margin, margin, targetWidthPx - margin, targetHeightPx - margin)
            canvas.translate(margin, margin)
            val title = android.text.StaticLayout.Builder.obtain(preview.title, 0, preview.title.length, paint, width)
                .setMaxLines(3).setIncludePad(false).build()
            title.draw(canvas)
            canvas.translate(0f, title.height + margin * .7f)
            paint.textSize = targetWidthPx * .034f
            paint.typeface = android.graphics.Typeface.create("serif", android.graphics.Typeface.NORMAL)
            android.text.StaticLayout.Builder.obtain(preview.body, 0, preview.body.length, paint, width)
                .setLineSpacing(0f, 1.35f).setIncludePad(false).build().draw(canvas)
            canvas.restore()
            return@withContext ByteArrayOutputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray()
            }
        } finally { bitmap.recycle() }
    }
    ParcelFileDescriptor.open(
        File(path),
        ParcelFileDescriptor.MODE_READ_ONLY
    ).use { descriptor ->
        PdfRenderer(descriptor).use { renderer ->
            if (renderer.pageCount == 0) return@withContext null
            renderer.openPage(0).use { page ->
                val scale = min(
                    targetWidthPx.toFloat() / page.width.coerceAtLeast(1),
                    targetHeightPx.toFloat() / page.height.coerceAtLeast(1)
                ).coerceAtLeast(0.05f)
                val width = max(1, (page.width * scale).roundToInt())
                val height = max(1, (page.height * scale).roundToInt())
                val bitmap = Bitmap.createBitmap(
                    width,
                    height,
                    Bitmap.Config.ARGB_8888
                )
                try {
                    bitmap.eraseColor(Color.WHITE)
                    page.render(
                        bitmap,
                        null,
                        null,
                        PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                    )
                    ByteArrayOutputStream().use { output ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                        output.toByteArray()
                    }
                } finally {
                    bitmap.recycle()
                }
            }
        }
    }
}
