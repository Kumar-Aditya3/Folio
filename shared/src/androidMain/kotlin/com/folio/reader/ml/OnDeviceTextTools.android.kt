package com.folio.reader.ml

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.annotation.VisibleForTesting
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * Phase 6 on Android: ML Kit text recognition + on-device translation.
 *
 * ### Why this is one class and not two
 *
 * OCR and translation are separate ML Kit modules and separate decisions, but they are one
 * *feature* — the reader points at a page and sees words they can read. Splitting them would
 * mean two availability checks and two lifecycle owners for something the UI treats as one
 * action, so they share a seam and share [close].
 *
 * ### Recognisers are cached, translators are cached, and both are closed
 *
 * Each ML Kit recogniser allocates a detector; constructing one per call is a visible stall the
 * first time it happens and a leak if the previous one is never closed. They are therefore held
 * in a [ConcurrentHashMap] keyed by script and released in [close], which the caller is
 * expected to invoke when the screen goes away — the same contract the embedder follows.
 *
 * Translators are keyed by the *language pair*, because ML Kit's translator is stateful: it
 * downloads a model per pair and caches it, so building one per call would re-check and
 * re-fetch the model every time.
 *
 * ### Every call goes through [offUiThread]
 *
 * ML Kit's own callbacks arrive on its own executor, but the callback *plumbing* runs on the
 * caller's thread until it suspends. The plan's rule is that no ML work touches the UI thread,
 * so both `recognise` and `translate` are wrapped. The `BitmapFactory.decodeByteArray` in
 * [recognise] matters most here: decoding a 12 MP page photograph is tens of milliseconds and
 * would be a dropped-frame stall if it happened on Main.
 */
