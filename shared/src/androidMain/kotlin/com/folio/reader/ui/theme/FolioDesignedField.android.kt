package com.folio.reader.ui.theme

import android.graphics.RuntimeShader
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import kotlinx.coroutines.delay

/** One procedural pass, with no image-filter source, full-rate library clock or object crop. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
internal fun FolioDesignedField(
    kind: FolioFieldKind,
    source: String,
    colors: List<Color>,
    speedFactor: Float,
    intensity: Float,
    patternScale: Float,
    modifier: Modifier,
) {
    val shader = remember(source) {
        runCatching { RuntimeShader(source) }.onFailure {
            Log.e("FolioField", "Cannot compile $kind field", it)
        }.getOrNull()
    }
    if (shader == null) {
        Box(modifier.background(colors.first()))
        return
    }
    val time = remember(kind) { mutableFloatStateOf(0f) }
    val motion = LocalMotion.current
    val speed = if (intensity > 0f) requireNotNull(kind.fieldSpeed()) * speedFactor else 0f
    val tickMs = if (motion.reduced) SLOW_MOTION_TICK_MS else kind.designedFieldFrameMs()
    LaunchedEffect(kind, speed, tickMs) {
        if (speed <= 0f) return@LaunchedEffect
        var previous = withFrameNanos { it }
        while (true) {
            delay(slowTickDelayMs(previous / 1_000_000L, tickMs))
            val now = withFrameNanos { it }
            // A background/resume gap must not fling the field forward on its first frame.
            val elapsed = ((now - previous).coerceAtLeast(0L) / 1_000_000_000.0).coerceAtMost(0.25)
            time.floatValue += (elapsed * speed).toFloat()
            previous = now
        }
    }
    Box(modifier.drawWithCache {
        val names = arrayOf("u_back", "u_color1", "u_color2", "u_color3", "u_color4", "u_color5")
        names.forEachIndexed { index, name ->
            val color = colors[index]
            shader.setFloatUniform(name, color.red, color.green, color.blue)
        }
        shader.setFloatUniform("u_scale", patternScale.coerceIn(0.25f, 4f))
        shader.setFloatUniform("u_intensity", intensity.coerceIn(0f, 1f))
        val brush = ShaderBrush(shader)
        onDrawBehind {
            if (size.width > 0f && size.height > 0f) {
                shader.setFloatUniform("u_size", size.width, size.height)
                shader.setFloatUniform("u_time", time.floatValue)
                drawRect(brush)
            }
        }
    })
}
