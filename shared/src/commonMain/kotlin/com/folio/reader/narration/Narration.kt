package com.folio.reader.narration

import com.folio.reader.model.ReadingPosition
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

enum class NarrationPhase { Idle, Preparing, Buffering, Playing, Paused, Seeking, Interrupted, Ended, Error }
enum class VoicePackPhase { Unsupported, NotInstalled, Downloading, Verifying, Installed, Failed, Removing }

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
    val wantsPlayback: Boolean get() = phase in setOf(NarrationPhase.Preparing, NarrationPhase.Buffering, NarrationPhase.Playing)
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
    @Volatile var playbackActive: Boolean = false
    @Volatile var ownerBookId: String? = null
    @Volatile var ownerDeviceId: String? = null
    @Volatile var totalPlaybackMs: Long = 0
}
