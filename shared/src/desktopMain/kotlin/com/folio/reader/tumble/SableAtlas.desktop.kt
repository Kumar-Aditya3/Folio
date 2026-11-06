package com.folio.reader.tumble

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.ImageBitmap
import com.folio.reader.experience.decodeCardImage

private val sableAtlas: ImageBitmap by lazy {
    requireNotNull(object {}.javaClass.classLoader.getResourceAsStream("companion/sable-poses-v2.png")) {"Missing Sable art"}.use {decodeCardImage(it.readBytes())}
}
@Composable internal actual fun rememberSableAtlas(): ImageBitmap = remember {sableAtlas}
