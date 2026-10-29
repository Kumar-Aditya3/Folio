package com.folio.reader.narration

import com.folio.reader.model.ReadingPosition
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

enum class NarrationPhase { Idle, Preparing, Buffering, Playing, Paused, Seeking, Interrupted, Ended, Error }
enum class VoicePackPhase { Unsupported, NotInstalled, Downloading, Verifying, Installed, Failed, Removing }

enum class NarrationModel(val label: String, val downloadBytes: Long, val attribution: String) {
    Phonon31M("Phonon 31M", 65_051_089, "Gradium · Apache 2.0"),
    Phonon40M("Phonon 40M", 75_092_305, "Gradium · CC BY-NC 4.0 · non-commercial use"),
    Kokoro82M("Kokoro 82M", 132_353_410, "Hexgrad · Apache 2.0 · INT8, v1.0"),
}

data class NarrationVoice(val id: Int, val label: String, val language: String)
object KokoroVoices {
    // IDs are pinned to the v1.0 Sherpa speaker table, not the v1.1 table.
    val all = listOf(NarrationVoice(3,"Heart · American", "en-us"),
        NarrationVoice(2,"Bella · American", "en-us"), NarrationVoice(6,"Nicole · American", "en-us"),
        NarrationVoice(16,"Michael · American", "en-us"), NarrationVoice(21,"Emma · British", "en-gb"),
        NarrationVoice(26,"George · British", "en-gb"))
}
data class NarrationConfiguration(val model: NarrationModel = NarrationModel.Phonon31M,
    val voiceId: Int = 3, val changing: Boolean = false, val phononVoiceId: String = "default", val narratorReady: Boolean = true)
private val defaultNarrationConfiguration = MutableStateFlow(NarrationConfiguration())
private val defaultNarrationOptions = MutableStateFlow(NarrationOptions())
private val defaultNarrationStorage = MutableStateFlow(NarrationStorageState())
private val defaultNarratorPacks = MutableStateFlow<Map<String,VoicePackState>>(emptyMap())
private val defaultPronunciations = MutableStateFlow<List<PronunciationRule>>(emptyList())

data class VoicePackState(val phase: VoicePackPhase = VoicePackPhase.NotInstalled,
    val bytes: Long = 0, val totalBytes: Long = 65_051_089, val error: String? = null)

data class NarrationState(
    val phase: NarrationPhase = NarrationPhase.Idle,
    val bookId: String? = null, val title: String = "", val author: String = "",
    val coverPath: String? = null, val chapterTitle: String = "",
    val position: ReadingPosition? = null, val blockId: String? = null,
    val passage: String = "", val speed: Float = 1f, val followText: Boolean = false,
    val sleepDeadlineMs: Long? = null, val stopAtChapterEnd: Boolean = false,
    val error: String? = null,
) {
    val hasBook: Boolean get() = bookId != null && phase != NarrationPhase.Idle
    val wantsPlayback: Boolean get() = when (phase) {
        NarrationPhase.Preparing, NarrationPhase.Buffering, NarrationPhase.Playing -> true
        else -> false
    }
    val label: String get() = when (phase) {
        NarrationPhase.Idle -> "English narration"
        NarrationPhase.Preparing -> "Preparing narration"
        NarrationPhase.Buffering -> "Buffering"
        NarrationPhase.Playing -> "Listening"
        NarrationPhase.Paused -> "Paused"
        NarrationPhase.Seeking -> "Moving to passage"
        NarrationPhase.Interrupted -> "Playback interrupted"
        NarrationPhase.Ended -> "Book finished"
        NarrationPhase.Error -> "Narration unavailable"
    }
}

/** One command surface shared by reader, mini-player and Android media session. */
interface NarrationController {
    val state: StateFlow<NarrationState>
    val pack: StateFlow<VoicePackState>
    val configuration: StateFlow<NarrationConfiguration> get() = defaultNarrationConfiguration
    val options: StateFlow<NarrationOptions> get() = defaultNarrationOptions
    val storage: StateFlow<NarrationStorageState> get() = defaultNarrationStorage
    val narratorPacks: StateFlow<Map<String,VoicePackState>> get() = defaultNarratorPacks
    fun setOptions(options: NarrationOptions) {}
    fun selectPhononVoice(id: String) {}
    fun downloadNarrator(id: String, wifiOnly: Boolean) {}
    fun cancelNarratorDownload(id: String) {}
    fun removeNarrator(id: String) {}
    fun previewVoice() {}
    fun stopPreview() {}
    fun pronunciations(bookId: String): StateFlow<List<PronunciationRule>> = defaultPronunciations
    fun setPronunciations(bookId: String, rules: List<PronunciationRule>) {}
    fun clearAudioCache() {}
    fun prepareChapter(position: ReadingPosition) {}
    fun cancelPreparation() {}
    fun forgetBook(bookId: String) {}
    fun selectModel(model: NarrationModel) {}
    fun selectVoice(id: Int) {}
    /** Access tokens are used only for this download and must never be persisted. */
    fun downloadWithAccess(wifiOnly: Boolean, token: String) { download(wifiOnly) }
    fun download(wifiOnly: Boolean)
    fun cancelDownload()
    fun removePack()
    fun start(position: ReadingPosition)
    fun play()
    fun pause()
    fun stop()
    fun skip(forward: Boolean)
    fun setSpeed(speed: Float)
    fun setFollowText(follow: Boolean)
    fun setSleepTimer(minutes: Int?, endOfChapter: Boolean = false)
    /** A real visual interaction, never a programmatic follow scroll. */
    fun visualActivity(bookId: String)
    fun visibleSource(bookId: String, spineIndex: Int, blockId: String) {}
}

@Serializable
data class NarrationCheckpoint(val position: ReadingPosition, val blockId: String,
    val passageIndex: Int, val passageHash: String, val frameOffset: Long = 0,
    val speed: Float = 1f, val completed: Boolean = false, val bookCompleted: Boolean = false)

/** Independent summary survives old clients rewriting the session's compatibility fields. */
@Serializable
data class SessionActivity(
    val version: Int = 1, val listeningMs: Long = 0, val followAlongMs: Long = 0,
    val listenedWords: Long = 0, val sittingId: String? = null,
    val timeZoneId: String? = null,
)

/** Process-local interlock. Playback updates it only when audio actually advances. */
object NarrationActivity {
    @Volatile var inferenceActive: Boolean = false
    @Volatile var playbackActive: Boolean = false
    private val owner = MutableStateFlow<String?>(null)
    val ownerBook = owner.asStateFlow()
    var ownerBookId: String?
        get() = owner.value
        set(value) { owner.value = value }
    @Volatile var ownerDeviceId: String? = null
    @Volatile var totalPlaybackMs: Long = 0
}
