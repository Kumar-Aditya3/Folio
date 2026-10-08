package com.folio.reader

import com.folio.reader.settings.AppSettingsStore
import com.folio.reader.settings.ReaderSettings
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The live settings view.
 *
 * Home's cover tint, ambient colour and daily goal all needed an app restart because
 * the ViewModel read the settings row through a cold `flow { emit(get()) }` — one
 * emission, then silence forever. These guards keep the replacement honest: the store
 * must move when the row moves, and must not move when it does not.
 */
class AppSettingsStoreTest {

    @Test
    fun aFreshStoreCarriesDefaultsRatherThanNull() {
        // Home combines this with its shelf queries; an absent first value would hold
        // the whole of Home behind a settings read it no longer makes.
        val store = AppSettingsStore()
        assertEquals(ReaderSettings(), store.current)
    }

    @Test
    fun publishingANewRowReachesCollectorsImmediately() = runTest {
        val store = AppSettingsStore()
        store.publish(ReaderSettings().copy(homeCoverTint = false))
        assertFalse(store.state.first().homeCoverTint)
        // And a *second* change is seen too. The old one-shot flow could only ever
        // report once, which is the whole bug this class exists to prevent.
        store.publish(ReaderSettings().copy(homeCoverTint = true, ambientColor = false))
        assertTrue(store.state.first().homeCoverTint)
        assertFalse(store.state.first().ambientColor)
        assertTrue(store.current.homeCoverTint)
    }

    /**
     * The store republishes the whole row on any save, so Home narrows it before
     * combining. Without that, changing the reading font would re-run Home's entire
     * query set. This exercises the operator chain Home uses, over a finite flow so
     * the test terminates — collecting a StateFlow never returns.
     */
    @Test
    fun anUnrelatedSaveDoesNotReEmitHomeSettings() = runTest {
        val goalOnly = ReaderSettings().copy(dailyGoalMinutes = 45)
        val sameGoalOtherFieldChanged = goalOnly.copy(showClock = true)
        val newGoal = ReaderSettings().copy(dailyGoalMinutes = 90, showClock = true)

        val emitted = flowOf(goalOnly, sameGoalOtherFieldChanged, newGoal)
            .distinctUntilChangedBy {
                it.dailyGoalMinutes to (it.homeCoverTint to it.ambientColor)
            }
            .map { it.dailyGoalMinutes }
            .toList()

        assertEquals(listOf(45, 90), emitted)
    }
}
