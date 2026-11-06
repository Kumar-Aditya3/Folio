package com.folio.reader.tumble

import kotlinx.serialization.json.Json
import kotlin.test.*

class TumbleEngineTest {
    private fun fresh(seed: Int = 42) = TumbleState("test", seed, 1000)
    private fun shoot(engine: TumbleEngine, angle: Float, power: Float) {
        engine.launch(angle, power)
        repeat(1000) { if (engine.state.phase == TumblePhase.FLIGHT) engine.tick() }
    }
    @Test fun everyTemplateHasBothSafeAndPerfectRoutes() {
        val seen = mutableSetOf<Int>()
        for (seed in 0..500) {
            val safe = TumbleEngine(fresh(seed))
            val template = safe.chamber.template
            if (!seen.add(template)) continue
            shoot(safe, 0f, .35f)
            assertEquals(1, safe.state.shelf, "Safe route for template $template")
            assertEquals(250, safe.state.score)
            val perfect = TumbleEngine(fresh(seed))
            val (angle, power) = TumbleEngine.referenceShots[template]
            shoot(perfect, angle, power)
            assertEquals(1, perfect.state.perfect, "Perfect route for template $template: ${perfect.state}")
            assertEquals(700, perfect.state.score)
            assertEquals(2, perfect.state.multiplier)
        }
        assertEquals(10, seen.size)
    }
    @Test fun midFlightSerializationRestoresTheExactRun() {
        val original = TumbleEngine(fresh())
        original.launch(.4f, .7f)
        repeat(70) { original.tick() }
        val restored = TumbleEngine(Json.decodeFromString<TumbleState>(Json.encodeToString(TumbleState.serializer(), original.state)))
        repeat(500) { original.tick(); restored.tick() }
        assertEquals(original.state, restored.state)
        assertEquals(original.chamber, restored.chamber)
    }
    @Test fun timeRunsDuringAimAndFinishesExactlyOnce() {
        val engine = TumbleEngine(fresh())
        repeat(TUMBLE_TICKS) { engine.tick() }
        assertEquals(TumblePhase.FINISHED, engine.state.phase)
        assertEquals(0, engine.state.ticksLeft)
        val end = engine.state
        repeat(500) { engine.tick(); engine.launch(0f, .4f) }
        assertEquals(end, engine.state)
    }
    @Test fun failedShotsCannotBankPointsAndConsumeHearts() {
        val engine = TumbleEngine(fresh().copy(multiplier = 4, phase = TumblePhase.FLIGHT,
            ball = TumblePoint(30f, 548f), velocity = TumblePoint(0f, 300f), collected = 7))
        engine.tick()
        assertEquals(0, engine.state.score)
        assertEquals(2, engine.state.hearts)
        assertEquals(1, engine.state.multiplier)
        assertEquals(0, engine.state.shelf)
        assertFalse(engine.state.legs.single().success)
    }
    @Test fun perfectChainCapsAndSafeLandingsPreserveIt() {
        val base = fresh().copy(multiplier = 5, bestMultiplier = 5, phase = TumblePhase.FLIGHT,
            ball = TumblePoint(TumbleEngine.chamber(42, 0).nestX, 472f), velocity = TumblePoint(0f, 300f))
        val perfect = TumbleEngine(base.copy(collected = 7))
        perfect.tick()
        assertEquals(3500, perfect.state.score)
        assertEquals(5, perfect.state.multiplier)
        val safe = TumbleEngine(base.copy(collected = 1))
        safe.tick()
        assertEquals(1250, safe.state.score)
        assertEquals(5, safe.state.multiplier)
    }
    @Test fun tiesShareRanksAndOtherModesDoNotCompete() {
        val a = TumbleRun(fresh().copy(score = 100, phase = TumblePhase.FINISHED), 1)
        val b = a.copy(state = a.state.copy(id = "b"))
        val daily = a.copy(state = a.state.copy(id = "daily", score = 900, mode = TumbleMode.DAILY))
        val practice = a.copy(state = a.state.copy(id = "practice", score = 999, mode = TumbleMode.PRACTICE))
        val abandoned = a.copy(state = a.state.copy(id = "abandoned", score = 999), abandoned = true)
        assertEquals(1, tumbleRank(a, listOf(a, b, daily, practice, abandoned)))
        assertFalse(practice.ranked)
        assertFalse(abandoned.ranked)
    }
    @Test fun previewDoesNotSpendTimeOrAlterTheRun() {
        val engine = TumbleEngine(fresh())
        val before = engine.state
        assertTrue(engine.preview(.2f, .5f).size > 2)
        assertEquals(before, engine.state)
        engine.launch(Float.NaN, .5f)
        assertEquals(before, engine.state)
    }
}
