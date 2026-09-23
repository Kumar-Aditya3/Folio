package com.folio.reader.ml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * c-TF-IDF labelling (stage 1). The property that matters: a term the whole library shares must
 * be suppressed, and a term distinctive to one cluster must surface — that is the entire reason
 * this replaces "first few words of one passage".
 */
class ClusterLabelerTest {

    @Test
    fun `distinctive terms beat globally common filler`() {
        // "ship"/"sailed" appear in every cluster (generic to this library); each cluster also has
        // its own distinctive vocabulary. The label must pick the distinctive words, not the filler.
        val docs = listOf(
            listOf("the ship sailed and the leviathan rose leviathan tide drowned the ship"),
            listOf("the ship sailed to the market where merchants traded coin ledger merchants coin"),
            listOf("the ship sailed under starlight comet orbit nebula comet orbit starlight"),
        )
        val labels = ClusterLabeler.label(docs, topN = 4)

        assertEquals(3, labels.size)
        assertTrue(labels[0].any { it.contains("leviathan") }, "sea cluster must surface 'leviathan': ${labels[0]}")
        assertTrue(labels[1].any { it.contains("merchants") || it.contains("coin") }, "market cluster: ${labels[1]}")
        assertTrue(labels[2].any { it.contains("comet") || it.contains("orbit") }, "space cluster: ${labels[2]}")

        labels.forEachIndexed { i, phrases ->
            assertTrue(phrases.firstOrNull() != "ship", "shared 'ship' must not lead cluster $i: $phrases")
        }
    }

    @Test
    fun `bigrams can surface as phrase labels`() {
        val docs = listOf(
            listOf("time travel paradox time travel machine time travel again"),
            listOf("garden flowers bloom garden flowers grow garden soil"),
        )
        val labels = ClusterLabeler.label(docs, topN = 5)
        assertTrue(labels[0].any { it == "time travel" }, "repeated adjacent pair should form a bigram: ${labels[0]}")
    }

    @Test
    fun `display title-cases and joins`() {
        assertEquals("Time Travel · Paradox", ClusterLabeler.display(listOf("time travel", "paradox", "machine"), max = 2))
        assertEquals("", ClusterLabeler.display(emptyList()))
    }

    @Test
    fun `empty input is handled`() {
        assertEquals(emptyList(), ClusterLabeler.label(emptyList()))
        assertEquals(listOf(emptyList<String>()), ClusterLabeler.label(listOf(emptyList())))
    }
}
