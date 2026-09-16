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
        ),
        // Standard reading faces (Google Fonts, all OFL — licenses ship next to
        // the files). Variable builds, so every reader weight renders from the
        // one file on both platforms.
        BundledFont(
            displayName = "Literata",
            fileName = "literata_variable.ttf",
            familyName = "Literata"
        ),
        BundledFont(
            displayName = "Merriweather",
            fileName = "merriweather_variable.ttf",
            familyName = "Merriweather"
        ),
        BundledFont(
            displayName = "Lora",
            fileName = "lora_variable.ttf",
            familyName = "Lora"
        ),
        BundledFont(
            displayName = "EB Garamond",
            fileName = "ebgaramond_variable.ttf",
            familyName = "EB Garamond"
        ),
        BundledFont(
            displayName = "Open Sans",
            fileName = "opensans_variable.ttf",
            familyName = "Open Sans"
        ),
        BundledFont(
            displayName = "Inter",
            fileName = "inter_variable.ttf",
            familyName = "Inter"
        ),
        BundledFont(
            displayName = "Noto Serif",
            fileName = "notoserif_variable.ttf",
            familyName = "Noto Serif"
        )
    )

    /**
     * The interface faces. Extracted next to the reader fonts so [UiFonts] can load
     * them from a file, but deliberately kept out of [ALL]: they are app chrome
     * (Fraunces for display, Manrope for everything functional), not typefaces to
     * read a book in.
     */
    val UI_ONLY = listOf(
        "fraunces_variable.ttf",
        "fraunces_italic_variable.ttf",
        "manrope_variable.ttf",
        "shancalluna_regular.ttf"
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
        UI_ONLY.forEach { name ->
            val dest = File(fontsDir, name)
            if (!dest.exists() || dest.length() == 0L) {
                readResource("fonts/$name")?.let { bytes -> runCatching { dest.writeBytes(bytes) } }
            }
        }

        val before = runCatching { settingsRepository.getGlobalSettings() }.getOrNull()
            ?.customFonts?.mapTo(mutableSetOf()) { it.fileName } ?: emptySet()
        val replaced = ALL.flatMap { it.replaces }.toSet()
        val available = ALL.mapNotNull { bundled ->
            if (!File(fontsDir, bundled.fileName).exists()) return@mapNotNull null
            CustomFont(
                id = bundled.fileName,
                name = bundled.displayName,
                fileName = bundled.fileName,
                familyName = bundled.familyName,
                weight = bundled.weight
            )
        }
        // Merge, so a settings write landing between the read above and this
        // save is not clobbered. No change means no write (and no sync event).
        runCatching {
            settingsRepository.mergeGlobalSettings { current ->
                val kept = current.customFonts.filterNot { it.fileName in replaced }
                val missing = available.filterNot { a -> current.customFonts.any { it.fileName == a.fileName } }
                if (kept == current.customFonts && missing.isEmpty()) current
                else current.copy(customFonts = kept + missing)
            }
        }
        return available.filter { it.fileName !in before }
    }
}
