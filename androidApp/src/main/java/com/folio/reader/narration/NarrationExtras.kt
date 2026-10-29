package com.folio.reader.narration

import android.content.Context
import android.media.*
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.work.*
import com.folio.reader.model.ReadingPosition
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** Optional assets, auditions and disk work share one native-inference owner with playback. */
internal class NarrationExtras(private val context: Context, private val controller: AndroidNarrationController) {
    private val scope get() = controller.scope
    private val preferences get() = controller.preferences
    private val optionMutable = MutableStateFlow(runCatching {
        Json.decodeFromString(NarrationOptions.serializer(),preferences.getString("deliveryOptions",null) ?: "{}")
    }.getOrDefault(NarrationOptions()))
    val options = optionMutable.asStateFlow()
    private val storageMutable = MutableStateFlow(NarrationStorageState())
    val storage = storageMutable.asStateFlow()
    val inference = Mutex()
    @Volatile var foregroundRequested = false
    @Volatile var preparationCancelled = false
    val cache = NarrationAudioCache(File(context.noBackupFilesDir,"narration-audio")).apply {
        maximumBytes = options.value.cacheMegabytes.coerceIn(32,256) * 1024L * 1024
    }
    private val voiceInstallers = PhononVoices.all.associate { voice -> voice.id to VoicePackInstaller(
        File(context.noBackupFilesDir,"narration-voices"),true,{ check(it.isDirectory) },
        canDownload = {
            val manager = context.getSystemService(ConnectivityManager::class.java)
            val net = manager.getNetworkCapabilities(manager.activeNetwork)
            net?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                (!voiceWifiOnly || net.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || net.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        },spec = VoicePackSpec("${PhononVoices.revision}-${voice.id}",
            "https://huggingface.co/gradium/phonon-voices/resolve/${PhononVoices.revision}/voices/",
            listOf(VoiceAsset("${voice.id}.safetensors",256_136,voice.sha256)))) }
    private var voiceWifiOnly = true
    private val voiceJobs = mutableMapOf<String,Job>()
    private val packsMutable = MutableStateFlow(voiceInstallers.mapValues { it.value.state.value })
    val narratorPacks = packsMutable.asStateFlow()
    private val rules = linkedMapOf<String,MutableStateFlow<List<PronunciationRule>>>()
    private val dictionaryWrites = Mutex()
    private var previewJob: Job? = null
    @Volatile private var previewTrack: AudioTrack? = null
    private val work by lazy { WorkManager.getInstance(context) }
    init {
        voiceInstallers.forEach { (id, installer) -> scope.launch {
            yield()
            installer.state.collect { state ->
                packsMutable.update { it + (id to state) }
                controller.refreshNarratorReady()
            }
        } }
        scope.launch(Dispatchers.IO) { cache.trim(); refreshStorage() }
        scope.launch {
            work.getWorkInfosForUniqueWorkFlow(WORK).collect { list ->
                val info = list.firstOrNull()
                val pending = info?.state in setOf(WorkInfo.State.ENQUEUED,WorkInfo.State.RUNNING,WorkInfo.State.BLOCKED)
                storageMutable.update { it.copy(preparing = pending,message = when(info?.state) {
                    WorkInfo.State.ENQUEUED,WorkInfo.State.BLOCKED -> "Waiting for charging and idle narration"
                    WorkInfo.State.RUNNING -> "Preparing chapter audio"
                    WorkInfo.State.SUCCEEDED -> "Chapter preparation finished"
                    WorkInfo.State.FAILED -> info.outputData.getString("message") ?: "Could not prepare this chapter"
                    else -> null
                }) }
            }
        }
    }
    fun voiceReady(config: NarrationConfiguration): Boolean = config.model == NarrationModel.Kokoro82M ||
        config.phononVoiceId == "default" || voiceInstallers[config.phononVoiceId]?.isInstalled() == true

    fun installVoiceCopy(config: NarrationConfiguration, model: File) {
        if (config.phononVoiceId == "default") return
        val installed = voiceInstallers[config.phononVoiceId] ?: error("Choose a narrator first.")
        check(installed.isInstalled()) { "Download the selected narrator first." }
        val source = File(installed.directory,"${config.phononVoiceId}.safetensors")
        val target = File(model,"voices/${config.phononVoiceId}.safetensors")
        if (target.isFile && target.length() == source.length()) return
        target.parentFile!!.mkdirs()
        val temporary = File(target.parentFile,"${config.phononVoiceId}.part")
        source.copyTo(temporary,overwrite = true)
        check(temporary.renameTo(target)) { "Could not install this narrator." }
    }
    fun downloadNarrator(id: String, wifiOnly: Boolean) {
        if (voiceJobs[id]?.isActive == true) return
        val installer = voiceInstallers[id] ?: return
        voiceWifiOnly = wifiOnly
        voiceJobs[id] = scope.launch { installer.install() }
    }
    fun cancelNarratorDownload(id: String) { voiceJobs[id]?.cancel() }
    fun removeNarrator(id: String) {
        if (id !in voiceInstallers) return
        scope.launch {
            voiceJobs[id]?.cancelAndJoin()
            controller.editNarration {
                inference.withLock { withContext(Dispatchers.IO) {
                    voiceInstallers[id]?.remove()
                    NarrationModel.entries.filter { it != NarrationModel.Kokoro82M }.forEach { model ->
                        File(context.noBackupFilesDir,"narration/${NarrationPacks.forModel(model).revision}/voices/$id.safetensors").delete()
                    }
                } }
            }
        }
    }
    fun setOptions(value: NarrationOptions) {
        val normalized = value.copy(cacheMegabytes = value.cacheMegabytes.coerceIn(32,256),
            prepareWhileCharging = value.prepareWhileCharging && value.audioCache)
        scope.launch {
            suspend fun apply() {
                optionMutable.value = normalized
                preferences.edit().putString("deliveryOptions",Json.encodeToString(NarrationOptions.serializer(),normalized)).apply()
                if (!normalized.prepareWhileCharging) cancelPreparation()
                withContext(Dispatchers.IO) {
                    cache.maximumBytes = normalized.cacheMegabytes * 1024L * 1024
                    cache.trim(); refreshStorage()
                }
            }
            if (normalized.paragraphPhrasing != options.value.paragraphPhrasing || normalized.structuralPauses != options.value.structuralPauses)
                controller.editNarration { apply() } else apply()
        }
    }
    fun pronunciations(bookId: String): StateFlow<List<PronunciationRule>> {
        rules.remove(bookId)?.let { current -> rules[bookId] = current; return current }
        while (rules.size >= 8) {
            val oldest = rules.keys.firstOrNull { it != controller.state.value.bookId } ?: break
            rules.remove(oldest)
        }
        val flow = MutableStateFlow<List<PronunciationRule>>(emptyList())
        rules[bookId] = flow
        scope.launch { dictionaryWrites.withLock { flow.value = readRules(bookId) } }
        return flow
    }
    suspend fun readRules(bookId: String): List<PronunciationRule> = withContext(Dispatchers.IO) {
        runCatching { NarrationDelivery.sanitize(Json.decodeFromString(ListSerializer(PronunciationRule.serializer()),
            controller.graph.settingsRepository.getRaw("narration.pronunciations:$bookId") ?: "[]")) }.getOrDefault(emptyList())
    }
    fun setPronunciations(bookId: String, value: List<PronunciationRule>) {
        scope.launch { dictionaryWrites.withLock {
            val clean = NarrationDelivery.sanitize(value)
            controller.editNarration {
                withContext(Dispatchers.IO) {
                    check(controller.graph.bookRepository.getBook(bookId) != null) { "This book is no longer available." }
                    controller.graph.settingsRepository.setRaw("narration.pronunciations:$bookId",Json.encodeToString(ListSerializer(PronunciationRule.serializer()),clean))
                    cache.clear(bookId)
                }
                (pronunciations(bookId) as MutableStateFlow<List<PronunciationRule>>).value = clean
                refreshStorage()
            }
        } }
    }
    suspend fun refreshStorage() { withContext(Dispatchers.IO) { val size = cache.bytes(); storageMutable.update { it.copy(bytes = size) } } }
    fun clearCache() { cancelPreparation(); scope.launch(Dispatchers.IO) { cache.clear(); refreshStorage() } }
    fun forgetBook(bookId: String) {
        cancelPreparation()
        if (controller.state.value.bookId == bookId) controller.stop()
        scope.launch { dictionaryWrites.withLock { withContext(Dispatchers.IO) {
            cache.clear(bookId)
            controller.graph.database.withConnection { conn ->
                conn.prepareStatement("DELETE FROM settings WHERE key = ?").use { statement ->
                    statement.setString(1,"narration.pronunciations:$bookId"); statement.executeUpdate()
                }
            }
        }; rules.remove(bookId); refreshStorage() } }
    }
    fun prepare(position: ReadingPosition) {
        if (!options.value.audioCache || !options.value.prepareWhileCharging) return
        preparationCancelled = false
        val request = OneTimeWorkRequestBuilder<NarrationPreparationWorker>()
            .setConstraints(Constraints.Builder().setRequiresCharging(true).setRequiresBatteryNotLow(true).build())
            .setInputData(workDataOf("position" to Json.encodeToString(ReadingPosition.serializer(),position.copy(previousPosition = null))))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,java.util.concurrent.TimeUnit.SECONDS).build()
        work.enqueueUniqueWork(WORK,ExistingWorkPolicy.REPLACE,request)
    }
    fun cancelPreparation() { preparationCancelled = true; work.cancelUniqueWork(WORK) }

