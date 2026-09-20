package com.folio.reader.ml

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 6's seam, from the desktop side.
 *
 * The plan asked for an *explicit* decision on the Android-only limitation, and a decision that
 * only exists in a comment is not enforced by anything. These tests are the enforcement: they
 * pin the behaviour the decision promises, so a future change that makes desktop claim OCR is
 * available — or that makes it throw instead of degrading — fails here rather than on a user's
 * laptop.
 *
 * The four properties that matter:
 *
 * 1. Desktop reports `UnsupportedOnThisPlatform` for **every** script, so no script can
 *    accidentally look supported and light up an affordance that does nothing.
 * 2. Nothing throws. A caller that skipped the availability check gets null, not a crash —
 *    the interface's contract is "null means no result", and that has to hold on the platform
 *    where the feature is absent.
 * 3. `isUsable` is false, because that is the flag the UI actually branches on.
 * 4. `close()` is safe with nothing open, since the caller closes on dispose regardless.
 */
class OnDeviceTextToolsDesktopTest {

    private val tools: OnDeviceTextTools = onDeviceTextTools()

    @Test
    fun `every script reports unsupported on desktop`() = runBlocking {
        OcrScript.entries.forEach { script ->
            val availability = tools.isAvailable(script)
            assertEquals(
                OcrAvailability.UnsupportedOnThisPlatform, availability,
                "desktop has no ML Kit; ${script.label} must not claim otherwise",
            )
            assertFalse(
                availability.isUsable,
                "${script.label} must not be usable — the UI branches on this to hide the button",
            )
        }
    }

    @Test
    fun `recognition returns null rather than throwing`() = runBlocking {
        // Deliberately not a valid image: the point is that even malformed input degrades to
        // null here, because desktop never gets far enough to decode it.
        assertNull(tools.recognise(byteArrayOf(1, 2, 3)))
        assertNull(tools.recognise(ByteArray(0)))
        OcrScript.entries.forEach { script ->
            assertNull(tools.recognise(byteArrayOf(0xFF.toByte()), script))
        }
    }

    @Test
    fun `translation returns null rather than throwing`() = runBlocking {
        assertNull(tools.translate("bonjour", targetLanguageTag = "en"))
        assertNull(tools.translate("bonjour", targetLanguageTag = "en", sourceLanguageTag = "fr"))
        assertNull(tools.translate("", targetLanguageTag = "en"), "blank input is a no-op")
    }

    @Test
    fun `model preparation reports false instead of pretending to download`() = runBlocking {
        assertFalse(tools.prepareTranslation("ja", "en"))
        assertFalse(tools.prepareTranslation("en", "hi"))
    }

    @Test
    fun `close is safe with nothing open and can be called twice`() = runBlocking {
        tools.close()
        tools.close()
        assertTrue(true, "closing an idle seam must not throw")
    }

    @Test
    fun `the availability type keeps absent separate from missing`() {
        // The reason this is a sealed interface rather than a Boolean pair: on desktop the
        // whole feature is absent, whereas on Android a script may simply not be downloaded
        // yet. Collapsing them would put "download the Japanese model" on a desktop that can
        // never use it.
        val absent: OcrAvailability = OcrAvailability.UnsupportedOnThisPlatform
        val missing: OcrAvailability = OcrAvailability.ScriptMissing(OcrScript.JAPANESE)

        assertFalse(absent.isUsable)
        assertFalse(missing.isUsable)
        assertTrue(absent != missing, "the two must remain distinguishable")
        assertTrue(OcrAvailability.Available.isUsable)
    }

    @Test
    fun `TextRegion derives its centre from the box`() {
        val region = TextRegion(text = "hello", left = 10, top = 20, right = 50, bottom = 40)
        assertEquals(30, region.centerX)
        assertEquals(30, region.centerY)
    }
}
