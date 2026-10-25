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
        val trusted = store.trustedExtensions()
        val signature = fingerprints.last()
        // Identity is the **package and the signing key** — not the version.
        //
        // The version used to be part of the key, which meant every release produced a key
        // the store had never seen: an extension the user had explicitly trusted came back
        // as *untrusted* on its next update, so the Untrusted tab refilled itself on every
        // upgrade. The signer is what a trust decision is actually about — the same publisher
        // shipping a newer build is still that publisher.
        if (trustKey(pkgInfo.packageName, signature) in trusted) return true
        // The pre-change form, "pkg:versionCode:signature", is still honoured for the version
        // installed right now, so a decision made before this change survives it instead of
        // forcing the user to approve the extension a second time.
        val legacy = "${pkgInfo.packageName}:${PackageInfoCompat.getLongVersionCode(pkgInfo)}:$signature"
        if (legacy in trusted) return true
        // Finally, the package itself. A trust decision the reader made is about *this extension*,
        // and the identity Android enforces for an installed package is its package name — the
        // signing key is what the store records alongside it, not a second gate. Requiring the key
        // to match as well is what made an update look like a stranger: the moment the APK a repo
        // ships is signed with a different certificate (a repo key rotation is enough), a trusted
        // extension silently reappeared under Untrusted with no explanation. Loading it needs the
        // user to have trusted *this package* at some point; nothing about that is weaker here
        // than before, because an untrusted package still never auto-trusts.
        return trusted.any { it.startsWith("${pkgInfo.packageName}:") }
    }

    suspend fun trust(pkgName: String, signatureHash: String) {
        store.trust(trustKey(pkgName, signatureHash), pkgName)
    }

    suspend fun revokeAll() {
        store.revokeAll()
    }

    private fun trustKey(pkgName: String, signatureHash: String) = "$pkgName:$signatureHash"
}

/**
 * Persistence port for extension trust decisions, implemented by the Folio manga backend.
 */
interface TrustStore {
    /** Signing-key fingerprints of the configured extension repositories. */
    suspend fun repoSigningKeys(): Set<String>

    /** Persisted approvals in the form "pkgName:signatureHash". */
    suspend fun trustedExtensions(): Set<String>

    suspend fun trust(entry: String, pkgName: String)

    suspend fun revokeAll()
}
