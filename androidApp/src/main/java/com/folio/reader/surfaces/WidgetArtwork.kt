package com.folio.reader.surfaces

import android.graphics.*
import com.folio.reader.settings.FOLIO_WIDGET_GLASS
import com.folio.reader.ui.theme.FolioSurfaceOpacity
import com.folio.reader.ui.theme.FolioTokens
import java.io.File
import kotlin.math.max

/** Bounded, full-update bitmaps: no wallpaper capture, network fetch or launcher blur. */
internal object WidgetArtwork {
    /** Launcher-safe painted glass: shared Folio material tokens, with no captured backdrop. */
    fun glass(accent: Int, surface: Int, widthDp: Int = 280, heightDp: Int = 184,
        opacity: Float = FOLIO_WIDGET_GLASS, sheen: Float = 1f, effects: Boolean = true): Bitmap {
        val w = widthDp.coerceIn(150, 600) * 2f
        val h = heightDp.coerceIn(96, 400) * 2f
        val bitmap = Bitmap.createBitmap(w.toInt(), h.toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val bounds = RectF(1f, 1f, w-1, h-1)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val fill = (opacity.coerceIn(FolioSurfaceOpacity.MIN, 1f) * 255).toInt()
        val light = sheen.coerceIn(0f, 1f)
        val dark = Color.luminance(surface) < .5f
        val catch = if (dark) blend(accent, Color.WHITE, .5f) else Color.WHITE
        val path = Path().apply { addRoundRect(bounds, 48f, 48f, Path.Direction.CW) }
        canvas.save()
        canvas.clipPath(path)
        paint.shader = LinearGradient(0f, 0f, w, h,
            intArrayOf(alpha(blend(surface, accent, .04f), fill), alpha(surface, fill)), null, Shader.TileMode.CLAMP)
        canvas.drawRect(bounds, paint)
        if (effects) {
            paint.shader = LinearGradient(0f, 0f, 0f, h * .38f,
                intArrayOf(alpha(catch, (22 * light).toInt()), alpha(catch, 0)), null, Shader.TileMode.CLAMP)
            canvas.drawRect(bounds, paint)
            // The same deterministic grain seed as Folio's unblurred Compose glass.
            paint.shader = null
            var seed = 0x5F3759DF
            val grainAlpha = (FolioTokens.glassGrainAlpha * 255).toInt()
            for (y in 0 until h.toInt() step 2) for (x in 0 until w.toInt() step 2) {
                seed = seed * 1_103_515_245 + 12_345
                paint.color = alpha(if ((seed ushr 16) and 1 == 0) Color.WHITE else Color.BLACK, grainAlpha)
                canvas.drawPoint(x.toFloat(), y.toFloat(), paint)
            }
        }
        paint.shader = null
        paint.style = Paint.Style.STROKE
        if (effects) {
            paint.strokeWidth = FolioTokens.glassRimWidth.value * 2f
            paint.shader = LinearGradient(0f, 0f, w, h, intArrayOf(
                alpha(catch, (255 * FolioTokens.glassRimLitAlpha * light).toInt()),
                alpha(catch, (255 * FolioTokens.glassRimCoreAlpha * light).toInt()),
                alpha(Color.BLACK, (255 * FolioTokens.glassDepthAlpha).toInt())),
                floatArrayOf(0f, .35f, 1f), Shader.TileMode.CLAMP)
            canvas.drawRoundRect(bounds, 48f, 48f, paint)
        }
        paint.strokeWidth = 1.5f
        paint.shader = LinearGradient(0f, 0f, w, h,
            intArrayOf(alpha(accent, 55), alpha(accent, 16)), null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(bounds, 48f, 48f, paint)
        canvas.restore()
        return bitmap
    }

    fun readingArrow(accent: Int): Bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).also { bitmap ->
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = alpha(accent, 24) }
        canvas.drawCircle(32f, 32f, 31f, paint)
        paint.color = accent
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        canvas.drawPath(Path().apply {
            moveTo(22f, 42f); lineTo(42f, 22f)
            moveTo(23f, 22f); lineTo(42f, 22f); lineTo(42f, 41f)
        }, paint)
    }

