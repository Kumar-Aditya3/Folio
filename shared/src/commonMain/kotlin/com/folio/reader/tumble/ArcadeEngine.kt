package com.folio.reader.tumble

import kotlinx.serialization.Serializable
import kotlin.math.*

const val ARCADE_RULES = 2
@Serializable enum class PocketGame(val title: String, val subtitle: String) {
    SHELVES("Shelf Shift", "Slide. Merge. Make room for one more."),
    LANTERNS("Lantern Lines", "Trace a chain. Close a loop. Clear the board."),
    BALANCE("Book Balance", "One tap. A perfect landing. A taller tower.")
}
@Serializable enum class Slide { LEFT, RIGHT, UP, DOWN }
@Serializable enum class RunBookmark(val title: String, val description: String) {
    CORNERS("Corner notes", "Merges in a corner score their value again."),
    FIRST_EDITIONS("First editions", "Every 4 or 8 you merge earns 20 extra points."),
    FOOTNOTES("Footnotes", "Consecutive merging moves earn 15 points per streak step."),
    BIG_IDEAS("Big ideas", "Merging a 32 or larger earns its value again.")
}
@Serializable data class BalanceBook(val x: Float, val width: Float, val perfect: Boolean = false)
@Serializable data class TileTravel(val from: Int, val to: Int, val value: Int)
@Serializable data class ArcadeState(
    val id: String, val game: PocketGame, val seed: Int, val startedAt: Long,
    val readingTitle: String = "",
    val daily: String = "", val rules: Int = ARCADE_RULES,
    val cells: List<Int> = emptyList(), val randomStep: Int = 0, val score: Int = 0,
    val moves: Int = 0, val combo: Int = 0, val bestCombo: Int = 0,
    val finished: Boolean = false, val message: String = "", val nextTile: Int = 2,
    val books: List<BalanceBook> = listOf(BalanceBook(.23f, .54f)),
    val movingX: Float = 0f, val direction: Int = 1, val ticks: Long = 0,
    val lastGain: Int = 0, val changed: List<Int> = emptyList(),
    val travel: List<TileTravel> = emptyList(),
    val practice: Boolean = false,
    val bookmarks: List<RunBookmark> = emptyList(), val draft: List<RunBookmark> = emptyList(),
)
@Serializable data class ArcadeRun(val state: ArcadeState, val endedAt: Long, val abandoned: Boolean = false)

data class LanternSelection(val loop: Boolean, val cleared: Set<Int>, val points: Int)

/** The same validation and score are used by the gesture preview and the committed move. */
fun ArcadeState.lanternSelection(path: List<Int>): LanternSelection? {
    if (finished || game != PocketGame.LANTERNS || cells.size != 36 || path.size < 2 || path.any { it !in cells.indices }) return null
    val loop = path.size >= 5 && path.last() == path.first()
    val unique = if (loop) path.dropLast(1) else path
    if (unique.distinct().size != unique.size || unique.size < if (loop) 4 else 2) return null
    val color = cells[path.first()]
    if (path.any { cells[it] != color } || path.zipWithNext().any { (a,b) -> !ArcadeEngine.adjacent(a,b,6) }) return null
    val cleared = if (loop) cells.indices.filter { cells[it] == color }.toSet() else unique.toSet()
    val gain = cleared.size * cleared.size * 10 + if (loop) 200 else if (cleared.size >= 6) 100 else 0
    return LanternSelection(loop, cleared, gain)
}

/** Immutable serialisable state; only the timing game consumes frame time. */
class ArcadeEngine(initial: ArcadeState) {
    var state = initial
        private set
    init {
        if (state.cells.isEmpty()) when (state.game) {
            PocketGame.SHELVES -> { state = state.copy(cells = List(16) { 0 }); spawn(); spawn(); pickNext() }
            PocketGame.LANTERNS -> { val cells = List(36) { draw(4) }; state = state.copy(cells = cells); ensureChain() }
            else -> Unit
        }
    }
    private fun draw(bound: Int): Int {
        // Avalanche the entire seed/counter. Re-seeding Kotlin Random on every draw
        // made nearby seeds share many early values, visibly repeating whole boards.
        var bits = state.seed xor ((state.randomStep + 1) * -1640531527)
        bits = (bits xor (bits ushr 16)) * -2048144789
        bits = (bits xor (bits ushr 13)) * -1028477387
        bits = bits xor (bits ushr 16)
        val result = (bits ushr 1) % bound
        state = state.copy(randomStep = state.randomStep + 1)
        return result
    }
    private fun pickNext() { val next = if (draw(10) == 0) 4 else 2; state = state.copy(nextTile = next) }
    private fun spawn() {
        val empty = state.cells.indices.filter { state.cells[it] == 0 }
        if (empty.isEmpty()) return
        val at = empty[draw(empty.size)]
        state = state.copy(cells = state.cells.toMutableList().also { it[at] = state.nextTile }, changed = state.changed + at)
    }

