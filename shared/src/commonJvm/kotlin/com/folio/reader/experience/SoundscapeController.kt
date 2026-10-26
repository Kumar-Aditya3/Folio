package com.folio.reader.experience

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sqrt

interface PcmOutput : AutoCloseable {
    fun start()
    fun write(samples: ShortArray)
    fun pause()
}

data class SoundscapeState(val preferences: SoundscapePreferences = SoundscapePreferences(),
    val playing: Boolean = false, val remainingMs: Long? = null, val volume: Float = .35f,
    val error: String? = null)

/** Audio I/O and PCM buffers belong exclusively to the worker, never the command lock. */
class SoundscapeController(private val load: (Soundscape) -> ShortArray, private val output: () -> PcmOutput) : AutoCloseable {
    private val mutable = MutableStateFlow(SoundscapeState())
    val state = mutable.asStateFlow()
    private val signal = MutableStateFlow(0f)
    val atmosphereSignal = signal.asStateFlow()
    private val lock = java.lang.Object()
    @Volatile private var closed = false
    @Volatile private var eligible = false
    @Volatile private var playbackEpoch = 0L
    private var releaseEpoch = 0L
    private var positionEpoch = 0L
    private var timerEpoch = 0L
    private var remainingSamples: Long? = null
    private var worker: Thread? = null

