package com.folio.reader.ml

/**
 * Deterministic stand-in for the ONNX embedder.
 *
 * It is a hashing vectoriser, not a bag of noise: tokens are hashed into buckets and the
 * result is L2-normalised, so two strings that share vocabulary genuinely score higher
 * than two that do not. That makes the retrieval tests below exercise real ranking
 * behaviour instead of merely proving the plumbing runs — while staying completely free of
 * ONNX, model files and platform natives.
 */
class FakeEmbedder(
    override val model: EmbeddingModel = TEST_MODEL,
    private val dims: Int = TEST_MODEL.dims,
    private val calls: MutableList<Pair<List<String>, EmbedKind>> = mutableListOf(),
    /**
     * Texts whose batch must fail, simulating a device-side ONNX fault. One poisoned text
     * fails its whole batch, which is how a real `OrtException` behaves — the forward pass is
     * per batch, not per row.
     */
    private val poison: (String) -> Boolean = { false },
) : Embedder {

    /** Every batch this embedder was asked for, so tests can assert on batching. */
    val recordedCalls: List<Pair<List<String>, EmbedKind>> get() = calls

    var closed: Boolean = false
        private set

    override suspend fun embed(texts: List<String>, kind: EmbedKind): List<FloatArray> {
        calls.add(texts to kind)
        val bad = texts.firstOrNull(poison)
        if (bad != null) {
            throw IllegalStateException("simulated forward-pass failure: ${bad.take(24)}")
        }
        return texts.map { vectorFor(it, kind) }
    }

    private fun vectorFor(text: String, kind: EmbedKind): FloatArray {
        val vector = FloatArray(dims)
        for (token in tokenize(text)) {
            val hash = token.hashCode()
            // Two probes per token keeps collisions from cancelling a token out entirely.
            vector[Math.floorMod(hash, dims)] += 1f
            vector[Math.floorMod(hash * 31 + 7, dims)] += 0.5f
        }
        // Prefixes must influence the vector, or a test could not tell QUERY from PASSAGE.
        if (kind == EmbedKind.QUERY) vector[0] += 0.25f
        return vector.l2Normalize()
    }

    private fun tokenize(text: String): List<String> =
        text.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }

    override fun close() {
        closed = true
    }

    companion object {
        val TEST_MODEL = EmbeddingModel(
            id = "fake-test-model",
            displayName = "Fake test model",
            dims = 128,
            quantization = EmbeddingQuantization.FLOAT32,
            fileName = "fake.onnx",
            downloadUrl = "",
            sizeBytes = 0,
            vocabFileName = "fake-vocab.txt",
            vocabUrl = "",
            maxTokens = 64,
        )
    }
}
