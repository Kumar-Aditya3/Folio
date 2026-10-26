package com.folio.reader.narration

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import java.io.File

/** Adapted from gradium-ai/xn-ptts android/PhononTTS.kt (MIT OR Apache-2.0).
 * Pinned source revision is recorded in PhononPack. One worker owns every native call.
 */
internal class PhononNative(model: File, nativeDirectory: String) : AutoCloseable {
    private val lib = Native.load(File(nativeDirectory, "libptts_ffi.so").absolutePath, Api::class.java)
    private var handle: Pointer? = lib.ptts_new(model.absolutePath, 0, "en")
        ?: error(lib.ptts_last_error(null) ?: "Could not load English narration.")
    fun speak(text: String, output: (FloatArray) -> Boolean) {
        var failure: Throwable? = null
        val callback = Api.Frame { data, count, _ ->
            try {
                check(count in 1..240_000) { "Invalid speech frame size." }
                if (output(data.getFloatArray(0, count.toInt()))) 1.toByte() else 0.toByte()
            } catch (error: Throwable) { failure = error; 0.toByte() }
        }
        val result = Timings()
        val success = lib.ptts_speak(handle!!, text, callback, null, result)
        failure?.let { throw it }
        check(success.toInt() != 0) { lib.ptts_last_error(handle) ?: "Speech generation failed." }
    }
    override fun close() { handle?.let { lib.ptts_free(it) }; handle = null }

    @Structure.FieldOrder("frames", "samples", "ttfaMs", "totalMs")
    class Timings : Structure() {
        @JvmField var frames = 0
        @JvmField var samples = 0L
        @JvmField var ttfaMs = 0.0
        @JvmField var totalMs = 0.0
    }
    interface Api : Library {
        fun ptts_new(dir: String, unit: Int, lang: String): Pointer?
        fun ptts_speak(handle: Pointer, text: String, callback: Frame, user: Pointer?, result: Timings): Byte
        fun ptts_free(handle: Pointer)
        fun ptts_last_error(handle: Pointer?): String?
        fun interface Frame : Callback { fun invoke(samples: Pointer, count: Long, user: Pointer?): Byte }
    }
}
