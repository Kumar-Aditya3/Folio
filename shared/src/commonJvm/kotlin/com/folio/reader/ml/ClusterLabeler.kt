package com.folio.reader.ml

import kotlin.math.ln

/**
 * Turns clusters of passages into legible theme labels **without an LLM**, via class-based
 * TF-IDF (c-TF-IDF), the technique BERTopic uses for topic naming.
 *
 * The idea: treat each cluster as one document, and score a term by how frequent it is *inside*
 * the cluster against how rare it is *across* the other clusters:
 *
 * ```
 * score(term, cluster) = tf(term, cluster) · ln(1 + N_clusters / clusters_containing(term))
 * ```
 *
 * A term every cluster uses (generic English) is cancelled by the second factor; a term
 * characteristic of one cluster survives. So a sea-battle cluster surfaces `leviathan · tide`
 * rather than `the · were` — the label describes what makes the region *distinct*, not merely
 * what words happen to appear. Bigrams are included and slightly up-weighted so labels can read
 * as phrases (`time travel`) rather than lone tokens.
 *
 * Pure and deterministic: text in, ranked phrases out, unit-testable with no model or database.
 */
object ClusterLabeler {

    private const val BIGRAM_WEIGHT = 1.6
    private const val MIN_WORD = 3

    /**
     * @param docs one entry per cluster; each is that cluster's representative passages.
     * @param topN how many ranked phrases to return per cluster.
     * @return ranked, de-overlapped phrases per cluster, in the same order as [docs].
     */
    fun label(docs: List<List<String>>, topN: Int = 6): List<List<String>> {
        if (docs.isEmpty()) return emptyList()
        val n = docs.size

        // Per-cluster term counts (unigrams + bigrams) and the cross-cluster document frequency.
        val perCluster = ArrayList<Map<String, Int>>(n)
        val df = HashMap<String, Int>()
        for (texts in docs) {
            val counts = countTerms(texts)
            perCluster.add(counts)
            for (term in counts.keys) df[term] = (df[term] ?: 0) + 1
        }

        return perCluster.map { counts ->
            val scored = counts.entries.map { (term, tf) ->
                val idf = ln(1.0 + n.toDouble() / (df[term] ?: 1))
                val phraseWeight = if (term.contains(' ')) BIGRAM_WEIGHT else 1.0
                term to tf * idf * phraseWeight
            }
                // Deterministic: score desc, then term asc for ties.
                .sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first })

            // De-overlap: once a bigram is chosen, drop its component unigrams so the label is not
            // "time travel · time · travel".
            val chosen = ArrayList<String>(topN)
            val covered = HashSet<String>()
            for ((term, _) in scored) {
                if (chosen.size >= topN) break
                val parts = term.split(' ')
                if (parts.any { it in covered && !term.contains(' ') }) continue
                if (term in covered) continue
                chosen.add(term)
                covered.add(term)
                covered.addAll(parts)
            }
            chosen
        }
    }

    /** A short display label — the top few phrases joined, Title Cased. */
    fun display(phrases: List<String>, max: Int = 3): String =
        phrases.take(max).joinToString(" · ") { p -> p.split(' ').joinToString(" ") { it.replaceFirstChar(Char::uppercase) } }

    private fun countTerms(texts: List<String>): Map<String, Int> {
        val counts = HashMap<String, Int>()
        for (text in texts) {
            val tokens = text.lowercase()
                .split(Regex("[^a-z]+"))
                .filter { it.length >= MIN_WORD && it !in STOPWORDS }
            // Unigrams.
            for (t in tokens) counts[t] = (counts[t] ?: 0) + 1
            // Adjacent bigrams (both content words).
            for (i in 0 until tokens.size - 1) {
                val bg = tokens[i] + " " + tokens[i + 1]
                counts[bg] = (counts[bg] ?: 0) + 1
            }
        }
        return counts
    }

    /** Function words and high-frequency prose filler that carry no theme. */
    private val STOPWORDS = setOf(
        "the", "and", "that", "with", "from", "this", "have", "were", "what", "when", "which",
        "their", "there", "they", "them", "then", "than", "into", "over", "your", "you", "was",
        "are", "for", "not", "but", "his", "her", "she", "him", "had", "has", "would", "could",
        "should", "about", "been", "will", "upon", "said", "such", "only", "very", "more", "most",
        "some", "like", "just", "who", "whom", "our", "out", "off", "its", "it's", "himself",
        "herself", "itself", "themselves", "did", "does", "done", "can", "cannot", "may", "might",
        "must", "shall", "let", "yet", "how", "why", "where", "while", "because", "before",
        "after", "again", "here", "hers", "him", "how", "own", "too", "any", "all", "each",
        "few", "nor", "one", "two", "get", "got", "make", "made", "even", "much", "many", "still",
        "way", "well", "back", "down", "now", "new", "old", "see", "saw", "seen", "came", "come",
        "went", "know", "knew", "think", "thought", "say", "says", "tell", "told", "look", "looked",
    )
}