    /** Engine remains lazy: a fully cached chapter never opens neural weights. Caller owns inference. */
    inner class Session(private val config: NarrationConfiguration, private val directory: File) : AutoCloseable {
        private var engine: NarrationEngine? = null
        fun render(bookId: String, spoken: String, output: (FloatArray) -> Boolean): Boolean {
            val enabled = options.value.audioCache
            val key = cache.key(config,spoken)
            if (enabled) cache.replay(bookId,key,output)?.let { return it }
            val writer = if (enabled) cache.writer(bookId,key) else null
            var complete = true
            var samples = 0L
            try {
                val loaded = engine ?: run {
                    NarrationActivity.inferenceActive = true
                    com.folio.reader.ui.components.clearCoverCache()
                    com.folio.reader.ui.manga.clearMangaCoverCache()
                    com.folio.reader.ui.manga.clearPageBitmapCache()
                    controller.openEngine(config,directory).also { engine = it }
                }
                for (chunk in NarrationDelivery.speechChunks(spoken)) {
                if (!complete) break
                loaded.speak(chunk) { pcm ->
                    val accepted = output(pcm)
                    if (accepted) { samples += pcm.size; writer?.append(pcm) } else complete = false
                    accepted
                }
                }
                check(samples > 0 || !complete) { "This passage produced no speech. Try another voice." }
                if (complete && options.value.audioCache) runCatching { writer?.commit() }
            } finally { writer?.close() }
            return complete
        }
        override fun close() { try { engine?.close() } finally { engine = null; NarrationActivity.inferenceActive = false } }
    }
    fun stopPreview() {
        previewJob?.cancel()
        runCatching { previewTrack?.pause(); previewTrack?.flush() }
    }
    suspend fun stopPreviewAndJoin() { stopPreview(); previewJob?.join(); previewJob = null }
    fun preview() {
        if (previewJob?.isCompleted == false) { stopPreview(); return }
        val config = controller.configuration.value
        if (config.changing || !voiceReady(config) || !controller.installer.isInstalled()) return
        foregroundRequested = true
        previewJob = scope.launch {
            controller.service?.reconfigure { controller.mutable.update { if (it.hasBook) it.copy(phase = NarrationPhase.Paused) else it } }
            controller.cleanup?.join()
            storageMutable.update { it.copy(previewing = true,message = null) }
            val manager = context.getSystemService(AudioManager::class.java)
            val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            val listener = AudioManager.OnAudioFocusChangeListener { if (it != AudioManager.AUDIOFOCUS_GAIN) stopPreview() }
            val noisy = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: android.content.Intent?) { stopPreview() }
            }
            context.registerReceiver(noisy,android.content.IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
            val focus = if (Build.VERSION.SDK_INT >= 26) AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes).setOnAudioFocusChangeListener(listener).build() else null
            try {
                val granted = if (focus != null) manager.requestAudioFocus(focus) else manager.requestAudioFocus(listener,AudioManager.STREAM_MUSIC,AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                check(granted == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "Another app is using audio." }
                withContext(Dispatchers.IO) { inference.withLock {
                    ensureActive()
                    val audio = AudioTrack.Builder().setAudioAttributes(attributes)
                        .setAudioFormat(AudioFormat.Builder().setSampleRate(24_000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build())
                        .setBufferSizeInBytes(24_000).setTransferMode(AudioTrack.MODE_STREAM).build()
                    previewTrack = audio
                    try {
                        check(audio.state == AudioTrack.STATE_INITIALIZED)
                        audio.play()
                        val job = currentCoroutineContext()[Job]!!
                        Session(config,controller.installer.directory).use { session -> session.render("voice-audition",PhononVoices.audition) { pcm ->
                            var offset = 0
                            while (offset < pcm.size && job.isActive) {
                                val n = audio.write(pcm,offset,minOf(1920,pcm.size-offset),AudioTrack.WRITE_BLOCKING)
                                if (n <= 0) break
                                offset += n
                            }
                            job.isActive && offset == pcm.size
                        } }
                        // Drain the small hardware buffer; cancellation still stops immediately.
                        delay(600)
                    } finally { previewTrack = null; audio.release() }
                } }
            } catch (error: Exception) {
                if (error !is CancellationException) storageMutable.update { it.copy(message = error.message ?: "Could not preview this voice") }
            } finally {
                runCatching { context.unregisterReceiver(noisy) }
                if (focus != null) manager.abandonAudioFocusRequest(focus) else manager.abandonAudioFocus(listener)
                foregroundRequested = false
                storageMutable.update { it.copy(previewing = false) }
                refreshStorage()
            }
        }
        val created = previewJob!!
        created.invokeOnCompletion {
            if (previewJob === created) {
                if (!controller.state.value.wantsPlayback) foregroundRequested = false
                storageMutable.update { it.copy(previewing = false) }
            }
        }
    }
    companion object { const val WORK = "narration-prepare-chapter" }
}
