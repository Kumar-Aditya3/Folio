package com.folio.reader.ml

import java.util.concurrent.atomic.AtomicInteger

/**
 * How many ONNX sessions are open right now, and how many have ever been opened.
 *
 * ### Why this exists
 *
 * The Android app was measured at **819 MB of native heap against a 19 MB Java heap**, with
 * indexing not running, and was eventually reaped by the low-memory killer. Four separate
 * rounds of on-device measurement failed to find the cause, and the reason was not subtle:
 * **nothing in [ml] logged anything**, so the app could not report what it opened or what it
 * leaked. The numbers had to be inferred from `dumpsys meminfo` deltas, and a delta cannot
 * distinguish one 40 MB session from forty 1 MB allocations.
 *
 * A desktop probe settled the *unit* cost — see `NativeFootprintProbeTest` — and it is large
 * enough that the count is the only fact that matters:
 *
 * ```
 * baseline rss            = 163258368
 * round 0: afterOpen=216547328 afterClose=181309440   <- +35 MB while open, released on close
 * round 5: afterOpen=198492160 afterClose=162447360   <- back to baseline, so close() is correct
 * held 1..4: +39.9, +81.7, +121.9, +163.4 MB          <- ~41 MB per *live* session, exactly linear
 * ```
 *
 * So `close()` is not the bug, and no single session is the bug. The bug, if there is one, can
 * only be **sessions that outlive the call that made them**, and the cheapest way to see that is
 * to count them. Hence this object: the count is small, cheap, and impossible to misread.
 *
 * ### Reading it
 *
 * [alive] should be 0 when the app is idle. It should reach 1, briefly, during any interactive
 * embed, and 1 during a backfill slice. A value that climbs and never returns to 0 is the leak
 * this was built to catch — the exact question four rounds of `meminfo` could not answer.
 *
 * It is deliberately not gated behind a debug flag. The cost is one atomic increment per session
 * open, it is the only visibility this subsystem has, and a diagnostic that ships turned off is
 * a diagnostic that is not there when the bug happens next.
 */
object MlSessionCensus {

    private const val TAG = "MlSession"

    /**
     * Live sessions above which a log line is worth writing.
     *
     * The app's own design holds at most one session at a time, so anything above two is already
     * unexpected — two is a legitimate transient, as `AutoTaggerService.isAvailable` opens and
     * closes one before the suggestion pass opens its own. Three is the first count that cannot
     * be explained by a single serial operation, and it fires only on a new high-water mark, so
     * it never becomes a per-call flood.
     */
    private const val LOG_THRESHOLD = 2

    private val openCount = AtomicInteger(0)

    /** Sessions opened and not yet closed. The number that must come back to zero. */
    val alive: Int get() = openCount.get()

    /** Peak concurrent sessions since process start — the high-water mark for memory. */
    @Volatile
    var peak: Int = 0
        private set

    /** Increments the live count. Call from a session's construction. */
    fun opened() {
        val now = openCount.incrementAndGet()
        if (now > peak) {
            peak = now
            // Only on a new high-water mark, so this cannot become a per-call log flood on the
            // hot path. This is the line to watch on device: `peak` climbing past a handful means
            // sessions are being held, and ~41 MB of native memory each is going with them.
            if (now > LOG_THRESHOLD) report("peak $now live sessions")
        }
    }

    /** Decrements the live count. Call from a session's `close`. */
    fun closed() {
        openCount.decrementAndGet()
    }

    /**
     * Reports a line to the platform log, if the platform has one.
     *
     * Reflection rather than a direct `Log.i`: this file is in `commonJvm`, which both platforms
     * share, and `android.util.Log` does not exist on desktop. The alternative — an `expect`/`actual`
     * pair and a second file — is more machinery than a single diagnostic line is worth.
     */
    private fun report(message: String) {
        runCatching {
            val log = Class.forName("android.util.Log")
            log.getMethod("i", String::class.java, String::class.java)
                .invoke(null, TAG, message)
        }
    }
}
