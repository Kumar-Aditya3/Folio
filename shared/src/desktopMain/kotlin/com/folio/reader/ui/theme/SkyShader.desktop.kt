package com.folio.reader.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Desktop side of the skyscape shader: AGSL is Android-only, so the field keeps
 * [folioField]'s portable Canvas skyscape, byte-for-byte (Rule 1). A no-op, exactly
 * like [folioAmbientShader] on desktop.
 */
@Composable
actual fun Modifier.folioSkyShader(enabled: Boolean): Modifier = this
