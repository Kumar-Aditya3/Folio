package com.folio.reader.baselineprofile

import androidx.benchmark.macro.*
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FolioBenchmarks {
    @get:Rule val rule = MacrobenchmarkRule()
    @Test fun startupWithoutProfile() = startup(CompilationMode.None())
    @Test fun startupWithProfile() = startup(CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require))
    private fun startup(mode: CompilationMode) = rule.measureRepeated(
        packageName = FolioJourneys.PACKAGE,metrics = listOf(StartupTimingMetric()),
        compilationMode = mode,iterations = 5,startupMode = StartupMode.COLD,
        setupBlock = { pressHome() },measureBlock = { startActivityAndWait() })
    @Test fun libraryAndReaderFrames() {
        FolioJourneys.importFixture()
        rule.measureRepeated(packageName = FolioJourneys.PACKAGE,metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Partial(),iterations = 5,
            setupBlock = { startActivityAndWait() },measureBlock = { FolioJourneys.navigationAndReader() })
    }
}
