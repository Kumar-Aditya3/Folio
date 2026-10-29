package com.folio.reader.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val rule = BaselineProfileRule()
    @Test fun startup() = rule.collect(FolioJourneys.PACKAGE,includeInStartupProfile = true) {
        pressHome(); startActivityAndWait()
    }
    @Test fun libraryStatisticsAndReader() {
        FolioJourneys.importFixture()
        rule.collect(FolioJourneys.PACKAGE,includeInStartupProfile = false) {
            startActivityAndWait()
            FolioJourneys.navigationAndReader()
        }
    }
}
