package com.folio.reader.ui.render

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * HTML overlay panels (contents/annotations/settings) that the desktop surface
 * renders inside the page. ReaderScreen provides these on platforms where the
 * browser surface is heavyweight and would otherwise cover Compose overlays;
 * the surfaces consume them. Null/empty means no overlay is open.
 */
val LocalOverlayHtml = compositionLocalOf<String?> { null }

/** Receives overlay actions: close, toc:<i>, set:<k>:<v>, del:<kind>:<id>, allsettings. */
val LocalOverlayAction = staticCompositionLocalOf<(String) -> Unit> { {} }
