package com.folio.reader.experience

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.folio.reader.ui.theme.*

@Composable fun ReadingBookmarkGlyph(saved: Boolean, tint: Color) {
    val reduced = LocalMotion.current.reduced
    val fraction by animateFloatAsState(if (saved) 1f else 0f, rememberMotionAnimation<Float>(MotionKey.BookmarkRibbon), label = "Bookmark ribbon")
    Canvas(Modifier.size(19.dp).semantics { contentDescription = if (saved) "Remove bookmark" else "Bookmark this spot" }) {
        val w = size.width; val h = size.height
        val ribbon = Path().apply { moveTo(w*.22f,h*.12f); lineTo(w*.78f,h*.12f); lineTo(w*.78f,h*.9f); lineTo(w*.5f,h*.7f); lineTo(w*.22f,h*.9f); close() }
        drawPath(ribbon,tint, style = Stroke(1.5.dp.toPx()))
        clipRect(bottom = h * if (reduced) { if (saved) 1f else 0f } else fraction) { drawPath(ribbon,tint) }
    }
}
