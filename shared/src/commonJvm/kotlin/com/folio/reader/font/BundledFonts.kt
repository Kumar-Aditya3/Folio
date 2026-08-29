package com.folio.reader.font

import com.folio.reader.database.SettingsRepository
import com.folio.reader.platform.FolioPlatform
import com.folio.reader.settings.CustomFont
import java.io.File

/**
 * Fonts shipped inside the app binary. They are extracted to the platform fonts
 * directory on first run and registered in the global settings' [CustomFont] list,
 * which is what both reader surfaces (WebView on Android, JCEF on desktop) turn
 * into @font-face rules. File names are deterministic so the same settings entry
 * works on every device.
 */
data class BundledFont(
    val displayName: String,
    val fileName: String,
    val familyName: String,
    val weight: Int = 400,
    /** Earlier bundled file names replaced by this entry; migrated out of settings. */
    val replaces: List<String> = emptyList()
)

object BundledFonts {
    val ALL = listOf(
        BundledFont(
            displayName = "Calluna",
            fileName = "calluna_regular.otf",
            familyName = "Calluna",
            replaces = listOf("calluna_regular.ttf")
        ),
        BundledFont(
            displayName = "Comfortaa",
            fileName = "comfortaa_variable.ttf",
            familyName = "Comfortaa"
        )
    )

    /**
     * Extracts any missing bundled font file and makes sure a [CustomFont] entry
     * exists for each one. Returns the entries that were newly added so callers can
     * refresh their in-memory settings. [readResource] resolves "fonts/<file>" to
     * raw bytes from the platform bundle (classpath on desktop, assets on Android).
     */
    suspend fun ensureInstalled(
        platform: FolioPlatform,
        settingsRepository: SettingsRepository,
        readResource: (String) -> ByteArray?
    ): List<CustomFont> {
        val fontsDir = platform.fileSystem.getFontsDir()
        if (!fontsDir.exists()) fontsDir.mkdirs()

        for (bundled in ALL) {
            val dest = File(fontsDir, bundled.fileName)
            if (!dest.exists() || dest.length() == 0L) {
                val bytes = readResource("fonts/${bundled.fileName}") ?: continue
                runCatching { dest.writeBytes(bytes) }
            }
            // Drop superseded bundled files (e.g. the Shancalluna stand-in).
            bundled.replaces.forEach { old -> File(fontsDir, old).takeIf { it.exists() }?.delete() }
        }

        val settings = runCatching { settingsRepository.getGlobalSettings() }.getOrNull() ?: return emptyList()
        val replaced = ALL.flatMap { it.replaces }.toSet()
        val kept = settings.customFonts.filterNot { it.fileName in replaced }
        val additions = ALL
            .filter { bundled -> kept.none { it.fileName == bundled.fileName } }
            .mapNotNull { bundled ->
                if (!File(fontsDir, bundled.fileName).exists()) return@mapNotNull null
                CustomFont(
                    id = bundled.fileName,
                    name = bundled.displayName,
                    fileName = bundled.fileName,
                    familyName = bundled.familyName,
                    weight = bundled.weight
                )
            }
        if (additions.isNotEmpty() || kept != settings.customFonts) {
            runCatching {
                settingsRepository.saveGlobalSettings(settings.copy(customFonts = kept + additions))
            }
        }
        return additions
    }
}
