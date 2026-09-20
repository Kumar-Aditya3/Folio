package com.folio.reader.ml

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Phase 6 — OCR and translation, behind a platform seam.
 *
 * ### The decision the plan asked for
 *
 * `ML_PLAN.md` Phase 6 says: *"`androidMain` only — there is no desktop equivalent, so this
 * needs an `expect`/`actual` with a desktop no-op. **Decide explicitly whether losing this on
 * desktop is acceptable.**"*
 *
 * **Decision: yes, losing OCR and translation on desktop is acceptable, and the feature ships
 * Android-only.** The reasoning, recorded here because the plan asked for it explicitly rather
 * than because the answer is comfortable:
 *
 * 1. **Desktop already has the better tool, off-app.** OCR on a desktop is a solved problem
 *    with better OCR than ML Kit offers — Tesseract, `pdftotext`, Preview's built-in text
 *    selection, any PDF reader. A reader who needs a scanned PDF read on a laptop has no
 *    reason to want this app to do it. On Android there is no equivalent sitting in the OS,
 *    which is why the feature is worth building there at all.
 * 2. **ML Kit has no desktop artifact — full stop.** This is not a "not yet" that a
 *    workaround hides. `com.google.mlkit:*` is an Android AAR; the on-device translation
 *    models are Play-Services-delivered. A desktop equivalent would mean shipping a different
 *    OCR engine *and* a different translation engine — two new dependencies, two new model
 *    delivery paths, and a second implementation of this entire file for a platform that
 *    already has both.
 * 3. **The corpus does not need it.** The desktop app's job here is the library — EPUBs,
 *    which are text by construction. The scanned-PDF and manga-page cases are both
 *    phone-shaped: a camera roll of photographed pages, a downloaded manga chapter.
 * 4. **The seam is honest about the gap.** [OnDeviceTextTools.isAvailable] returns false on
 *    desktop and the UI hides the affordance rather than showing a button that does nothing.
 *    That is the same rule semantic search follows for a missing model. A feature that
 *    silently does nothing is worse than one that is absent.
 *
 * This is reversible. If desktop OCR is ever wanted, `desktopMain` gets a real actual and
 * nothing in the shared layer changes — which is the point of putting the seam here.
 *
 * ### Scripts
 *
 * Text recognition is one model per script and the models are not small, so [OcrScript] is an
 * enumerable choice rather than "recognise everything": a reader who never opens a Japanese
 * book should not pay for the Japanese model. [OcrScript.LATIN] is the only one shipped as a
 * hard dependency; the others are opt-in, and [OnDeviceTextTools.isScriptReady] reports which
 * are actually on the device so the UI can offer to fetch one.
 *
 * ### Where the text goes
 *
 * Recognition returns [TextRegion]s with bounding boxes, not a flat string, because the two
 * consumers want different things: a document wants reading order (the boxes let the caller
 * sort a two-column page correctly), and a manga bubble wants *position* (so a translation
 * can be drawn back over the bubble it came from). Flattening to a string here would throw
 * away the only thing that makes the manga case tractable later.
 */
enum class OcrScript(val label: String) {
    /** The default. Covers English and most European languages. */
    LATIN("Latin"),
    CHINESE("Chinese"),
    DEVANAGARI("Devanagari"),
    JAPANESE("Japanese"),
    KOREAN("Korean"),
}

/** One recognised piece of text and where it was. Boxes are in source-image pixels. */
data class TextRegion(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    /** ML Kit's confidence, 0..1, or null when the recogniser does not report one. */
    val confidence: Float? = null,
) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
}

/** A recognised script, ready to translate. */
data class RecognisedText(
    val regions: List<TextRegion>,
    /** Joins the regions in the order the recogniser returned them (see the class doc). */
    val text: String,
)

/**
 * What the platform can actually do.
 *
 * Modelled as a sealed type rather than a boolean pair so "the whole feature is absent here"
 * (desktop) and "this script is not downloaded yet" (Android, opt-in model) stay separate
 * sentences in the UI. Collapsing them would put a "download the Japanese model" prompt on a
 * desktop that can never use it.
 */
sealed interface OcrAvailability {
    data object Available : OcrAvailability
    data class ScriptMissing(val script: OcrScript) : OcrAvailability
    data object UnsupportedOnThisPlatform : OcrAvailability

