package com.folio.reader.font

import com.folio.reader.platform.FolioPlatform
import com.folio.reader.settings.CustomFont
import java.io.File

class FontManager(private val platform: FolioPlatform) {
    private val fontCache = mutableMapOf<String, Any>()
    private val familyCache = mutableMapOf<String, String>()

    fun importFont(sourceFile: File, name: String): CustomFont? {
        val fontsDir = platform.fileSystem.getFontsDir()
        if (!fontsDir.exists()) fontsDir.mkdirs()
        
        val ext = sourceFile.extension.lowercase()
        if (ext != "ttf" && ext != "otf") return null
        
        val fontId = "${name.replace(Regex("[^a-zA-Z0-9]"), "_").lowercase()}_${
            System.currentTimeMillis()
        }.$ext"
        val dest = File(fontsDir, fontId)
        
        return try {
            sourceFile.copyTo(dest, overwrite = true)
            val family = readFamilyName(dest) ?: name
            registerWithDesktopGraphicsEnvironment(dest)
            val isVariable = dest.length() > 1_000_000 // Variable fonts are typically larger
            
            fontCache[fontId] = dest
            familyCache[fontId] = family
            
            CustomFont(
                id = fontId,
                name = name,
                fileName = fontId,
                familyName = family,
                weight = 400,
                isVariable = isVariable
            )
        } catch (e: Exception) {
            dest.delete()
            null
        }
    }

    fun removeFont(fontId: String): Boolean {
        val fontsDir = platform.fileSystem.getFontsDir()
        val file = File(fontsDir, fontId)
        val deleted = file.delete()
        fontCache.remove(fontId)
        familyCache.remove(fontId)
        return deleted
    }

    fun getAvailableFonts(): List<CustomFont> {
        val fontsDir = platform.fileSystem.getFontsDir()
        if (!fontsDir.exists()) return emptyList()
        
        return fontsDir.listFiles()?.filter { 
            it.extension.lowercase() in listOf("ttf", "otf") 
        }?.mapNotNull { file ->
            try {
                val family = readFamilyName(file) ?: file.nameWithoutExtension
                fontCache[file.name] = file
                familyCache[file.name] = family
                
                CustomFont(
                    id = file.name,
                    name = file.nameWithoutExtension,
                    fileName = file.name,
                    familyName = family,
                    weight = 400,
                    isVariable = file.length() > 1_000_000
                )
            } catch (e: Exception) {
                null
            }
        } ?: emptyList()
    }

    fun getFontFile(fontId: String): File? {
        val fontsDir = platform.fileSystem.getFontsDir()
        val file = File(fontsDir, fontId)
        return if (file.exists()) file else null
    }

    fun getFontFamilyName(fontId: String): String? {
        if (familyCache.containsKey(fontId)) return familyCache[fontId]
        val file = getFontFile(fontId) ?: return null
        return try {
            val family = readFamilyName(file) ?: file.nameWithoutExtension
            fontCache[fontId] = file
            familyCache[fontId] = family
            family
        } catch (e: Exception) {
            null
        }
    }

    fun clearCache() {
        fontCache.clear()
        familyCache.clear()
    }

    /**
     * FontManager is shared by Android and Desktop. Keep platform font APIs
     * reflective here so Android never tries to load java.awt classes.
     */
    private fun readFamilyName(file: File): String? {
        val isAndroid = runCatching {
            Class.forName("android.graphics.Typeface", false, javaClass.classLoader)
        }.isSuccess
        return if (isAndroid) {
            runCatching {
                val typeface = Class.forName("android.graphics.Typeface")
                    .getMethod("createFromFile", File::class.java).invoke(null, file)
                typeface.javaClass.getMethod("getFamily").invoke(typeface) as? String
            }.getOrNull()
        } else {
            runCatching {
                val fontClass = Class.forName("java.awt.Font")
                val fontType = fontClass.getField("TRUETYPE_FONT").getInt(null)
                val font = fontClass.getMethod("createFont", Int::class.javaPrimitiveType, File::class.java)
                    .invoke(null, fontType, file)
                fontClass.getMethod("getFamily").invoke(font) as? String
            }.getOrNull()
        }
    }

    private fun registerWithDesktopGraphicsEnvironment(file: File) {
        runCatching {
            val fontClass = Class.forName("java.awt.Font")
            val fontType = fontClass.getField("TRUETYPE_FONT").getInt(null)
            val font = fontClass.getMethod("createFont", Int::class.javaPrimitiveType, File::class.java)
                .invoke(null, fontType, file)
            val environment = Class.forName("java.awt.GraphicsEnvironment")
                .getMethod("getLocalGraphicsEnvironment").invoke(null)
            environment.javaClass.getMethod("registerFont", fontClass).invoke(environment, font)
        }
    }
}
