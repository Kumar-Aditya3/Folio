package com.folio.reader.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The per-theme **skyscape**, rendered.
 *
 * The field's signature (dark = deep-space nebula + stars + a far planet; light = an
 * ethereal daylight sky) wants structure the Canvas scatter could never give it —
 * wispy clouds, soft star bloom, a lit limb. This draws it with one AGSL fragment
 * shader on Android 13+ (domain-warped FBM nebula, a hashed multi-scale star field,
 * an SDF planet with a ring), driven entirely by the theme's own [FolioSignature]
 * colours as uniforms, animated on the house slow clock.
 *
 * Contract (Rule 19): the full effect needs `RuntimeShader` (API 33+). Below that, on
 * desktop, in previews and under reduce-motion the actual is a **no-op** and
 * [folioField]'s portable Canvas skyscape stands in — the two are gated on the same
 * [rememberShaderSupported], so exactly one of them ever draws.
 *
 * Legibility is unchanged: the shader fades to its content-band floor through the
 * middle of the screen (where cardless body text lives) and `folioClearing` still
 * parts it behind that text.
 */
@Composable
expect fun Modifier.folioSkyShader(enabled: Boolean): Modifier
