package eu.kanade.tachiyomi.extension.api

import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.ExtensionRepo
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import logcat.LogPriority
import okhttp3.OkHttpClient
import tachiyomi.core.common.util.system.logcat

/**
 * Fetches the list of available extensions from the configured extension repositories.
 * Supports the Keiyoushi new-format store JSON (`index.json`, carries the repo signing
 * key and the full catalog), the legacy `index.min.json` array, and the `repo.json`
 * metadata wrapper.
 */
class ExtensionApi(
    private val client: OkHttpClient,
    private val repoProvider: () -> List<ExtensionRepo>,
) {

    private val json = Json { ignoreUnknownKeys = true }

    private val _repoSigningKeys = MutableStateFlow<Set<String>>(emptySet())
    val repoSigningKeys: StateFlow<Set<String>> = _repoSigningKeys

    suspend fun findExtensions(): List<Extension.Available> {
        val repos = repoProvider()
        val signingKeys = mutableSetOf<String>()
        val extensions = repos.flatMap { repo ->
            try {
                fetchRepo(repo, signingKeys)
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to fetch extension repo ${repo.name}" }
                emptyList()
            }
        }
        _repoSigningKeys.value = signingKeys
        return extensions
    }

    private suspend fun fetchRepo(
        repo: ExtensionRepo,
        signingKeys: MutableSet<String>,
    ): List<Extension.Available> {
        var effectiveRepo = repo
        var indexUrl = repo.indexUrl

        // repo.json is a metadata wrapper: read the signing key, then fall back to the
        // legacy JSON index next to it (the v2 index it points at is protobuf).
        if (repo.indexUrl.endsWith("repo.json")) {
            val body = client.newCall(GET(repo.indexUrl)).awaitSuccess().body.string()
            val meta = runCatching { json.decodeFromString<RepoMetadata>(body) }.getOrNull()
            val key = meta?.meta?.signingKeyFingerprint?.takeIf { it.isNotBlank() }
            if (key != null) signingKeys += key
            effectiveRepo = repo.copy(
                name = meta?.meta?.name ?: repo.name,
                signingKey = key ?: repo.signingKey,
            )
            val relative = meta?.indexV2 ?: "index.min.json"
            indexUrl = if (relative.startsWith("http")) {
                relative
            } else {
                repo.baseUrl.trimEnd('/') + "/" + relative.removePrefix("/")
            }
        }

        val body = client.newCall(GET(indexUrl)).awaitSuccess().body.string()
        val trimmed = body.trimStart()
        return when {
            trimmed.startsWith("[") ->
                json.decodeFromString<List<NetworkLegacyExtension>>(body)
                    .filterNot { it.name in PLACEHOLDER_NAMES }
                    .map { it.toAvailable(effectiveRepo) }
            else -> {
                val store = runCatching { json.decodeFromString<NetworkExtensionStore>(body) }.getOrNull()
                store?.signingKey?.takeIf { it.isNotBlank() }?.let { signingKeys += it }
                val storeRepo = store?.signingKey?.let { effectiveRepo.copy(signingKey = it) } ?: effectiveRepo
                (store?.extensionList?.extensions ?: emptyList())
                    .filterNot { it.name in PLACEHOLDER_NAMES }
                    .map { it.toAvailable(storeRepo) }
            }
        }
    }

    @Serializable
    private data class RepoMetadata(
        @SerialName("index_v2") val indexV2: String? = null,
        val meta: Meta? = null,
    ) {
        @Serializable
        data class Meta(
            val name: String? = null,
            val signingKeyFingerprint: String? = null,
        )
    }

    // ---------- New-format store (index.json) ----------

    @Serializable
    private data class NetworkExtensionStore(
        val name: String? = null,
        val signingKey: String? = null,
        val extensionList: ExtensionList? = null,
    ) {
        @Serializable
        data class ExtensionList(val extensions: List<NetExtension> = emptyList())

        @Serializable
        data class NetExtension(
            val name: String,
            val packageName: String,
            val resources: Resources? = null,
            val extensionLib: String? = null,
            val versionCode: String? = null,
            val versionName: String? = null,
            val contentWarning: String? = null,
            val sources: List<NetSource> = emptyList(),
        ) {
            @Serializable
            data class Resources(val apkUrl: String? = null, val iconUrl: String? = null)

            @Serializable
            data class NetSource(
                val id: String? = null,
                val name: String? = null,
                val language: String? = null,
                val homeUrl: String? = null,
            )
        }
    }

    private fun NetworkExtensionStore.NetExtension.toAvailable(repo: ExtensionRepo): Extension.Available =
        Extension.Available(
            name = name,
            pkgName = packageName,
            versionName = versionName ?: "0",
            versionCode = versionCode?.toLongOrNull() ?: 0L,
            libVersion = extensionLib?.toDoubleOrNull()
                ?: versionName?.substringBeforeLast('.')?.toDoubleOrNull()
                ?: 0.0,
            lang = sources.firstOrNull()?.language ?: "all",
            isNsfw = contentWarning == "CONTENT_WARNING_NSFW",
            sources = sources.map {
                Extension.Available.Source(
                    id = it.id?.toLongOrNull() ?: 0L,
                    lang = it.language ?: "",
                    name = it.name ?: name,
                    baseUrl = it.homeUrl ?: "",
                )
            },
            apkUrl = resources?.apkUrl ?: "",
            iconUrl = resources?.iconUrl ?: "",
            repo = repo,
        )

    // ---------- Legacy format (index.min.json) ----------

    @Serializable
    private data class NetworkLegacyExtension(
        val name: String,
        val pkg: String,
        val apk: String,
        val lang: String,
        val code: Int,
        val version: String,
        val nsfw: Int = 0,
        val sources: List<NetworkSource> = emptyList(),
    ) {
        fun toAvailable(repo: ExtensionRepo): Extension.Available {
            val base = repo.baseUrl.trimEnd('/')
            return Extension.Available(
                name = name.removePrefix("Tachiyomi: "),
                pkgName = pkg,
                versionName = version,
                versionCode = code.toLong(),
                libVersion = version.substringBeforeLast('.').toDoubleOrNull() ?: 0.0,
                lang = lang,
                isNsfw = nsfw == 1,
                sources = sources.map {
                    Extension.Available.Source(
                        id = it.id,
                        lang = it.lang,
                        name = it.name,
                        baseUrl = it.baseUrl,
                    )
                },
                apkUrl = if (apk.startsWith("http")) apk else "$base/apk/$apk",
                iconUrl = "$base/icon/$pkg.png",
                repo = repo,
            )
        }
    }

    @Serializable
    private data class NetworkSource(
        val id: Long,
        val lang: String,
        val name: String,
        val baseUrl: String = "",
    )

    companion object {
        /** Stub entries Keiyoushi ships for outdated clients; never useful to install. */
        private val PLACEHOLDER_NAMES = setOf(
            "Outdated App",
            "Update to Mihon 0.20.1+",
            "Update to Tachiyomi 0.15.0+",
        )
    }
}
