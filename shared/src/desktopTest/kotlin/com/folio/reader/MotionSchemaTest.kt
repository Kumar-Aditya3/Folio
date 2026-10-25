package com.folio.reader

import com.folio.reader.settings.MotionPreferences
import com.folio.reader.settings.MotionPreset
import com.folio.reader.settings.ReduceMotionMode
import com.folio.reader.ui.theme.FolioFieldKind
import com.folio.reader.ui.theme.FolioTokens
import com.folio.reader.ui.theme.FieldMotion
import com.folio.reader.ui.theme.MotionChannel
import com.folio.reader.ui.theme.MotionKey
import com.folio.reader.ui.theme.MotionTier
import com.folio.reader.ui.theme.ResolvedMotion
import com.folio.reader.ui.theme.FolioFieldCost
import com.folio.reader.ui.theme.fieldRenderScale
import com.folio.reader.ui.theme.fieldSpeed
import com.folio.reader.ui.theme.isClockless
import com.folio.reader.ui.theme.isLoop
import com.folio.reader.ui.theme.motionProfile
import com.folio.reader.ui.theme.motionTierFor
import com.folio.reader.ui.theme.resolveMotion
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The app's motion, as a contract.
 *
 * Two things are being defended here.
 *
 * **That the table is the old behaviour.** The whole system was built by moving shipped literals into
 * one place, so the first job of this file is to prove nothing moved: the keys that came from
 * [FolioTokens] must still equal it, and the families' rates must still be the numbers the Android
 * dispatch shipped.
 *
 * **That the reader's levers cannot break the design.** A preset or a lever is a multiplier, so it
 * can make an animation faster or slower but must never produce a duration outside the tuned band, a
 * spring where a tween belongs, or — the failure that matters most — an invisible state change or a
 * *frozen* background under reduce-motion. The fuzz test at the bottom walks every combination of
 * preset, lever, tier and reduce mode for every key, which is the only way to be sure a future lever
 * cannot open a hole.
 */
class MotionSchemaTest {

    private val keys = MotionKey.entries

    // ── The table is the shipped behaviour ─────────────────────────────────────

    @Test
    fun theTableKeepsTheTokensItCameFrom() {
        assertEquals(FolioTokens.motionMorph, motionProfile(MotionKey.CoverMorph).durationMs)
        assertEquals(FolioTokens.motionTabMorph, motionProfile(MotionKey.TabDissolve).durationMs)
        assertEquals(FolioTokens.motionEmphasis, motionProfile(MotionKey.SheetEnter).durationMs)
        assertEquals(FolioTokens.motionStandard, motionProfile(MotionKey.NavPush).durationMs)
        assertEquals(FolioTokens.motionStandard, motionProfile(MotionKey.ReaderChromeToggle).durationMs)
        assertEquals(FolioTokens.motionShimmer, motionProfile(MotionKey.ShimmerSweep).durationMs)
        assertEquals(FolioTokens.motionLiquidSweep, motionProfile(MotionKey.LiquidSweep).durationMs)
        assertEquals(FolioTokens.chipAutoDismiss, motionProfile(MotionKey.ChipDwell).dwellMs)
        // `motionFast + 60` in, `motionFast` out — the value that used to be recomputed in three
        // files now has one definition.
        assertEquals(FolioTokens.motionFast + 60, motionProfile(MotionKey.ShelfFadeSwap).durationMs)
    }

    @Test
    fun theOverlayHostsOwnConstantsSurvive() {
        // These two were private constants in FolioOverlayHost rather than tokens; the table must
        // still reproduce them exactly, since the overlay's feel is the app's most-used transition.
        assertEquals(160L, motionProfile(MotionKey.OverlayEnter).durationMs)
        assertEquals(110L, motionProfile(MotionKey.OverlayExit).durationMs)
    }

    @Test
    fun theFieldRatesAreTheOnesTheDispatchShipped() {
        // A sample across the range, including the fastest and slowest, so a future edit to the
        // table cannot quietly retune the room.
        assertEquals(0.3f, FolioFieldKind.Swirl.fieldSpeed())
        assertEquals(0.3f, FolioFieldKind.GrainGradient.fieldSpeed())
        assertEquals(0.9f, FolioFieldKind.MeshGradient.fieldSpeed())
        assertEquals(0.9f, FolioFieldKind.Water.fieldSpeed())
        assertEquals(0.9f, FolioFieldKind.DotOrbit.fieldSpeed())
        assertEquals(0.85f, FolioFieldKind.Warp.fieldSpeed())
    }

