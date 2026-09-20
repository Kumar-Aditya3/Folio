package com.folio.reader.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.nio.LongBuffer

/**
 * Desktop ONNX Runtime session.
 *
 * `onnxruntime` (this artifact) and `onnxruntime-android` are different builds with
 * different native loaders, which is the whole reason [openOnnxModel] has to be
 * `expect`/`actual`. The Java API surface above them is identical, so the two actuals
 * differ only in which execution providers they can offer.
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
        throw OnnxUnavailableException("ONNX Runtime native library failed to load on desktop", t)
    }
    // `SessionOptions` is a *native* handle, and it owns the CPU memory arena of the session
    // it created — so it must outlive that session and be closed only when the session is.
    // It used to be closed on the failure path only, which leaked one native arena per
    // successfully opened model. That is invisible for the app (one model per process) and
    // ruinous for the benchmark harness, which opens three: measured here, the leaked
    // arenas took the test JVM to a 35 GB working set against a 648 MB heap — i.e. all of
    // it off-heap, where no Java GC could ever reclaim it.
    val options = OrtSession.SessionOptions()
    try {
        options.setIntraOpNumThreads(threads)
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        // The arena stays on for a sweep; see the `enableCpuMemArena` doc on the `expect` and
        // `OnnxEmbedderFactory.create` for the reversal and the device numbers behind it.
        //
        // This used to be `runCatching { addConfigEntry("session.use_cpu_mem_arena", "0") }`, with a
        // comment claiming it was set "through the config entry rather than a typed setter so an ORT
        // version that drops the key degrades to the default instead of failing the whole session".
        // That reasoning is backwards and it hid a real defect for the life of the feature: the key
        // does not exist (`strings libonnxruntime.so | grep '^session\.'` finds
        // `disable_prepacking`, `intra_op.allow_spinning` and `use_env_allocators`, but not this),
        // ONNX Runtime silently ignores unknown config entries, and so the arena stayed **on** for
        // every sweep while every comment said it was off.
        //
        // A typed setter is compile-checked: an ORT version that removes it fails the *build*, which
        // is strictly better than silently degrading to the expensive default.
        //
        // Note this only turns the *arena* off, not the memory pattern optimiser. Disabling both
        // removed ORT's buffer reuse outright and raised peak native memory from 1.6 GB to 2.8 GB on
        // the test device — see `OnnxModel.android.kt`.
        if (!enableCpuMemArena) {
            options.setCPUArenaAllocator(false)
        }
        // XNNPACK and NNAPI are mobile execution providers; desktop has neither, so
        // `useXnnpack` is intentionally ignored rather than silently mapping to something
        // else. Graph-level optimisation is the desktop equivalent.
        val session = env.createSession(modelPath, options)
        return DesktopOnnxModel(session, options)
    } catch (t: Throwable) {
        runCatching { options.close() }
        throw OnnxUnavailableException("Could not open ONNX model at $modelPath", t)
    }
}

private class DesktopOnnxModel(
    private val session: OrtSession,
    /** The session's owning options; see [openOnnxModel] for why this is retained. */
    private val options: OrtSession.SessionOptions,
) : OnnxModel {

    override val inputNames: Set<String> = session.inputNames

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
            // Only ask for the graph's first output. These exports also emit
            // `token_embeddings`/`sentence_embedding`; running all of them wastes time and
            // the pooled one is not what we pool from.
            session.run(inputs, setOf(session.outputNames.first())).use { result ->
                val tensor = result.get(0) as OnnxTensor
                return tensor.toOnnxOutput(batchSize)
            }
        } finally {
            inputs.values.forEach { runCatching { it.close() } }
        }
    }

    override fun close() {
        // Session first, then its options: the arena belongs to the options object, so
        // closing options while the session still references it would be a use-after-free.
        runCatching { session.close() }
        runCatching { options.close() }
    }
}

/** Flattens the tensor and defers shape handling to the shared interpreter. */
internal fun OnnxTensor.toOnnxOutput(batchSize: Int): OnnxOutput {
    val shape = (info as TensorInfo).shape
    val values: FloatArray = floatBuffer.let { buffer ->
        val out = FloatArray(buffer.remaining())
        buffer.get(out)
        out
    }
    return interpretOnnxOutput(values, shape, batchSize)
}
