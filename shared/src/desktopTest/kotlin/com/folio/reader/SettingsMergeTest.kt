package com.folio.reader

import com.folio.reader.settings.CustomAppTheme
import com.folio.reader.settings.CustomFont
import com.folio.reader.settings.ReaderSettings
import com.folio.reader.settings.Theme
import com.folio.reader.settings.diffFields
import com.folio.reader.settings.withFieldsFrom
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.double
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * B0 drift guard: diff/apply on the global settings row must cover EVERY field
 * of [ReaderSettings], including fields added after this test was written. The
 * test enumerates the serializer's descriptor, so a new field that the helpers
 * fail to notice fails here by name.
 */
class SettingsMergeTest {

    private val json = Json { encodeDefaults = false; ignoreUnknownKeys = true }

    private fun encode(settings: ReaderSettings): JsonObject =
        json.encodeToJsonElement(ReaderSettings.serializer(), settings).jsonObject

    private fun decode(element: JsonObject): ReaderSettings =
        json.decodeFromJsonElement(ReaderSettings.serializer(), element)

    /** A row where every field holds a non-default value, so every key is encoded. */
    private fun nonDefaultRow(): ReaderSettings = ReaderSettings(
        fontFamily = "Calluna",
        fontSize = 21f,
        fontWeight = 450,
        lineHeight = 1.7f,
        letterSpacing = 0.3f,
        wordSpacing = 0.2f,
        paragraphSpacing = 1.4f,
        margins = com.folio.reader.settings.Margins(left = 31f, right = 32f, top = 41f, bottom = 42f),
        textWidth = com.folio.reader.settings.TextWidth.WIDE,
        alignment = com.folio.reader.settings.TextAlignment.JUSTIFIED,
        hyphenation = false,
        themeId = "dusk",
        layoutMode = com.folio.reader.settings.LayoutMode.PAGINATED,
        formattingMode = com.folio.reader.model.FormattingMode.NORMALIZED,
        showChapterTitle = false,
        showProgress = false,
        showClock = true,
        customTheme = Theme(
            id = "custom-1", name = "Custom One",
            background = 1, surface = 2, primaryText = 3, secondaryText = 4,
            headingText = 5, link = 6, selection = 7, bookmark = 8,
            highlightColors = listOf(1, 2, 3, 4, 5, 6, 7, 8),
            progress = 9, divider = 10, isDark = true
        ),
        useEmbeddedFonts = true,
        appThemeId = "midnight",
        fontThemeId = "rounded",
        customFonts = listOf(
            // addedAt is pinned: a live Clock.System.now() here is sometimes
            // omitted at encode (encodeDefaults=false, same clock tick) and
            // re-filled with a different instant at decode, making the
            // round-trip flaky for no reason this test cares about.
            CustomFont(
                id = "f1", name = "Font One", fileName = "f1.ttf", familyName = "Font One",
                addedAt = Instant.parse("2026-01-01T00:00:00Z")
            )
        ),
        highlightColorIndex = 3,
        firebaseApiKey = "key",
        firebaseProjectId = "proj",
        cloudSyncEnabled = false,
        syncAccountEmail = "a@b.c",
        syncAccountPassword = "pw",
        syncPositions = false,
        syncAnnotations = false,
        syncSettings = false,
        autoSyncInterval = 30,
        dailyGoalMinutes = 90,
        mangaUpdateIntervalHours = 12,
        homeCoverTint = false,
        customAppTheme = CustomAppTheme(name = "Mine", background = 0xFF123456.toInt()),
        topBarOpacity = 0.5f,
        navBarOpacity = 0.8f,
        panelOpacity = 0.9f,
        readerChromeOpacity = 0.85f
    )

    @Test
    fun equalRowsHaveNoDiff() {
        val row = nonDefaultRow()
        assertEquals(emptySet(), row.diffFields(row))
        assertEquals(emptySet(), ReaderSettings().diffFields(ReaderSettings()))
    }

    @Test
    fun everyFieldIsDiffedAndApplied() {
        val base = nonDefaultRow()
        val baseJson = encode(base)
        val descriptor = ReaderSettings.serializer().descriptor

        // The guard for the guard: every declared field must actually appear in
        // the encoded row, otherwise the per-field assertions below would pass
        // vacuously for a field this fixture forgot to set.
        assertEquals(
            descriptor.elementsCount, baseJson.size,
            "nonDefaultRow must set every field of ReaderSettings"
        )

        for (index in 0 until descriptor.elementsCount) {
            val name = descriptor.getElementName(index)

            // (a) Restoring the default: the field disappears from the JSON.
            val defaulted = decode(JsonObject(baseJson.toMutableMap().minus(name)))
            assertEquals(setOf(name), base.diffFields(defaulted), "diff on default restore of '$name'")
            assertEquals(defaulted, base.withFieldsFrom(setOf(name), defaulted), "apply default of '$name'")
            assertEquals(base, defaulted.withFieldsFrom(setOf(name), base), "re-apply '$name'")

            // (b) Changing to a different non-default value.
            val mutated = mutateValue(baseJson.getValue(name), descriptor.getElementDescriptor(index))
            if (mutated != null) {
                val changedRow = decode(JsonObject(baseJson.toMutableMap().plus(name to mutated)))
                assertEquals(setOf(name), base.diffFields(changedRow), "diff on value change of '$name'")
                assertEquals(changedRow, base.withFieldsFrom(setOf(name), changedRow), "apply value of '$name'")
            }
        }
    }

    /**
     * A different encoded value for the element described by [descriptor],
     * derived from the declared serial kind so enums flip to another entry and
     * numbers stay numbers (a naive string mangling would be undecodable).
     * Structures mutate their first mutable child; lists append their first
     * element again.
     */
    private fun mutateValue(original: JsonElement, descriptor: SerialDescriptor): JsonElement? {
        return when (descriptor.kind) {
            PrimitiveKind.BOOLEAN -> JsonPrimitive(!original.jsonPrimitive.boolean)
            SerialKind.ENUM -> {
                val options = descriptor.elementNames.toList()
                val current = original.jsonPrimitive.content
                options.firstOrNull { it != current }?.let(::JsonPrimitive)
            }
            PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG ->
                JsonPrimitive(original.jsonPrimitive.long + 1)
            PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE ->
                JsonPrimitive(original.jsonPrimitive.double + 1.0)
            PrimitiveKind.STRING -> JsonPrimitive(original.jsonPrimitive.content + "-mutated")
            StructureKind.CLASS -> {
                val obj = original as? JsonObject ?: return null
                for (child in 0 until descriptor.elementsCount) {
                    val key = descriptor.getElementName(child)
                    val value = obj[key] ?: continue
                    val mutated = mutateValue(value, descriptor.getElementDescriptor(child)) ?: continue
                    return JsonObject(obj.toMutableMap().plus(key to mutated))
                }
                null
            }
            StructureKind.LIST -> {
                val items = original as? JsonArray ?: return null
                items.firstOrNull()?.let { first -> JsonArray(items + first) }
            }
            else -> null
        }
    }
}
