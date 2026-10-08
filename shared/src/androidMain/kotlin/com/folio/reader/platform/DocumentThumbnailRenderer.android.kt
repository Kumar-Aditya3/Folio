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
