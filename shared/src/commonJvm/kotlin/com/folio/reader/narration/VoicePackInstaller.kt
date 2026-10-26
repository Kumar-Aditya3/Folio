package com.folio.reader.narration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class VoiceAsset(val name: String, val bytes: Long, val sha256: String)
object PhononPack {
    const val revision = "92741c5b07d085ff840e26fea8ce43110b87852f"
    const val runtimeRevision = "0418e21963f646e66f5b27d6c1aa5740524ec545"
    const val baseUrl = "https://huggingface.co/gradium/phonon-31m-en/resolve/$revision/"
    val assets = listOf(
        VoiceAsset("model.q8.gguf", 64_512_352, "a7d9aad2cd6dc66473f24a08bc6d2939030ef4d751ef82349361fbef3272dbae"),
        VoiceAsset("tokenizer.json", 277_967, "691a00068fa7b989f2f3e5ae5daa2d1a5bb319f2d5360deafcfb19a8bb6e7936"),
        VoiceAsset("config.json", 4_634, "b5d0f88353638370d94035b7d73d7d6577c0554d78f8d7ecfd40490aae39ae85"),
        VoiceAsset("default-voice.safetensors", 256_136, "dc537478eaadc1a5257fcda2e3501ab81e14c111a5d95a5838f350db2afd0f1a"),
    )
    val sizeBytes = assets.sumOf { it.bytes }
}

/** Construction and inspection are local-only. Only install(), called from Download, uses HTTP. */
class VoicePackInstaller(private val root: File, supported: Boolean,
    private val validate: (File) -> Unit, private val canDownload: () -> Boolean = { true }) {
    val directory = File(root, PhononPack.revision)
    private val staging = File(root, "${PhononPack.revision}.partial")
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(VoicePackState(if (!supported) VoicePackPhase.Unsupported
        else if (isInstalled()) VoicePackPhase.Installed else VoicePackPhase.NotInstalled))
    val state = mutable.asStateFlow()
    fun isInstalled(): Boolean = File(directory, "installed").takeIf { it.isFile }?.readText() == PhononPack.revision &&
        PhononPack.assets.all { File(directory, it.name).length() == it.bytes }

    suspend fun install() = withContext(Dispatchers.IO) { mutex.withLock {
        if (mutable.value.phase == VoicePackPhase.Unsupported || isInstalled()) return@withLock
        try {
            root.mkdirs(); staging.mkdirs()
            val needed = PhononPack.assets.sumOf { (it.bytes-File(staging,it.name).length()).coerceAtLeast(0) }
            check(root.usableSpace > needed + 16_000_000) { "Free more storage to install English narration." }
            var complete = 0L
            for (asset in PhononPack.assets) {
                currentCoroutineContext().ensureActive()
                check(canDownload()) { "Connect to Wi-Fi, or turn off Wi-Fi only and retry." }
                val target = File(staging, asset.name)
                if (target.length() != asset.bytes || digest(target) != asset.sha256) {
                    if (target.length() >= asset.bytes) target.delete()
                    val existing = target.length()
                    val connection = URL(PhononPack.baseUrl + asset.name).openConnection() as HttpURLConnection
                    connection.connectTimeout = 15_000; connection.readTimeout = 10_000
                    if (existing > 0) connection.setRequestProperty("Range","bytes=$existing-")
                    try {
                        check(connection.responseCode in 200..299) { "Download failed (${connection.responseCode}). Try again." }
                        val resume = existing > 0 && connection.responseCode == 206
                        if (connection.responseCode == 206) check(connection.getHeaderField("Content-Range")?.let {
                            it.startsWith("bytes ${if (resume) existing else 0}-") && it.endsWith("/${asset.bytes}") } == true) { "Could not safely resume the download. Retry." }
                        var read = if (resume) existing else 0L
                        connection.inputStream.use { input -> java.io.FileOutputStream(target,resume).use { output ->
                            val bytes = ByteArray(32 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                check(canDownload()) { "Wi-Fi disconnected. Retry when connected." }
                                val n = input.read(bytes); if (n < 0) break
                                read += n; check(read <= asset.bytes) { "Unexpected voice file size." }
                                output.write(bytes, 0, n)
                                mutable.value = VoicePackState(VoicePackPhase.Downloading, complete + read)
                            }
                        } }
                    } finally { connection.disconnect() }
                }
                mutable.value = VoicePackState(VoicePackPhase.Verifying, complete)
                check(target.length() == asset.bytes && digest(target) == asset.sha256) { "Voice verification failed. Retry the download." }
                complete += asset.bytes
            }
            currentCoroutineContext().ensureActive()
            validate(staging)
            currentCoroutineContext().ensureActive()
            // Marker is published last. A failure/restart cannot mistake partial files for a pack.
            check(!directory.exists() || directory.deleteRecursively()) { "Could not replace the incomplete voice pack." }
            check(staging.renameTo(directory)) { "Could not finish installing the voice pack." }
            File(directory, "installed").writeText(PhononPack.revision)
            mutable.value = VoicePackState(VoicePackPhase.Installed, PhononPack.sizeBytes)
        } catch (cancel: CancellationException) {
            mutable.value = VoicePackState(VoicePackPhase.NotInstalled); throw cancel
        } catch (error: Exception) {
            mutable.value = VoicePackState(VoicePackPhase.Failed, error = error.message ?: "Download failed. Try again.")
        }
    } }
    suspend fun remove() = withContext(Dispatchers.IO) { mutex.withLock {
        mutable.value = VoicePackState(VoicePackPhase.Removing)
        // Both paths are fixed children of the injected application-private root.
        if ((!directory.exists() || directory.deleteRecursively()) && (!staging.exists() || staging.deleteRecursively()))
            mutable.value = VoicePackState(VoicePackPhase.NotInstalled)
        else mutable.value = VoicePackState(VoicePackPhase.Failed, error = "Could not remove all voice files. Retry.")
    } }
    private fun digest(file: File): String {
        if (!file.isFile) return ""
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(32 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
