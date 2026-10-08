package com.folio.reader.ui.theme

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The pack-id → Mirage-family mapping is pure data (no Compose), so it is asserted directly.
 *
 * This is the regression guard for the bug the rebuild fixed: the lookup used to key on a second
 * "art-direction family" id space (`ocean`, `nebula`, …) that the running app never produced, so
 * nine of the ten packs silently fell through to one default and eight families were dead code.
 * The mapping now keys on [ThemePack.id] — the exact value `LocalFolioPackId` carries — and these
 * tests drive it from [ThemePack.ALL], the same data the app reads, so a future id-space drift
 * fails here instead of shipping.
 */
class FolioFieldKindTest {

    @Test
    fun everyShippedPackMapsToItsOwnFamily() {
        val byPack = ThemePack.ALL.associate { it.id to folioFieldKindFor(it.id) }

        assertEquals(FolioFieldKind.GrainGradient, byPack["gallery"])
        assertEquals(FolioFieldKind.Warp, byPack["manuscript"])
        assertEquals(FolioFieldKind.Dithering, byPack["silver"])
        assertEquals(FolioFieldKind.Swirl, byPack["arctic"])
        assertEquals(FolioFieldKind.DotOrbit, byPack["sakura"])
        assertEquals(FolioFieldKind.Metaballs, byPack["honey"])
        assertEquals(FolioFieldKind.Voronoi, byPack["moss"])
        assertEquals(FolioFieldKind.GodRays, byPack["dawn"])
        assertEquals(FolioFieldKind.MeshGradient, byPack["lagoon"])
        assertEquals(FolioFieldKind.SmokeRing, byPack["rouge"])
    }

    @Test
    fun everyShippedPackAnimatesDistinctly() {
        val kinds = ThemePack.ALL.map { folioFieldKindFor(it.id) }
        // One family per pack, no two packs sharing motion: this is what "every theme has its own
        // identity" means mechanically, and it is the assertion that was absent before.
        assertEquals(ThemePack.ALL.size, kinds.toSet().size)
    }

    @Test
    fun noShippedPackSilentlyFallsBackToTheDefault() {
        // The fallback for an unknown id is MeshGradient. No shipped pack is allowed to resolve by
        // falling through — every one must be named explicitly in the when — so the count of packs
        // whose family equals the fallback must equal the count that are *assigned* it (lagoon).
        val meshPacks = ThemePack.ALL
            .filter { folioFieldKindFor(it.id) == FolioFieldKind.MeshGradient }
            .map { it.id }
        assertEquals(listOf("lagoon"), meshPacks)
    }

    @Test
    fun unknownIdFallsBackToMesh() {
        assertEquals(FolioFieldKind.MeshGradient, folioFieldKindFor(""))
        assertEquals(FolioFieldKind.MeshGradient, folioFieldKindFor("not-a-pack"))
    }

    @Test
    fun allTenFamiliesAreUsed() {
        // The ten packs cover the ten *designed-default* families — a bijection onto
        // FOLIO_DEFAULT_FIELD_KINDS. The remaining FolioFieldKind entries are opt-in via the
        // per-theme animation picker and are deliberately not any pack's default.
        val used = ThemePack.ALL.map { folioFieldKindFor(it.id) }.toSet()
        assertEquals(FOLIO_DEFAULT_FIELD_KINDS.toSet(), used)
    }
}