    @Test
    fun voronoiFacetsDriftMoreSlowlyThanTheRevolvingPanels() {
        assertEquals(0.8f, FolioFieldKind.Voronoi.fieldSpeed())
        assertEquals(0.9f, FolioFieldKind.ColorPanels.fieldSpeed())
        val reduced = resolveMotion(MotionPreferences(), MotionTier.STANDARD, systemReduced = true)
        val speed = requireNotNull(FolioFieldKind.Voronoi.fieldSpeed())
        assertTrue(speed * reduced.fieldSpeedFactor > 0f)
        assertTrue(speed * reduced.fieldSpeedFactor < speed)
    }

    @Test
    fun theSlowLayersKeepTheirPeriods() {
        assertEquals(47_000L, FieldMotion.ambientSwingPeriodMs)
        assertEquals(71_000L, FieldMotion.ambientBreathPeriodMs)
        assertEquals(24_000L, FieldMotion.rimPeriodMs)
        assertEquals(61_000L, FieldMotion.lampPeriodMs)
        assertEquals(3, FieldMotion.silkPeriodsMs.size)
        // The periods reach the table, so the ambient layers are resolvable like everything else.
        assertEquals(FieldMotion.ambientSwingPeriodMs, motionProfile(MotionKey.AmbientSwing).durationMs)
        assertEquals(FieldMotion.rimTickMs, motionProfile(MotionKey.RimBeam).tickMs)
    }

    // ── Coverage: nothing may be silently missed ───────────────────────────────

    @Test
    fun everyKeyHasASpecAndAChannel() {
        // `motionProfile` is an exhaustive `when`, so this is really a guard that the enum and the
        // table cannot drift apart — a key added without a row fails to compile, and this fails if a
        // row is added with the wrong channel.
        keys.forEach { key ->
            val spec = motionProfile(key)
            assertNotNull(spec.channel, "$key has no channel")
            assertTrue(spec.durationMs >= 0, "$key has a negative duration")
            assertTrue(spec.intensity in 0f..1f, "$key has an out-of-range intensity")
        }
        // The background rows are the FIELD channel and nothing else is, which is what keeps the
        // field levers off the foreground.
        val fieldKeys = keys.filter { motionProfile(it).channel == MotionChannel.FIELD }
        assertEquals(
            setOf(
                MotionKey.FieldMotion, MotionKey.FieldIntensity, MotionKey.FieldScale,
                MotionKey.AmbientSwing,
                MotionKey.AmbientBreath, MotionKey.SilkDrift, MotionKey.RimBeam, MotionKey.LampBreath,
            ),
            fieldKeys.toSet(),
        )
    }

    @Test
    fun everyFieldFamilyHasARateAndOnlyTheClocklessOnesAreNull() {
        val clockless = FolioFieldKind.entries.filter { it.isClockless() }
        assertEquals(
            setOf(
                FolioFieldKind.DotGrid, FolioFieldKind.HalftoneDots,
                FolioFieldKind.StaticMeshGradient, FolioFieldKind.StaticRadialGradient,
            ),
            clockless.toSet(),
            "the set of families with no u_time uniform must not change silently",
        )
        assertEquals(0.85f, FolioFieldKind.Waves.fieldSpeed(), "Waves now has its own travelling phase")
        FolioFieldKind.entries.forEach { kind ->
            val speed = kind.fieldSpeed()
            if (kind.isClockless()) {
                assertNull(speed, "$kind cannot move, so it must have no rate")
            } else {
                assertNotNull(speed, "$kind moves, so it needs a rate")
                assertTrue(speed in 0.1f..2f, "$kind's rate $speed is outside the authored band")
            }
        }
    }

    // ── The user layer cannot invent values ────────────────────────────────────

