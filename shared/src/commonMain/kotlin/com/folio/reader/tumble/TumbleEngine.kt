package com.folio.reader.tumble

import kotlinx.serialization.Serializable
import kotlin.math.*
import kotlin.random.Random

const val TUMBLE_RULES = 1
const val TUMBLE_HZ = 120
const val TUMBLE_TICKS = 120 * TUMBLE_HZ

@Serializable data class TumblePoint(val x: Float, val y: Float) {
    operator fun plus(b: TumblePoint) = TumblePoint(x + b.x, y + b.y)
    operator fun minus(b: TumblePoint) = TumblePoint(x - b.x, y - b.y)
    operator fun times(n: Float) = TumblePoint(x * n, y * n)
    fun dot(b: TumblePoint) = x * b.x + y * b.y
    fun length() = sqrt(dot(this))
}
data class TumbleRail(val a: TumblePoint, val b: TumblePoint)
data class TumbleChamber(val start: TumblePoint, val nestX: Float, val lanterns: List<TumblePoint>, val rails: List<TumbleRail>, val template: Int)
@Serializable enum class TumbleMode { ARCADE, DAILY, PRACTICE }
@Serializable enum class TumblePhase { AIM, FLIGHT, SETTLE, FINISHED }
@Serializable data class TumbleShot(val shelf: Int, val atTick: Int, val angle: Float, val power: Float)
@Serializable data class TumbleLeg(val shelf: Int, val lanterns: Int, val points: Int, val multiplier: Int, val success: Boolean)
@Serializable data class TumbleState(
    val id: String, val seed: Int, val startedAt: Long, val mode: TumbleMode = TumbleMode.ARCADE,
    val challenge: String = "", val rules: Int = TUMBLE_RULES,
    val ticksLeft: Int = TUMBLE_TICKS, val hearts: Int = 3, val shelf: Int = 0,
    val score: Int = 0, val multiplier: Int = 1, val bestMultiplier: Int = 1, val perfect: Int = 0,
    val phase: TumblePhase = TumblePhase.AIM, val ball: TumblePoint = TumblePoint(180f, 64f),
    val velocity: TumblePoint = TumblePoint(0f, 0f), val collected: Int = 0,
    val shotTicks: Int = 0, val settleTicks: Int = 0, val message: String = "Find your line",
    val shots: List<TumbleShot> = emptyList(), val legs: List<TumbleLeg> = emptyList(),
)
@Serializable data class TumbleRun(val state: TumbleState, val endedAt: Long, val abandoned: Boolean = false) {
    val ranked get() = !abandoned && state.mode != TumbleMode.PRACTICE && state.phase == TumblePhase.FINISHED
}

/** All coordinates and physics are independent of pixels and display refresh rate. */
class TumbleEngine(initial: TumbleState) {
    var state = initial
        private set
    var chamber = chamber(initial.seed, initial.shelf)
        private set
    init { if (state.phase == TumblePhase.AIM) state = state.copy(ball = chamber.start) }

    fun launch(angle: Float, power: Float) {
        if (state.phase != TumblePhase.AIM || !angle.isFinite() || !power.isFinite()) return
        val a = angle.coerceIn(-1.28f, 1.28f)
        val p = power.coerceIn(0f, 1f)
        val speed = 180f + p * 380f
        state = state.copy(phase = TumblePhase.FLIGHT, ball = chamber.start,
            velocity = TumblePoint(sin(a) * speed, cos(a) * speed), shotTicks = 0,
            shots = state.shots + TumbleShot(state.shelf, TUMBLE_TICKS - state.ticksLeft, a, p))
    }

    fun tick() {
        if (state.phase == TumblePhase.FINISHED) return
        state = state.copy(ticksLeft = (state.ticksLeft - 1).coerceAtLeast(0))
        if (state.ticksLeft == 0) { state = state.copy(phase = TumblePhase.FINISHED, message = "Time's up"); return }
        when (state.phase) {
            TumblePhase.FLIGHT -> fly()
            TumblePhase.SETTLE -> {
                if (state.settleTicks <= 1) state = state.copy(phase = TumblePhase.AIM, ball = chamber.start, collected = 0, message = "Find your line")
                else state = state.copy(settleTicks = state.settleTicks - 1)
            }
            else -> Unit
        }
    }

    private fun fly() {
        val dt = 1f / TUMBLE_HZ
        var v = state.velocity + TumblePoint(0f, 420f * dt)
        var p = state.ball + v * dt
        if (p.x < 22f) { p = p.copy(x = 22f); v = v.copy(x = abs(v.x) * .88f) }
        if (p.x > 338f) { p = p.copy(x = 338f); v = v.copy(x = -abs(v.x) * .88f) }
        if (p.y < 20f) { p = p.copy(y = 20f); v = v.copy(y = abs(v.y)) }
        for (rail in chamber.rails) {
            val ab = rail.b - rail.a
            val closest = rail.a + ab * ((p - rail.a).dot(ab) / ab.dot(ab)).coerceIn(0f, 1f)
            val delta = p - closest
            val distance = delta.length()
            if (distance < 18f && distance > .001f) {
                val n = delta * (1f / distance)
                p = closest + n * 18f
                if (v.dot(n) < 0) v = v - n * (1.84f * v.dot(n))
            }
        }
        var mask = state.collected
        chamber.lanterns.forEachIndexed { i, light -> if ((p - light).length() < 25f) mask = mask or (1 shl i) }
        state = state.copy(ball = p, velocity = v, collected = mask, shotTicks = state.shotTicks + 1)
        // Crossing the lip from above is necessary: bouncing under a nest is not a landing.
        if (state.ball.y >= 473f && (state.ball.y - v.y * dt) < 473f && v.y > 0 && abs(p.x - chamber.nestX) < 32f && mask != 0) {
            finishShot(true)
        } else if (p.y > 550f || state.shotTicks >= TUMBLE_HZ * 7) finishShot(false)
    }

