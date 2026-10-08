package eu.kanade.tachiyomi.extension.model

sealed interface LoadResult {
    data class Success(val extension: Extension.Installed) : LoadResult
    data class Untrusted(val extension: Extension.Untrusted) : LoadResult

    /**
     * The extension installed fine but could not be loaded. [reason] is a concrete,
     * user-presentable cause ("unsupported library version 1.5 (supported: 1.4, 1.6)") —
     * the extensions screen shows it so the failure stops being an extension that
     * silently vanished from the list.
     */
    data class Error(val reason: String) : LoadResult
}
