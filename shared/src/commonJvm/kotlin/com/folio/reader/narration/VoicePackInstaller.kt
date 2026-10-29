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

data class VoicePackSpec(val revision: String, val baseUrl: String, val assets: List<VoiceAsset>,
    val gated: Boolean = false, val archiveRoot: String? = null,
    val installedFiles: Map<String, Long> = assets.associate { it.name to it.bytes }) {
    val sizeBytes: Long get() = assets.sumOf { it.bytes }
}

object NarrationPacks {
    val phonon31 = VoicePackSpec(PhononPack.revision, PhononPack.baseUrl, PhononPack.assets)
    val phonon40 = VoicePackSpec("cc738b7e4d182c2ba1bafb8062aa4846af000637",
        "https://huggingface.co/gradium/phonon-40m-en/resolve/cc738b7e4d182c2ba1bafb8062aa4846af000637/",
        listOf(
            VoiceAsset("model.q8.gguf", 74_553_568, "f6acc8f43cb296be31341b4428a6f01b6402659cd0039b624c4aea224936cf95"),
            VoiceAsset("tokenizer.json", 277_967, "70d39980cb2154bd364dbed8424b1343923a03e2"),
            VoiceAsset("config.json", 4_634, "f6852aa041b306de1f34bbe6dc38f17073cc332e"),
            PhononPack.assets.last()), gated = true)
    val kokoro = VoicePackSpec("kokoro-int8-multi-lang-v1_0-72c34723",
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/",
        listOf(VoiceAsset("kokoro-int8-multi-lang-v1_0.tar.bz2", 132_353_410,
            "72c347230107ef8a31098ec4d468a605244beef5f1ed00db97efa13518800653")),
        archiveRoot = "kokoro-int8-multi-lang-v1_0", installedFiles = mapOf(
            "model.int8.onnx" to 114_202_993L, "voices.bin" to 28_200_960L, "tokens.txt" to 687L,
            "lexicon-us-en.txt" to 5_956_885L, "lexicon-gb-en.txt" to 6_366_635L,
            "espeak-ng-data/phondata" to 0L, "espeak-ng-data/en_dict" to 0L))
    fun forModel(model: NarrationModel) = when (model) {
        NarrationModel.Phonon31M -> phonon31
        NarrationModel.Phonon40M -> phonon40
        NarrationModel.Kokoro82M -> kokoro
    }
}

