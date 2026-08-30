package com.folio.reader.manga

import android.app.Application
import android.content.Context
import dev.mihon.injekt.patchInjekt
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.serialization.json.Json
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton

/**
 * Registers the singletons that loaded Mihon extension APKs resolve through Injekt at
 * runtime (Context/Application for ConfigurableSource and WebView-backed sources,
 * NetworkHelper for HttpSource, Json for response parsing). Must run before any
 * extension is loaded.
 */
object FolioInjektSetup {

    @Volatile
    private var registered = false

    fun register(context: Context, networkHelper: NetworkHelper) {
        if (registered) return
        registered = true
        patchInjekt()
        val appContext = context.applicationContext
        Injekt.addSingleton<Context>(appContext)
        (appContext as? Application)?.let { Injekt.addSingleton<Application>(it) }
        Injekt.addSingleton<NetworkHelper>(networkHelper)
        Injekt.addSingleton<Json>(Json { ignoreUnknownKeys = true })
    }
}
