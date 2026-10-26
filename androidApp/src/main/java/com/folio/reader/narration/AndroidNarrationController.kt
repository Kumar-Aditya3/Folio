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
import kotlinx.serialization.json.Json
import java.io.File

class AndroidNarrationController(private val context: Context, internal val graph: AppGraph) : NarrationController {
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    internal val mutable = MutableStateFlow(NarrationState())
    override val state = mutable.asStateFlow()
    internal val persistence = NarrationPersistence(graph.database)
    private val preferences = context.getSharedPreferences("narration", Context.MODE_PRIVATE)
    @Volatile internal var wifiOnly = preferences.getBoolean("wifiOnly", true)
    private val supported = Process.is64Bit() && Build.SUPPORTED_ABIS.any { it in setOf("arm64-v8a", "x86_64") } &&
        File(context.applicationInfo.nativeLibraryDir, "libptts_ffi.so").isFile
    internal val installer = VoicePackInstaller(File(context.noBackupFilesDir, "narration"), supported,
        validate = { directory ->
            android.system.Os.setenv("RAYON_NUM_THREADS", "2", true)
            PhononNative(directory, context.applicationInfo.nativeLibraryDir).use { }
        }, canDownload = {
            val manager = context.getSystemService(ConnectivityManager::class.java)
            val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
            capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                (!wifiOnly || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        })
    override val pack = installer.state
    private var downloadJob: Job? = null
    internal var service: NarrationPlaybackService? = null
    internal var cleanup: Job? = null
    init {
        scope.launch {
            if (!installer.isInstalled()) return@launch
            val checkpoint = persistence.latest() ?: return@launch
            val book = withContext(Dispatchers.IO) { graph.bookRepository.getBook(checkpoint.position.bookId) } ?: return@launch
            if (mutable.value.bookId == null) mutable.value = NarrationState(
                phase = if (checkpoint.bookCompleted) NarrationPhase.Ended else NarrationPhase.Paused,
                bookId = book.id, title = book.title, author = book.displayAuthor, coverPath = book.coverPath,
                position = checkpoint.position, blockId = checkpoint.blockId, speed = checkpoint.speed)
        }
    }
    override fun download(wifiOnly: Boolean) {
        if (downloadJob?.isActive == true) return
        this.wifiOnly = wifiOnly; preferences.edit().putBoolean("wifiOnly", wifiOnly).apply()
        downloadJob = scope.launch { installer.install() }
    }
    override fun cancelDownload() { downloadJob?.cancel() }
    override fun removePack() {
        scope.launch {
            downloadJob?.cancelAndJoin()
            cleanup?.join()
            service?.coordinator?.halt(releaseOwnership = true)
            mutable.value = NarrationState()
            installer.remove()
        }
    }
    private fun command(action: String, position: ReadingPosition? = null) {
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
