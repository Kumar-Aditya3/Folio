package com.folio.reader.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.os.Debug
import android.util.Log
import java.nio.LongBuffer

/**
 * Where the memory instrument writes.
 *
 * A session's cost is native and therefore invisible to the Java heap, `run-as`, and every
 * `/proc/<pid>/smaps`-based tool the shell user is allowed to read — so the only way to see it is
 * from inside the process. `Debug.getPss()` reports whole-process PSS, which is the quantity the
 * lowmemorykiller prices, and the *deltas* around create / run / close are what attribute it.
 */
private const val ML_MEM_TAG = "OnnxMem"

private fun pssMb(): Int = (Debug.getPss() / 1024).toInt()

/**
 * Android ONNX Runtime session.
 *
 * Differs from the desktop actual only in the execution providers it can offer:
 * XNNPACK (the default, and the one Phase 0a expects to win) and NNAPI (which delegates
 * to the device NPU but adds per-inference IPC overhead that usually loses on small
 * transformer encoders). Both are measured rather than assumed.
 *
 * This is why the seam is `expect`/`actual`: `onnxruntime-android` ships its own native
 * library for each ABI and cannot be resolved on desktop.
 */
actual fun openOnnxModel(
    modelPath: String,
    threads: Int,
    useXnnpack: Boolean,
    enableCpuMemArena: Boolean,
): OnnxModel {
    val env = try {
        OrtEnvironment.getEnvironment()
    } catch (t: Throwable) {
        throw OnnxUnavailableException("ONNX Runtime native library failed to load on Android", t)
    }
    // `SessionOptions` is a native handle that owns the session's CPU memory arena, so it is
    // retained for the session's lifetime rather than closed once `createSession` returns.
    // It used to be closed only on the failure path, which leaked a native arena per opened
    // model. On desktop that was measurable (a test harness reached a 35 GB working set on a
    // 648 MB heap); here it matters more, because the app opens a session per model switch and
    // per backfill run, and none of that memory is visible to the Android GC.
    val options = OrtSession.SessionOptions()
    try {
        options.setIntraOpNumThreads(threads)
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        if (!enableCpuMemArena) {
            // Kept as a knob, but **the sweep no longer uses it** — see the comment below, and
            // `OnnxEmbedderFactory.create`.
            //
            // This was `runCatching { addConfigEntry("session.use_cpu_mem_arena", "0") }`, and the
            // key does not exist: `strings jni/arm64-v8a/libonnxruntime.so | grep '^session\.'`
            // returns `session.disable_prepacking`, `session.intra_op.allow_spinning` and
            // `session.use_env_allocators` — not that one. ONNX Runtime stores unknown config
            // entries without complaint and ignores them, so the call *succeeded*, the `runCatching`
            // never fired, and the arena stayed on for every sweep while the parameter, its docs and
            // every caller said otherwise. `setCPUArenaAllocator` is the typed setter that actually
            // works, so the knob is now real.
            //
            // ### Why the sweep stopped using it
            //
            // Turning the arena off *raised* peak native memory, from 1.6 GB to 2.8 GB. The arena is
            // how ORT reuses buffers between nodes; without it every node allocates its own, and a
            // 12-layer encoder at `[8, 448]` then holds its intermediates simultaneously. Measured on
            // device, `dumpsys meminfo` during a backfill:
            //
            //   t=4s   Native Heap RSS 2,135 MB     Java Heap 36 MB
            //   t=8s   Native Heap RSS   503 MB     (session torn down)
            //   t=16s  Native Heap RSS 1,358 MB
            //   t=24s  Native Heap RSS 2,805 MB     -> killed by lowmemorykiller
            //
            // The accumulation the arena was blamed for is real, but it is a *shape* problem, not an
            // arena problem: the arena only grows when it sees allocations it has no buffer for, and
            // `padToLongest` gave every batch a different width — hundreds of distinct shapes across
            // a sweep. Rounding the width up to `WordPieceTokenizer.PAD_BUCKET` bounds that to eight,
            // so the arena allocates once per bucket and reuses from then on. That is the fix; this
            // is not.
            options.setCPUArenaAllocator(false)
        }
        if (!useXnnpack) {
            // XNNPACK is on by default in the Android build. Turning it off leaves the session on
            // the plain CPU execution provider — which is what a **sweep** now does, and this is
            // the memory fix rather than a throughput preference. See `OnnxEmbedderFactory.create`.
            //
            // This branch used to also call `addNnapi()`, because its only consumer was the Phase 0a
            // "NNAPI only" comparison. Nothing in the app ever passed `useXnnpack = false`, so that
            // measurement was the sole caller; NNAPI is now opted into explicitly rather than
            // arriving as a side effect of switching XNNPACK off, because the two have opposite
            // memory profiles and conflating them made the sweep inherit the wrong one.
            runCatching { options.addConfigEntry("session.use_xnnpack", "0") }
        }
        val before = pssMb()
        val session = env.createSession(modelPath, options)
        Log.i(
            ML_MEM_TAG,
            "session created: pss ${before}MB -> ${pssMb()}MB " +
                "(delta ${pssMb() - before}MB; threads=$threads xnnpack=$useXnnpack " +
                "arena=$enableCpuMemArena model=${modelPath.substringAfterLast('/')})",
        )
        return AndroidOnnxModel(session, options)
    } catch (t: Throwable) {
        runCatching { options.close() }
        throw OnnxUnavailableException("Could not open ONNX model at $modelPath", t)
    }
}

