package com.folio.reader.narration

import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import java.io.File

internal interface NarrationEngine : AutoCloseable {
    fun speak(text: String, output: (FloatArray) -> Boolean)
}

/** One model per listening session; bounded sentence input and two CPU threads. */
internal class KokoroNative(private val directory: File, private val voiceId: Int) : NarrationEngine {
    private fun path(name: String) = File(directory,name).absolutePath
    private val voice = KokoroVoices.all.first { it.id == voiceId }
    private val engine = OfflineTts(config = OfflineTtsConfig(
        model = OfflineTtsModelConfig(kokoro = OfflineTtsKokoroModelConfig(
            model = path("model.int8.onnx"), voices = path("voices.bin"), tokens = path("tokens.txt"),
            dataDir = path("espeak-ng-data"),
            lexicon = path(if (voice.language == "en-gb") "lexicon-gb-en.txt" else "lexicon-us-en.txt"),
            // eSpeak SetVoiceByName expects a voice file identifier, not a BCP-47 alias.
            // The British voice is lang/gmw/en; en-gb is only its language property.
            lang = if (voice.language == "en-gb") "en" else "en-us"),
            numThreads = 2, provider = "cpu"), maxNumSentences = 1))
    init {
        try {
            // This pinned upstream wrapper predates its null-handle guard. Checking the
            // retained JNI field before any getter prevents a native crash on load failure.
            // The complete Sherpa ABI (including ptr) is explicitly kept by R8.
            check(OfflineTts::class.java.getDeclaredField("ptr").apply { isAccessible = true }.getLong(engine) != 0L) {
                "Could not load Kokoro. Remove the voice pack and download it again."
            }
            check(engine.sampleRate() == 24_000 && engine.numSpeakers() > voiceId) { "Unsupported Kokoro voice pack." }
        } catch (error: Throwable) { engine.release(); throw error }
    }
    /** Sherpa JNI looks up invoke(float[]): Integer by its exact signature. An
     * invokedynamic Kotlin lambda exposes only the erased Function1 bridge. */
    private class AudioCallback(private val output: (FloatArray) -> Boolean) : (FloatArray) -> Int {
        var failure: Throwable? = null
        override fun invoke(samples: FloatArray): Int = try {
            if (output(samples)) 1 else 0
        } catch (error: Throwable) { failure = error; 0 }
    }
    override fun speak(text: String, output: (FloatArray) -> Boolean) {
        val callback = AudioCallback(output)
        engine.generateWithCallback(text, voiceId, 1f, callback)
        callback.failure?.let { throw it }
    }
    override fun close() = engine.release()
}
