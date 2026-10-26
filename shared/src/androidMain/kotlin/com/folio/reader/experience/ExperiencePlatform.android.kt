package com.folio.reader.experience

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

actual fun encodeCardPng(image: ImageBitmap): ByteArray = ByteArrayOutputStream().use {
    check(image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
    it.toByteArray()
}
actual fun decodeCardImage(bytes: ByteArray) = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size).asImageBitmap()

@Composable actual fun rememberExperiencePlatform(): ExperiencePlatform {
    val context = LocalContext.current
    return remember(context) { AndroidExperiencePlatform(context) }
}

@Composable actual fun ReaderForegroundEffect(onEligibility: (Boolean) -> Unit) {
    val owner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val current by rememberUpdatedState(onEligibility)
    DisposableEffect(owner, context) {
        val observer = LifecycleEventObserver { _, _ -> current(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
        val receiver = object : BroadcastReceiver() { override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action == Intent.ACTION_SCREEN_OFF) current(false)
        } }
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        owner.lifecycle.addObserver(observer)
        current(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        onDispose { current(false); owner.lifecycle.removeObserver(observer); context.unregisterReceiver(receiver) }
    }
}

private class AndroidExperiencePlatform(private val context: Context) : ExperiencePlatform {
    override val nativeSharing = true
    override fun soundscapeController(): SoundscapeController {
        lateinit var controller: SoundscapeController
        controller = SoundscapeController(load = { scene -> context.assets.open("soundscapes/${scene.name.lowercase()}.wav").use { readSoundscapeWav(it.readBytes()) } },
            output = { AndroidPcmOutput(context) { controller.pause() } })
        return controller
    }
    override suspend fun saveImages(files: List<ExportFile>, share: Boolean): String? {
        if (!share) return withContext(Dispatchers.IO) {
            val fs = com.folio.reader.platform.AndroidFileSystem(context)
            files.forEach { fs.exportToDownloads(it.name, it.bytes) }
            "Saved ${files.size} image${if (files.size == 1) "" else "s"} to Downloads"
        }
        val dir = File(context.cacheDir, "quote-exports").apply { mkdirs() }
        val uris = withContext(Dispatchers.IO) { files.map { item ->
            val file = File(dir, "${java.util.UUID.randomUUID()}-${item.name}").apply { writeBytes(item.bytes) }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } }
        withContext(Dispatchers.Main) {
            val intent = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
                type = "image/png"; addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris.first())
                else putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                clipData = android.content.ClipData.newUri(context.contentResolver, "Folio quote", uris.first()).also { clip ->
                    uris.drop(1).forEach { clip.addItem(android.content.ClipData.Item(it)) }
                }
            }
            context.startActivity(Intent.createChooser(intent, "Share passages"))
        }
        return null
    }
    override suspend fun copyImage(image: ImageBitmap) { saveImages(listOf(ExportFile("quote.png", encodeCardPng(image))), true) }
    override suspend fun journal(title: String, html: String, print: Boolean): String? = withContext(Dispatchers.Main) {
        withTimeout(30_000) {
        suspendCancellableCoroutine<String?> { continuation ->
        val activity = context as? Activity ?: error("Open the journal from Folio")
        val view = WebView(activity)
        continuation.invokeOnCancellation { view.post { view.destroy() } }
        view.settings.javaScriptEnabled = true
        view.webViewClient = object : WebViewClient() {
            override fun onPageFinished(v: WebView, url: String?) {
                v.evaluateJavascript("Promise.all([document.fonts.ready,...Array.from(document.images).map(i=>i.decode().catch(()=>{}))]).then(()=>{document.title='folio-print-ready'})", null)
            }
        }
        view.webChromeClient = object : android.webkit.WebChromeClient() {
            private var started = false
            override fun onReceivedTitle(v: WebView, value: String?) {
                if (started || value != "folio-print-ready" || !continuation.isActive) return
                started = true
                try {
                val adapter = v.createPrintDocumentAdapter(title)
                val delegate = object : android.print.PrintDocumentAdapter() {
                    override fun onStart() = adapter.onStart()
                    override fun onLayout(old: PrintAttributes?, new: PrintAttributes?, signal: android.os.CancellationSignal?, callback: LayoutResultCallback?, extras: android.os.Bundle?) = adapter.onLayout(old, new, signal, callback, extras)
                    override fun onWrite(pages: Array<out android.print.PageRange>?, file: android.os.ParcelFileDescriptor?, signal: android.os.CancellationSignal?, callback: WriteResultCallback?) = adapter.onWrite(pages, file, signal, callback)
                    override fun onFinish() { adapter.onFinish(); v.destroy() }
                }
                val letter = html.contains("data-paper=\"letter\"")
                (activity.getSystemService(Context.PRINT_SERVICE) as PrintManager).print(title, delegate,
                    PrintAttributes.Builder().setMediaSize(if (letter) PrintAttributes.MediaSize.NA_LETTER else PrintAttributes.MediaSize.ISO_A4)
                        .setMinMargins(PrintAttributes.Margins(709,709,709,709)).build())
                continuation.resume(null) // Handoff is not a claim that the user printed/saved.
                } catch (error: Exception) { v.destroy(); if (continuation.isActive) continuation.resumeWithException(error) }
            }
        }
        view.loadDataWithBaseURL("https://folio.invalid/", html, "text/html", "UTF-8", null)
        }
        }
    }
    override fun openExportFolder() = Unit
    override fun fontData(name: String): ByteArray? = runCatching { context.assets.open("fonts/$name").use { it.readBytes() } }.getOrNull()
}

private class AndroidPcmOutput(context: Context, private val lostFocus: () -> Unit) : PcmOutput {
    private val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private val listener = AudioManager.OnAudioFocusChangeListener { if (it != AudioManager.AUDIOFOCUS_GAIN) lostFocus() }
    private val request = if (Build.VERSION.SDK_INT >= 26) AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attrs).setOnAudioFocusChangeListener(listener).build() else null
    private var focused = false
    private val track = AudioTrack.Builder().setAudioAttributes(attrs)
        .setAudioFormat(AudioFormat.Builder().setSampleRate(44100).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
        .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(maxOf(8820, AudioTrack.getMinBufferSize(44100, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT))).build()
    private val receiver = object : BroadcastReceiver() { override fun onReceive(c: Context?, i: Intent?) { lostFocus() } }
    private val app = context.applicationContext.also { it.registerReceiver(receiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)) }
    override fun start() {
        check(com.folio.reader.narration.NarrationActivity.ownerBookId == null) { "Stop narration to play a soundscape." }
        if (!focused) {
            val result = if (Build.VERSION.SDK_INT >= 26) manager.requestAudioFocus(request!!) else manager.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
            check(result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "Audio focus unavailable" }; focused = true
        }
        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
    }
    override fun write(samples: ShortArray) { check(track.write(samples, 0, samples.size) >= 0) }
    override fun pause() { track.pause(); track.flush(); if (focused) {
        if (Build.VERSION.SDK_INT >= 26) manager.abandonAudioFocusRequest(request!!) else manager.abandonAudioFocus(listener)
        focused = false
    } }
    override fun close() { try { pause() } finally { track.release(); app.unregisterReceiver(receiver) } }
}