class AndroidOnDeviceTextTools(
    private val dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default,
) : OnDeviceTextTools {

    private val recognizers = ConcurrentHashMap<OcrScript, TextRecognizer>()
    private val translators = ConcurrentHashMap<String, com.google.mlkit.nl.translate.Translator>()

    /**
     * Which scripts this device can use right now.
     *
     * The Latin recogniser is bundled with the app, so it is always present. The other four are
     * separate artifacts and a build that does not include them will throw `NoClassDefFoundError`
     * on first construction — which is exactly what [isScriptReady] probes for, once, and then
     * remembers. Probing by constructing is the only honest check: ML Kit exposes no "is this
     * module on the device" query.
     */
    private val readyScripts = ConcurrentHashMap<OcrScript, Boolean>()

    override suspend fun isAvailable(script: OcrScript): OcrAvailability = withContext(dispatcher) {
        if (isScriptReady(script)) OcrAvailability.Available else OcrAvailability.ScriptMissing(script)
    }

    private fun isScriptReady(script: OcrScript): Boolean = readyScripts.getOrPut(script) {
        runCatching { recognizerFor(script) }.isSuccess
    }

    private fun recognizerFor(script: OcrScript): TextRecognizer =
        recognizers.getOrPut(script) {
            val options = when (script) {
                OcrScript.LATIN -> TextRecognizerOptions.DEFAULT_OPTIONS
                OcrScript.CHINESE -> ChineseTextRecognizerOptions.Builder().build()
                OcrScript.DEVANAGARI -> DevanagariTextRecognizerOptions.Builder().build()
                OcrScript.JAPANESE -> JapaneseTextRecognizerOptions.Builder().build()
                OcrScript.KOREAN -> KoreanTextRecognizerOptions.Builder().build()
            }
            TextRecognition.getClient(options)
        }

    override suspend fun recognise(imageBytes: ByteArray, script: OcrScript): RecognisedText? =
        offUiThread(dispatcher) {
            if (imageBytes.isEmpty()) return@offUiThread null
            val recognizer = runCatching { recognizerFor(script) }.getOrNull() ?: return@offUiThread null

            // Decode first, off the UI thread: a full-page photograph is not small, and
            // `inPreferredConfig = RGB_565` is not used because ML Kit's detector wants
            // 8-bit channels and downsamples internally anyway.
            val bitmap: Bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                ?: return@offUiThread null

            try {
                val image = InputImage.fromBitmap(bitmap, 0)
                val result = recognizer.process(image).await() ?: return@offUiThread null
                val regions = result.textBlocks.flatMap { block ->
                    block.lines.map { line ->
                        val box = line.boundingBox
                        TextRegion(
                            text = line.text,
                            left = box?.left ?: 0,
                            top = box?.top ?: 0,
                            right = box?.right ?: 0,
                            bottom = box?.bottom ?: 0,
                            confidence = line.confidence.takeIf { it >= 0f },
                        )
                    }
                }
                if (regions.isEmpty()) return@offUiThread null
                RecognisedText(regions = regions, text = result.text)
            } finally {
                // The recogniser owns the pixels after `process`; the bitmap is ours to free.
                bitmap.recycle()
            }
        }

    override suspend fun translate(
        text: String,
        targetLanguageTag: String,
        sourceLanguageTag: String?,
    ): TranslatedText? = offUiThread(dispatcher) {
        if (text.isBlank()) return@offUiThread null
        val target = TranslateLanguage.fromLanguageTag(targetLanguageTag) ?: return@offUiThread null
        val translator = runCatching { translatorFor(sourceLanguageTag, target) }.getOrNull()
            ?: return@offUiThread null
        val result = runCatching { translator.translate(text).await() }.getOrNull()
            ?: return@offUiThread null
        TranslatedText(text = result, sourceLanguageTag = sourceLanguageTag)
    }

    /**
     * Builds (and downloads) a translator for the pair.
     *
     * Passing a null source means "detect it", which ML Kit models as the single `UNDEFINED`
     * language requirement rather than a pair — so the key has to encode null explicitly or a
     * detected-source translator and an explicit-source one would collide in the cache.
     */
    private fun translatorFor(sourceLanguageTag: String?, target: String): com.google.mlkit.nl.translate.Translator {
        val source = sourceLanguageTag?.let { TranslateLanguage.fromLanguageTag(it) }
        val key = "${source ?: "auto"}->$target"
        return translators.getOrPut(key) {
            val options = if (source == null) {
                TranslatorOptions.Builder()
                    .setTargetLanguage(target)
                    .build()
            } else {
                TranslatorOptions.Builder()
                    .setSourceLanguage(source)
                    .setTargetLanguage(target)
                    .build()
            }
            Translation.getClient(options)
        }
    }

    /**
     * Fetches the models for a pair, reporting whether they are now available.
     *
     * `DownloadConditions` deliberately requires no network-by-any-means but does *not* require
     * charging: a reader tapping "download Japanese" is asking for it now, unlike the embedding
     * backfill, which is background work and is charging-gated for that reason.
     */
    override suspend fun prepareTranslation(
        sourceLanguageTag: String,
        targetLanguageTag: String,
    ): Boolean = offUiThread(dispatcher) {
        val target = TranslateLanguage.fromLanguageTag(targetLanguageTag) ?: return@offUiThread false
        val source = TranslateLanguage.fromLanguageTag(sourceLanguageTag) ?: return@offUiThread false
        val translator = runCatching { translatorFor(source, target) }.getOrNull()
            ?: return@offUiThread false
        val conditions = DownloadConditions.Builder().build()
        runCatching { translator.downloadModelIfNeeded(conditions).await() }.isSuccess
    }

    /**
     * Releases every cached recogniser and translator.
     *
     * Safe to call more than once, and safe to call while nothing was ever created — the
     * caller closes on dispose without knowing whether it recognised anything.
     */
    override suspend fun close() = withContext(dispatcher) {
        recognizers.values.forEach { runCatching { it.close() } }
        recognizers.clear()
        translators.values.forEach { runCatching { it.close() } }
        translators.clear()
    }

    /** Number of live recognisers, for tests that assert [close] actually releases them. */
    @VisibleForTesting
    internal fun openRecognizerCount(): Int = recognizers.size

    @VisibleForTesting
    internal fun openTranslatorCount(): Int = translators.size

    companion object {
        /**
         * Awaits a `com.google.android.gms.tasks.Task` without pulling in
         * `kotlinx-coroutines-play-services` for one adapter.
         *
         * Returns null on failure rather than throwing. Every failure mode here is a normal
         * outcome the interface already models as null — a blank page, an unsupported script,
         * a language pair with no on-device model — and turning those into exceptions would
         * force every call site into a `runCatching` whose only sensible body is the same
         * `return null`.
         *
         * `suspendCancellableCoroutine` rather than `suspendCoroutine`: a recognition the
         * reader has already navigated away from must not leave a running ML Kit task behind.
         */
        private suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T? =
            suspendCancellableCoroutine { continuation ->
                addOnSuccessListener { value ->
                    if (continuation.isActive) continuation.resume(value)
                }
                addOnFailureListener {
                    if (continuation.isActive) continuation.resume(null)
                }
                addOnCanceledListener {
                    if (continuation.isActive) continuation.resume(null)
                }
            }
    }
}

actual fun onDeviceTextTools(): OnDeviceTextTools = AndroidOnDeviceTextTools()