    fun readingJacket(path: String?, accent: Int, surface: Int): Bitmap {
        val source = jacket(path, accent, surface)
        val bitmap = Bitmap.createBitmap(174, 232, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        paint.color = Color.BLACK
        paint.setShadowLayer(9f, 2f, 5f, 0x60000000)
        canvas.drawRoundRect(RectF(11f, 7f, 159f, 218f), 10f, 10f, paint)
        paint.clearShadowLayer()
        paint.color = blend(surface, Color.WHITE, .65f)
        canvas.drawRoundRect(RectF(17f, 10f, 163f, 220f), 8f, 8f, paint)
        paint.color = alpha(surface, 90)
        paint.strokeWidth = 1f
        for (y in 213..217 step 2) canvas.drawLine(22f, y.toFloat(), 155f, y.toFloat(), paint)
        paint.color = Color.WHITE
        canvas.drawBitmap(source, null, RectF(9f, 5f, 153f, 213f), paint)
        paint.shader = LinearGradient(9f, 0f, 29f, 0f,
            intArrayOf(0x55000000, 0x18FFFFFF, Color.TRANSPARENT), floatArrayOf(0f, .35f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(RectF(9f, 5f, 153f, 213f), 10f, 10f, paint)
        source.recycle()
        return bitmap
    }

    private fun blend(from: Int, to: Int, fraction: Float): Int = Color.rgb(
        (Color.red(from) + (Color.red(to) - Color.red(from)) * fraction).toInt(),
        (Color.green(from) + (Color.green(to) - Color.green(from)) * fraction).toInt(),
        (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * fraction).toInt())

    fun jacket(path: String?, accent: Int, surface: Int): Bitmap = cover(path) ?: Bitmap.createBitmap(144,208,Bitmap.Config.ARGB_8888).also { bitmap ->
        Canvas(bitmap).apply {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            clipPath(Path().apply { addRoundRect(RectF(0f,0f,144f,208f),12f,12f,Path.Direction.CW) })
            drawColor(surface)
            paint.shader = LinearGradient(0f,0f,144f,208f,alpha(accent,180),alpha(accent,25),Shader.TileMode.CLAMP)
            drawRect(0f,0f,144f,208f,paint); paint.shader = null
            paint.color = alpha(accent,100); drawCircle(133f,46f,70f,paint)
            paint.color = accent; paint.textSize=60f; paint.typeface=Typeface.create("serif",Typeface.NORMAL)
            drawText("f",23f,166f,paint)
        }
    }

    fun progress(percent: Int, accent: Int, ink: Int, widthDp: Int): Bitmap {
        val w = widthDp.coerceIn(80, 550)*2
        return Bitmap.createBitmap(w,16,Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas=Canvas(bitmap); val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap=Paint.Cap.ROUND; strokeWidth=4f }
            paint.color=alpha(ink,32); canvas.drawLine(6f,8f,w-6f,8f,paint)
            val end=6+(w-12)*percent.coerceIn(0,100)/100f
            paint.color=accent; canvas.drawLine(6f,8f,end,8f,paint); canvas.drawCircle(end,8f,5f,paint)
        }
    }

    fun listeningDisc(path: String?, percent: Int, accent: Int, ink: Int, surface: Int): Bitmap {
        val bitmap=Bitmap.createBitmap(192,192,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap); val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color=alpha(accent,18); canvas.drawCircle(96f,96f,94f,paint)
        paint.style=Paint.Style.STROKE; paint.strokeWidth=4f; paint.color=alpha(ink,24)
        canvas.drawCircle(96f,96f,88f,paint)
        paint.color=accent; paint.strokeCap=Paint.Cap.ROUND
        canvas.drawArc(RectF(8f,8f,184f,184f),-90f,percent.coerceIn(0,100)*3.6f,false,paint)
        paint.style=Paint.Style.FILL
        val cover=cover(path)
        if (cover!=null) {
            canvas.save(); canvas.clipPath(Path().apply { addCircle(96f,96f,76f,Path.Direction.CW) })
            canvas.drawBitmap(cover,null,RectF(20f,-14f,172f,206f),paint); canvas.restore(); cover.recycle()
        } else {
            paint.color=surface; canvas.drawCircle(96f,96f,76f,paint)
            paint.color=accent; paint.style=Paint.Style.STROKE; paint.strokeWidth=6f
            canvas.drawArc(RectF(61f,58f,131f,134f),180f,180f,false,paint)
            paint.style=Paint.Style.FILL
            canvas.drawRoundRect(RectF(58f,94f,72f,124f),6f,6f,paint)
            canvas.drawRoundRect(RectF(120f,94f,134f,124f),6f,6f,paint)
        }
        return bitmap
    }

    fun control(action: String, accent: Int, ink: Int, enabled: Boolean): Bitmap {
        val bitmap=Bitmap.createBitmap(96,96,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap); val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        val primary=action=="play" || action=="pause"
        paint.color=if (primary && enabled) alpha(accent, 28) else alpha(ink,if(enabled) 9 else 4)
        canvas.drawCircle(48f,48f,35f,paint)
        if (primary) {
            paint.style=Paint.Style.STROKE; paint.strokeWidth=1.5f
            paint.shader=LinearGradient(16f,13f,80f,83f,alpha(accent,100),alpha(accent,22),Shader.TileMode.CLAMP)
            canvas.drawCircle(48f,48f,35f,paint)
            paint.shader=null; paint.style=Paint.Style.FILL
        }
        paint.color=if(primary && enabled) accent else alpha(ink,if(enabled) 225 else 70)
        when(action) {
            "pause" -> { canvas.drawRoundRect(RectF(36f,33f,44f,63f),2f,2f,paint); canvas.drawRoundRect(RectF(52f,33f,60f,63f),2f,2f,paint) }
            "previous", "next" -> {
                canvas.save()
                if(action=="previous") canvas.scale(-1f,1f,48f,48f)
                canvas.drawPath(Path().apply { moveTo(34f,34f); lineTo(55f,48f); lineTo(34f,62f); close() },paint)
                canvas.drawRoundRect(RectF(57f,34f,62f,62f),1.5f,1.5f,paint); canvas.restore()
            }
            else -> canvas.drawPath(Path().apply { moveTo(39f,31f); lineTo(64f,48f); lineTo(39f,65f); close() },paint)
        }
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
        val barWidth = minOf(slot * .34f, 22f)
        val base = height - 29f
        days.forEachIndexed { index, value ->
            val x = slot * index + (slot - barWidth) / 2f
            paint.color = alpha(ink, 13)
            canvas.drawRoundRect(RectF(x, 2f, x+barWidth, base), barWidth/2, barWidth/2, paint)
            paint.color = Color.WHITE
            paint.shader = LinearGradient(x, 0f, x, base, alpha(accent, 245), alpha(accent, 145), Shader.TileMode.CLAMP)
            if (value > 0) canvas.drawRoundRect(RectF(x, base - max(5f, value / maxValue * (base - 2f)), x+barWidth, base), barWidth/2, barWidth/2, paint)
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
