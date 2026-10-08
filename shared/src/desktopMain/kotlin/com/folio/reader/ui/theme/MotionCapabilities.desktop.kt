package com.folio.reader.ui.theme

import androidx.compose.runtime.Composable

@Composable
actual fun rememberMotionEnabled(): Boolean = true

@Composable
actual fun rememberBlurSupported(): Boolean = true

// AGSL is Android-only; desktop gradients stay static Brushes by design.
@Composable
actual fun rememberShaderSupported(): Boolean = false

// Desktop has no haptics; the glass press tick is a silent no-op there.
@Composable
actual fun rememberGlassTick(): () -> Unit = {}