    private fun finishShot(success: Boolean) {
        val count = (0..2).count { state.collected and (1 shl it) != 0 }
        val perfect = success && count == 3
        val points = if (success) (count * 100 + 150 + if (perfect) 250 else 0) * state.multiplier else 0
        val nextMultiplier = if (!success) 1 else if (perfect) (state.multiplier + 1).coerceAtMost(5) else state.multiplier
        val hearts = state.hearts - if (success) 0 else 1
        state = state.copy(score = state.score + points, hearts = hearts,
            shelf = state.shelf + if (success) 1 else 0, perfect = state.perfect + if (perfect) 1 else 0,
            multiplier = nextMultiplier, bestMultiplier = max(state.bestMultiplier, nextMultiplier),
            phase = if (hearts == 0) TumblePhase.FINISHED else TumblePhase.SETTLE, settleTicks = 38,
            message = if (perfect) "Perfect shelf · +$points" else if (success) "Safe landing · +$points" else "Missed the nest",
            legs = state.legs + TumbleLeg(state.shelf + 1, count, points, state.multiplier, success))
        chamber = chamber(state.seed, state.shelf)
    }

    /** Preview the actual simulation, stopping after the first collision. */
    fun preview(angle: Float, power: Float): List<TumblePoint> {
        val simulation = TumbleEngine(state.copy(phase = TumblePhase.AIM))
        simulation.launch(angle, power)
        val points = mutableListOf(chamber.start)
        var previous = simulation.state.velocity
        repeat(95) { i ->
            simulation.tick()
            if (i % 4 == 0) points += simulation.state.ball
            val next = simulation.state.velocity
            if (previous.x * next.x < 0 || previous.y * next.y < 0 || simulation.state.phase != TumblePhase.FLIGHT) return points
            previous = next
        }
        return points
    }

    companion object {
        // Authored trick-shot routes, verified against the same fixed-step engine in tests.
        // Keep the first lantern on the safe vertical route; the other two reward a ricochet.
        val referenceShots = listOf(-.64f to .85f, -.46f to .8f, .36f to .35f, -.88f to .7f,
            -.8f to .9f, .44f to 1f, -.58f to .2f, .96f to .95f, .52f to .2f, .74f to .85f)
        private val trickLights = listOf(
            TumblePoint(324f, 231f) to TumblePoint(36f, 356f),
            TumblePoint(324f, 178f) to TumblePoint(36f, 354f),
            TumblePoint(36f, 191f) to TumblePoint(248f, 427f),
            TumblePoint(324f, 206f) to TumblePoint(36f, 338f),
            TumblePoint(36f, 225f) to TumblePoint(36f, 358f),
            TumblePoint(303f, 268f) to TumblePoint(36f, 351f),
            TumblePoint(324f, 207f) to TumblePoint(36f, 343f),
            TumblePoint(324f, 203f) to TumblePoint(36f, 368f),
            TumblePoint(36f, 168f) to TumblePoint(194f, 321f),
            TumblePoint(324f, 273f) to TumblePoint(36f, 363f),
        )
        fun chamber(seed: Int, shelf: Int): TumbleChamber {
            val random = Random(seed xor (shelf * 7919))
            val template = random.nextInt(10)
            val x = listOf(140f, 180f, 220f)[template % 3]
            val rails = listOf(
                TumbleRail(TumblePoint(34f, 245f + template * 3), TumblePoint(x - 42f, 288f)),
                TumbleRail(TumblePoint(x + 42f, 310f), TumblePoint(326f, 268f + template * 2)),
            )
            return TumbleChamber(TumblePoint(x, 64f), x,
                listOf(TumblePoint(x, 114f), trickLights[template].first, trickLights[template].second), rails, template)
        }
    }
}

val tumbleRanking: Comparator<TumbleRun> = compareByDescending<TumbleRun> { it.state.score }
    .thenByDescending { it.state.perfect }.thenByDescending { it.state.shelf }
fun tumbleSameBoard(a: TumbleState, b: TumbleState) = a.rules == b.rules && a.mode == b.mode &&
    (a.mode != TumbleMode.DAILY || a.challenge == b.challenge)
fun tumbleRank(run: TumbleRun, runs: List<TumbleRun>): Int = 1 + runs.count {
    it.ranked && tumbleSameBoard(run.state, it.state) && tumbleRanking.compare(it, run) < 0
}
