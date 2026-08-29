package com.folio.reader.ui.render

import kotlinx.serialization.builtins.serializer

/**
 * Page dimming, drawn by the page itself.
 *
 * A Compose scrim cannot do this: on desktop the embedded browser is a heavyweight
 * window that paints over anything Compose draws above it. A fixed pseudo-element on
 * the root dims the paper uniformly on both engines, and unlike a `filter` on the root
 * it does not turn the document into a containing block for the page engine's
 * fixed-position stage or the glass overlays. Its z-index sits below those overlays so
 * the contents, annotations and settings panels stay at full strength.
 */
object PageDim {

    const val MIN_BRIGHTNESS = 0.15f

    fun css(brightness: Float): String {
        val dim = 1f - brightness.coerceIn(MIN_BRIGHTNESS, 1f)
        if (dim < 0.01f) return ""
        return "html::after{content:'';position:fixed;inset:0;background:#000;" +
                "opacity:${(dim * 100).toInt()}%;pointer-events:none;z-index:2147483400;}"
    }

    /**
     * Installs the dim as its own style element.
     *
     * Android rebuilds the whole reader stylesheet whenever a setting changes, which
     * reloads the chapter — acceptable for a font size picked once, unusable for a
     * brightness control that gets dragged. This rewrites one element in place.
     */
    fun applyJs(brightness: Float): String {
        val literal = kotlinx.serialization.json.Json.encodeToString(
            String.serializer(), css(brightness)
        )
        return "(function(){var s=document.getElementById('folio-dim-style');" +
                "if(!s){s=document.createElement('style');s.id='folio-dim-style';document.head.appendChild(s);}" +
                "s.textContent=$literal;})();"
    }
}
