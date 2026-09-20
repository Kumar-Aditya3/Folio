package com.folio.reader.ml

/**
 * Gold set for the Phase 0b retrieval-quality measurement.
 *
 * Three kinds of query, and the split matters:
 *
 * - **Paraphrase** queries deliberately share almost no content words with their target
 *   passage. These are the ones that can distinguish an embedding model from BM25 — a
 *   lexical query would be answered by BM25 on word overlap alone and would tell us
 *   nothing about whether semantic retrieval works.
 * - **Lexical** controls share distinctive vocabulary with their target. They are the
 *   harness's sanity check: if BM25 cannot find these, the harness is broken rather than
 *   the retrieval being bad.
 * - **Negative** queries have **no target at all** — sentences this corpus cannot answer.
 *   They exist because the app's relevance floor is a *decision boundary*, and a boundary
 *   cannot be set from one side. Recall tables only ever measure the positive side, so a
 *   floor tuned on them alone will happily admit nonsense: on the device, the query
 *   `motorcycle elevator refrigerator cryptocurrency` returned three confident hits, because
 *   the floor had been calibrated on a model that is not the one shipping. The negatives
 *   supply the other half — the highest score a query that *should* be refused receives —
 *   and the report prints the window between the two distributions.
 *
 * [needle] is a verbatim span from the book, empty for a negative. It is located in the indexed
 * text by normalized containment (case, whitespace and quote style are folded), and the owning
 * chapter becomes the gold answer. `RetrievalQualityBenchmark` fails loudly if a needle cannot be
 * found, so a sanitizer change cannot silently turn this into a test that measures nothing.
 */
object RetrievalGoldSet {

    data class GoldQuery(
        val query: String,
        /** Empty for [Kind.NEGATIVE]: there is no correct answer to point at. */
        val needle: String = "",
        val kind: Kind,
    ) {
        enum class Kind { PARAPHRASE, LEXICAL, NEGATIVE }

        /** Whether this query has a gold passage. Recall is only defined when it does. */
        val hasTarget: Boolean get() = kind != Kind.NEGATIVE
    }

