package com.folio.reader.ml

/**
 * The models Folio knows how to download and run.
 *
 * Models are **never** bundled in the APK — the APK is 28 MB and the smallest candidate
 * here is 22 MB. They are fetched on demand into the models directory and verified by
 * SHA-256 before first use, because a truncated download otherwise fails as a confusing
 * ONNX parse error at search time.
 *
 * [sha256] values were taken from the artifacts actually downloaded during the Phase 0
 * measurements, not from the model card.
 */
object EmbeddingModelCatalog {

    /**
     * English, 384 dims, 22 MB int8. The candidate the plan expects to win: the test
     * corpus is English, so a multilingual model buys nothing here.
     */
    val MINILM_L6_V2_INT8 = EmbeddingModel(
        id = "all-MiniLM-L6-v2-int8",
        displayName = "MiniLM-L6-v2 (English, 22 MB)",
        dims = 384,
        quantization = EmbeddingQuantization.INT8,
        fileName = "all-MiniLM-L6-v2-int8.onnx",
        downloadUrl = "https://huggingface.co/Xenova/all-MiniLM-L6-v2/resolve/main/onnx/model_quantized.onnx",
        sizeBytes = 22_972_370,
        vocabFileName = "all-MiniLM-L6-v2-vocab.txt",
        vocabUrl = "https://huggingface.co/Xenova/all-MiniLM-L6-v2/resolve/main/vocab.txt",
        maxTokens = 256,
        usesTokenTypeIds = true,
    )

    /**
     * 100+ languages, 384 dims, 113 MB int8. Kept in the catalog so the Phase 0b
     * comparison can be re-run, but not offered as a download unless the measurement
     * says the library actually contains non-English text.
     *
     * Note this one is XLM-R based: Unigram/SentencePiece, not WordPiece, so it needs a
     * different tokenizer and is therefore not runnable through [WordPieceTokenizer].
     */
    val MULTILINGUAL_E5_SMALL_INT8 = EmbeddingModel(
        id = "multilingual-e5-small-int8",
        displayName = "multilingual-e5-small (100+ languages, 113 MB)",
        dims = 384,
        quantization = EmbeddingQuantization.INT8,
        fileName = "multilingual-e5-small-int8.onnx",
        downloadUrl = "https://huggingface.co/Xenova/multilingual-e5-small/resolve/main/onnx/model_quantized.onnx",
        sizeBytes = 118_308_185,
        vocabFileName = "multilingual-e5-small-tokenizer.json",
        vocabUrl = "https://huggingface.co/Xenova/multilingual-e5-small/resolve/main/tokenizer.json",
        maxTokens = 512,
        queryPrefix = "query: ",
        passagePrefix = "passage: ",
        usesTokenTypeIds = false,
    )

    /**
     * English, 384 dims, 34 MB int8. A drop-in *shape* for [MINILM_L6_V2_INT8] — same
     * dimensionality (so the same 384-wide schema and the same cosine index), same
     * 30 522-token BERT WordPiece vocabulary, same declared `token_type_ids` input — but
     * 33 M parameters against MiniLM's 22 M and trained differently in two ways that the
     * descriptor has to state, because both fail *silently* if got wrong:
     *
     * - **CLS pooling**, not mean. Arctic's model card reads `model(**tok)[0][:, 0]`, and
     *   pooling it by mean instead produces correct-looking vectors that retrieve worse.
     * - **A query prefix** (`Represent this sentence for searching relevant passages: `),
     *   applied to queries only. Without it the query is embedded as if it were a passage
     *   and loses the asymmetric-encoding benefit the model is trained for.
     *
     * The vocabulary was verified byte-identical to MiniLM's
     * (`sha256 07eced37…`), so this entry deliberately reuses that digest and URL rather
     * than pointing at a second copy of the same 231 KB file.
     */
    val SNOWFLAKE_ARCTIC_EMBED_S_INT8 = EmbeddingModel(
        id = "snowflake-arctic-embed-s-int8",
        displayName = "Snowflake Arctic Embed S (English, 34 MB)",
        dims = 384,
        quantization = EmbeddingQuantization.INT8,
        fileName = "snowflake-arctic-embed-s-int8.onnx",
        downloadUrl = "https://huggingface.co/Snowflake/snowflake-arctic-embed-s/resolve/main/onnx/model_quantized.onnx",
        sizeBytes = 34_015_111,
        vocabFileName = "snowflake-arctic-embed-s-vocab.txt",
        vocabUrl = "https://huggingface.co/Snowflake/snowflake-arctic-embed-s/resolve/main/vocab.txt",
        maxTokens = 512,
        pooling = PoolingStrategy.CLS,
        queryPrefix = "Represent this sentence for searching relevant passages: ",
        usesTokenTypeIds = true,
        // Retrieval-quality choice, not a token limit: the 512-token budget derives a ~350-word
        // window, but on-corpus measurement (malazan-probe) showed a 150-word window retrieves the
        // specific passage more sharply. Changing this changes the chunk recipe, so existing Arctic
        // vectors are reconciled and rebuilt on next index (see EmbeddingIndexer.reconcileRecipe).
        chunkWordsOverride = 150,
    )

