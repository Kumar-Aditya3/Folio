package com.folio.reader.experience

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap

data class ExportFile(val name: String, val bytes: ByteArray)
interface ExperiencePlatform {
    fun soundscapeController(): SoundscapeController
    suspend fun saveImages(files: List<ExportFile>, share: Boolean): String?
    suspend fun copyImage(image: ImageBitmap)
    suspend fun journal(title: String, html: String, print: Boolean): String?
    fun openExportFolder()
    fun fontData(name: String): ByteArray?
    val nativeSharing: Boolean
}

@Composable expect fun rememberExperiencePlatform(): ExperiencePlatform
@Composable expect fun ReaderForegroundEffect(onEligibility: (Boolean) -> Unit)
expect fun encodeCardPng(image: ImageBitmap): ByteArray
expect fun decodeCardImage(bytes: ByteArray): ImageBitmap
