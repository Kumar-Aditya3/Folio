package eu.kanade.tachiyomi.extension.util

import eu.kanade.domain.extension.interactor.TrustExtension

/**
 * Dependency holder for the vendored Mihon extension runtime. Folio's Android manga
 * backend initializes this once at startup; the vendored [ExtensionLoader] reads from it
 * instead of Mihon's application graph.
 */
object ExtensionRuntime {
    var trustExtension: TrustExtension? = null
    var showNsfwSource: () -> Boolean = { true }
}
