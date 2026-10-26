package com.folio.reader.surfaces

import android.graphics.*
import java.io.File
import kotlin.math.max

/** Bounded, full-update bitmaps: no wallpaper capture, network fetch or launcher blur. */
internal object WidgetArtwork {
    fun glass(accent: Int, surface: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(512, 320, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val bounds = RectF(2f, 2f, 510f, 318f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val path = Path().apply { addRoundRect(bounds, 30f, 30f, Path.Direction.CW) }
        canvas.save(); canvas.clipPath(path)
        paint.shader = LinearGradient(0f, 0f, 512f, 320f,
            intArrayOf(alpha(surface, 245), alpha(surface, 218)), null, Shader.TileMode.CLAMP)
        canvas.drawRect(bounds, paint)
        paint.shader = RadialGradient(65f, 12f, 390f, intArrayOf(alpha(accent, 58), alpha(accent, 0)), null, Shader.TileMode.CLAMP)
        canvas.drawRect(bounds, paint)
        paint.shader = LinearGradient(0f, 0f, 270f, 220f,
            intArrayOf(0x35FFFFFF, 0x00FFFFFF, 0x08FFFFFF), floatArrayOf(0f, .4f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(bounds, paint)
        canvas.restore()
        paint.shader = LinearGradient(0f, 0f, 400f, 320f, intArrayOf(0x70FFFFFF, 0x12FFFFFF, alpha(accent, 65)), null, Shader.TileMode.CLAMP)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.5f
        canvas.drawRoundRect(bounds, 30f, 30f, paint)
        return bitmap
    }

    fun cover(path: String?): Bitmap? = runCatching {
        if (path == null || !File(path).isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > 384 || bounds.outHeight / sample > 512) sample *= 2
        val source = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val result = Bitmap.createBitmap(144, 208, Bitmap.Config.ARGB_8888)
        val scale = max(144f / source.width, 208f / source.height)
        val w = source.width * scale; val h = source.height * scale
        Canvas(result).apply {
            clipPath(Path().apply { addRoundRect(RectF(0f, 0f, 144f, 208f), 12f, 12f, Path.Direction.CW) })
            drawBitmap(source, null, RectF((144-w)/2, (208-h)/2, (144+w)/2, (208+h)/2), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        }
        source.recycle()
        result
    }.getOrNull()

    fun week(minutes: List<Long>, goal: Int, accent: Int, ink: Int, widthDp: Int = 224, heightDp: Int = 47): Bitmap {
        val width = widthDp.coerceIn(100, 600) * 2
        val height = heightDp.coerceIn(34, 180) * 2
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val days = (List(7) { 0L } + minutes).takeLast(7)
        val maxValue = max(goal.coerceAtLeast(1).toLong(), days.maxOrNull() ?: 1).toFloat()
        val calendar = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, -6) }
        val slot = width / 7f
        val barWidth = slot * .60f
        val base = height - 29f
        days.forEachIndexed { index, value ->
            val x = slot * index + (slot - barWidth) / 2f
            paint.color = alpha(ink, 16)
            canvas.drawRoundRect(RectF(x, 2f, x+barWidth, base), 7f, 7f, paint)
            paint.color = Color.WHITE
            paint.shader = LinearGradient(x, 0f, x, base, alpha(accent, 245), alpha(accent, 145), Shader.TileMode.CLAMP)
            if (value > 0) canvas.drawRoundRect(RectF(x, base - max(5f, value / maxValue * (base - 2f)), x+barWidth, base), 7f, 7f, paint)
            paint.shader = null; paint.color = alpha(ink, if (index == 6) 240 else 175)
            paint.textSize = 20f; paint.textAlign = Paint.Align.CENTER; paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            val label = java.text.SimpleDateFormat("EEEEE", java.util.Locale.getDefault()).format(calendar.time)
            canvas.drawText(label.take(1), x+barWidth/2, height-3f, paint)
            calendar.add(java.util.Calendar.DAY_OF_YEAR, 1)
        }
        return bitmap
    }

    private fun alpha(color: Int, opacity: Int) = (color and 0x00FFFFFF) or (opacity shl 24)
}
