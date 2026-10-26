package com.folio.reader.experience

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import javax.swing.JFileChooser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

actual fun encodeCardPng(image: ImageBitmap): ByteArray = ByteArrayOutputStream().use { ImageIO.write(image.toAwtImage(), "png", it); it.toByteArray() }
actual fun decodeCardImage(bytes: ByteArray) = org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
@Composable actual fun rememberExperiencePlatform(): ExperiencePlatform = remember { DesktopExperiencePlatform() }
@Composable actual fun ReaderForegroundEffect(onEligibility: (Boolean) -> Unit) {
    val current by rememberUpdatedState(onEligibility)
    DisposableEffect(Unit) {
        val manager = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager()
        val pauseTimer = javax.swing.Timer(60) { if (manager.activeWindow == null) current(false) }.apply { isRepeats = false }
        val listener = java.beans.PropertyChangeListener {
            // Owned export dialogs remain part of reading; another app has no active AWT window.
            if (manager.activeWindow == null) pauseTimer.restart() else { pauseTimer.stop(); current(true) }
        }
        manager.addPropertyChangeListener("activeWindow", listener)
        current(manager.activeWindow != null)
        onDispose { pauseTimer.stop(); current(false); manager.removePropertyChangeListener("activeWindow", listener) }
    }
}

private class DesktopExperiencePlatform : ExperiencePlatform {
    private var lastFolder: File? = null
    override val nativeSharing = false
    override fun soundscapeController() = SoundscapeController(load = { scene ->
        val resource = javaClass.classLoader.getResourceAsStream("soundscapes/${scene.name.lowercase()}.wav") ?: error("Missing soundscape")
        resource.use { readSoundscapeWav(it.readBytes()) }
    }, output = { object : PcmOutput {
        val line = AudioSystem.getSourceDataLine(AudioFormat(44100f, 16, 1, true, false)).apply { open(formatForAudio(), 8820) }
        private val buffer = ByteArray(SoundscapeController.BLOCK * 2)
        override fun start() { if (!line.isRunning) line.start() }
        override fun write(samples: ShortArray) {
            samples.forEachIndexed { i, v -> buffer[i * 2] = v.toByte(); buffer[i * 2 + 1] = (v.toInt() shr 8).toByte() }
            check(line.write(buffer, 0, samples.size * 2) == samples.size * 2)
        }
        override fun pause() { line.stop(); line.flush() }
        override fun close() { try { pause() } finally { line.close() } }
    } })
    override suspend fun saveImages(files: List<ExportFile>, share: Boolean): String? {
        val destination = choose(if (files.size == 1) files.first().name else "", files.size != 1) ?: return null
        return withContext(Dispatchers.IO) {
            if (files.size == 1) { destination.writeBytes(files.first().bytes); lastFolder = destination.parentFile }
            else { files.forEach { item -> File(destination, item.name).writeBytes(item.bytes) }; lastFolder = destination }
            "Saved ${files.size} image${if (files.size == 1) "" else "s"}"
        }
    }
    override suspend fun copyImage(image: ImageBitmap) {
        val awt = image.toAwtImage()
        Toolkit.getDefaultToolkit().systemClipboard.setContents(object : Transferable {
            override fun getTransferDataFlavors() = arrayOf(DataFlavor.imageFlavor)
            override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.imageFlavor
            override fun getTransferData(flavor: DataFlavor): Any { require(isDataFlavorSupported(flavor)); return awt }
        }, null)
    }
    override suspend fun journal(title: String, html: String, print: Boolean): String? {
        val destination = if (print) File.createTempFile("folio-journal-", ".pdf") else choose("Folio reading journal.pdf", false) ?: return null
        com.folio.reader.ui.render.exportJournalPdf(html, destination)
        lastFolder = destination.parentFile
        if (print) { Desktop.getDesktop().print(destination); return null }
        return "Saved ${destination.name}"
    }
    override fun openExportFolder() { lastFolder?.let { Desktop.getDesktop().open(it) } }
    override fun fontData(name: String) = javaClass.classLoader.getResourceAsStream("fonts/$name")?.use { it.readBytes() }
    private suspend fun choose(name: String, directory: Boolean): File? = suspendCancellableCoroutine { continuation ->
        javax.swing.SwingUtilities.invokeLater {
            val picker = JFileChooser(lastFolder).apply {
                if (directory) fileSelectionMode = JFileChooser.DIRECTORIES_ONLY else selectedFile = File(name)
            }
            val selected = if (picker.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) picker.selectedFile else null
            if (continuation.isActive) continuation.resume(selected)
        }
    }
}
private fun formatForAudio() = AudioFormat(44100f, 16, 1, true, false)