    /** Models the shipped app offers for download. */
    val downloadable: List<EmbeddingModel> = listOf(MINILM_L6_V2_INT8, SNOWFLAKE_ARCTIC_EMBED_S_INT8)

    /**
     * The model used when the reader has not chosen one. Chosen by the Phase 0b
     * measurement on the real corpus — see `docs/semantic-search.md`.
     */
    val default: EmbeddingModel = MINILM_L6_V2_INT8

    /**
     * Settings key holding the reader's chosen model id.
     *
     * Stored in the existing raw key/value settings store rather than a new column: it is a
     * single string that belongs to the device, not to a book, and unlike the reader
     * settings row it must be readable before the settings row is first written — the graph
     * resolves the model during construction.
     */
    const val SELECTED_MODEL_KEY = "ml.embedding_model_id"

    /**
     * Resolves the stored selection to a model, falling back to [default].
     *
     * An unknown or empty id resolves to the default rather than throwing: the value is
     * written by an older build of this app, and refusing to start a search because a key
     * mentions a model that has since been withdrawn would be a worse failure than quietly
     * using the default. A selection whose model is not downloaded is *not* special-cased
     * here — the embedder factory returns null for it and the UI reports "not downloaded",
     * which is the honest state and one the reader can act on.
     */
    fun resolve(storedId: String?): EmbeddingModel =
        storedId?.takeIf { it.isNotBlank() }?.let { byId(it) } ?: default

    /** Every model the settings picker may offer, in the order it should list them. */
    val selectable: List<EmbeddingModel> = listOf(MINILM_L6_V2_INT8, SNOWFLAKE_ARCTIC_EMBED_S_INT8)

    fun byId(id: String): EmbeddingModel? =
        (downloadable + MULTILINGUAL_E5_SMALL_INT8).firstOrNull { it.id == id }

    /** Integrity digests for the artifacts this project actually measured. */
    val knownSha256: Map<String, String> = mapOf(
        MINILM_L6_V2_INT8.fileName to
            "afdb6f1a0e45b715d0bb9b11772f032c399babd23bfc31fed1c170afc848bdb1",
        MINILM_L6_V2_INT8.vocabFileName to
            "07eced375cec144d27c900241f3e339478dec958f92fddbc551f295c992038a3",
        MULTILINGUAL_E5_SMALL_INT8.fileName to
            "f80102d3f2a1229f387d3c81909990d8945513e347b0eab049f7de3c6f98c193",
        SNOWFLAKE_ARCTIC_EMBED_S_INT8.fileName to
            "f93ff225320628d2e88baf2a395cae791b0e3b27edf5c70bf7b312a4d3260c14",
        SNOWFLAKE_ARCTIC_EMBED_S_INT8.vocabFileName to
            "07eced375cec144d27c900241f3e339478dec958f92fddbc551f295c992038a3",
    )
}
