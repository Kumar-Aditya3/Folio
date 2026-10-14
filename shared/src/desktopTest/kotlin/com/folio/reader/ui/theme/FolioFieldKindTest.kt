package com.folio.reader.ui.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The pack-id → animation resolution, as it now stands.
 *
 * This file is the regression guard for two different designs, and the second replaced the first,
 * so both are pinned:
 *
 * 1. **The id-space bug.** The lookup used to key on a second "art-direction family" id space
 *    (`ocean`, `nebula`, …) that the running app never produced, so nine of the ten packs silently
 *    fell through to one default and eight families were dead code. It now keys on [ThemePack.id] —
 *    the exact value `LocalFolioPackId` carries — and it is driven here from [ThemePack.ALL], the
 *    same data the app reads.
 * 2. **The unification.** The motion was then made deliberately *uniform*: one family for every
 *    theme, so switching themes re-colours the field and never drags its style with it. This class
 *    used to assert a one-family-per-pack bijection; that assertion was stale the moment the motion
 *    was unified, and these tests now assert the shipped contract instead — the whole point being
 *    that a reader who wants Gallery still and Arctic swirling uses the per-theme override below,
 *    not a different default per pack.
 */
class FolioFieldKindTest {

    @Test
    fun everyPackResolvesTheUnifiedFamily() {
        val byPack = ThemePack.ALL.associate { it.id to folioFieldKindFor(it.id) }
        byPack.forEach { (packId, family) ->
            assertEquals(
                FOLIO_FIELD_UNIFIED, family,
                "$packId must resolve to the unified family, not one of its own",
            )
        }
    }

    @Test
    fun anUnknownIdFallsThroughToTheUnifiedFamily() {
        // The fallback is the unified family, so a bad persisted value can never blank the field.
        assertEquals(FOLIO_FIELD_UNIFIED, folioFieldKindFor(""))
        assertEquals(FOLIO_FIELD_UNIFIED, folioFieldKindFor("not-a-pack"))
    }

    @Test
    fun aPackWithNoOverrideStillAnimates() {
        ThemePack.ALL.forEach { pack ->
            val choice = folioFieldChoiceFor(pack.id, emptyMap())
            val animated = choice as? FolioFieldChoice.Animated
            assertTrue(animated != null, "a pack with no override must still animate")
            assertEquals(FOLIO_FIELD_UNIFIED, animated!!.kind, "absent an override, the default wins")
        }
    }

    // ── Scope: global by default, per theme when asked ────────────────────────────

    @Test
    fun theGlobalChoiceAppliesToEveryTheme() {
        val overrides = mapOf(FOLIO_FIELD_GLOBAL_KEY to "Swirl")
        ThemePack.ALL.forEach { pack ->
            assertEquals(
                FolioFieldChoice.Animated(FolioFieldKind.Swirl),
                folioFieldChoiceFor(pack.id, overrides),
            )
        }
    }

    @Test
    fun aPerThemeChoiceOverridesTheGlobalOne() {
        val overrides = mapOf(
            FOLIO_FIELD_GLOBAL_KEY to "Swirl",
            "arctic" to "GodRays",
        )
        assertEquals(
            FolioFieldChoice.Animated(FolioFieldKind.GodRays),
            folioFieldChoiceFor("arctic", overrides),
        )
        // And every other theme keeps the global choice.
        assertEquals(
            FolioFieldChoice.Animated(FolioFieldKind.Swirl),
            folioFieldChoiceFor("dawn", overrides),
        )
    }

    @Test
    fun plainMeansStillAndIsStoredAsTheSentinel() {
        val overrides = mapOf(FOLIO_FIELD_GLOBAL_KEY to FOLIO_FIELD_PLAIN)
        ThemePack.ALL.forEach { pack ->
            assertEquals(FolioFieldChoice.Plain, folioFieldChoiceFor(pack.id, overrides))
        }
    }

    @Test
    fun anUnknownTokenFallsThroughRatherThanBlankingTheField() {
        // A stale or malformed token must never resolve to "nothing": the reader gets the designed
        // default instead, exactly as the resolver has always insisted.
        val overrides = mapOf(FOLIO_FIELD_GLOBAL_KEY to "NotAFamily")
        ThemePack.ALL.forEach { pack ->
            assertEquals(
                FolioFieldChoice.Animated(FOLIO_FIELD_UNIFIED),
                folioFieldChoiceFor(pack.id, overrides),
            )
        }
    }

    // ── The picker's surface ─────────────────────────────────────────────────────

    @Test
    fun everyFamilyIsNamedAndDescribedForThePicker() {
        // The picker renders displayName + describe for all 25 families; a family added without
        // either would show a blank row, so this is the enum's coverage guard.
        FolioFieldKind.entries.forEach { kind ->
            assertTrue(kind.displayName().isNotBlank(), "$kind has no display name")
            assertTrue(kind.describe().isNotBlank(), "$kind has no description")
        }
    }

    @Test
    fun theDesignedDefaultsAreASubsetOfTheFamilies() {
        // The ten "designed default" entries are the ones the picker lists first; they must be real
        // families, and they are deliberately not any pack's default — the unified family is.
        FOLIO_DEFAULT_FIELD_KINDS.forEach { kind ->
            assertTrue(kind in FolioFieldKind.entries, "$kind is not a real family")
        }
        assertTrue(
            FolioFieldKind.entries.size > FOLIO_DEFAULT_FIELD_KINDS.size,
            "the opt-in families must remain a real list",
        )
    }
}
