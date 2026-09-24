package com.folio.reader.ml

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The broad-genre classifier — the Atlas's community namer.
 *
 * Two halves, tested apart the way they run apart:
 *
 * - **Metadata mapping** ([GenreTaxonomy.canonicalize]) is pure and deterministic, so it is
 *   asserted directly with no model: BISAC codes and subject words resolve to the right shelf, the
 *   more specific keyword wins ("science fiction" is not "science"), and an unmapped subject is null.
 * - **Zero-shot inference** mirrors `ZeroShotTaggerTest`: a real hashing `FakeEmbedder` so the
 *   ranking exercises genuine cosine behaviour, metadata beats inference, and a book that resembles
 *   no genre resolves to null rather than a confident-looking wrong shelf.
 */
class GenreClassifierTest {

    private class FakeFactory(override val model: EmbeddingModel = FakeEmbedder.TEST_MODEL) : EmbedderFactory {
        override suspend fun create(threads: Int?, sweep: Boolean): Embedder = FakeEmbedder(model)
    }

    private fun classifier(margin: Float = GenreClassifier.DEFAULT_MARGIN) =
        GenreClassifier(FakeFactory(), margin = margin)

    private fun passageVector(text: String): FloatArray = runBlocking {
        FakeEmbedder(FakeEmbedder.TEST_MODEL).embed(listOf(text), EmbedKind.PASSAGE).first()
    }

    // ---------- metadata canonicalization ----------

    @Test
    fun `a BISAC code resolves to its broad genre`() {
        assertEquals(BroadGenre.SCIENCE_FICTION, GenreTaxonomy.canonicalize(listOf("FIC028000")))
        assertEquals(BroadGenre.HISTORY, GenreTaxonomy.canonicalize(listOf("HIS000000")))
        assertEquals(BroadGenre.BIOGRAPHY, GenreTaxonomy.canonicalize(listOf("BIO000000")))
    }

    @Test
    fun `a subject phrase resolves by its most specific keyword`() {
        // "science fiction" must win over the bare "science" of Science & Nature.
        assertEquals(BroadGenre.SCIENCE_FICTION, GenreTaxonomy.canonicalize(listOf("Science Fiction")))
        assertEquals(
            BroadGenre.SCIENCE_FICTION,
            GenreTaxonomy.canonicalize(listOf("Fiction / Science Fiction / General")),
        )
        assertEquals(BroadGenre.TRAVEL_FOOD, GenreTaxonomy.canonicalize(listOf("Cooking")))
        assertEquals(BroadGenre.PHILOSOPHY_RELIGION, GenreTaxonomy.canonicalize(listOf("Philosophy")))
    }

    @Test
    fun `an unmapped or empty subject is null`() {
        assertNull(GenreTaxonomy.canonicalize(emptyList()))
        assertNull(GenreTaxonomy.canonicalize(listOf("", "   ")))
        assertNull(GenreTaxonomy.canonicalize(listOf("Zqxwv nonsense subject")))
    }

    @Test
    fun `the strongest voted genre wins across several subjects`() {
        val genre = GenreTaxonomy.canonicalize(listOf("History", "Historical", "Ancient Rome"))
        assertEquals(BroadGenre.HISTORY, genre)
    }

    // ---------- zero-shot inference ----------

    @Test
    fun `a book that reads like a genre is inferred to it`() = runBlocking {
        val taxonomy = classifier().taxonomyVectors()
        assertNotNull(taxonomy, "the fake factory always builds an embedder")
        val bookVec = passageVector("philosophy religion spirituality ethics meditation and theology")
        val assignment = classifier().rank(bookVec, taxonomy!!)
        assertNotNull(assignment, "a clearly on-topic book must resolve")
        assertEquals(BroadGenre.PHILOSOPHY_RELIGION, assignment!!.genre)
        assertEquals(GenreSource.INFERRED, assignment.source)
    }

    @Test
    fun `metadata beats inference`() = runBlocking {
        val taxonomy = classifier().taxonomyVectors()!!
        // The prose reads like philosophy, but the publisher's subject says Fantasy — metadata wins.
        val bookVec = passageVector("philosophy religion spirituality ethics meditation")
        val assignment = classifier().classify(
            subjects = listOf("Fiction / Fantasy / Epic"),
            bookVector = bookVec,
            taxonomyVectors = taxonomy,
        )
        assertNotNull(assignment)
        assertEquals(BroadGenre.FANTASY, assignment!!.genre)
        assertEquals(GenreSource.METADATA, assignment.source)
        assertEquals(1f, assignment.confidence)
    }

