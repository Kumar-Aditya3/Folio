package com.folio.reader.tumble

import kotlinx.serialization.json.Json
import kotlin.test.*

class PocketArcadeTest {
    private fun fresh(game:PocketGame,seed:Int=42) = ArcadeState("run",game,seed,1000)
    @Test fun mergeUsesEachBookOnceAndNoOpDoesNotConsumeRandomness() {
        val engine=ArcadeEngine(fresh(PocketGame.SHELVES).copy(cells=listOf(2,2,2,2)+List(12){0}))
        assertTrue(engine.slide(Slide.LEFT))
        assertEquals(listOf(4,4),engine.state.cells.take(2))
        assertEquals(8,engine.state.score)
        assertEquals(4,engine.state.travel.size)
        val still=ArcadeEngine(fresh(PocketGame.SHELVES).copy(cells=listOf(2,4,0,0)+List(12){0}))
        val before=still.state
        assertFalse(still.slide(Slide.LEFT))
        assertEquals(before,still.state)
    }
    @Test fun seededGamesAndMidRunRestoreRemainIdentical() {
        val engine=ArcadeEngine(fresh(PocketGame.SHELVES))
        repeat(25) {if(engine.state.draft.isNotEmpty()) engine.chooseBookmark(engine.state.draft.first());engine.slide(Slide.entries[it%4])}
        val restored=ArcadeEngine(Json.decodeFromString<ArcadeState>(Json.encodeToString(ArcadeState.serializer(),engine.state)))
        repeat(80) {
            if(engine.state.draft.isNotEmpty()) {engine.chooseBookmark(engine.state.draft.first());restored.chooseBookmark(restored.state.draft.first())}
            engine.slide(Slide.entries[it%4]);restored.slide(Slide.entries[it%4])
        }
        assertEquals(engine.state,restored.state)
        assertNotEquals(ArcadeEngine(fresh(PocketGame.SHELVES,1)).state.cells,ArcadeEngine(fresh(PocketGame.SHELVES,2)).state.cells)
    }
    @Test fun freshLanternBoardsAreVariedAndAlwaysHaveAValidConnection() {
        val signatures=mutableSetOf<List<Int>>()
        repeat(300) {seed->
            val engine=ArcadeEngine(fresh(PocketGame.LANTERNS,seed))
            assertEquals(36,engine.state.cells.size)
            assertEquals(36,engine.state.randomStep)
            assertTrue(ArcadeEngine.hasChain(engine.state.cells))
            assertTrue(engine.state.cells.all {it in 0..3})
            signatures+=engine.state.cells
        }
        assertEquals(300,signatures.size)
    }
    @Test fun loopClearsAllMatchingSymbolsAndRefillsTheBoard() {
        val cells=List(36) {if(it==35) 1 else 0}
        val engine=ArcadeEngine(fresh(PocketGame.LANTERNS).copy(cells=cells))
        assertTrue(engine.connect(listOf(0,1,7,6,0)))
        assertEquals(35,engine.state.changed.size)
        assertEquals(35*35*10+200,engine.state.lastGain)
        assertEquals(36,engine.state.cells.size)
        assertEquals(36,engine.state.travel.size)
        assertEquals(1,engine.state.moves)
        assertTrue(ArcadeEngine.hasChain(engine.state.cells))
    }
    @Test fun liveLanternPreviewMatchesCommittedScoresWithoutChangingTheBoard() {
        val board=fresh(PocketGame.LANTERNS).copy(cells=List(36) {if(it==35) 1 else 0})
        for(path in listOf(listOf(0,1),listOf(0,1,2,3,4,5),listOf(0,1,7,6,0))) {
            val engine=ArcadeEngine(board)
            val preview=assertNotNull(engine.state.lanternSelection(path))
            assertEquals(board,engine.state)
            assertTrue(engine.connect(path))
            assertEquals(preview.points,engine.state.lastGain)
            assertEquals(preview.cleared.toList().sorted(),engine.state.changed.sorted())
        }
        for(path in listOf(emptyList(),listOf(0),listOf(0,7),listOf(0,1,0),listOf(34,35),listOf(-1,0))) {
            assertNull(board.lanternSelection(path))
            val engine=ArcadeEngine(board)
            assertFalse(engine.connect(path));assertEquals(board,engine.state)
        }
    }
    @Test fun illegalPathsDoNotSpendAMoveAndLanternRunsEndAt25Moves() {
        val engine=ArcadeEngine(fresh(PocketGame.LANTERNS).copy(cells=List(36){0}))
        val before=engine.state
        assertFalse(engine.connect(listOf(0,7)))
        assertFalse(engine.connect(listOf(0,1,0,1)))
        assertEquals(before,engine.state)
        repeat(25) {
            val s=engine.state
            val pair=s.cells.indices.firstNotNullOf {a->s.cells.indices.firstOrNull {b->ArcadeEngine.adjacent(a,b,6) && s.cells[a]==s.cells[b]}?.let {listOf(a,it)}}
            assertTrue(engine.connect(pair))
        }
        assertTrue(engine.state.finished)
        val ended=engine.state
        assertFalse(engine.connect(listOf(0,1)))
        assertEquals(ended,engine.state)
    }
    @Test fun balanceCutsOverhangAndRewardsThreePreciseLandingsWithRecovery() {
        val engine=ArcadeEngine(fresh(PocketGame.BALANCE).copy(movingX=.3f))
        assertTrue(engine.drop())
        assertEquals(.47f,engine.state.books.last().width,.0001f)
        var state=engine.state
        repeat(3) {
            val perfect=ArcadeEngine(state.copy(movingX=state.books.last().x))
            assertTrue(perfect.drop());state=perfect.state
        }
        assertEquals(3,state.combo)
        assertEquals(.505f,state.books.last().width,.0001f)
        val miss=ArcadeEngine(fresh(PocketGame.BALANCE).copy(books=listOf(BalanceBook(.7f,.2f)),movingX=0f))
        assertFalse(miss.drop())
        assertTrue(miss.state.finished)
        assertEquals(0,miss.state.score)
    }
    @Test fun timingIsEquivalentAtDifferentFrameRates() {
        val slow=ArcadeEngine(fresh(PocketGame.BALANCE))
        val fast=ArcadeEngine(fresh(PocketGame.BALANCE))
        repeat(90){slow.tick(1f/30)}
        repeat(360){fast.tick(1f/120)}
        assertEquals(slow.state.movingX,fast.state.movingX,.0001f)
        assertEquals(slow.state.direction,fast.state.direction)
    }
    @Test fun scoresAreSeparatedByGameAndDailyChallengeAndIgnorePractice() {
        val a=ArcadeRun(fresh(PocketGame.SHELVES).copy(score=200,finished=true),2000)
        val same=a.copy(state=a.state.copy(id="better",score=300))
        val other=a.copy(state=a.state.copy(id="other",game=PocketGame.LANTERNS,score=9000))
        val daily=a.copy(state=a.state.copy(id="daily",daily="2026-11-05",score=9000))
        val practice=a.copy(state=a.state.copy(id="practice",practice=true,score=9000))
        assertEquals(2,arcadeRank(a,listOf(a,same,other,daily,practice)))
    }
    @Test fun bookmarksCreateDifferentScoringStrategiesAndDraftsCannotBeBypassed() {
        val board=listOf(2,2,0,0)+List(12){0}
        val normal=ArcadeEngine(fresh(PocketGame.SHELVES).copy(cells=board))
        val corner=ArcadeEngine(fresh(PocketGame.SHELVES).copy(cells=board,bookmarks=listOf(RunBookmark.CORNERS)))
        val editions=ArcadeEngine(fresh(PocketGame.SHELVES).copy(cells=board,bookmarks=listOf(RunBookmark.FIRST_EDITIONS)))
        normal.slide(Slide.LEFT);corner.slide(Slide.LEFT);editions.slide(Slide.LEFT)
        assertEquals(4,normal.state.score);assertEquals(8,corner.state.score);assertEquals(24,editions.state.score)
        val draft=ArcadeEngine(fresh(PocketGame.SHELVES).copy(cells=board,moves=11))
        draft.slide(Slide.LEFT)
        assertEquals(2,draft.state.draft.distinct().size)
        val waiting=draft.state
        assertFalse(draft.slide(Slide.RIGHT));assertEquals(waiting,draft.state)
        val offered=draft.state.draft.first()
        assertTrue(draft.chooseBookmark(offered))
        assertEquals(listOf(offered),draft.state.bookmarks)
        assertTrue(draft.state.draft.isEmpty())
        assertFalse(draft.chooseBookmark(offered))
    }
}
