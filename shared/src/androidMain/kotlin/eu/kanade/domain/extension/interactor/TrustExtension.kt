package eu.kanade.domain.extension.interactor

import android.content.pm.PackageInfo
import androidx.core.content.pm.PackageInfoCompat

/**
 * Vendored from Mihon, adapted: trust decisions are backed by a [TrustStore] supplied by
 * the host app instead of Mihon's SQLDelight repository + preference store.
 */
class TrustExtension(private val store: TrustStore) {

    suspend fun isTrusted(pkgInfo: PackageInfo, fingerprints: List<String>): Boolean {
        // SECURITY: trust is granted ONLY by explicit user consent -- an entry the user
        // approved from the Extensions "Untrusted" tab, persisted via store.trustedExtensions().
        // Repository signing keys are deliberately NOT used as an auto-trust anchor here:
        // those keys are declared by the fetched remote index (index.json) and are therefore
        // attacker-controllable if the repo host/account is compromised, which would let a
        // malicious repo auto-approve its own APK and load native code with no user prompt.
        val key = "${pkgInfo.packageName}:${PackageInfoCompat.getLongVersionCode(pkgInfo)}:${fingerprints.last()}"
        return key in store.trustedExtensions()
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