    @Test
    fun `a book resembling no genre is not classified`() {
        // Hand-crafted vectors keep this deterministic: a book orthogonal to every taxonomy label
        // scores 0 against all of them, so after centring the top-vs-runner-up margin is 0 — below
        // any positive margin — and it must resolve to null rather than a shelf.
        val taxonomy = mapOf(
            BroadGenre.HISTORY to floatArrayOf(1f, 0f, 0f, 0f),
            BroadGenre.SCIENCE_NATURE to floatArrayOf(0f, 1f, 0f, 0f),
            BroadGenre.ROMANCE to floatArrayOf(0f, 0f, 1f, 0f),
        )
        val orthogonal = floatArrayOf(0f, 0f, 0f, 1f)
        assertNull(
            classifier().rank(orthogonal, taxonomy),
            "a book unlike any genre label must fall below the margin rather than pick one",
        )
        // And a vector aligned with a label clears it by a wide margin.
        val aligned = floatArrayOf(0.9f, 0.1f, 0f, 0f)
        assertEquals(BroadGenre.HISTORY, classifier().rank(aligned, taxonomy)?.genre)
    }

    @Test
    fun `calibration cancels a genre that is generically close to everything`() {
        // A library of mostly history-ish books makes HISTORY's mean similarity high. The target
        // book leans to HISTORY in raw cosine, so a naive argmax picks HISTORY — but it is *more
        // romance than the average book*, which is what the genre should reflect.
        val taxonomy = mapOf(
            BroadGenre.HISTORY to floatArrayOf(1f, 0f),
            BroadGenre.ROMANCE to floatArrayOf(0f, 1f),
        )
        val target = floatArrayOf(0.8f, 0.6f)            // raw: cosHIST 0.8 > cosROM 0.6
        val library = listOf(
            floatArrayOf(1f, 0f),
            floatArrayOf(1f, 0f),
            floatArrayOf(1f, 0f),
            target,
        )
        // Raw (uncalibrated) argmax is the generically-close HISTORY…
        assertEquals(
            BroadGenre.HISTORY,
            classifier().rank(target, taxonomy)?.genre,
            "sanity: without calibration the anisotropic favourite wins",
        )
        // …but subtracting each genre's library-wide mean lifts the book's true lean, ROMANCE.
        val bias = classifier().calibrationBias(library, taxonomy)
        val calibrated = classifier().rank(target, taxonomy, bias)
        assertNotNull(calibrated)
        assertEquals(BroadGenre.ROMANCE, calibrated!!.genre, "calibration must lift the book's true genre")
    }

    @Test
    fun `a large margin classifies, a tiny one does not`() {
        val taxonomy = mapOf(
            BroadGenre.HISTORY to floatArrayOf(1f, 0f, 0f),
            BroadGenre.ROMANCE to floatArrayOf(0f, 1f, 0f),
        )
        // Nearly equidistant to both → below the relative margin → Unclassified.
        val ambiguous = floatArrayOf(0.71f, 0.70f, 0f)
        assertNull(
            GenreClassifier(FakeFactory(), margin = 0.2f).rank(ambiguous, taxonomy),
            "a book almost equidistant to two genres must be left Unclassified",
        )
        // Clearly one genre → classified.
        val clear = floatArrayOf(0.98f, 0.05f, 0f)
        assertEquals(
            BroadGenre.HISTORY,
            GenreClassifier(FakeFactory(), margin = 0.2f).rank(clear, taxonomy)?.genre,
        )
    }

    @Test
    fun `classify falls back to inference only when metadata is absent`() = runBlocking {
        val taxonomy = classifier().taxonomyVectors()!!
        val bookVec = passageVector("business economics management finance marketing investing")
        val assignment = classifier().classify(subjects = emptyList(), bookVector = bookVec, taxonomyVectors = taxonomy)
        assertNotNull(assignment)
        assertEquals(GenreSource.INFERRED, assignment!!.source)
        assertEquals(BroadGenre.BUSINESS, assignment.genre)
    }

    @Test
    fun `ranking is deterministic`() = runBlocking {
        val taxonomy = classifier().taxonomyVectors()!!
        val bookVec = passageVector("computer programming software technology engineering")
        val first = classifier().rank(bookVec, taxonomy)
        val second = classifier().rank(bookVec, taxonomy)
        assertEquals(first?.genre, second?.genre)
        assertTrue(first == null || first.genre == BroadGenre.TECHNOLOGY)
    }
}
