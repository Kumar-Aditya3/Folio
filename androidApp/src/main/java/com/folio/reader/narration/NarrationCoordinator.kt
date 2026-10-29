package com.folio.reader.narration

import android.content.*
import android.media.*
import android.os.*
import com.folio.reader.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.*
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/** Service lifetime, not composition lifetime. Native work and output have distinct workers. */
internal class NarrationCoordinator(private val service: NarrationPlaybackService,
    private val controller: AndroidNarrationController) {
    private val scope = controller.scope
    private val commands = Mutex()
    private val commits = Mutex()
    private val epoch = AtomicLong()
    private var producer: Job? = null
    private var consumer: Job? = null
    private var observer: Job? = null
    @Volatile private var track: AudioTrack? = null
    private val pauseGate = MutableStateFlow(true)
    private var paused: Boolean
        get() = pauseGate.value
        set(value) { pauseGate.value = value }
    private var frames: Channel<Frame>? = null
    private var audioWritable: Channel<Unit>? = null
    @Volatile private var audioStarted = false
    @Volatile private var producedAll = false
    @Volatile private var written = 0L
    private val markers = ConcurrentLinkedQueue<Marker>()
    private var current: Source? = null
    private var checkpoint: NarrationCheckpoint? = null
    private var span: ReadingSession? = null
    private var sitting = UUID.randomUUID().toString()
    private var cycleId: String? = null
    private var lastHead = 0L
    private var clock = ListeningClock()
    private var lastTick = SystemClock.elapsedRealtime()
    private var lastSave = lastTick
    private var lastSync = lastTick
    private var lastDate: LocalDate? = null
    private var pausedAt = 0L
    private val manager = service.getSystemService(AudioManager::class.java)
    private val wake = service.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Folio:Narration").apply { setReferenceCounted(false) }
    private var resumeAfterFocus = false
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        scope.launch {
            if (change == AudioManager.AUDIOFOCUS_GAIN) {
                if (resumeAfterFocus) { resumeAfterFocus = false; play() }
            } else {
                val shouldResume = change in setOf(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) && controller.state.value.wantsPlayback
                pause(interrupted = true); resumeAfterFocus = shouldResume
            }
        }
    }
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = if (Build.VERSION.SDK_INT >= 26) AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes).setOnAudioFocusChangeListener(focusListener).setWillPauseWhenDucked(true).build() else null
    private val noisy = object : BroadcastReceiver() { override fun onReceive(c: Context?, i: Intent?) {
        scope.launch { pause(interrupted = true) }
    } }
    init { service.registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)) }

    private data class Source(val passage: NarrationPassage, val index: Int, val chapter: Chapter,
        val start: ReadingPosition, val end: ReadingPosition, val lastInChapter: Boolean)
    private data class Marker(val frame: Long, val source: Source, val end: Boolean)
    private sealed interface Frame {
        data class Start(val source: Source) : Frame
        data class Samples(val values: FloatArray, val offset: Int, val count: Int) : Frame
        data class End(val source: Source) : Frame
    }

    fun speedChanged(value: Float): Boolean = runCatching { track?.playbackParams = PlaybackParams().setSpeed(value).setPitch(1f); true }.getOrDefault(false)

    suspend fun start(position: ReadingPosition, restore: Boolean = false, playImmediately: Boolean = true) = commands.withLock {
        if (controller.state.value.bookId != position.bookId) sitting = UUID.randomUUID().toString()
        haltInternal(false)
        try {
            check(controller.installer.isInstalled()) { "Download English narration first." }
            controller.extras.stopPreviewAndJoin()
            controller.extras.foregroundRequested = true
            val configuration = controller.configuration.value
            check(configuration.narratorReady) { "Download the selected narrator first." }
            val delivery = controller.options.value
            val rules = controller.extras.readRules(position.bookId)
            val modelDirectory = controller.installer.directory
            val book = withContext(Dispatchers.IO) { controller.graph.bookRepository.getBook(position.bookId) }
                ?: error("This book is no longer available.")
            val chapters = withContext(Dispatchers.IO) { controller.graph.bookRepository.getChaptersForBook(book.id) }
                .sortedBy { it.spineIndex }
            check(chapters.isNotEmpty()) { "This book has no readable chapters." }
            cycleId = withContext(Dispatchers.IO) {
                val repository = controller.graph.readingCycleRepository
                repository.getCurrentCycle(book.id)?.id ?: run {
                    val id = UUID.randomUUID().toString()
                    repository.insertCycle(ReadingCycle(id,book.id,(repository.getCyclesForBook(book.id).maxOfOrNull { it.cycleNumber } ?: 0)+1,Clock.System.now()))
                    id
                }
            }
            val saved = if (restore) controller.persistence.load(book.id) else null
            val origin = (saved?.position ?: position).copy(deviceId = controller.graph.deviceId, previousPosition = null)
            checkpoint = saved ?: NarrationCheckpoint(origin,"",0,"")
            current = null; span = null; lastDate = null
            controller.setPlayerDismissed(false)
            controller.mutable.value = controller.state.value.copy(phase = NarrationPhase.Preparing,
                bookId = book.id, title = book.title, author = book.displayAuthor, coverPath = book.coverPath,
                position = origin, chapterTitle = chapters.firstOrNull { it.spineIndex == origin.spineIndex }?.title.orEmpty(),
                error = null, blockId = null, passage = "")
            withContext(Dispatchers.IO) { controller.graph.database.withConnection {
                NarrationActivity.ownerDeviceId = controller.graph.deviceId
                NarrationActivity.ownerBookId = book.id
            } }
            com.folio.reader.ui.components.clearCoverCache()
            com.folio.reader.ui.manga.clearMangaCoverCache()
            com.folio.reader.ui.manga.clearPageBitmapCache()
            if (playImmediately) {
                val granted = if (Build.VERSION.SDK_INT >= 26) manager.requestAudioFocus(focus!!)
                    else manager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
                check(granted == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "Another app is using audio. Try again." }
            }
            val audio = AudioTrack.Builder().setAudioAttributes(attributes)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(24_000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build())
                .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(maxOf(24_000,
                    AudioTrack.getMinBufferSize(24_000,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_FLOAT))).build()
            check(audio.state == AudioTrack.STATE_INITIALIZED) { "Could not open audio output." }
            track = audio; paused = !playImmediately; pausedAt = SystemClock.elapsedRealtime(); producedAll = false; written = 0; lastHead = 0
            val writable = Channel<Unit>(Channel.CONFLATED)
            audioWritable = writable
            audio.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(track: AudioTrack) {}
                override fun onPeriodicNotification(track: AudioTrack) { writable.trySend(Unit) }
            }, Handler(Looper.getMainLooper()))
            audio.positionNotificationPeriod = 480 // Wake the writer on consumed audio, not a 5 ms timer.
            clock = ListeningClock().apply { sample(SystemClock.elapsedRealtime(),0,false,1f) }
            lastTick = SystemClock.elapsedRealtime(); lastSave = lastTick; lastSync = lastTick
            speedChanged(controller.state.value.speed)
            if (!paused && !wake.isHeld) wake.acquire()
            audioStarted = false
            val generation = epoch.get()
            val generatedSamples = AtomicLong()
            val generationFinished = java.util.concurrent.atomic.AtomicBoolean()
            val channel = Channel<Frame>(128) // At 80 ms/frame, approximately 10 seconds of PCM.
            frames = channel
            // Native callbacks are synchronous. Cancelling the channel during halt unblocks
            // this wait before joining the producer, including while output is paused.
            fun send(frame: Frame): Boolean = epoch.get() == generation && channel.trySendBlocking(frame).isSuccess
            producer = scope.launch(Dispatchers.IO) {
                try {
                    android.system.Os.setenv("RAYON_NUM_THREADS", "2", true)
                    controller.extras.inference.withLock { controller.extras.Session(configuration, modelDirectory).use { session ->
                        val totalWords = chapters.sumOf { it.wordCount.coerceAtLeast(0) }.coerceAtLeast(1)
                        var wordsBefore = chapters.takeWhile { it.spineIndex < origin.spineIndex }.sumOf { it.wordCount.coerceAtLeast(0) }
                        for (chapter in chapters.filter { it.spineIndex >= origin.spineIndex }) {
                            if (epoch.get() != generation) break
                            val html = controller.graph.contentProvider.getHtml(book.id, chapter.href)
                            check(!com.folio.reader.ui.render.FixedLayoutDetector.detect(html).isFixedLayout) {
                                "Narration currently supports reflowable text chapters."
                            }
                            val document = NarrationDelivery.plan(NarrationText.prepare(html),delivery)
                            val initial = if (chapter.spineIndex != origin.spineIndex) 0 else {
                                val exact = saved?.takeIf { it.passageHash.isNotEmpty() }?.let { cp ->
                                    val exactIndex = cp.passageIndex.takeIf { idx -> document.passages.getOrNull(idx)?.let {
                                        it.hash == cp.passageHash && it.blockId == cp.blockId } == true }
                                        ?: document.passages.indexOfFirst { it.hash == cp.passageHash && it.blockId == cp.blockId }.takeIf { it >= 0 }
                                    exactIndex?.let { if (cp.completed) it + 1 else it }
                                }
                                exact ?: saved?.let { document.passages.indexOfFirst { it.end > origin.characterOffset }.takeIf { it >= 0 } ?: document.passages.size } ?: (origin.contentLocator.takeIf { it.startsWith("n:") }?.substringAfter("n:")?.let { block ->
                                    document.passages.indexOfFirst { it.blockId == block }.takeIf { it >= 0 }
                                }) ?: if (origin.contentLocator.startsWith("selection:")) document.passages.indexOfFirst {
                                    it.paragraph >= origin.paragraphIndex }.coerceAtLeast(0)
                                else {
                                    val offset = if (origin.characterOffset > 0) origin.characterOffset else
                                        (origin.chapterProgress * document.length).toInt()
                                    document.passages.indexOfFirst { it.end > offset }.takeIf { it >= 0 } ?: document.passages.size
                                }
                            }
                            for ((index, passage) in document.passages.withIndex().drop(initial)) {
                                if (epoch.get() != generation) break
                                fun positionAt(offset: Int): ReadingPosition {
                                    val fraction = offset.toDouble() / document.length.coerceAtLeast(1)
                                    return origin.copy(chapterId = chapter.id, spineIndex = chapter.spineIndex,
                                        contentLocator = "n:${passage.blockId}",
                                        paragraphIndex = passage.paragraph.coerceAtLeast(0), characterOffset = offset,
                                        chapterProgress = fraction.coerceIn(0.0,1.0), scrollOffset = fraction.coerceIn(0.0,1.0),
                                        normalizedProgress = ((wordsBefore + chapter.wordCount * fraction) / totalWords).coerceIn(0.0,1.0),
                                        updatedAt = Clock.System.now())
                                }
                                val source = Source(passage,index,chapter,positionAt(passage.start),
                                    positionAt(if (index == document.passages.lastIndex) document.length else passage.end),
                                    index == document.passages.lastIndex)
                                if (!send(Frame.Start(source))) break
                                var passageSamples = 0L
                                session.render(book.id,NarrationDelivery.spoken(passage.text,rules)) { pcm ->
                                    var offset = 0
                                    var accepted = true
                                    while (offset < pcm.size && accepted) {
                                        val end = minOf(offset+1920,pcm.size)
                                        accepted = send(Frame.Samples(pcm,offset,end-offset))
                                        if (accepted) {
                                            generatedSamples.addAndGet((end-offset).toLong())
                                            passageSamples += end-offset
                                        }
                                        offset = end
                                    }
                                    accepted
                                }
                                check(passageSamples > 0 || epoch.get() != generation) {
                                    "This passage produced no speech. Choose another starting passage or voice."
                                }
                                if (passage.pauseAfterMs > 0 && epoch.get() == generation) {
                                    val silence = FloatArray(passage.pauseAfterMs * 24)
                                    if (!send(Frame.Samples(silence,0,silence.size))) break
                                    generatedSamples.addAndGet(silence.size.toLong())
                                }
                                if (!send(Frame.End(source))) break
                            }
                            wordsBefore += chapter.wordCount.coerceAtLeast(0)
                        }
                    }
                    }
                    controller.extras.refreshStorage()
                    generationFinished.set(true)
                    channel.close()
                } catch (error: Throwable) {
                    channel.close(error)
                    if (epoch.get() == generation) scope.launch { fail(error) }
                }
            }
            consumer = scope.launch(Dispatchers.IO) {
                try {
                    for (frame in channel) {
                        pauseGate.first { !it }
                        if (epoch.get() != generation) break
                        when (frame) {
                            is Frame.Start -> markers.add(Marker(written,frame.source,false))
                            is Frame.End -> markers.add(Marker(written,frame.source,true))
                            is Frame.Samples -> {
                                var offset = frame.offset
                                val end = frame.offset + frame.count
                                while (offset < end && epoch.get() == generation) {
                                    pauseGate.first { !it }
                                    if (epoch.get() != generation) break
                                    val count = audio.write(frame.values,offset,end-offset,AudioTrack.WRITE_NON_BLOCKING)
                                    check(count >= 0) { "Audio output was disconnected." }
                                    if (count == 0) withTimeoutOrNull(100) { writable.receive() }
                                    else { written += count; offset += count }
                                }
                            }
                        }
                    }
                    producedAll = true
                } catch (error: Throwable) { if (epoch.get() == generation) scope.launch { fail(error) } }
            }
            observer = scope.launch {
                try { while (epoch.get() == generation) {
                    if (paused) {
                        val resumed = withTimeoutOrNull((60_000-(SystemClock.elapsedRealtime()-pausedAt)).coerceAtLeast(1)) {
                            pauseGate.first { !it }; true
                        }
                        if (resumed == null) {
                            scope.launch { commands.withLock { if (paused) haltInternal(false) } }
                            break
                        }
                    }
                    delay(100)
                    if (!paused && !audioStarted && written > 0 && (generatedSamples.get() >= 24_000 || generationFinished.get())) {
                        audio.play(); audioStarted = true
                    }
                    observeAudio()
                    if (paused && SystemClock.elapsedRealtime()-pausedAt > 60_000) {
                        scope.launch { commands.withLock { if (paused) haltInternal(false) } }
                        break
                    }
                    val state = controller.state.value
                    if (state.sleepDeadlineMs?.let { SystemClock.elapsedRealtime() >= it } == true) {
                        controller.setSleepTimer(null); pause(); continue
                    }
                    if (!paused && producedAll && lastHead >= written && markers.isEmpty()) {
                        check(current != null || saved?.completed == true) { "This book has no accessible narration text." }
                        checkpoint = checkpoint?.let { it.copy(bookCompleted = true,position = it.position.copy(normalizedProgress = 1.0)) }
                        pause(); haltInternal(true)
                        controller.mutable.update { it.copy(phase = NarrationPhase.Ended,position = it.position?.copy(normalizedProgress = 1.0)) }
                        break
                    }
                } } catch (error: Exception) { fail(error) }
            }
            if (!playImmediately) {
                controller.mutable.update { it.copy(phase = NarrationPhase.Paused) }
                persist(true)
            }
        } catch (error: Throwable) { fail(error) }
    }

    private suspend fun observeAudio() {
        val audio = track ?: return
        val now = SystemClock.elapsedRealtime()
        val creditedMs = clock.sample(now,audio.playbackHeadPosition.toLong(),!paused && audioStarted,controller.state.value.speed)
        val head = clock.presentedFrames
        val advanced = head > lastHead && !paused
        NarrationActivity.playbackActive = advanced
        if (advanced) {
            val delta = creditedMs
            NarrationActivity.totalPlaybackMs += delta
            val sourcePosition = checkpoint?.position ?: controller.state.value.position
            if (sourcePosition != null) {
                val zone = TimeZone.currentSystemDefault()
                for (slice in splitListeningInterval(Clock.System.now(),delta,zone)) {
                if (lastDate != null && (lastDate != slice.date || span?.activity?.timeZoneId != zone.id)) { persist(true) }
                lastDate = slice.date
                if (span == null) span = ReadingSession(UUID.randomUUID().toString(),sourcePosition.bookId,cycleId,
                    controller.graph.deviceId,slice.startedAt, startPosition = sourcePosition,
                    startProgress = sourcePosition.normalizedProgress, endProgress = sourcePosition.normalizedProgress, isActive = false,
                    activity = SessionActivity(sittingId = sitting,timeZoneId = zone.id))
                val following = controller.lastVisualBook == sourcePosition.bookId && now-controller.lastVisualAt < 30_000
                span = span?.let { old -> old.copy(durationMs = old.durationMs+slice.durationMs,
                    endedAt = Instant.fromEpochMilliseconds(slice.startedAt.toEpochMilliseconds()+slice.durationMs),
                    activity = old.activity!!.copy(listeningMs = old.durationMs+slice.durationMs,
                        followAlongMs = old.activity!!.followAlongMs + if (following) slice.durationMs else 0)) }
                }
            }
            controller.mutable.update { if (it.phase != NarrationPhase.Playing) it.copy(phase = NarrationPhase.Playing) else it }
        } else if (!paused && written <= head && !producedAll) {
            controller.mutable.update { it.copy(phase = NarrationPhase.Buffering) }
        }
        while (true) {
            val marker = markers.peek() ?: break
            if (marker.frame > head || (!marker.end && marker.frame == head)) break
            markers.poll()
            val s = marker.source
            if (!marker.end) {
                current = s
                checkpoint = NarrationCheckpoint(s.start,s.passage.blockId,s.index,s.passage.hash,speed = controller.state.value.speed)
                controller.mutable.update { it.copy(position = s.start, blockId = s.passage.blockId,
                    chapterTitle = s.chapter.title, passage = s.passage.text) }
            } else {
                checkpoint = NarrationCheckpoint(s.end,s.passage.blockId,s.index,s.passage.hash,
                    speed = controller.state.value.speed, completed = true)
                span = span?.let { old -> old.copy(endPosition = s.end,endProgress = s.end.normalizedProgress,
                    activity = old.activity!!.copy(listenedWords = old.activity!!.listenedWords + s.passage.words)) }
                controller.mutable.update { it.copy(position = s.end) }
                if (s.lastInChapter && controller.state.value.stopAtChapterEnd) {
                    controller.setSleepTimer(null); lastHead = head; pause(); return
                }
            }
        }
        lastHead = head; lastTick = now
        if (now-lastSave >= 5_000 && checkpoint != null) {
            val sync = now-lastSync >= 60_000
            persist(sync)
            lastSave = now
        }
    }

    private suspend fun persist(sync: Boolean) {
        val cp = checkpoint ?: return
        val fresh = cp.copy(position = cp.position.copy(updatedAt = Clock.System.now()), speed = controller.state.value.speed)
        val measured = span
        // Close the sync span before suspending. A pause can observe more presented audio
        // while this write waits for IO; it must receive its own span rather than be erased.
        if (sync) span = null
        commits.withLock { withContext(Dispatchers.IO) { controller.persistence.save(fresh,measured,sync) } }
        if (sync) lastSync = SystemClock.elapsedRealtime()
    }
    suspend fun pause(interrupted: Boolean = false) {
        resumeAfterFocus = false
        if (!paused) {
            track?.pause()
            observeAudio()
            paused = true; pausedAt = SystemClock.elapsedRealtime(); NarrationActivity.playbackActive = false
            persist(true)
            span = null
        }
        controller.extras.foregroundRequested = false
        if (controller.options.value.prepareWhileCharging && !interrupted)
            controller.state.value.position?.let(controller.extras::prepare)
        if (wake.isHeld) wake.release()
        if (!interrupted) {
            if (Build.VERSION.SDK_INT >= 26) manager.abandonAudioFocusRequest(focus!!) else manager.abandonAudioFocus(focusListener)
        }
        controller.mutable.update { it.copy(phase = if (interrupted) NarrationPhase.Interrupted else NarrationPhase.Paused) }
    }
    suspend fun play() {
        // An idle shutdown may still be releasing the old output/model. On Main,
        // waiting here makes the subsequent non-suspending output reuse safe.
        commands.withLock { }
        resumeAfterFocus = false
        controller.extras.stopPreviewAndJoin()
        controller.extras.foregroundRequested = true
        if (track == null || controller.state.value.phase == NarrationPhase.Ended) {
            val position = controller.state.value.position ?: controller.persistence.latest()?.position ?: return
            if (controller.state.value.phase == NarrationPhase.Ended) {
                val first = withContext(Dispatchers.IO) { controller.graph.bookRepository.getChaptersForBook(position.bookId).minByOrNull { it.spineIndex } } ?: return
                sitting = UUID.randomUUID().toString()
                start(position.copy(chapterId = first.id,spineIndex = first.spineIndex,characterOffset = 0,paragraphIndex = 0,
                    contentLocator = "",chapterProgress = 0.0,normalizedProgress = 0.0))
                return
            }
            start(position,true); return
        }
        val granted = if (Build.VERSION.SDK_INT >= 26) manager.requestAudioFocus(focus!!)
            else manager.requestAudioFocus(focusListener,AudioManager.STREAM_MUSIC,AudioManager.AUDIOFOCUS_GAIN)
        if (granted != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return
        lastTick = SystemClock.elapsedRealtime(); paused = false
        if (!wake.isHeld) wake.acquire()
        if (audioStarted) track?.play()
        controller.mutable.update { it.copy(phase = NarrationPhase.Buffering,error = null) }
    }
    suspend fun skip(forward: Boolean) {
        val source = current
        val position = source?.start ?: controller.state.value.position ?: return
        val resume = controller.state.value.wantsPlayback
        val chapters = withContext(Dispatchers.IO) { controller.graph.bookRepository.getChaptersForBook(position.bookId).sortedBy { it.spineIndex } }
        val chapter = source?.chapter ?: chapters.firstOrNull { it.spineIndex == position.spineIndex } ?: return
        val document = withContext(Dispatchers.IO) { NarrationDelivery.plan(NarrationText.prepare(controller.graph.contentProvider.getHtml(position.bookId,chapter.href)),controller.options.value) }
        val index = source?.index ?: document.passages.indexOfFirst { it.end > position.characterOffset }
            .let { if (it >= 0) it else document.passages.lastIndex.coerceAtLeast(0) }
        val target = document.passages.getOrNull(index + if (forward) 1 else -1)
        if (target != null) {
            start(position.copy(characterOffset = target.start,contentLocator = "",chapterProgress = 0.0),playImmediately = resume)
            return
        }
        val chapterIndex = chapters.indexOfFirst { it.id == chapter.id }
        val adjacent = chapters.getOrNull(chapterIndex + if (forward) 1 else -1) ?: return
        val next = withContext(Dispatchers.IO) { NarrationDelivery.plan(NarrationText.prepare(controller.graph.contentProvider.getHtml(position.bookId,adjacent.href)),controller.options.value) }
        val passage = if (forward) next.passages.firstOrNull() else next.passages.lastOrNull()
        start(position.copy(chapterId = adjacent.id,spineIndex = adjacent.spineIndex,
            characterOffset = passage?.start ?: 0,contentLocator = "",chapterProgress = 0.0),playImmediately = resume)
    }
    suspend fun halt(releaseOwnership: Boolean = true) = commands.withLock { haltInternal(releaseOwnership) }
    suspend fun releasePausedResources() = commands.withLock {
        if (paused) haltInternal(false)
    }
    private suspend fun haltInternal(releaseOwnership: Boolean) {
        epoch.incrementAndGet(); paused = true; resumeAfterFocus = false
        frames?.cancel(); frames = null
        audioWritable?.cancel(); audioWritable = null
        NarrationActivity.playbackActive = false
        if (observer != currentCoroutineContext()[Job]) observer?.cancel()
        observer = null
        track?.pause()
        consumer?.cancelAndJoin(); consumer = null
        producer?.join(); producer = null
        try { persist(true) } finally {
            span = null
            track?.release(); track = null; audioStarted = false; markers.clear()
            if (wake.isHeld) wake.release()
            if (Build.VERSION.SDK_INT >= 26) manager.abandonAudioFocusRequest(focus!!) else manager.abandonAudioFocus(focusListener)
            controller.extras.foregroundRequested = false
            if (releaseOwnership) {
                NarrationActivity.ownerBookId = null
                checkpoint = null; current = null
            }
        }
    }
    internal suspend fun fail(error: Throwable) {
        if (error is CancellationException) return
        runCatching { haltInternal(true) }
        controller.mutable.update { it.copy(phase = NarrationPhase.Error,error = error.message ?: "Could not play this passage. Try again.") }
    }
    fun destroy(): Job {
        epoch.incrementAndGet(); paused = true; resumeAfterFocus = false; NarrationActivity.playbackActive = false
        runCatching { track?.pause() }
        if (wake.isHeld) wake.release()
        runCatching { service.unregisterReceiver(noisy) }
        return scope.launch { runCatching { halt() } }
    }
}
