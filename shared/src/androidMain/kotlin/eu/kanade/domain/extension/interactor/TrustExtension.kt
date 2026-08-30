package eu.kanade.domain.extension.interactor

import android.content.pm.PackageInfo
import androidx.core.content.pm.PackageInfoCompat

/**
 * Vendored from Mihon, adapted: trust decisions are backed by a [TrustStore] supplied by
 * the host app instead of Mihon's SQLDelight repository + preference store.
 */
class TrustExtension(private val store: TrustStore) {

    suspend fun isTrusted(pkgInfo: PackageInfo, fingerprints: List<String>): Boolean {
        val trustedFingerprints = store.repoSigningKeys()
        val key = "${pkgInfo.packageName}:${PackageInfoCompat.getLongVersionCode(pkgInfo)}:${fingerprints.last()}"
        return trustedFingerprints.any { fingerprints.contains(it) } || key in store.trustedExtensions()
    }

    fun trust(pkgName: String, versionCode: Long, signatureHash: String) {
        store.trust("$pkgName:$versionCode:$signatureHash", pkgName)
    }

    fun revokeAll() {
        store.revokeAll()
    }
}

/**
 * Persistence port for extension trust decisions, implemented by the Folio manga backend.
 */
interface TrustStore {
    /** Signing-key fingerprints of the configured extension repositories. */
    suspend fun repoSigningKeys(): Set<String>

    /** Entries of the form "pkgName:versionCode:signatureHash" the user has trusted. */
    suspend fun trustedExtensions(): Set<String>

    fun trust(entry: String, pkgName: String)

    fun revokeAll()
}
