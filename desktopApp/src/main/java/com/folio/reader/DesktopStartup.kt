package com.folio.reader

import java.io.File
import java.io.FileOutputStream
import java.io.PrintStream
import java.awt.Component
import java.awt.Container
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import org.jetbrains.skiko.SkiaLayer

internal fun logDesktopRenderer(component: Component) {
    if (component is SkiaLayer) println("Folio renderer=${component.renderApi}")
    if (component is Container) component.components.forEach(::logDesktopRenderer)
}

/** GUI launchers have no console: keep startup failures visible and diagnosable. */
internal fun installDesktopDiagnostics() {
    val log = runCatching {
        val root = System.getenv("LOCALAPPDATA")?.let { File(it, "Folio") }
            ?: File(System.getProperty("user.home"), ".folio")
        root.mkdirs()
        File(root, "folio.log").also { file ->
            if (file.length() > 2 * 1024 * 1024) file.copyTo(File(root, "folio.previous.log"), overwrite = true)
            val stream = PrintStream(FileOutputStream(file, file.length() <= 2 * 1024 * 1024), true, Charsets.UTF_8)
            System.setOut(stream)
            System.setErr(stream)
        }
    }.getOrNull()
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
        if (isComposeSceneClosedException(failure)) return@setDefaultUncaughtExceptionHandler
        System.err.println("${java.time.Instant.now()} Uncaught failure on ${thread.name}")
        failure.printStackTrace(System.err)
        previous?.uncaughtException(thread, failure)
        if (thread.name == "main") {
            runCatching {
                SwingUtilities.invokeAndWait {
                    JOptionPane.showMessageDialog(null,
                        "The app could not start.\n${failure.message ?: failure.javaClass.simpleName}" +
                            (log?.let { "\n\nDetails: ${it.absolutePath}" } ?: ""),
                        "Folio could not start", JOptionPane.ERROR_MESSAGE)
                }
            }
        }
    }
}