    fun slide(direction: Slide): Boolean {
        if (state.finished || state.game != PocketGame.SHELVES || state.draft.isNotEmpty()) return false
        val before = state.cells
        val after = before.toMutableList()
        var gain = 0
        val changed = mutableListOf<Int>()
        val travel = mutableListOf<TileTravel>()
        for (line in 0..3) {
            val indices = (0..3).map { i -> when (direction) {
                Slide.LEFT -> line * 4 + i; Slide.RIGHT -> line * 4 + 3 - i
                Slide.UP -> i * 4 + line; Slide.DOWN -> (3 - i) * 4 + line
            } }
            val values = indices.map { before[it] }.filter { it > 0 }
            val origins = indices.filter { before[it] > 0 }
            val result = mutableListOf<Int>()
            var i = 0
            while (i < values.size) {
                if (i + 1 < values.size && values[i] == values[i+1]) {
                    val destination = indices[result.size]
                    travel += TileTravel(origins[i], destination, values[i]); travel += TileTravel(origins[i+1], destination, values[i+1])
                    result += values[i] * 2; gain += values[i] * 2; i += 2
                    changed += indices[result.lastIndex]
                } else { travel += TileTravel(origins[i], indices[result.size], values[i]); result += values[i]; i++ }
            }
            indices.forEachIndexed { n, index -> after[index] = result.getOrElse(n) { 0 } }
        }
        if (after == before) return false
        val chain = if (gain > 0) state.combo + 1 else 0
        var bookmarkBonus = 0
        if(RunBookmark.CORNERS in state.bookmarks) bookmarkBonus += changed.filter {it in listOf(0,3,12,15)}.sumOf {after[it]}
        if(RunBookmark.FIRST_EDITIONS in state.bookmarks) bookmarkBonus += changed.count {after[it] in listOf(4,8)}*20
        if(RunBookmark.FOOTNOTES in state.bookmarks && gain>0) bookmarkBonus += chain*15
        if(RunBookmark.BIG_IDEAS in state.bookmarks) bookmarkBonus += changed.filter {after[it]>=32}.sumOf {after[it]}
        val bonus = gain * (1 + chain.coerceAtMost(5) / 2) + bookmarkBonus
        state = state.copy(cells = after, moves = state.moves + 1, combo = chain,
            bestCombo = max(state.bestCombo, chain), score = state.score + bonus, lastGain = bonus,
            changed = changed, travel = travel, message = if (gain > 0) "${if (chain > 1) "$chain merge streak · " else ""}+$bonus" else "Make space for the next book")
        spawn(); pickNext()
        if (!canSlide(state.cells)) state = state.copy(finished = true, message = "A shelf full of stories")
        else if(state.moves%12==0 && state.bookmarks.size<3) {
            val available=RunBookmark.entries.filterNot {it in state.bookmarks}.toMutableList()
            val first=available.removeAt(draw(available.size))
            val second=available.removeAt(draw(available.size))
            state=state.copy(draft=listOf(first,second),message="Choose a bookmark for this run")
        }
        return true
    }
    fun chooseBookmark(bookmark: RunBookmark): Boolean {
        if(state.finished || bookmark !in state.draft) return false
        state=state.copy(bookmarks=state.bookmarks+bookmark,draft=emptyList(),message="${bookmark.title} joins this run")
        return true
    }

