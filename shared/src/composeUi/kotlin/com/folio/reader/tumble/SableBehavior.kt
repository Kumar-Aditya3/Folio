package com.folio.reader.tumble

import kotlin.math.sin
import kotlin.math.PI

/** A short, interruptible action. Travel is measured in character widths. */
internal data class SableFrame(val cell: Int, val travel: Float = 0f, val lean: Float = 0f)

internal fun sableFrame(action: String, progress: Float, resting: Boolean = false): SableFrame {
    val t = progress.coerceIn(0f, 1f)
    fun stride() = 2 + ((t * 18).toInt() % 2)
    return when (action) {
        "greet", "wave", "celebrate" -> when {
            t < .18f -> SableFrame(1, -.16f)
            t < .60f -> SableFrame(stride(), -.16f * (1f - (t - .18f) / .42f))
            t < .83f -> SableFrame(1)
            else -> SableFrame(0)
        }
        "bookmark", "inspect", "read" -> when {
            t < .25f -> SableFrame(1, lean = t * 4)
            t < .70f -> SableFrame(4)
            t < .86f -> SableFrame(1)
            else -> SableFrame(if (resting) 7 else 0)
        }
        "pet" -> SableFrame(if (t < .8f) 1 else 0, lean = sin(t * PI).toFloat() * 3f)
        "stretch", "roll" -> SableFrame(if (t in .16f.. .80f) 6 else if (t < .16f) 1 else 0)
        "groom" -> SableFrame(if (t in .12f.. .85f) 5 else 0, lean = sin(t * PI * 5).toFloat())
        "settle" -> when {
            t < .3f -> SableFrame(4)
            t < .5f -> SableFrame(6)
            else -> SableFrame(7)
        }
        else -> SableFrame(if (resting) 7 else 0)
    }
}
