package com.folio.reader.tumble

import kotlin.test.*

class SableBehaviorTest {
    @Test fun greetingApproachesUsingBothStridesAndEndsAtTheBook() {
        val frames=(0..100).map {sableFrame("greet",it/100f)}
        assertTrue(frames.any {it.cell==2} && frames.any {it.cell==3})
        assertTrue(frames.first().travel<0)
        assertEquals(SableFrame(0),frames.last())
    }

    @Test fun bookmarkingInvestigatesThenRespectsTheNightRestingPose() {
        assertEquals(4,sableFrame("bookmark",.5f).cell)
        assertEquals(0,sableFrame("bookmark",1f).cell)
        assertEquals(7,sableFrame("bookmark",1f,resting=true).cell)
        assertEquals(7,sableFrame("idle",0f,resting=true).cell)
    }

    @Test fun everyActionStaysWithinTheAtlasAndFinishesWithoutTravel() {
        for(action in listOf("greet","bookmark","inspect","pet","stretch","groom","settle","idle")) {
            for(t in -10..110) assertTrue(sableFrame(action,t/100f).cell in 0..7)
            assertEquals(0f,sableFrame(action,1f).travel)
        }
    }
}
