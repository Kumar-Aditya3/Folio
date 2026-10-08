package eu.kanade.tachiyomi.util.storage

import java.io.File
import java.io.IOException

/**
 * Copies this file to [target], setting [target] as read-only afterwards (required for
 * privately installed extension APKs on Android 14+).
 */
fun File.copyAndSetReadOnlyTo(target: File, overwrite: Boolean = false, bufferSize: Int = DEFAULT_BUFFER_SIZE) {
    if (target.exists() && !overwrite) {
        throw IOException("Target file already exists: $target")
    }

    target.parentFile?.mkdirs()

    inputStream().use { input ->
        target.outputStream().use { output ->
            input.copyTo(output, bufferSize)
        }
    }

    target.setReadOnly()
}
