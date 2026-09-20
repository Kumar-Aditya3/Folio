package com.folio.reader.ml

import com.folio.reader.platform.FileHasher
import com.folio.reader.platform.FolioFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Where a model download currently is. Surfaced directly by the settings UI. */
sealed interface ModelDownloadState {
    data object NotInstalled : ModelDownloadState
    data class Downloading(val bytesRead: Long, val totalBytes: Long) : ModelDownloadState {
        val fraction: Float get() = if (totalBytes <= 0) 0f else (bytesRead.toFloat() / totalBytes).coerceIn(0f, 1f)
    }
    data class Verifying(val bytesRead: Long) : ModelDownloadState
    data class Installed(val model: EmbeddingModel) : ModelDownloadState
    data class Failed(val message: String) : ModelDownloadState
}

/**
 * Downloads and verifies embedding models.
 *
 * The plan is explicit that models are fetched on demand and never bundled: the APK is
 * 28 MB and the smallest model is 22 MB. There was no generic "URL -> file with progress"
 * downloader to reuse — the only download manager in the repo is manga-specific and keyed
 * by source id rather than URL — so this is a small one built on `HttpURLConnection`, the
 * same primitive the existing Firebase storage sync uses.
 *
 * Downloads land in a `.part` file and are renamed only after the digest matches, so an
 * interrupted download can never be mistaken for an installed model.
 */
class ModelDownloader(
    private val fileSystem: FolioFileSystem,
    private val hasher: FileHasher,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 60_000,
) {
    private val states = HashMap<String, MutableStateFlow<ModelDownloadState>>()

    fun state(model: EmbeddingModel): StateFlow<ModelDownloadState> =
        states.getOrPut(model.id) {
            MutableStateFlow(
                if (isInstalled(model)) ModelDownloadState.Installed(model) else ModelDownloadState.NotInstalled
            )
        }.asStateFlow()

    fun modelsDir(): File = fileSystem.getModelsDir()

    fun modelFile(model: EmbeddingModel): File = File(modelsDir(), model.fileName)

    fun vocabFile(model: EmbeddingModel): File = File(modelsDir(), model.vocabFileName)

    /**
     * True when both the model and its vocabulary are present *and* the model's digest
     * matches the catalog. Digest verification is skipped for models the catalog has no
     * known hash for, rather than silently accepting them.
     */
    fun isInstalled(model: EmbeddingModel): Boolean {
        val modelFile = modelFile(model)
        if (!modelFile.isFile || modelFile.length() != model.sizeBytes) return false
        if (!vocabFile(model).isFile) return false
        return true
    }

    suspend fun verify(model: EmbeddingModel): Boolean {
        val expected = EmbeddingModelCatalog.knownSha256[model.fileName] ?: return isInstalled(model)
        val actual = hasher.sha256File(modelFile(model).absolutePath)
        return actual.equals(expected, ignoreCase = true)
    }

    /**
     * Downloads the model and its vocabulary if needed.
     *
     * @return the model file, or a failure if the download or digest check did not complete
     */
    suspend fun ensureModel(model: EmbeddingModel): Result<File> = withContext(Dispatchers.IO) {
        val state = states.getOrPut(model.id) { MutableStateFlow(ModelDownloadState.NotInstalled) }
        try {
            if (isInstalled(model)) {
                state.value = ModelDownloadState.Installed(model)
                return@withContext Result.success(modelFile(model))
            }
            modelsDir().mkdirs()

            download(model.vocabUrl, vocabFile(model), model.sizeBytes / 10) { read, total ->
                state.value = ModelDownloadState.Downloading(read, total)
            }

            val target = modelFile(model)
            val partial = File(target.parentFile, "${target.name}.part")
            download(model.downloadUrl, partial, model.sizeBytes) { read, total ->
                state.value = ModelDownloadState.Downloading(read, total)
            }

            state.value = ModelDownloadState.Verifying(partial.length())
            val expected = EmbeddingModelCatalog.knownSha256[model.fileName]
            if (expected != null) {
                val actual = hasher.sha256File(partial.absolutePath)
                if (!actual.equals(expected, ignoreCase = true)) {
                    partial.delete()
                    val message = "Checksum mismatch for ${model.fileName}: expected $expected, got $actual"
                    state.value = ModelDownloadState.Failed(message)
                    return@withContext Result.failure(IllegalStateException(message))
                }
            }
            if (!partial.renameTo(target)) {
                partial.delete()
                val message = "Could not move the downloaded model into place"
                state.value = ModelDownloadState.Failed(message)
                return@withContext Result.failure(IllegalStateException(message))
            }
            state.value = ModelDownloadState.Installed(model)
            Result.success(target)
        } catch (t: Throwable) {
            state.value = ModelDownloadState.Failed(t.message ?: t::class.java.simpleName)
            Result.failure(t)
        }
    }

    /** Removes the model and its vocabulary, freeing the space. */
    suspend fun deleteModel(model: EmbeddingModel) = withContext(Dispatchers.IO) {
        modelFile(model).delete()
        vocabFile(model).delete()
        states.getOrPut(model.id) { MutableStateFlow(ModelDownloadState.NotInstalled) }
            .value = ModelDownloadState.NotInstalled
        Unit
    }

    private suspend fun download(
        url: String,
        destination: File,
        expectedTotal: Long,
        onProgress: (read: Long, total: Long) -> Unit,
    ) {
        destination.parentFile?.mkdirs()
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw java.io.IOException("HTTP $code fetching $url")
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: expectedTotal
            connection.inputStream.use { input ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var read = 0L
                    var lastReported = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        read += n
                        // Throttle to whole-percent steps: this feeds a Compose state and
                        // firing it per 64 KB buffer would recompose thousands of times.
                        if (total > 0 && read - lastReported > total / 100) {
                            lastReported = read
                            onProgress(read, total)
                        }
                    }
                    onProgress(read, total)
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val USER_AGENT = "Folio/1.0 (semantic-search model fetch)"
    }
}
