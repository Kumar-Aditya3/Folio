package com.folio.reader.tumble

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.folio.reader.experience.decodeCardImage

private var cachedSableAtlas: ImageBitmap? = null
@Composable internal actual fun rememberSableAtlas(): ImageBitmap {
    val context=LocalContext.current.applicationContext
    return remember(context) {cachedSableAtlas ?: context.assets.open("companion/sable-poses-v2.png").use {decodeCardImage(it.readBytes())}.also {cachedSableAtlas=it}}
}
