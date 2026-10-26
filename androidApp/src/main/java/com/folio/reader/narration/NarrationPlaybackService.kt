@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.folio.reader.narration

import android.app.*
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.media3.common.*
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.folio.reader.FolioApplication
import com.folio.reader.MainActivity
import com.folio.reader.R
import com.folio.reader.model.ReadingPosition
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

class NarrationPlaybackService : MediaSessionService() {
    private lateinit var controller: AndroidNarrationController
    internal lateinit var coordinator: NarrationCoordinator
    private lateinit var player: NarrationPlayer
    private var session: MediaSession? = null
    private var changes: Job? = null
    private val actions = Mutex()
    private val commandScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override fun onCreate() {
        super.onCreate()
        setMediaNotificationProvider(androidx.media3.session.DefaultMediaNotificationProvider.Builder(this)
            .setNotificationId(715).setChannelId("folio_narration").build())
        controller = (application as FolioApplication).graph.narration
        controller.service = this
        coordinator = NarrationCoordinator(this,controller)
        player = NarrationPlayer(controller)
        session = MediaSession.Builder(this,player).setCallback(object : MediaSession.Callback {
            override fun onConnect(session: MediaSession, info: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                return if (info.packageName == packageName || info.isTrusted || session.isMediaNotificationController(info))
                    super.onConnect(session,info) else MediaSession.ConnectionResult.reject()
            }
        }).build()
        addSession(session!!)
        changes = controller.scope.launch { controller.state.collectLatest { state ->
            player.refresh()
            val intent = Intent(this@NarrationPlaybackService,MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW).setData(Uri.parse("folio://reader/${Uri.encode(state.bookId.orEmpty())}"))
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            session?.setSessionActivity(PendingIntent.getActivity(this@NarrationPlaybackService,715,intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        } }
    }
    override fun onGetSession(info: MediaSession.ControllerInfo): MediaSession? = session
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "dismiss") {
            command("stop")
            return START_NOT_STICKY
        }
        if (intent?.action in setOf("start","play","next","previous")) {
            showPreparingNotification()
            val position = intent?.getStringExtra("position")?.let {
                runCatching { Json.decodeFromString(ReadingPosition.serializer(),it) }.getOrNull()
            }
            command(intent!!.action!!,position)
            return START_NOT_STICKY
        }
        return super.onStartCommand(intent,flags,startId)
    }
    internal fun command(action: String, position: ReadingPosition? = null) {
        if (action in setOf("play","start") && !controller.state.value.wantsPlayback) showPreparingNotification()
        commandScope.launch {
            controller.cleanup?.join()
            actions.withLock { try { when (action) {
                "start" -> position?.let { coordinator.start(it) }
                "play" -> coordinator.play()
                "pause" -> coordinator.pause()
                "next" -> coordinator.skip(true)
                "previous" -> coordinator.skip(false)
                "stop" -> {
                    coordinator.halt()
                    controller.mutable.value = NarrationState(speed = controller.state.value.speed, position = controller.state.value.position)
                    stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
                }
            } } catch (error: Exception) { coordinator.fail(error) } }
        }
    }
    private fun showPreparingNotification() {
        val id = "folio_narration"
        if (android.os.Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(id,"Book narration",NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(this,id).setSmallIcon(R.drawable.ic_notification_folio)
            .setContentTitle("English narration").setContentText("Preparing narration")
            // Media3's regular notification already dispatches COMMAND_STOP on dismissal.
            // Give the brief preparing notification the same saved-position stop behavior.
            .setDeleteIntent(PendingIntent.getService(this,716,Intent(this,NarrationPlaybackService::class.java)
                .setAction("dismiss"),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setSilent(true).setOngoing(false).setCategory(NotificationCompat.CATEGORY_TRANSPORT).build()
        if (android.os.Build.VERSION.SDK_INT >= 29) startForeground(715,notification,
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(715,notification)
    }
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!controller.state.value.wantsPlayback) stopSelf()
    }
    override fun onDestroy() {
        commandScope.cancel()
        changes?.cancel(); controller.cleanup = coordinator.destroy()
        if (controller.state.value.wantsPlayback) controller.mutable.value = controller.state.value.copy(
            phase = NarrationPhase.Interrupted,error = "Playback stopped. Resume to continue.")
        controller.service = null
        session?.release(); session = null
        player.release()
        super.onDestroy()
    }
}

private class NarrationPlayer(private val controller: AndroidNarrationController) : SimpleBasePlayer(Looper.getMainLooper()) {
    private var coverRequest: String? = null
    private var coverArt: ByteArray? = null
    fun refresh() {
        val path = controller.state.value.coverPath
        if (coverRequest != path) {
            coverRequest = path; coverArt = null
            controller.scope.launch {
                val bytes = withContext(Dispatchers.IO) { path?.let { java.io.File(it) }
                    ?.takeIf { it.isFile && it.length() <= 2_000_000 }?.let { runCatching { it.readBytes() }.getOrNull() } }
                if (coverRequest == path) { coverArt = bytes; invalidateState() }
            }
        }
        invalidateState()
    }
    override fun getState(): State {
        val snapshot = controller.state.value
        val builder = State.Builder().setAvailableCommands(Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE,Player.COMMAND_PREPARE,Player.COMMAND_STOP,Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_METADATA,Player.COMMAND_GET_TIMELINE,Player.COMMAND_SET_SPEED_AND_PITCH,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM).build())
            .setPlaybackParameters(PlaybackParameters(snapshot.speed))
            .setPlayWhenReady(snapshot.wantsPlayback,Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(when (snapshot.phase) {
                NarrationPhase.Preparing,NarrationPhase.Buffering,NarrationPhase.Seeking -> Player.STATE_BUFFERING
                NarrationPhase.Playing,NarrationPhase.Paused,NarrationPhase.Interrupted -> Player.STATE_READY
                NarrationPhase.Ended -> Player.STATE_ENDED
                else -> Player.STATE_IDLE
            })
        if (snapshot.hasBook) {
            val metadata = MediaMetadata.Builder().setTitle(snapshot.title).setArtist(snapshot.author)
                .setAlbumTitle(snapshot.chapterTitle).setDisplayTitle(snapshot.title)
            coverArt?.let { metadata.setArtworkData(it,MediaMetadata.PICTURE_TYPE_FRONT_COVER) }
            val item = MediaItem.Builder().setMediaId(snapshot.bookId!!).setMediaMetadata(metadata.build()).build()
            builder.setPlaylist(listOf(MediaItemData.Builder(snapshot.bookId!!).setMediaItem(item)
                .setIsSeekable(false).setDurationUs(C.TIME_UNSET).build())).setCurrentMediaItemIndex(0)
        }
        return builder.build()
    }
    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) controller.play() else controller.pause()
        return Futures.immediateVoidFuture()
    }
    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()
    override fun handleStop(): ListenableFuture<*> { controller.stop(); return Futures.immediateVoidFuture() }
    override fun handleSeek(index: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        controller.skip(seekCommand == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        return Futures.immediateVoidFuture()
    }
    override fun handleSetPlaybackParameters(parameters: PlaybackParameters): ListenableFuture<*> {
        controller.setSpeed(parameters.speed); return Futures.immediateVoidFuture()
    }
}