    fun restore(preferences: SoundscapePreferences) = synchronized(lock) {
        if (closed) return@synchronized
        val normalized = preferences.normalized()
        if (normalized == mutable.value.preferences) return@synchronized
        val timerChanged = normalized.timerMinutes != mutable.value.preferences.timerMinutes
        mutable.value = mutable.value.copy(preferences = normalized, error = null)
        if (timerChanged) resetTimer(normalized.timerMinutes)
        if (normalized.scene == Soundscape.OFF) off()
        lock.notifyAll()
    }
    fun select(preferences: SoundscapePreferences) = synchronized(lock) {
        restore(preferences)
        if (preferences.scene != Soundscape.OFF) play()
    }
    fun setVolume(value: Float) = synchronized(lock) {
        mutable.value = mutable.value.copy(volume = value.coerceIn(0f, 1f))
        if (value <= 0f) signal.value = 0f
    }
    fun setEligible(value: Boolean) = synchronized(lock) { eligible = value; if (!value) pause() }
    fun play() = synchronized(lock) {
        if (!eligible || closed || mutable.value.preferences.scene == Soundscape.OFF) return@synchronized
        if (remainingSamples == null && mutable.value.preferences.timerMinutes > 0) resetTimer(mutable.value.preferences.timerMinutes)
        mutable.value = mutable.value.copy(playing = true, error = null)
        if (worker?.isAlive != true) worker = Thread(::runMixer, "Folio soundscape").apply { isDaemon = true; start() }
        lock.notifyAll()
    }
    fun pause() = synchronized(lock) {
        if (mutable.value.playing) { playbackEpoch++; mutable.value = mutable.value.copy(playing = false) }
        signal.value = 0f
        lock.notifyAll()
    }
    fun off() = synchronized(lock) {
        pause(); releaseEpoch++; positionEpoch++; timerEpoch++; remainingSamples = null
        mutable.value = mutable.value.copy(preferences = mutable.value.preferences.copy(scene = Soundscape.OFF), remainingMs = null)
        lock.notifyAll()
    }
    /** Resource disposal runs on the mixer; callers never wait for a device write or asset load. */
    fun releaseOutput() = synchronized(lock) { pause(); releaseEpoch++; lock.notifyAll() }
    private fun resetTimer(minutes: Int) {
        timerEpoch++
        remainingSamples = minutes.takeIf { it > 0 }?.toLong()?.times(60L * RATE)
        mutable.value = mutable.value.copy(remainingMs = remainingSamples?.times(1000)?.div(RATE))
    }
    private data class Command(val state: SoundscapeState, val remaining: Long?, val playback: Long,
        val release: Long, val position: Long, val timer: Long)
    private fun command() = synchronized(lock) { Command(mutable.value, remainingSamples, playbackEpoch, releaseEpoch, positionEpoch, timerEpoch) }
    private fun mayWrite(command: Command): Boolean {
        val current = mutable.value
        return !closed && eligible && current.playing && playbackEpoch == command.playback &&
            current.preferences.scene == command.state.preferences.scene && current.preferences.secondary == command.state.preferences.secondary
    }
    private fun runMixer() {
        var sink: PcmOutput? = null
        var running = false
        val clips = arrayOfNulls<ShortArray>(Soundscape.entries.size)
        val positions = IntArray(clips.size)
        val gains = FloatArray(clips.size)
        val targets = FloatArray(clips.size)
        val samples = ShortArray(BLOCK)
        var seenPlayback = -1L; var seenRelease = -1L; var seenPosition = -1L
        var frames = 0
        var envelope = 0f
        fun stopOutput(release: Boolean) {
            if (running) { runCatching { sink?.pause() }; running = false }
            if (release) { runCatching { sink?.close() }; sink = null; clips.fill(null) }
            gains.fill(0f); envelope = 0f; signal.value = 0f
        }
        try {
            while (!closed) {
                val command = command()
                if (seenPlayback != command.playback || seenRelease != command.release) {
                    stopOutput(seenRelease != command.release)
                    seenPlayback = command.playback; seenRelease = command.release
                }
                if (seenPosition != command.position) { positions.fill(0); seenPosition = command.position }
                if (!command.state.playing || !eligible) {
                    synchronized(lock) {
                        if (!closed && playbackEpoch == command.playback && releaseEpoch == command.release && !mutable.value.playing) lock.wait()
                    }
                    continue
                }
                try {
                    val p = command.state.preferences
                    targets.fill(0f)
                    targets[p.scene.ordinal] = p.primaryLevel
                    if (p.secondary != Soundscape.OFF && p.secondary != p.scene) targets[p.secondary.ordinal] = p.secondaryLevel
                    targets[0] = 0f
                    // Load each required clip once, outside the command lock and sample loop.
                    for (index in 1 until clips.size) if ((targets[index] > 0f || gains[index] > .00001f) && clips[index] == null) {
                        clips[index] = load(Soundscape.entries[index]).also { require(it.isNotEmpty()) }
                        positions[index] %= clips[index]!!.size
                    }
                    if (!mayWrite(command)) continue
                    if (sink == null) sink = output()
                    if (!mayWrite(command)) continue
                    if (!running) { sink!!.start(); running = true }
                    if (!mayWrite(command)) continue
                    var remaining = command.remaining ?: -1L
                    var energy = 0.0
                    for (sample in samples.indices) {
                        var mixed = 0f; var gainSum = 0f
                        for (index in 1 until clips.size) {
                            val gain = (gains[index] + (targets[index] - gains[index]).coerceIn(-GAIN_STEP, GAIN_STEP)).also { gains[index] = it }
                            if (gain <= .00001f) continue
                            val clip = clips[index] ?: continue
                            val position = positions[index]
                            mixed += clip[position] * gain; gainSum += gain
                            positions[index] = if (position + 1 == clip.size) 0 else position + 1
                        }
                        val fade = if (remaining < 0) 1f else (remaining / FADE_SAMPLES).coerceIn(0f, 1f)
                        val value = (mixed / maxOf(1f, gainSum) * command.state.volume * fade).coerceIn(-32767f, 32767f)
                        samples[sample] = value.toInt().toShort(); energy += value * value
                        if (remaining > 0) remaining--
                    }
                    if (!mayWrite(command)) continue
                    sink!!.write(samples) // Deliberately outside lock: UI commands never wait on hardware.
                    frames++
                    synchronized(lock) {
                        if (mayWrite(command)) {
                            if (timerEpoch == command.timer) remainingSamples = remaining.takeIf { it >= 0 }
                            val millis = remainingSamples?.times(1000)?.div(RATE)
                            // Countdown labels need one update per second; visual response has its own flow.
                            if (millis?.div(1000) != mutable.value.remainingMs?.div(1000)) mutable.value = mutable.value.copy(remainingMs = millis)
                            if (frames % 5 == 0) {
                                val rms = (sqrt(energy / BLOCK) / 12000).toFloat().coerceIn(0f, 1f)
                                envelope = envelope * .95f + rms * .05f
                                signal.value = if (mutable.value.volume > 0f) envelope else 0f
                            }
                            if (remainingSamples == 0L) {
                                pause(); remainingSamples = null
                                mutable.value = mutable.value.copy(remainingMs = 0)
                            }
                        }
                    }
                } catch (error: Exception) {
                    stopOutput(true)
                    synchronized(lock) {
                        if (mayWrite(command)) {
                            pause()
                            mutable.value = mutable.value.copy(error = "Sound unavailable. Check your audio device and retry.")
                        }
                    }
                }
            }
        } finally { stopOutput(true) }
    }
    override fun close() = synchronized(lock) { pause(); closed = true; releaseEpoch++; lock.notifyAll() }
    companion object {
        const val RATE = 44_100
        const val BLOCK = 882
        private const val GAIN_STEP = 1f / (RATE * .4f)
        private const val FADE_SAMPLES = RATE * 10f
    }
}

/** Only our vetted little-endian PCM assets are accepted; RIFF chunks may include metadata. */
fun readSoundscapeWav(bytes: ByteArray): ShortArray {
    fun int(at: Int) = (0..3).fold(0) { value, i -> value or ((bytes[at + i].toInt() and 255) shl (8 * i)) }
    require(bytes.size >= 44 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE")
    var offset = 12
    var validFormat = false
    while (offset + 8 <= bytes.size) {
        val size = int(offset + 4); require(size >= 0 && size <= bytes.size - offset - 8)
        when (String(bytes, offset, 4)) {
            "fmt " -> { require(size >= 16); validFormat = bytes[offset + 8] == 1.toByte() && bytes[offset + 10] == 1.toByte() && int(offset + 12) == SoundscapeController.RATE && bytes[offset + 22] == 16.toByte() }
            "data" -> { require(validFormat && size % 2 == 0); return ShortArray(size / 2) { i ->
                ((bytes[offset + 8 + i * 2].toInt() and 255) or (bytes[offset + 9 + i * 2].toInt() shl 8)).toShort()
            } }
        }
        offset += 8 + size + size % 2
    }
    error("Missing PCM data")
}