    @Test
    fun thePersistedRecordHoldsOnlyThePresetTheLeversAndTheMode() {
        // The separation contract: no tuned duration, easing or curve is reachable from Settings.
        // Serializing with defaults on is the honest check — every key that can be persisted shows
        // up, and nothing else can.
        val json = Json { encodeDefaults = true }.encodeToString(MotionPreferences.serializer(), MotionPreferences())
        assertEquals(
            setOf(
                "preset", "reduceMotion", "fieldMotion", "fieldIntensity", "fieldScale",
                "readerChrome", "sheetsOverlays", "coverMorph", "progressFeedback", "chartEntry",
            ),
            Json.parseToJsonElement(json).jsonObject.keys,
        )
    }

    @Test
    fun standardWithNoLeversIsTheIdentity() {
        val resolved = resolveMotion(MotionPreferences(), MotionTier.STANDARD, systemReduced = false)
        keys.forEach { key ->
            assertEquals(motionProfile(key).durationMs, resolved[key].durationMs, "$key moved under STANDARD")
            assertEquals(motionProfile(key).intensity, resolved[key].intensity, "$key changed intensity under STANDARD")
        }
        assertEquals(1f, resolved.fieldSpeedFactor)
        assertEquals(1f, resolved.fieldIntensityFactor)
        assertTrue(!resolved.reduced)
    }

    @Test
    fun theDefaultIsWhatAnUnprovidedTreeGets() {
        // Preview is the contract for desktop, previews and tests: the shipped motion, nothing else.
        val preview = ResolvedMotion.Preview
        assertEquals(motionProfile(MotionKey.CoverMorph).durationMs, preview[MotionKey.CoverMorph].durationMs)
        assertTrue(!preview.reduced)
    }

    // ── Presets ────────────────────────────────────────────────────────────────

    @Test
    fun calmIsSlowerAndLivelyIsFasterForEveryTransition() {
        val calm = resolveMotion(MotionPreferences(preset = MotionPreset.CALM), MotionTier.STANDARD, false)
        val standard = resolveMotion(MotionPreferences(preset = MotionPreset.STANDARD), MotionTier.STANDARD, false)
        val lively = resolveMotion(MotionPreferences(preset = MotionPreset.LIVELY), MotionTier.STANDARD, false)
        keys.filter { it !in backgroundKeys && it != MotionKey.ChipDwell }
            .forEach { key ->
                val base = standard[key]
                if (base.durationMs == 0L) return@forEach   // springs have no duration to scale
                assertTrue(
                    calm[key].durationMs >= base.durationMs,
                    "$key must not get faster under CALM (${calm[key].durationMs} < ${base.durationMs})",
                )
                assertTrue(
                    lively[key].durationMs <= base.durationMs,
                    "$key must not get slower under LIVELY (${lively[key].durationMs} > ${base.durationMs})",
                )
            }
    }

    @Test
    fun theFieldLeversRunTheOtherWayAndAreNotDurations() {
        val calm = resolveMotion(MotionPreferences(preset = MotionPreset.CALM), MotionTier.STANDARD, false)
        val lively = resolveMotion(MotionPreferences(preset = MotionPreset.LIVELY), MotionTier.STANDARD, false)
        // CALM is the *slower* room: a lower speed factor and therefore a *longer* period.
        assertTrue(calm.fieldSpeedFactor < lively.fieldSpeedFactor)
        assertTrue(calm[MotionKey.AmbientSwing].durationMs > lively[MotionKey.AmbientSwing].durationMs)
        // And a calmer room is a quieter one.
        assertTrue(calm.fieldIntensityFactor < lively.fieldIntensityFactor)
        // No profile changes the room's size: a profile is a tempo.
        assertEquals(1f, calm.fieldScaleFactor)
        assertEquals(1f, lively.fieldScaleFactor)
    }

    @Test
    fun thePatternSizeLeverIsTheReadersOwnAndDefaultsToOne() {
        // Size is taste, not tempo and not cost: no preset moves it, no tier moves it, and an
        // untouched install resolves to exactly the size the library renders by default.
        val base = resolveMotion(MotionPreferences(), MotionTier.STANDARD, false)
        assertEquals(1f, base.fieldScaleFactor)
        val big = resolveMotion(MotionPreferences(fieldScale = 2f), MotionTier.STANDARD, false)
        assertEquals(2f, big.fieldScaleFactor)
        // And it reaches only the field — never a foreground animation. Same tier, same reduce
        // state on both sides, so the only difference between the two resolutions is the lever.
        keys.filter { motionProfile(it).channel != MotionChannel.FIELD }.forEach { key ->
            assertEquals(base[key].durationMs, big[key].durationMs, "$key moved with the size lever")
        }
    }