    /** Orthogonal matching chains; returning to the start after four cells closes a loop. */
    fun connect(path: List<Int>): Boolean {
        val selection = state.lanternSelection(path) ?: return false
        val loop = selection.loop
        val unique = if (loop) path.dropLast(1) else path
        val cleared = selection.cleared
        val gain = selection.points
        val cells = state.cells.toMutableList()
        val travel = mutableListOf<TileTravel>()
        for (column in 0..5) {
            val origins = (0..5).map { it * 6 + column }.filterNot { it in cleared }
            val kept = origins.map { cells[it] }
            val fresh = List(6 - kept.size) { draw(4) } + kept
            origins.forEachIndexed { index, from -> travel += TileTravel(from, (6 - kept.size + index) * 6 + column, cells[from]) }
            repeat(6 - kept.size) { row -> travel += TileTravel((row - (6 - kept.size)) * 6 + column, row * 6 + column, fresh[row]) }
            for (row in 0..5) cells[row * 6 + column] = fresh[row]
        }
        val chain = if (loop || unique.size >= 5) state.combo + 1 else 0
        state = state.copy(cells = cells, score = state.score + gain, moves = state.moves + 1,
            combo = chain, bestCombo = max(state.bestCombo, chain), lastGain = gain, changed = cleared.toList(), travel = travel,
            message = if (loop) "Loop! Every matching lantern · +$gain" else "${unique.size} linked · +$gain",
            finished = state.moves + 1 >= 25)
        ensureChain()
        return true
    }
    private fun ensureChain() {
        if (!hasChain(state.cells)) {
            val cells = state.cells.toMutableList(); cells[1] = cells[0]
            state = state.copy(cells = cells, message = "A fresh connection is waiting")
        }
    }

    fun tick(seconds: Float) {
        if (state.finished || state.game != PocketGame.BALANCE || !seconds.isFinite() || seconds <= 0f) return
        // Reflect overshoot instead of discarding it; equivalent movement at 30/60/120 Hz.
        val width = state.books.last().width
        val limit = 1f - width
        val speed = (.38f + (state.books.size - 1) * .022f).coerceAtMost(1.25f)
        var x = state.movingX + state.direction * speed * seconds.coerceAtMost(.1f)
        var direction = state.direction
        while (x < 0f || x > limit) {
            if (x < 0f) { x = -x; direction = 1 }
            if (x > limit) { x = 2 * limit - x; direction = -1 }
        }
        state = state.copy(movingX = x, direction = direction, ticks = state.ticks + (seconds * 1000).roundToLong())
    }
    fun drop(): Boolean {
        if (state.finished || state.game != PocketGame.BALANCE) return false
        val previous = state.books.last()
        val error = abs(previous.x - state.movingX)
        val perfect = error <= .018f
        val left = if (perfect) previous.x else max(previous.x, state.movingX)
        val right = if (perfect) previous.x + previous.width else min(previous.x + previous.width, state.movingX + previous.width)
        val width = right - left
        if (width <= .018f) { state = state.copy(finished = true, message = "One book too far", moves = state.moves + 1, combo = 0, lastGain = 0); return false }
        val combo = if (perfect) state.combo + 1 else 0
        val recovery = if (perfect && combo % 3 == 0) .035f else 0f
        val newWidth = (width + recovery).coerceAtMost(.6f)
        val newLeft = (left - recovery / 2).coerceIn(0f, 1f - newWidth)
        val gain = 100 + if (perfect) 100 + combo.coerceAtMost(10) * 25 else 0
        val nextX = if (draw(2) == 0) 0f else 1f - newWidth
        state = state.copy(books = state.books + BalanceBook(newLeft, newWidth, perfect), movingX = nextX,
            direction = if (nextX == 0f) 1 else -1, score = state.score + gain, moves = state.moves + 1,
            combo = combo, bestCombo = max(state.bestCombo, combo), lastGain = gain,
            message = if (recovery > 0) "Perfect trio · a little room to recover" else if (perfect) "Perfect landing · ×$combo" else "Steady · +$gain")
        return true
    }
    fun end() { state = state.copy(finished = true) }
    companion object {
        fun adjacent(a: Int, b: Int, columns: Int) = abs(a / columns - b / columns) + abs(a % columns - b % columns) == 1
        fun canSlide(cells: List<Int>) = cells.any { it == 0 } || cells.indices.any { a -> cells.indices.any { b -> adjacent(a,b,4) && cells[a] == cells[b] } }
        fun hasChain(cells: List<Int>) = cells.size == 36 && cells.indices.any { a -> (a+1..35).any { b -> adjacent(a,b,6) && cells[a] == cells[b] } }
    }
}
val arcadeRanking = compareByDescending<ArcadeRun> { it.state.score }.thenByDescending { it.state.bestCombo }
fun sameArcadeBoard(a: ArcadeState, b: ArcadeState) = a.game == b.game && a.rules == b.rules && a.daily == b.daily
fun arcadeRank(run: ArcadeRun, runs: List<ArcadeRun>) = 1 + runs.count { !it.abandoned && !it.state.practice && it.state.finished && sameArcadeBoard(it.state,run.state) && arcadeRanking.compare(it,run) < 0 }
