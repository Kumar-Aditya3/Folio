package com.folio.reader.tumble

import com.folio.reader.database.Database
import com.folio.reader.database.JdbcReadingSessionRepository
import com.folio.reader.model.ReadingSession
import com.folio.reader.ui.reader.ReaderActivityInterruption
import com.folio.reader.ui.reader.ReadingSessionTracker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*

class PicaReadingClockTest {
    @Test fun returningFromAGameCannotCreditTheGameGapAsReading() {
        val db=Database(":memory:")
        ReaderActivityInterruption.arcadeVisible(false)
        try {
            val tracker=ReadingSessionTracker(JdbcReadingSessionRepository(db),MutableStateFlow<ReadingSession?>(null)) {0L}
            tracker.markReadingActivity();Thread.sleep(20);tracker.markReadingActivity()
            val before=tracker.activeMs()
            assertTrue(before>0)
            ReaderActivityInterruption.arcadeVisible(true)
            Thread.sleep(40) // No page/scroll events happen while the game covers the reader.
            ReaderActivityInterruption.arcadeVisible(false)
            tracker.markReadingActivity()
            assertEquals(before,tracker.activeMs(),"Game time must not enter the plausible-reading interval")
            Thread.sleep(20);tracker.markReadingActivity()
            assertTrue(tracker.activeMs()>before,"Normal reading resumes after the interruption")
        } finally {ReaderActivityInterruption.arcadeVisible(false);db.close()}
    }
}
