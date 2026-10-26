package com.folio.reader.narration

import kotlin.math.min

/** Credits presented frames, capped by elapsed time. Handles AudioTrack's unsigned 32-bit wrap. */
class ListeningClock(private val sampleRate: Int = 24_000) {
    private var previousFrames = 0L
    private var previousTime: Long? = null
    private var wrap = 0L
    private var fraction = 0.0
    var presentedFrames: Long = 0; private set
    fun sample(nowMs: Long, rawFrames: Long, playing: Boolean, speed: Float): Long {
        val raw = rawFrames and 0xffffffffL
        if (raw < previousFrames && previousFrames - raw > 0x80000000L) wrap += 0x1_0000_0000L
        val absolute = wrap + raw
        val delta = (absolute - presentedFrames).coerceAtLeast(0)
        val wall = previousTime?.let { (nowMs - it).coerceAtLeast(0) } ?: 0
        val credit = if (playing) min(wall.toDouble(), delta * 1000.0 / sampleRate / speed.coerceIn(.75f,2f)) else 0.0
        previousFrames = raw; presentedFrames = absolute; previousTime = nowMs
        val full = credit + fraction
        val result = full.toLong(); fraction = full - result
        return result
    }
}