/** Local inspection never loads a model or makes a request. Installation is explicitly opt-in. */
class VoicePackInstaller(private val root: File, supported: Boolean,
    private val validate: (File) -> Unit, private val canDownload: () -> Boolean = { true },
    val spec: VoicePackSpec = NarrationPacks.phonon31) {
    val directory = File(root, spec.revision)
    private val staging = File(root, "${spec.revision}.partial")
    private val mutex = Mutex()
    private fun snapshot(phase: VoicePackPhase, bytes: Long = 0, error: String? = null) =
        VoicePackState(phase, bytes, spec.sizeBytes, error)
    private val mutable = MutableStateFlow(snapshot(if (!supported) VoicePackPhase.Unsupported
        else if (isInstalled()) VoicePackPhase.Installed else VoicePackPhase.NotInstalled))
    val state = mutable.asStateFlow()
    fun isInstalled(): Boolean = File(directory, "installed").takeIf { it.isFile }?.readText() == spec.revision &&
        spec.installedFiles.all { (name, size) -> File(directory, name).let { it.isFile &&
            if (size > 0) it.length() == size else it.length() > 0 } }

    suspend fun install(accessToken: String = "") = withContext(Dispatchers.IO) { mutex.withLock {
        if (mutable.value.phase == VoicePackPhase.Unsupported || isInstalled()) return@withLock
        try {
            check(!spec.gated || accessToken.startsWith("hf_") && accessToken.none { it.isWhitespace() }) {
                "Accept the model terms on Hugging Face, then enter a read token with access to this model."
            }
            root.mkdirs(); staging.mkdirs()
            val needed = spec.assets.sumOf { (it.bytes-File(staging,it.name).length()).coerceAtLeast(0) }
            check(root.usableSpace > needed + if (spec.archiveRoot != null) 300_000_000 else 16_000_000) {
                "Free more storage to install this voice pack."
            }
            var complete = 0L
            for (asset in spec.assets) {
                currentCoroutineContext().ensureActive()
                val target = File(staging, asset.name)
                if (target.length() != asset.bytes || digest(target, asset) != asset.sha256) {
                    check(canDownload()) { "Connect to Wi-Fi, or turn off Wi-Fi only and retry." }
                    if (target.length() >= asset.bytes) target.delete()
                    val existing = target.length()
                    val connection = connect(URL(spec.baseUrl + asset.name), existing, accessToken)
                    try {
                        val code = connection.responseCode
                        check(code in 200..299) {
                            if (spec.gated && code in listOf(401,403)) "Access denied. Accept Phonon 40M's terms and check your Hugging Face read token."
                            else "Download failed ($code). Try again."
                        }
                        val resume = existing > 0 && code == 206
                        if (code == 206) check(connection.getHeaderField("Content-Range")?.let {
                            it.startsWith("bytes ${if (resume) existing else 0}-") && it.endsWith("/${asset.bytes}") } == true) {
                            "Could not safely resume the download. Retry."
                        }
                        var read = if (resume) existing else 0L
                        connection.inputStream.use { input -> java.io.FileOutputStream(target,resume).use { output ->
                            val bytes = ByteArray(64 * 1024)
                            var published = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                check(canDownload()) { "Wi-Fi disconnected. Retry when connected." }
                                val n = input.read(bytes); if (n < 0) break
                                read += n; check(read <= asset.bytes) { "Unexpected voice file size." }
                                output.write(bytes, 0, n)
                                // Avoid sending thousands of recompositions per second on fast connections.
                                val now = System.nanoTime()
                                if (now-published >= 100_000_000 || read == asset.bytes) {
                                    mutable.value = snapshot(VoicePackPhase.Downloading, complete + read); published = now
                                }
                            }
                        } }
                    } finally { connection.disconnect() }
                }
                mutable.value = snapshot(VoicePackPhase.Verifying, complete + asset.bytes)
                check(target.length() == asset.bytes && digest(target, asset) == asset.sha256) {
                    target.delete(); "Voice verification failed. Retry the download."
                }
                complete += asset.bytes
            }
            currentCoroutineContext().ensureActive()
            val content = if (spec.archiveRoot != null) extractArchive(File(staging, spec.assets.single().name)) else staging
            validate(content)
            currentCoroutineContext().ensureActive()
            check(!directory.exists() || directory.deleteRecursively()) { "Could not replace the incomplete voice pack." }
            check(content.renameTo(directory)) { "Could not finish installing the voice pack." }
            File(directory, "installed").writeText(spec.revision)
            if (content != staging) staging.deleteRecursively()
            mutable.value = snapshot(VoicePackPhase.Installed, spec.sizeBytes)
        } catch (cancel: CancellationException) {
            mutable.value = snapshot(VoicePackPhase.NotInstalled); throw cancel
        } catch (error: Exception) {
            // Never expose a request URL or credential through arbitrary network exception messages.
            val message = if (error is IllegalStateException) error.message else "Could not download the voice pack. Check your connection and retry."
            mutable.value = snapshot(VoicePackPhase.Failed, error = message)
        }
    } }

    /** Authentication stays on huggingface.co; signed CDN redirects never receive the token. */
    private fun connect(initial: URL, existing: Long, token: String): HttpURLConnection {
        var url = initial
        repeat(8) {
            check(url.protocol == "https" || url.host in listOf("127.0.0.1", "localhost")) { "Unsafe download address." }
            val connection = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false; connectTimeout = 15_000; readTimeout = 10_000
                if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
                if (spec.gated && url.host == "huggingface.co" && token.isNotEmpty()) setRequestProperty("Authorization", "Bearer $token")
            }
            if (connection.responseCode !in listOf(301,302,303,307,308)) return connection
            val next = connection.getHeaderField("Location")
            connection.disconnect()
            check(next != null) { "Invalid download redirect." }
            url = URL(url,next)
        }
        error("Too many download redirects. Try again.")
    }

    private suspend fun extractArchive(archive: File): File {
        val output = File(staging,"unpacked")
        if (output.exists()) check(output.deleteRecursively())
        output.mkdirs()
        var expanded = 0L; var files = 0
        org.apache.commons.compress.archivers.tar.TarArchiveInputStream(
            org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream(archive.inputStream().buffered())).use { tar ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = tar.nextEntry ?: break
                val path = entry.name.removePrefix("./")
                check(path == spec.archiveRoot || path.startsWith("${spec.archiveRoot}/")) { "Invalid voice archive." }
                check(!entry.isSymbolicLink && !entry.isLink && (entry.isDirectory || entry.isFile)) { "Invalid voice archive entry." }
                val destination = File(output,path.removePrefix(spec.archiveRoot!!).removePrefix("/"))
                check(destination.canonicalPath == output.canonicalPath || destination.canonicalPath.startsWith(output.canonicalPath + File.separator)) {
                    "Invalid voice archive path."
                }
                if (entry.isDirectory) { destination.mkdirs(); continue }
                expanded += entry.size; files++
                check(entry.size >= 0 && expanded <= 512_000_000 && files <= 3000) { "Voice archive exceeds its storage limit." }
                destination.parentFile?.mkdirs()
                destination.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = tar.read(buffer); if (n < 0) break
                        copied += n; check(copied <= entry.size); out.write(buffer,0,n)
                    }
                    check(copied == entry.size) { "Incomplete voice archive." }
                }
            }
        }
        check(spec.installedFiles.all { (name, _) -> File(output,name).let { it.isFile && it.length() > 0 } }) { "Voice archive is incomplete." }
        return output
    }

    suspend fun remove() = withContext(Dispatchers.IO) { mutex.withLock {
        mutable.value = snapshot(VoicePackPhase.Removing)
        if ((!directory.exists() || directory.deleteRecursively()) && (!staging.exists() || staging.deleteRecursively()))
            mutable.value = snapshot(VoicePackPhase.NotInstalled)
        else mutable.value = snapshot(VoicePackPhase.Failed, error = "Could not remove all voice files. Retry.")
    } }
    private suspend fun digest(file: File, asset: VoiceAsset): String {
        if (!file.isFile) return ""
        // HF non-LFS files are pinned with their Git blob object ID, not a SHA-256 guess.
        val gitBlob = asset.sha256.length == 40
        val digest = MessageDigest.getInstance(if (gitBlob) "SHA-1" else "SHA-256")
        if (gitBlob) digest.update("blob ${file.length()}\u0000".toByteArray(Charsets.UTF_8))
        file.inputStream().use { input -> val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