    @Test
    fun aLeverBeatsThePreset() {
        // A lever *overrides* its channel's preset factor rather than composing with it: a reader
        // who sets the chrome lever is stating what they want, not a nudge on the profile.
        val presetOnly = resolveMotion(MotionPreferences(preset = MotionPreset.CALM), MotionTier.STANDARD, false)
        val withLever = resolveMotion(
            MotionPreferences(preset = MotionPreset.CALM, readerChrome = 1.5f),
            MotionTier.STANDARD, false,
        )
        val key = MotionKey.ReaderChromeToggle
        assertTrue(
            withLever[key].durationMs < presetOnly[key].durationMs,
            "the lever must win over CALM's slower chrome",
        )
        // And it resolves to the same value the lever gives at STANDARD — the proof that it replaced
        // the preset factor instead of multiplying it (0.75 × 1.5 would have been 1.125).
        val standardWithLever = resolveMotion(
            MotionPreferences(preset = MotionPreset.STANDARD, readerChrome = 1.5f),
            MotionTier.STANDARD, false,
        )
        assertEquals(standardWithLever[key].durationMs, withLever[key].durationMs)
    }

    // ── Device tiers ───────────────────────────────────────────────────────────

    @Test
    fun theTierIsDerivedFromTheGlassBudgetAndBlurSupport() {
        assertEquals(MotionTier.STANDARD, motionTierFor(glassBudget = 1f, canBlur = true))
        assertEquals(MotionTier.CONSTRAINED, motionTierFor(glassBudget = 0.5f, canBlur = true))
        assertEquals(MotionTier.CONSTRAINED, motionTierFor(glassBudget = 1f, canBlur = false))
    }

    @Test
    fun aConstrainedDeviceGetsCheaperMotionAndNotDifferentMotion() {
        val standard = resolveMotion(MotionPreferences(), MotionTier.STANDARD, false)
        val constrained = resolveMotion(MotionPreferences(), MotionTier.CONSTRAINED, false)
        // Springs become tweens — the cheap shape — but still animate.
        val spring = standard[MotionKey.PressScale].spring
        assertNotNull(spring)
        assertNull(constrained[MotionKey.PressScale].spring)
        assertTrue(constrained[MotionKey.PressScale].durationMs >= 40L)
        // The morph is clamped harder.
        assertTrue(constrained[MotionKey.CoverMorph].durationMs <= 380L)
        // The field is quieter.
        assertTrue(constrained.fieldIntensityFactor < standard.fieldIntensityFactor)
        // But the design is intact: the reader's animations still have their own durations.
        assertTrue(constrained[MotionKey.SelectionToolbar].durationMs > 0L)
    }

    // ── Reduce motion: shorter, never frozen ───────────────────────────────────

    @Test
    fun reduceMotionIsNeverAFreeze() {
        val reduced = resolveMotion(MotionPreferences(), MotionTier.STANDARD, systemReduced = true)
        assertTrue(reduced.reduced)
        keys.filter { it !in backgroundKeys && it != MotionKey.ChipDwell && it !in decorativeKeys }
            .forEach { key ->
                val spec = reduced[key]
                assertNull(spec.spring, "$key must not keep a spring under reduce-motion")
                assertTrue(spec.durationMs <= 140L, "$key must be shortened under reduce-motion")
                assertTrue(
                    spec.intensity >= 0.35f,
                    "$key must stay visible under reduce-motion (intensity ${spec.intensity})",
                )
            }
    }

    @Test
    fun reduceMotionSwitchesTheDecorationOffRatherThanShorteningIt() {
        val reduced = resolveMotion(MotionPreferences(), MotionTier.STANDARD, systemReduced = true)
        decorativeKeys.forEach { key ->
            assertEquals(0f, reduced[key].intensity, "$key is decoration and must switch off")
        }
        // A dwell is a timer, not motion, so it is untouched — the chip still waits its four seconds.
        assertEquals(FolioTokens.chipAutoDismiss, reduced[MotionKey.ChipDwell].dwellMs)
    }

