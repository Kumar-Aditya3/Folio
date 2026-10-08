package com.folio.reader.ml

/**
 * Desktop: Phase 6 is deliberately absent. See the decision recorded in
 * `commonMain/.../ml/OnDeviceTextTools.kt`.
 *
 * The short version: ML Kit has no desktop artifact — `com.google.mlkit:*` is an Android AAR
 * and the translation models arrive through Play Services — and a desktop already has better
 * OCR built into its OS (Preview's text selection, `pdftotext`, Tesseract) than anything this
 * app would ship. The corpus here is EPUBs, which are text by construction. The cases that
 * actually need OCR — a photographed page, a downloaded manga chapter — are phone-shaped.
 *
 * This is a real implementation of the interface, not a stub that throws: it reports
 * [OcrAvailability.UnsupportedOnThisPlatform] so the UI can hide the affordance honestly, and
 * returns null from everything else so a caller that skipped that check degrades instead of
 * crashing.
 */
actual fun onDeviceTextTools(): OnDeviceTextTools = NoOpOnDeviceTextTools()
