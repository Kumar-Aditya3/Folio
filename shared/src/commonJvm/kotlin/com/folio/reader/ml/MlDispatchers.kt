package com.folio.reader.ml

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Where ML inference runs.
 *
 * **Not `Dispatchers.Default`.** That pool is sized `availableProcessors` and is the same pool
 * every non-main-thread thing in the app uses — cover accent sampling, the library and
 * statistics view models, the search fan-out. Running a multi-threaded ONNX session inside a
 * `Dispatchers.Default` worker therefore does not merely add load, it *removes cores from the
 * UI's own off-main work*.
 *
 * That is not theoretical. On the 8-core MT6897 test device, with the semantic backfill
 * running, `dumpsys gfxinfo` reported **59% janky frames and a 90th-percentile frame time of
 * 105 ms** while the GPU sat idle at a 13 ms median — the signature of a starved CPU, not
 * over-recomposition. Four `DefaultDispatcher` threads were burning ~310% CPU against an 800%
 * budget, and the UI thread could not get a slot.
 *
 * So inference gets its own pool, deliberately smaller than the machine. Together with the
 * per-session intra-op thread counts below, this caps ML at roughly half the cores.
 */
object MlDispatchers {

    /** Ceiling on intra-op threads for any one ONNX session; the encoder stops scaling past this. */
    private const val MAX_SESSION_THREADS = 4

    private val threadIndex = AtomicInteger(0)

    /**
     * Worker threads for ML coroutines.
     *
     * Deliberately a small constant rather than `availableProcessors`: this pool decides how
     * many *slices* or *queries* may be in flight, and the parallel work that actually needs
     * cores happens inside ONNX. Two allows a foreground query to run alongside a backfill
     * slice without letting either fan out across the machine.
     */
    private const val PARALLELISM = 2

    @OptIn(ExperimentalCoroutinesApi::class)
    val inference: CoroutineDispatcher by lazy {
        runCatching {
            Executors.newFixedThreadPool(PARALLELISM) { runnable ->
                Thread(runnable, "folio-ml-${threadIndex.getAndIncrement()}").apply {
                    // Just below the UI. The reader is the foreground customer of this process
                    // and a backfill is not, so the backfill should lose any tie for a core.
                    priority = Thread.NORM_PRIORITY - 1
                    isDaemon = true
                }
            }.asCoroutineDispatcher()
        }.getOrElse {
            // A missing pool must not be the reason semantic search stops working entirely.
            Dispatchers.Default
        }
    }

    /**
     * Intra-op threads for an *interactive* session — a search query, a chapter tag.
     *
     * Half the machine, because interactive work runs for milliseconds on one short string and
     * the reader is waiting for it.
     */
    val sessionThreads: Int = (Runtime.getRuntime().availableProcessors() / 2)
        .coerceIn(1, MAX_SESSION_THREADS)

    /**
     * Intra-op threads for a *backfill* session that has confirmed the reader is not in the app.
     *
     * The sweep is the one workload here that is measured in hours, and it is the only one whose
     * cost the reader never sees. Holding it to [backfillThreads] in that state is not protecting
     * anything — it just makes the index take four times as long, which is its own kind of bug
     * ("extremely slow even when it is running").
     *
     * The worker picks between the two per slice: this when the process reports no visible UI,
     * [backfillThreads] when it does. So the conservative value is still what protects an open
     * reader, and this is what applies when nothing else wants the cores.
     *
     * Capped at [MAX_SESSION_THREADS] like every other session: the encoder stops scaling past a
     * handful of threads, and `cores - 1` keeps one core for the system to run its own work on.
     */
    val backgroundThreads: Int = (Runtime.getRuntime().availableProcessors() - 1)
        .coerceIn(1, MAX_SESSION_THREADS)

    /**
     * Intra-op threads for a *backfill* session running while the reader may be in the app.
     *
     * A backfill is a long sweep nobody is waiting on, so it gets the smaller share: on this
     * 8-core phone, 2 — leaving the UI and interactive search their cores. The sweep is
     * correspondingly slower, which is the correct trade: it is resumable and measured in minutes
     * either way, whereas a janky reader is immediately visible.
     *
     * This is the *foreground* figure only — see [backgroundThreads] for what a sweep uses once
     * the process reports no visible UI.
     */
    val backfillThreads: Int = (Runtime.getRuntime().availableProcessors() / 4)
        .coerceIn(1, 2)
}