    @Test
    fun reduceMotionMakesTheRoomCrawlInsteadOfStoppingIt() {
        val reduced = resolveMotion(MotionPreferences(), MotionTier.STANDARD, systemReduced = true)
        // The field keeps moving: a frozen mid-drift field reads as a rendering bug.
        assertTrue(reduced.fieldSpeedFactor > 0f, "the field must still move under reduce-motion")
        assertTrue(reduced.fieldSpeedFactor < 1f, "and it must be much slower")
        // The slow layers stretch rather than stop.
        assertTrue(reduced[MotionKey.AmbientSwing].durationMs > motionProfile(MotionKey.AmbientSwing).durationMs)
    }

    @Test
    fun theModeCanOverrideThePlatformInBothDirections() {
        // The platform says reduce; the reader says full.
        val forced = resolveMotion(
            MotionPreferences(reduceMotion = ReduceMotionMode.FULL), MotionTier.STANDARD, systemReduced = true,
        )
        assertTrue(!forced.reduced)
        // The platform says nothing; the reader asks for reduced.
        val asked = resolveMotion(
            MotionPreferences(reduceMotion = ReduceMotionMode.REDUCED), MotionTier.STANDARD, systemReduced = false,
        )
        assertTrue(asked.reduced)
        // And the default follows the platform either way.
        assertTrue(resolveMotion(MotionPreferences(), MotionTier.STANDARD, systemReduced = true).reduced)
        assertTrue(!resolveMotion(MotionPreferences(), MotionTier.STANDARD, systemReduced = false).reduced)
    }

    // ── The fuzz: no combination may escape the tuned band ─────────────────────

    @Test
    fun everyCombinationOfPresetLeverTierAndModeResolvesInsideTheBand() {
        val presets = MotionPreset.entries
        val modes = ReduceMotionMode.entries
        val tiers = MotionTier.entries
        // The lever values bracket the presets: the calmest preset's factor and the liveliest's.
        val leverValues = listOf(null, 0.5f, 1f, 2f)
        var combinations = 0
        presets.forEach { preset ->
            modes.forEach { mode ->
                tiers.forEach { tier ->
                    leverValues.forEach { lever ->
                        leverValues.forEach { fieldLever ->
                            val prefs = MotionPreferences(
                                preset = preset,
                                reduceMotion = mode,
                                fieldMotion = fieldLever,
                                fieldIntensity = lever,
                                readerChrome = lever,
                                sheetsOverlays = lever,
                                coverMorph = lever,
                                progressFeedback = lever,
                                chartEntry = lever,
                            )
                            val resolved = resolveMotion(prefs, tier, systemReduced = true)
                            combinations++
                            keys.forEach { key ->
                                val spec = resolved[key]
                                val where = "$key under $preset/$mode/$tier/lever=$lever"
                                assertTrue(spec.durationMs >= 0, "$where has a negative duration")
                                assertTrue(spec.intensity.isFinite(), "$where has a non-finite intensity")
                                assertTrue(spec.intensity in 0f..1f, "$where has intensity ${spec.intensity}")
                                assertTrue(spec.dwellMs >= 0, "$where has a negative dwell")
                                assertTrue(spec.tickMs >= 0, "$where has a negative tick")
                                // A transition is either instant or inside its band; a period is
                                // either instant (a factor row) or inside the slow band.
                                if (spec.durationMs > 0L) {
                                    when {
                                        spec.channel == MotionChannel.FIELD -> assertTrue(
                                            spec.durationMs >= 4_000L,
                                            "$where has a period too short to read (${spec.durationMs} ms)",
                                        )
                                        // A loop is a cycle, not a transition: bounded as one.
                                        spec.isLoop -> assertTrue(
                                            spec.durationMs in 200L..4_000L,
                                            "$where has a loop outside its band (${spec.durationMs} ms)",
                                        )
                                        else -> {
                                            assertTrue(spec.durationMs >= 40L, "$where is too short to perceive (${spec.durationMs} ms)")
                                            assertTrue(spec.durationMs <= 800L, "$where is too long to be in the way (${spec.durationMs} ms)")
                                        }
                                    }
                                }
                            }
                            // The morph's own promise, under every combination.
                            assertTrue(
                                resolved[MotionKey.CoverMorph].durationMs <= 600L,
                                "the morph exceeded its ceiling under $preset/$tier/lever=$lever",
                            )
                            assertTrue(resolved.fieldSpeedFactor > 0f, "the field stopped under $preset/$mode")
                            assertTrue(resolved.fieldIntensityFactor >= 0f, "the field went negative")
                        }
                    }
                }
            }
        }
        assertTrue(combinations >= 3 * 3 * 2 * 4 * 4, "the fuzz did not cover the space")
    }