    val isUsable: Boolean get() = this is Available
}

/** Translation result: the text, plus what language it was detected as. */
data class TranslatedText(val text: String, val sourceLanguageTag: String?)

/**
 * The platform seam for Phase 6.
 *
 * Every method is `suspend` even where the underlying ML Kit call is not, because both APIs
 * are genuinely asynchronous (model download, on-device inference) and a synchronous signature
 * would invite a caller to block a frame. Implementations must move off the calling thread.
 */
interface OnDeviceTextTools {

    /**
     * What this platform can do for [script].
     *
     * Callers should treat anything but [OcrAvailability.Available] as "do not show the
     * affordance", not as an error — this is a normal state on desktop and on a first run.
     */
    suspend fun isAvailable(script: OcrScript = OcrScript.LATIN): OcrAvailability

    /**
     * Recognises text in an image.
     *
     * @param imageBytes the encoded image (PNG/JPEG), exactly as stored on disk. Bytes rather
     *   than a platform image type because this interface is in `commonMain`, where no
     *   platform image exists.
     * @return the recognised regions, or null when OCR is unavailable or nothing was found —
     *   the two are distinguished by [isAvailable], which the caller already asked.
     */
    suspend fun recognise(imageBytes: ByteArray, script: OcrScript = OcrScript.LATIN): RecognisedText?

    /**
     * Translates [text] from [sourceLanguageTag] into [targetLanguageTag], or detects the
     * source when [sourceLanguageTag] is null.
     *
     * BCP-47 tags (`"en"`, `"ja"`, `"zh"`), not display names: that is what ML Kit takes and
     * what a settings row can round-trip without a lookup table.
     *
     * @return null when translation is unavailable, or when the language pair is not one the
     *   on-device models cover — the caller shows the original text and a short reason.
     */
    suspend fun translate(
        text: String,
        targetLanguageTag: String,
        sourceLanguageTag: String? = null,
    ): TranslatedText?

    /**
     * Whether the models for a language pair are on the device, and if not, fetching them.
     *
     * Separated from [translate] so a settings screen can prepare the download ahead of use
     * and report progress, while the reader path just calls [translate] and takes the null.
     */
    suspend fun prepareTranslation(sourceLanguageTag: String, targetLanguageTag: String): Boolean

    suspend fun close()
}

/**
 * A no-op implementation, used by desktop and available to tests.
 *
 * [isAvailable] reports [OcrAvailability.UnsupportedOnThisPlatform] — not a lie, and not an
 * error either: it is the fact that desktop has no ML Kit. Everything else returns null
 * rather than throwing, so a caller that skipped the availability check degrades to "no
 * result" instead of a crash.
 */
class NoOpOnDeviceTextTools : OnDeviceTextTools {

    override suspend fun isAvailable(script: OcrScript): OcrAvailability =
        OcrAvailability.UnsupportedOnThisPlatform

    override suspend fun recognise(imageBytes: ByteArray, script: OcrScript): RecognisedText? = null

    override suspend fun translate(
        text: String,
        targetLanguageTag: String,
        sourceLanguageTag: String?,
    ): TranslatedText? = null

    override suspend fun prepareTranslation(sourceLanguageTag: String, targetLanguageTag: String): Boolean = false

    override suspend fun close() = Unit
}

/**
 * Wraps a call so it can never run on the calling thread.
 *
 * The plan's cross-cutting rule — *"every ML call off the UI thread"* — is a rule about the
 * implementation, not the interface, so each actual has to honour it. This helper exists so
 * they cannot forget: it is not possible to write the Android actual without going through it.
 */
internal suspend fun <T> offUiThread(
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    block: suspend () -> T,
): T = withContext(dispatcher) { block() }

/**
 * The platform's text tools.
 *
 * `expect`/`actual` function rather than a property on an `expect object`, so each platform
 * controls its own lifetime: the Android side holds live ML Kit recognisers that must be
 * closed, and desktop has nothing to hold. Callers [OnDeviceTextTools.close] when their screen
 * goes away, exactly as they do with the embedder.
 */
expect fun onDeviceTextTools(): OnDeviceTextTools
