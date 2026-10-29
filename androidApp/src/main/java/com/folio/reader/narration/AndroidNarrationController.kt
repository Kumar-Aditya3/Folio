package com.folio.reader.narration

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Process
import androidx.core.content.ContextCompat
import com.folio.reader.AppGraph
import com.folio.reader.model.ReadingPosition
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.io.File

class AndroidNarrationController(private val context: Context, internal val graph: AppGraph) : NarrationController {
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    internal val mutable = MutableStateFlow(NarrationState())
    override val state = mutable.asStateFlow()
    internal val persistence = NarrationPersistence(graph.database)
    internal val preferences = context.getSharedPreferences("narration", Context.MODE_PRIVATE)
    @Volatile internal var wifiOnly = preferences.getBoolean("wifiOnly", true)
    private val supported = Process.is64Bit() && Build.SUPPORTED_ABIS.any { it in setOf("arm64-v8a", "x86_64") } &&
        File(context.applicationInfo.nativeLibraryDir, "libptts_ffi.so").isFile
    private val configurationMutable = MutableStateFlow(NarrationConfiguration(
        model = NarrationModel.entries.firstOrNull { it.name == preferences.getString("model",null) } ?: NarrationModel.Phonon31M,
        voiceId = preferences.getInt("kokoroVoice",3).takeIf { id -> KokoroVoices.all.any { it.id == id } } ?: 3,
        phononVoiceId = preferences.getString("phononVoice","default").orEmpty().takeIf { id -> id == "default" || PhononVoices.all.any { it.id == id } } ?: "default"))
    override val configuration = configurationMutable.asStateFlow()
    private val selection = Mutex()
    private val installers = NarrationModel.entries.associateWith { model ->
        VoicePackInstaller(File(context.noBackupFilesDir, "narration"), supported,
        // Digests validate the complete assets. Opening another neural model here would
        // compete with active playback and double peak memory merely to install a pack.
        validate = { directory -> check(directory.isDirectory) }, canDownload = {
            val manager = context.getSystemService(ConnectivityManager::class.java)
            val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
            capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                (!wifiOnly || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        }, spec = NarrationPacks.forModel(model))
    }
    internal val installer get() = installers.getValue(configuration.value.model)
    private val packMutable = MutableStateFlow(installer.state.value)
    override val pack = packMutable.asStateFlow()
    private var downloadJob: Job? = null
    internal var service: NarrationPlaybackService? = null
    internal var cleanup: Job? = null
    private var restoreJob: Job? = null
    internal fun setPlayerDismissed(dismissed: Boolean) {
        restoreJob?.cancel()
        preferences.edit().putBoolean("playerDismissed", dismissed).apply()
    }
    internal val extras = NarrationExtras(context,this)
    override val options get() = extras.options
    override val storage get() = extras.storage
    override val narratorPacks get() = extras.narratorPacks
    override fun setOptions(options: NarrationOptions) { extras.setOptions(options) }
    override fun selectPhononVoice(id: String) {
        if (id == "default" || PhononVoices.all.any { it.id == id }) changeConfiguration(null,null,id)
    }
    override fun downloadNarrator(id: String, wifiOnly: Boolean) { extras.downloadNarrator(id,wifiOnly) }
    override fun cancelNarratorDownload(id: String) { extras.cancelNarratorDownload(id) }
    override fun removeNarrator(id: String) { extras.removeNarrator(id) }
    override fun previewVoice() { extras.preview() }
    override fun stopPreview() { extras.stopPreview() }
    override fun pronunciations(bookId: String) = extras.pronunciations(bookId)
    override fun setPronunciations(bookId: String, rules: List<PronunciationRule>) { extras.setPronunciations(bookId,rules) }
    override fun clearAudioCache() { extras.clearCache() }
    override fun prepareChapter(position: ReadingPosition) { extras.prepare(position) }
    override fun cancelPreparation() { extras.cancelPreparation() }
    override fun forgetBook(bookId: String) { extras.forgetBook(bookId) }
    internal fun refreshNarratorReady() {
        configurationMutable.value = configuration.value.let { it.copy(narratorReady = extras.voiceReady(it)) }
    }
    /** Content/voice edits use the same command barrier as model changes. */
    internal suspend fun editNarration(change: suspend () -> Unit) = selection.withLock {
        configurationMutable.value = configuration.value.copy(changing = true)
        try {
            extras.cancelPreparation()
            extras.stopPreviewAndJoin()
            cleanup?.join()
            suspend fun apply() {
                change()
                mutable.value = state.value.let { if (it.hasBook && it.phase != NarrationPhase.Ended)
                    it.copy(phase = NarrationPhase.Paused) else it }
            }
            val active = service
            if (active != null) active.reconfigure { apply() } else apply()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            mutable.value = state.value.copy(error = error.message ?: "Could not update narration settings. Try again.")
        } finally {
            configurationMutable.value = configuration.value.let { it.copy(changing = false,narratorReady = extras.voiceReady(it)) }
        }
    }

    init {
        refreshNarratorReady()
        installers.forEach { (model, installer) -> scope.launch {
            installer.state.collect { if (configuration.value.model == model) packMutable.value = it }
        } }
        restoreJob = scope.launch {
            if (preferences.getBoolean("playerDismissed", false) || !installer.isInstalled()) return@launch
            val checkpoint = persistence.latest() ?: return@launch
            val book = withContext(Dispatchers.IO) { graph.bookRepository.getBook(checkpoint.position.bookId) } ?: return@launch
            if (mutable.value.bookId == null) mutable.value = NarrationState(
                phase = if (checkpoint.bookCompleted) NarrationPhase.Ended else NarrationPhase.Paused,
                bookId = book.id, title = book.title, author = book.displayAuthor, coverPath = book.coverPath,
                position = checkpoint.position, blockId = checkpoint.blockId, speed = checkpoint.speed)
        }
    }
    override fun download(wifiOnly: Boolean) {
        downloadWithAccess(wifiOnly, "")
    }
    override fun downloadWithAccess(wifiOnly: Boolean, token: String) {
        if (downloadJob?.isActive == true || configuration.value.changing) return
        this.wifiOnly = wifiOnly; preferences.edit().putBoolean("wifiOnly", wifiOnly).apply()
        val target = installer
        downloadJob = scope.launch { target.install(token.trim()) }
    }
    override fun cancelDownload() { downloadJob?.cancel() }
    override fun removePack() {
        scope.launch { selection.withLock {
            val target = installer
            configurationMutable.value = configuration.value.copy(changing = true)
            try {
            downloadJob?.cancelAndJoin()
            extras.cancelPreparation()
            extras.stopPreviewAndJoin()
            cleanup?.join()
            service?.reconfigure { }
            mutable.value = NarrationState()
            extras.inference.withLock { target.remove() }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.value = state.value.copy(phase = NarrationPhase.Error,error = "Could not remove this voice pack. Try again.")
            } finally { configurationMutable.value = configuration.value.copy(changing = false) }
        } }
    }
    override fun selectModel(model: NarrationModel) = changeConfiguration(model,null)
    override fun selectVoice(id: Int) {
        if (KokoroVoices.all.any { it.id == id }) changeConfiguration(null,id)
    }
    private fun changeConfiguration(model: NarrationModel?, voiceId: Int?, phononVoice: String? = null) {
        scope.launch { selection.withLock {
            val old = configuration.value
            val next = old.copy(model = model ?: old.model, voiceId = voiceId ?: old.voiceId, phononVoiceId = phononVoice ?: old.phononVoiceId)
            if (next == old) return@withLock
            configurationMutable.value = old.copy(changing = true)
            try {
                if (next.model != old.model) downloadJob?.cancelAndJoin()
                extras.stopPreviewAndJoin()
                extras.cancelPreparation()
                cleanup?.join()
                suspend fun apply() {
                    configurationMutable.value = next.copy(changing = true, narratorReady = extras.voiceReady(next))
                    packMutable.value = installer.state.value
                    preferences.edit().putString("model",next.model.name).putInt("kokoroVoice",next.voiceId).putString("phononVoice",next.phononVoiceId).apply()
                    mutable.value = state.value.let { state -> if (state.hasBook && state.phase != NarrationPhase.Ended)
                        state.copy(phase = NarrationPhase.Paused, error = null) else state.copy(error = null) }
                }
                val active = service
                if (active != null) active.reconfigure { apply() } else apply()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.value = state.value.copy(phase = NarrationPhase.Error,error = "Could not change the narration voice. Try again.")
            } finally { configurationMutable.value = configuration.value.copy(changing = false) }
        } }
    }
    internal fun openEngine(config: NarrationConfiguration, directory: File): NarrationEngine = when (config.model) {
        NarrationModel.Kokoro82M -> KokoroNative(directory,config.voiceId)
        else -> {
            extras.installVoiceCopy(config,directory)
            PhononNative(directory,context.applicationInfo.nativeLibraryDir,config.phononVoiceId)
        }
    }
    private fun command(action: String, position: ReadingPosition? = null) {
        if (configuration.value.changing && action != "stop" && action != "pause") return
        if (action in setOf("play","start","next","previous") && (!installer.isInstalled() || !configuration.value.narratorReady)) return
        if (action in setOf("play","start","next","previous")) {
            extras.foregroundRequested = true
            extras.stopPreview()
        }
        val current = service
        if (current != null) {
            current.command(action, position); return
        }
        if (action !in setOf("play","start","next","previous")) return
        if (pack.value.phase != VoicePackPhase.Installed) return
        val intent = Intent(context, NarrationPlaybackService::class.java).setAction(action)
        position?.let { intent.putExtra("position", Json.encodeToString(ReadingPosition.serializer(), it)) }
        try { ContextCompat.startForegroundService(context, intent) }
        catch (error: Exception) { mutable.value = mutable.value.copy(phase = NarrationPhase.Error,
            error = "Open Folio and try playback again.") }
    }
    @Volatile private var visible: Triple<String,Int,String>? = null
    override fun visibleSource(bookId: String, spineIndex: Int, blockId: String) {
        visible = Triple(bookId,spineIndex,blockId)
    }
    override fun start(position: ReadingPosition) {
        val anchor = visible?.takeIf { it.first == position.bookId && !position.contentLocator.startsWith("selection:") }
        command("start", if (anchor != null) position.copy(spineIndex = anchor.second, contentLocator = "n:${anchor.third}",characterOffset = 0)
            else position)
    }
    override fun play() = command("play")
    override fun pause() = command("pause")
    override fun stop() {
        setPlayerDismissed(true)
        extras.stopPreview()
        extras.foregroundRequested = false
        if (service != null) command("stop") else scope.launch {
            cleanup?.join()
            NarrationActivity.ownerBookId = null
            mutable.value = NarrationState(speed = state.value.speed,position = state.value.position)
        }
    }
    override fun skip(forward: Boolean) = command(if (forward) "next" else "previous")
    override fun setSpeed(speed: Float) {
        val value = speed.coerceIn(.75f, 2f)
        if (service?.coordinator?.speedChanged(value) != false) mutable.value = mutable.value.copy(speed = value,error = null)
        else mutable.value = mutable.value.copy(error = "This audio output does not support that speed.")
    }
    override fun setFollowText(follow: Boolean) { mutable.value = mutable.value.copy(followText = follow) }
    override fun setSleepTimer(minutes: Int?, endOfChapter: Boolean) {
        mutable.value = mutable.value.copy(sleepDeadlineMs = minutes?.let { android.os.SystemClock.elapsedRealtime() + it * 60_000L }, stopAtChapterEnd = endOfChapter)
    }
    @Volatile internal var lastVisualBook: String? = null
    @Volatile internal var lastVisualAt: Long = 0
    override fun visualActivity(bookId: String) {
        lastVisualBook = bookId; lastVisualAt = android.os.SystemClock.elapsedRealtime()
    }
}
