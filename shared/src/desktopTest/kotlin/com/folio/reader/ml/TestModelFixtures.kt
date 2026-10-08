package com.folio.reader.ml

import java.io.File

/**
 * Locates the model artifacts used by the tokenizer-parity and retrieval-quality tests.
 *
 * These files are deliberately **not** committed: the two models total ~135 MB. Tests that
 * need them call [requireModel] and are skipped via a JUnit assumption when it is absent,
 * so a clean checkout still runs the full suite. `scripts/fetch-models.sh` downloads them.
 */
object TestModelFixtures {

    /** Candidate roots, covering Gradle running from either the root or the module dir. */
    private val roots: List<File> = listOf(
        File(".models"),
        File("..", ".models"),
        File(System.getProperty("user.home"), ".folio-models"),
    )

    /**
     * Extra roots for Arctic-S only.
     *
     * It was fetched by hand into `.models/arctic-probe/` for a session-cost measurement rather than
     * by `scripts/fetch-models.sh`, so it sits one level below the shared roots. `find` does not
     * recurse — deliberately, since a recursive search over a models directory would be a slow and
     * surprising way to resolve a fixture — so the probe directory has to be named.
     *
     * The cost of it not being named was silent: the benchmark logged
     * `arctic skipped: fixture missing (looked for arctic-s-quantized.onnx)` and produced a full
     * report with **no row for the model the app actually ships**, which is the one row the floor
     * calibration needs. A skip message is easy to read past in a 40-minute run.
     */
    private val arcticRoots: List<File> = listOf(
        File(".models/arctic-probe"),
        File("..", ".models/arctic-probe"),
        File(System.getProperty("user.home"), ".folio-models/arctic-probe"),
    )

    private fun findIn(searchRoots: List<File>, fileName: String): File? =
        searchRoots.asSequence().map { File(it, fileName) }.firstOrNull { it.isFile }

    fun find(fileName: String): File? = findIn(roots, fileName)

    fun miniLmVocab(): File? = find("minilm-vocab.txt")

    fun miniLmModel(): File? = find("minilm-int8.onnx")

    /**
     * Arctic-S, the model the Android build actually ships as its default.
     *
     * Present under a probe directory rather than the catalog's filename because it was fetched for a
     * session-cost measurement rather than by `scripts/fetch-models.sh`. That matters for what it is
     * used for: the app's relevance floor and margin are calibrated against *this* model's cosine
     * distribution, and MiniLM's is not a substitute — the two put English prose in cones of
     * different width, so a threshold read off one is not a threshold for the other.
     */
    fun arcticModel(): File? = findIn(arcticRoots, "arctic-s-quantized.onnx")
        ?: find("arctic-s-quantized.onnx")
        ?: find("snowflake-arctic-embed-s-int8.onnx")

    /**
     * Arctic-S's vocabulary.
     *
     * Falls back to MiniLM's deliberately rather than by accident: both are BERT-uncased WordPiece
     * vocabularies and the two files are byte-identical in size (231 508 bytes), which is what
     * `TokenizerParityTest` and `ChunkWindowFitsModelTest` already exercise. If that ever stops
     * being true the fallback would tokenize differently rather than fail, so it is asserted in
     * `ChunkWindowFitsModelTest` alongside the window measurements.
     */
    fun arcticVocab(): File? = findIn(arcticRoots, "arctic-vocab.txt")
        ?: find("arctic-vocab.txt")
        ?: miniLmVocab()

    fun e5TokenizerJson(): File? = find("e5small-tokenizer.json")

    fun e5Model(): File? = find("e5small-int8.onnx")

    /** Human-readable hint used in skip messages so a failure is actionable. */
    fun missingHint(name: String): String =
        "Skipping: $name not found. Run scripts/fetch-models.sh to download the Phase 0 fixtures."
}
