package com.folio.reader.ml

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test

/**
 * Measurement-only probe: how much native memory does each ML path actually cost, and does it
 * come back when the session is closed?
 *
 * This is not a correctness test — it asserts nothing about behaviour. It exists because the app
 * was observed at **819 MB of native heap against a 19 MB Java heap** on device with indexing
 * stopped, and no amount of reading the code settled whether that was a leak, an arena
 * high-water mark, or a measurement artefact. `dumpsys meminfo` on a phone cannot tell those
 * apart on its own and a 10-minute Gradle cycle per hypothesis is not a way to find out.
 *
 * It fails deliberately at the end so Gradle will print the numbers: **Gradle discards a passing
 * test's stdout**, which has already cost time in this repo. Run it with
 * `--tests '*NativeFootprintProbeTest*'` and read the report XML, or run it directly.
 *
 * Native footprint is read from the OS-level working set via `com.sun.management.OperatingSystemMXBean`,
 * not from `Runtime`, because the Java heap was never the problem.
 */
class NativeFootprintProbeTest {

    private val model: File? = TestModelFixtures.miniLmModel()
    private val vocab: File? = TestModelFixtures.miniLmVocab()

    @Test
    fun `report native footprint of the session lifecycle`() {
        val modelFile = model ?: return skip("MiniLM int8 model")
        val vocabFile = vocab ?: return skip("MiniLM vocab")

        val log = StringBuilder()
        fun note(line: String) {
            log.append(line).append('\n')
            println(line)
        }

        val descriptor = EmbeddingModelCatalog.MINILM_L6_V2_INT8
        note("model=${descriptor.id} dims=${descriptor.dims} maxTokens=${descriptor.maxTokens}")

        // Baseline before anything native is loaded.
        val baseline = workingSet()
        note("baseline rss            = $baseline")

        // One session opened, embedded once, closed. Repeated, to separate "first load is
        // expensive" from "each open strands memory".
        repeat(ROUNDS) { round ->
            val embedder = OnnxEmbedder.wordPiece(
                descriptor = descriptor,
                modelFile = modelFile,
                vocabFile = vocabFile,
                threads = 2,
                enableCpuMemArena = true,
            )
            runBlocking { embedder.embed(listOf(SAMPLE), EmbedKind.PASSAGE) }
            val opened = workingSet()
            embedder.close()
            val closed = workingSet()
            note(
                "round $round: afterOpen=$opened afterClose=$closed " +
                    "delta_from_baseline_open=${opened - baseline} delta_alive=${closed - baseline}"
            )
        }

        // The interesting question for the app: N sessions alive at once, as a backfill that
        // leaks would leave behind.
        val held = ArrayList<OnnxEmbedder>(HELD)
        repeat(HELD) {
            val e = OnnxEmbedder.wordPiece(
                descriptor = descriptor,
                modelFile = modelFile,
                vocabFile = vocabFile,
                threads = 2,
                enableCpuMemArena = true,
            )
            runBlocking { e.embed(listOf(SAMPLE), EmbedKind.PASSAGE) }
            held.add(e)
            note("held ${it + 1}: rss=${workingSet()} delta=${workingSet() - baseline}")
        }
        val withAllHeld = workingSet()
        note("with $HELD sessions held = $withAllHeld delta=${withAllHeld - baseline}")

        held.forEach { it.close() }
        // Give the allocator a moment to return regions before sampling.
        System.gc()
        Thread.sleep(500)
        val afterReleasingAll = workingSet()
        note("after releasing all     = $afterReleasingAll delta=${afterReleasingAll - baseline}")

        error(
            "NativeFootprintProbeTest is a measurement, not an assertion.\n" +
                "MODEL=${descriptor.id} baseline=$baseline\n$log",
        )
    }

    private fun skip(what: String): Unit =
        assumeTrue(false, TestModelFixtures.missingHint(what))

    companion object {
        private const val ROUNDS = 6
        private const val HELD = 4
        private const val SAMPLE = "the vessel foundered and the sea took everything"
    }
}

/**
 * OS-level resident memory for this process, in bytes.
 *
 * Read from the process's own working set, not from `Runtime` and not from committed *virtual*
 * size: a leaked native arena is resident, and virtual size counts every reservation the
 * allocator has made whether or not it has been touched, which is far too noisy to see a
 * 40 MB-per-session leak against.
 */
private fun workingSet(): Long = runCatching {
    // Windows names it differently from Linux, and this repo is developed on both.
    val pid = ProcessHandle.current().pid()
    val source = java.io.File("/proc/$pid/status")
    if (source.isFile) {
        source.readLines().firstOrNull { it.startsWith("VmRSS:") }
            ?.filter { it.isDigit() }?.toLongOrNull()?.times(1024) ?: -1L
    } else {
        // Windows: tasklist gives working set in KB as the 5th column with /FO CSV.
        val out = ProcessBuilder("tasklist", "/FI", "PID eq $pid", "/FO", "CSV", "/NH")
            .redirectErrorStream(true).start().inputStream.bufferedReader().readText()
        val cells = out.trim().trim('"').split("\",\"")
        cells.getOrNull(4)?.replace(",", "")?.replace(" K", "")?.toLongOrNull()?.times(1024) ?: -1L
    }
}.getOrElse { -1L }