    val queries: List<GoldQuery> = listOf(
        // ---------- Paraphrase: no meaningful word overlap with the target ----------
        GoldQuery(
            "recording the past cannot hold a moment as it really was",
            "we attempt such histories not to preserve knowledge, but to fix the past in a settled way",
            GoldQuery.Kind.PARAPHRASE,
        ),
        GoldQuery(
            "a man cannot bear to face the person who raised his own child",
            "To confront Burrich with his usurpation of my wife and my child would destroy him",
            GoldQuery.Kind.PARAPHRASE,
        ),
        GoldQuery(
            "a room arranged so its owner always appears at his most beautiful",
            "This entire rich room was a setting for his golden beauty",
            GoldQuery.Kind.PARAPHRASE,
        ),
        GoldQuery(
            "an animal's thought arrives directly inside a human mind",
            "the kit thought delightedly",
            GoldQuery.Kind.PARAPHRASE,
        ),
        GoldQuery(
            "reading the signs that someone rode away in a hurry",
            "I saw signs of the rider's haste as he raced back to warn his party",
            GoldQuery.Kind.PARAPHRASE,
        ),
        GoldQuery(
            "a friend speaks gently to talk a man out of killing",
            "He addressed me as if calming a horse",
            GoldQuery.Kind.PARAPHRASE,
        ),
        GoldQuery(
            "grieving over a dead animal as if it were a child",
            "He held it to his breast as if it were a child and rocked back and forth, keening",
            GoldQuery.Kind.PARAPHRASE,
        ),
        GoldQuery(
            "newborn animals found dead inside a traveller's bag",
            "Inside was a litter of kittens, perfectly formed with long claws",
            GoldQuery.Kind.PARAPHRASE,
        ),
        GoldQuery(
            "a port town that never truly sleeps",
            "Neither Bingtown proper nor the docks ever truly slept",
            GoldQuery.Kind.PARAPHRASE,
        ),
        GoldQuery(
            "anger that finally finds somewhere to go",
            "frustration and anger of the past week suddenly had a target",
            GoldQuery.Kind.PARAPHRASE,
        ),
        GoldQuery(
            "a boy proves to himself that pain is not what frightens him",
            "He would prove to them all now that it had not been pain he feared",
            GoldQuery.Kind.PARAPHRASE,
        ),
        GoldQuery(
            "quiet pride in a girl who proved herself aboard a ship",
            "She had looked just like any tough ship's lad",
            GoldQuery.Kind.PARAPHRASE,
        ),
        GoldQuery(
            "a vessel thrashing about until she nearly tips herself over",
            "Her motion set the entire ship to rocking",
            GoldQuery.Kind.PARAPHRASE,
        ),
        GoldQuery(
            "creatures must speak aloud or humans pay them no attention",
            "Sound was useful to make humans in general focus on what a dragon was trying to convey",
            GoldQuery.Kind.PARAPHRASE,
        ),

        // ---------- Lexical controls: the harness must be able to find these ----------
        GoldQuery(
            "usurpation of my wife and my child",
            "To confront Burrich with his usurpation of my wife and my child would destroy him",
            GoldQuery.Kind.LEXICAL,
        ),
        GoldQuery(
            "a litter of kittens",
            "Inside was a litter of kittens, perfectly formed with long claws",
            GoldQuery.Kind.LEXICAL,
        ),
        GoldQuery(
            "night market music pipes and wrist-bells",
            "A trick of the wind brought him a brief gust of music, pipes and wrist-bells",
            GoldQuery.Kind.LEXICAL,
        ),
        GoldQuery(
            "he steepled his graceful hands",
            "He steepled his graceful hands before him",
            GoldQuery.Kind.LEXICAL,
        ),
        GoldQuery(
            "iridescent patches behind their ears",
            "iridescent patches behind their ears",
            GoldQuery.Kind.LEXICAL,
        ),
        GoldQuery(
            "leaped out briefly into the great Lack",
            "leaped out briefly into the great Lack",
            GoldQuery.Kind.LEXICAL,
        ),

        // ---------- Negative: no answer exists in this corpus ----------
        //
        // Chosen to be *plausibly typed into a book search* rather than obviously absurd. A query
        // like `zzzz qqqq` is refused by any threshold and proves nothing; these are the shape of
        // thing a reader actually types by mistake — a different domain, a how-to, a factual
        // question — and they are the ones a too-low floor admits.
        GoldQuery("motorcycle elevator refrigerator cryptocurrency", kind = GoldQuery.Kind.NEGATIVE),
        GoldQuery("how to change a car tyre in the rain", kind = GoldQuery.Kind.NEGATIVE),
        GoldQuery("quarterly earnings report for a software company", kind = GoldQuery.Kind.NEGATIVE),
        GoldQuery("a recipe for sourdough bread with olive oil", kind = GoldQuery.Kind.NEGATIVE),
        GoldQuery("photosynthesis in tropical rainforest ferns", kind = GoldQuery.Kind.NEGATIVE),
        GoldQuery("debugging a null pointer exception in java", kind = GoldQuery.Kind.NEGATIVE),
        GoldQuery("the rules of cricket explained for beginners", kind = GoldQuery.Kind.NEGATIVE),
        GoldQuery("cheap flights to lisbon in november", kind = GoldQuery.Kind.NEGATIVE),
    )

    /** Queries with a gold passage — the ones recall is defined for. */
    val scored: List<GoldQuery> get() = queries.filter { it.hasTarget }

    /** Queries that must be refused. See the class doc for why these exist. */
    val negatives: List<GoldQuery> get() = queries.filter { !it.hasTarget }

    /**
     * Folds the differences between how a needle was transcribed and how the sanitizer
     * emits text: case, whitespace runs, and curly vs straight apostrophes/quotes.
     */
    fun normalizeForMatch(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text.lowercase()) {
            when {
                ch == '\u2019' || ch == '\u2018' || ch == '\u02BC' -> sb.append('\'')
                ch == '\u201C' || ch == '\u201D' -> sb.append('"')
                ch.isWhitespace() -> sb.append(' ')
                else -> sb.append(ch)
            }
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }
}
