package com.folio.reader.ui.theme

import androidx.compose.runtime.Composable

/**
 * Rule 19 capability flags — every §13 effect reads these instead of probing the
 * platform at the call site, so the degradation path is decided in one place.
 * Motion off (`ANIMATOR_DURATION_SCALE = 0`) means every effect renders its
 * final static form; blur needs API 31; AGSL shaders need API 33.
 */
@Composable
expect fun rememberMotionEnabled(): Boolean

/** `Modifier.blur` renders; below API 31 glass fills raise alpha instead. */
@Composable
expect fun rememberBlurSupported(): Boolean

/** `RuntimeShader`/AGSL available; below API 33 gradients are static Brushes. */
@Composable
expect fun rememberShaderSupported(): Boolean

/**
 * §16's settle tick: a low-amplitude haptic for glass press-settle — the nav
 * capsule and sheet handles only, nowhere else (app-wide press haptics would be
 * noise). API 31+ composes `VibrationEffect.PRIMITIVE_TICK`; below that it falls
 * back to the platform's softest standard feedback; desktop is a no-op.
 */
@Composable
expect fun rememberGlassTick(): () -> Unit
