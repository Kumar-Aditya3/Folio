package com.folio.reader.tumble

import com.folio.reader.database.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable data class TumbleLibrary(
    val runs: List<TumbleRun> = emptyList(), val readingMinutes: Long = 0,
    val owned: Set<String> = setOf("cushion"), val decoration: String = "cushion",
    val homeVisible: Boolean = true,
    val arcadeRuns: List<ArcadeRun> = emptyList(),
    val bond: PicaBond = PicaBond(),
)
@Serializable data class PicaMemory(val text: String, val at: Long, val key: String = "")
@Serializable data class PicaBond(val affection: Int = 0, val curiosity: Int = 0, val calm: Int = 0,
    val playfulness: Int = 0, val discoveries: Int = 0, val lastPetAt: Long = 0,
    val discoveryBaseline: Long = -1,
    val memories: List<PicaMemory> = emptyList())
data class PicaDiscovery(val question: String, val first: String, val second: String, val firstReply: String, val secondReply: String)
val picaDiscoveries = listOf(
    PicaDiscovery("What do you notice first in a new book?", "A place", "A voice", "Reading prompt · A place comes first.", "Reading prompt · A voice comes first."),
    PicaDiscovery("Where would you put a bookmark today?", "A sentence to return to", "The start of something new", "Reading prompt · A sentence to return to.", "Reading prompt · The start of something new."),
    PicaDiscovery("What stays with you after the last page?", "A character", "An unanswered question", "Reading prompt · A character that stayed.", "Reading prompt · An unanswered question."),
    PicaDiscovery("Your next read?", "Somewhere unfamiliar", "An old favourite", "Reading prompt · Somewhere unfamiliar next.", "Reading prompt · Returning to an old favourite."),
    PicaDiscovery("A detail worth keeping?", "A small observation", "A surprising idea", "Reading prompt · A small observation.", "Reading prompt · A surprising idea."),
    PicaDiscovery("How do you like a story to end?", "A little closure", "Room to imagine", "Reading prompt · A little closure.", "Reading prompt · Room to imagine.")
)
fun TumbleLibrary.pendingDiscoveries(): Long = ((if(bond.discoveryBaseline < 0) 1 else readingMinutes/15-bond.discoveryBaseline+1)-bond.discoveries).coerceAtLeast(0)
data class TumbleDecoration(val id: String, val name: String, val cost: Int)
val tumbleDecorations = listOf(TumbleDecoration("cushion", "Linen paper", 0),
    TumbleDecoration("lamp", "Night ink", 2), TumbleDecoration("plant", "Paper fern", 2),
    TumbleDecoration("books", "Ruled paper", 4), TumbleDecoration("stars", "Star print", 6))
fun TumbleLibrary.scraps(): Long = (readingMinutes / 15 - tumbleDecorations.filter { it.id in owned }.sumOf { it.cost }).coerceAtLeast(0)

/** Local-only keys: never publish game time as reading time or a global settings sync event. */
class TumbleRepository(private val settings: SettingsRepository) {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutable = MutableStateFlow(TumbleLibrary())
    private var lastCheckpoint: String? = null
    val library = mutable.asStateFlow()
    var loaded = false
        private set

    suspend fun load(): TumbleState? = mutex.withLock {
        val raw = settings.getRaw("tumble_library_v1")
        // Do not overwrite unreadable history with an empty table.
        mutable.value = raw?.let { json.decodeFromString<TumbleLibrary>(it) } ?: TumbleLibrary()
        loaded = true
        val checkpoint = settings.getRaw("tumble_active_v1")
        lastCheckpoint = checkpoint
        checkpoint?.let { json.decodeFromString<TumbleState>(it) }
            ?.takeUnless { active -> mutable.value.runs.any { it.state.id == active.id } }
    }

    private suspend fun change(transform: (TumbleLibrary) -> TumbleLibrary) = mutex.withLock {
        check(loaded) { "Load Tumble before saving" }
        val next = transform(mutable.value)
        if (next != mutable.value) {
            settings.setRaw("tumble_library_v1", json.encodeToString(TumbleLibrary.serializer(), next))
            mutable.value = next
        }
    }

    suspend fun checkpoint(state: TumbleState) = mutex.withLock {
        check(loaded)
        if (mutable.value.runs.none { it.state.id == state.id }) {
            val encoded = json.encodeToString(TumbleState.serializer(), state)
            if (encoded != lastCheckpoint) {
                settings.setRaw("tumble_active_v1", encoded)
                lastCheckpoint = encoded
            }
        }
    }

    suspend fun finish(run: TumbleRun) {
        change { data -> if (data.runs.any { it.state.id == run.state.id }) data else data.copy(runs = data.runs + run) }
        mutex.withLock {
            val current = settings.getRaw("tumble_active_v1")?.let { json.decodeFromString<TumbleState>(it) }
            if (current?.id == run.state.id) {
                settings.setRaw("tumble_active_v1", "")
                lastCheckpoint = null
            }
        }
    }