    // ── Motion must not move cost ────────────────────────────────────────────

    @Test
    fun theFieldLeversReachOnlyTheBackground() {
        // Channel scoping, asserted: the two field levers move the room and nothing else, and the
        // five foreground levers move their channel and nothing else. A lever that reaches further
        // than its channel is the bug this catches.
        val calmField = resolveMotion(MotionPreferences(fieldMotion = 0.25f), MotionTier.STANDARD, false)
        val livelyField = resolveMotion(MotionPreferences(fieldMotion = 1.8f), MotionTier.STANDARD, false)
        keys.filter { motionProfile(it).channel != MotionChannel.FIELD }.forEach { key ->
            assertEquals(
                calmField[key].durationMs, livelyField[key].durationMs,
                "$key moved with the field-motion lever, which is background-only",
            )
        }
        assertTrue(
            calmField[MotionKey.AmbientSwing].durationMs != livelyField[MotionKey.AmbientSwing].durationMs,
            "the field lever must reach the slow layers",
        )
        // And the field's cost is a *resolution* question, not a motion one: the render scale is a
        // property of the family and no lever, preset or tier may reprice it.
        val livelyConstrained = resolveMotion(
            MotionPreferences(preset = MotionPreset.LIVELY, fieldMotion = 2f), MotionTier.CONSTRAINED, false,
        )
        keys.filter { motionProfile(it).channel != MotionChannel.FIELD }
            .filter { motionProfile(it).durationMs > 0L }   // timers carry a dwell, not a duration
            .forEach { key ->
                // A loop is a cycle and is bounded as one; a transition is bounded as a transition.
                val loop = motionProfile(key).isLoop
                val band = if (loop) 200L..4_000L else 40L..800L
                assertTrue(
                    livelyConstrained[key].durationMs in band,
                    "$key escaped ${if (loop) "the loop" else "the transition"} band on a lively " +
                        "constrained device (${livelyConstrained[key].durationMs} ms)",
                )
            }
        FolioFieldKind.entries.forEach { kind ->
            assertTrue(
                kind.fieldRenderScale() in FolioFieldCost.minScale..1f,
                "$kind's render scale left its band",
            )
        }
    }

    @Test
    fun theAmbientPeriodsComeFromTheOneBackgroundTable() {
        // The ambient light's swing and breath are table rows now, so a field-motion preset can
        // carry the whole room's tempo instead of only the shader's.
        assertEquals(FieldMotion.ambientSwingPeriodMs, motionProfile(MotionKey.AmbientSwing).durationMs)
        assertEquals(FieldMotion.ambientBreathPeriodMs, motionProfile(MotionKey.AmbientBreath).durationMs)
        assertEquals(FieldMotion.silkPeriodMs, motionProfile(MotionKey.SilkDrift).durationMs)
        assertEquals(FieldMotion.rimPeriodMs, motionProfile(MotionKey.RimBeam).durationMs)
        assertEquals(FieldMotion.lampPeriodMs, motionProfile(MotionKey.LampBreath).durationMs)
    }

    private val backgroundKeys = setOf(
        MotionKey.FieldMotion, MotionKey.FieldIntensity, MotionKey.AmbientSwing,
        MotionKey.AmbientBreath, MotionKey.SilkDrift, MotionKey.RimBeam, MotionKey.LampBreath,
    )

    private val decorativeKeys = setOf(MotionKey.ShimmerSweep, MotionKey.LiquidSweep, MotionKey.SyncPulse)
}