private class AndroidOnnxModel(
    private val session: OrtSession,
    /** The session's owning options; see [openOnnxModel] for why this is retained. */
    private val options: OrtSession.SessionOptions,
) : OnnxModel {

    override val inputNames: Set<String> = session.inputNames

    /** Forward passes served. Reported by the memory instrument so growth can be attributed. */
    private var runs = 0

    override fun run(
        inputIds: LongArray,
        attentionMask: LongArray,
        tokenTypeIds: LongArray?,
        batchSize: Int,
        seqLength: Int,
    ): OnnxOutput {
        val env = OrtEnvironment.getEnvironment()
        val shape = longArrayOf(batchSize.toLong(), seqLength.toLong())
        val inputs = HashMap<String, OnnxTensor>(4)
        try {
            inputs["input_ids"] = OnnxTensor.createTensor(env, LongBuffer.wrap(inputIds), shape)
            inputs["attention_mask"] = OnnxTensor.createTensor(env, LongBuffer.wrap(attentionMask), shape)
            if (tokenTypeIds != null && inputNames.contains("token_type_ids")) {
                inputs["token_type_ids"] = OnnxTensor.createTensor(env, LongBuffer.wrap(tokenTypeIds), shape)
            }
            session.run(inputs, setOf(session.outputNames.first())).use { result ->
                val tensor = result.get(0) as OnnxTensor
                val shape = (tensor.info as TensorInfo).shape
                val values: FloatArray = tensor.floatBuffer.let { buffer ->
                    val out = FloatArray(buffer.remaining())
                    buffer.get(out)
                    out
                }
                val interpreted = interpretOnnxOutput(values, shape, batchSize)
                // First pass and every twentieth after it: the growth curve inside one session is
                // what separates "one expensive construction" from "a leak per forward pass".
                runs++
                if (runs == 1 || runs % 20 == 0) {
                    Log.i(
                        ML_MEM_TAG,
                        "run #$runs: pss=${pssMb()}MB batch=$batchSize seq=$seqLength " +
                            "out=${values.size} floats",
                    )
                }
                return interpreted
            }
        } finally {
            inputs.values.forEach { runCatching { it.close() } }
        }
    }

    override fun close() {
        // Session first, then its options: the arena belongs to the options object, so
        // closing options while the session still references it would be a use-after-free.
        val before = pssMb()
        runCatching { session.close() }
        runCatching { options.close() }
        // The delta here is what decides whether a sweep is paying for its sessions once each or
        // accumulating them. `MlSessionCensus` counts live sessions; this counts their bytes.
        Log.i(ML_MEM_TAG, "session closed after $runs run(s): pss ${before}MB -> ${pssMb()}MB")
    }
}