    suspend fun readingProgress(minutes: Long) = change { data ->
        val highWater=maxOf(data.readingMinutes,minutes)
        data.copy(readingMinutes=highWater,bond=if(data.bond.discoveryBaseline<0) data.bond.copy(discoveryBaseline=highWater/15) else data.bond)
    }
    suspend fun loadArcade(): ArcadeState? = mutex.withLock {
        check(loaded)
        settings.getRaw("pica_arcade_active_v2")?.let { json.decodeFromString<ArcadeState>(it) }
            ?.takeUnless { active -> mutable.value.arcadeRuns.any { it.state.id == active.id } }
    }
    suspend fun checkpointArcade(state: ArcadeState) = mutex.withLock {
        check(loaded)
        if (mutable.value.arcadeRuns.none { it.state.id == state.id }) {
            settings.setRaw("pica_arcade_active_v2", json.encodeToString(ArcadeState.serializer(), state))
        }
    }
    suspend fun finishArcade(run: ArcadeRun) {
        change { data ->
            if(data.arcadeRuns.any {it.state.id==run.state.id}) data else {
                val previous=data.arcadeRuns.filter {!it.abandoned && !it.state.practice && sameArcadeBoard(it.state,run.state)}.maxOfOrNull {it.state.score} ?: 0
                val best=!run.abandoned && !run.state.practice && run.state.finished && run.state.score>previous
                val memory=PicaMemory("${run.state.game.title} · a new best of ${run.state.score}${if(run.state.readingTitle.isBlank()) "." else " between chapters of ${run.state.readingTitle}."}",run.endedAt,"run:${run.state.id}")
                data.copy(arcadeRuns=data.arcadeRuns+run,bond=if(run.abandoned) data.bond else data.bond.copy(
                    playfulness=data.bond.playfulness+1,memories=if(best) (data.bond.memories+memory).takeLast(120) else data.bond.memories))
            }
        }
        mutex.withLock {
            val current = settings.getRaw("pica_arcade_active_v2")?.let { json.decodeFromString<ArcadeState>(it) }
            if (current?.id == run.state.id) settings.setRaw("pica_arcade_active_v2", "")
        }
    }
    suspend fun pet(now: Long = System.currentTimeMillis()) = change { data ->
        if (now - data.bond.lastPetAt < 1500) data else data.copy(bond = data.bond.copy(
            affection = data.bond.affection + 1, lastPetAt = now))
    }
    suspend fun rememberBook(id: String, title: String, now: Long = System.currentTimeMillis()) = change { data ->
        if(data.bond.memories.any {it.key=="book:$id"}) data else data.copy(bond=data.bond.copy(
            affection=data.bond.affection+1,memories=(data.bond.memories+PicaMemory("Finished · $title",now,"book:$id")).takeLast(120)))
    }
    suspend fun discover(choice: Int, now: Long = System.currentTimeMillis()) = change { data ->
        if (data.pendingDiscoveries() <= 0 || choice !in 0..1) return@change data
        val story = picaDiscoveries[data.bond.discoveries % picaDiscoveries.size]
        val memory = if (choice == 0) story.firstReply else story.secondReply
        data.copy(bond = data.bond.copy(discoveries = data.bond.discoveries + 1,
            curiosity = data.bond.curiosity + if (choice == 0) 2 else 0,
            calm = data.bond.calm + if (choice == 1) 2 else 0,
            playfulness = data.bond.playfulness + 1,
            memories = (data.bond.memories + PicaMemory(memory, now)).takeLast(120)))
    }
    suspend fun visible(value: Boolean) = change { it.copy(homeVisible = value) }
    suspend fun decorate(id: String) = change { data ->
        val item = tumbleDecorations.firstOrNull { it.id == id } ?: return@change data
        if (id in data.owned) data.copy(decoration = id)
        else if (data.scraps() >= item.cost) data.copy(owned = data.owned + id, decoration = id) else data
    }
}

data class PicaPersonality(val mood: String, val line: String, val pose: String)
fun picaPersonality(hour: Int, todayMinutes: Long, goal: Int, daysAway: Int?, finished: Int, runs: List<TumbleRun>, now: Long = System.currentTimeMillis()): PicaPersonality {
    val latest = runs.filter { !it.abandoned && it.state.mode != TumbleMode.PRACTICE }.maxByOrNull { it.endedAt }
    return when {
        daysAway != null && daysAway >= 3 && todayMinutes == 0L -> PicaPersonality("A familiar face", "Kept your place. Shall we?", "wave")
        latest != null && now - latest.endedAt in 0..43_200_000L && tumbleRank(latest, runs) == 1 && latest.state.score > 0 -> PicaPersonality("Quietly triumphant", "That last run? Absolutely meant to do that.", "proud")
        hour >= 22 || hour < 5 -> PicaPersonality("After-hours reader", "One more page. Then maybe one little tumble.", "sleep")
        finished > 0 && todayMinutes > 0 -> PicaPersonality("Proud little librarian", "A whole book. That calls for a victory roll.", "proud")
        todayMinutes >= goal.coerceAtLeast(1) -> PicaPersonality("Content", "A good reading day. Saved you the soft cushion.", "sleep")
        runs.filter { !it.abandoned }.takeLast(3).count { it.state.hearts == 0 && now - it.endedAt in 0..43_200_000L } >= 3 -> PicaPersonality("Determined", "I've been studying the angles. Probably.", "think")
        runs.takeLast(5).sumOf { it.state.perfect } >= 10 -> PicaPersonality("A taste for adventure", "The long way round has the best lanterns.", "wave")
        todayMinutes > 0 -> PicaPersonality("In good company", "You read. I rearranged this one pebble.", "read")
        hour < 10 -> PicaPersonality("Slow morning", "The books are still waking up. So am I.", "sleep")
        else -> PicaPersonality("Curious", "Found a gap between the shelves. Want to see?", "wave")
    }
}
