package com.folio.reader.surfaces

import com.folio.reader.manga.MangaChapter
import kotlin.test.*

class ReadingSurfacePolicyTest {
    @Test fun remindersOnlyArriveInsideTheReadersChosenWindow() {
        assertFalse(reminderDue(1199, 1200, "today", null, false))
        assertTrue(reminderDue(1200, 1200, "today", null, false))
        assertTrue(reminderDue(1289, 1200, "today", null, false))
        assertFalse(reminderDue(1290, 1200, "today", null, false))
    }
    @Test fun readingAndAnAlreadySentReminderBothSuppressAnotherNotification() {
        assertFalse(reminderDue(1210, 1200, "today", "today", false))
        assertFalse(reminderDue(1210, 1200, "today", "yesterday", true))
        assertTrue(reminderDue(1210, 1200, "today", "yesterday", false))
    }
    @Test fun lateNightRemindersDoNotSpillIntoTheNextDay() {
        assertTrue(reminderDue(1430, 1410, "today", null, false))
        assertFalse(reminderDue(10, 1410, "tomorrow", null, false))
        assertFalse(reminderDue(100, -1, "today", null, false))
        assertFalse(reminderDue(100, 1440, "today", null, false))
    }
    @Test fun goalPercentHandlesDisabledGoalsNegativeDataAndOvershoot() {
        assertEquals(40, goalPercent(24, 60))
        assertEquals(100, goalPercent(Long.MAX_VALUE, 60))
        assertEquals(0, goalPercent(-3, 60))
        assertEquals(0, goalPercent(30, 0))
    }
    @Test fun mangaContinuesInStoryOrderWhenASourceListsNewestFirst() {
        val newest = MangaChapter("3", "series", "3", "Chapter 3", sortOrder = 0)
        val next = MangaChapter("2", "series", "2", "Chapter 2", sortOrder = 1)
        val read = MangaChapter("1", "series", "1", "Chapter 1", sortOrder = 2, read = true)
        assertEquals(next, nextReadingChapter(listOf(newest, next, read)))
        assertNull(nextReadingChapter(listOf(read)))
        assertNull(nextReadingChapter(emptyList()))
    }
}
