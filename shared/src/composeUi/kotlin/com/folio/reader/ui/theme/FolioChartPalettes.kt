package com.folio.reader.ui.theme

import androidx.compose.ui.graphics.Color

/** Eight categorical inks per face, reviewed offline for separation and graphical contrast.
 * Variations stay within that material's authored hues; Classic remains achromatic.
 */
private val authoredChartPalettes = mapOf(
    "gallery-true" to listOf(0xFF383838, 0xFF8D8D8D, 0xFF000000, 0xFF6F6F6F, 0xFF1F1F1F, 0xFF535353, 0xFF131313, 0xFF7E7E7E).map { Color(it) },
    "gallery-false" to listOf(0xFFA8A8A8, 0xFFFFFFFF, 0xFF727272, 0xFFE1E1E1, 0xFF8C8C8C, 0xFFC4C4C4, 0xFF7F7F7F, 0xFFF0F0F0).map { Color(it) },
    "manuscript-true" to listOf(0xFF355D79, 0xFF72548B, 0xFF775C13, 0xFF8F4235, 0xFF87542E, 0xFF4A3128, 0xFF3A2E4C, 0xFFB17B72).map { Color(it) },
    "manuscript-false" to listOf(0xFF9BBFD7, 0xFFC4AEDC, 0xFFD5C27D, 0xFFE89C88, 0xFFE4B17C, 0xFFF2DBCD, 0xFF95856A, 0xFF6C7F96).map { Color(it) },
    "forest-true" to listOf(0xFF3F657F, 0xFF72578D, 0xFF765B1C, 0xFF8C483D, 0xFF286453, 0xFF41311E, 0xFF9D7D6E, 0xFF3A304E).map { Color(it) },
    "forest-false" to listOf(0xFF9BC6DB, 0xFFCAB5E7, 0xFFD9C489, 0xFFE9A694, 0xFFA0D9B6, 0xFF988771, 0xFFF3DFD2, 0xFF689882).map { Color(it) },
    "ocean-true" to listOf(0xFF266A68, 0xFF76558B, 0xFF825C24, 0xFF945242, 0xFF285F9A, 0xFF113C9A, 0xFF483923, 0xFF092155).map { Color(it) },
    "ocean-false" to listOf(0xFF8FD2C6, 0xFFC9B1ED, 0xFFE0BC7B, 0xFFEDA29A, 0xFF90BFF3, 0xFFEEDAC7, 0xFF9D836B, 0xFF729393).map { Color(it) },
    "editorial-true" to listOf(0xFF326B66, 0xFF685A95, 0xFF725C20, 0xFF9E4C2E, 0xFF963C50, 0xFF57372C, 0xFF3D3B52, 0xFFA67085).map { Color(it) },
    "editorial-false" to listOf(0xFF9ACCC2, 0xFFBEBAEB, 0xFFD5C385, 0xFFE8AC82, 0xFFED9AA9, 0xFF95866E, 0xFFF5D5D6, 0xFF7B7FA4).map { Color(it) },
    "blush-true" to listOf(0xFF2C6973, 0xFF675297, 0xFF7D5B20, 0xFF9D4E3F, 0xFF9A3C70, 0xFF55313E, 0xFF452407, 0xFFA97187).map { Color(it) },
    "blush-false" to listOf(0xFF9ED3D5, 0xFFC7B7F0, 0xFFE5C887, 0xFFF0AF9B, 0xFFE9A3CF, 0xFFF3D8E4, 0xFFA09272, 0xFF678C95).map { Color(it) },
    "acid-true" to listOf(0xFF285F92, 0xFF785395, 0xFF845C17, 0xFFA34B2B, 0xFF53691A, 0xFF5A302B, 0xFF2C3A1B, 0xFF638FA2).map { Color(it) },
    "acid-false" to listOf(0xFF89C9F5, 0xFFC1B1EC, 0xFFE3BF73, 0xFFF1A77C, 0xFFCEE880, 0xFFE2F2CA, 0xFF8DA26F, 0xFFA9846F).map { Color(it) },
    "ceramic-true" to listOf(0xFF256A70, 0xFF795395, 0xFF95501F, 0xFFA1434F, 0xFF3557A0, 0xFF523725, 0xFF433952, 0xFF2033A0).map { Color(it) },
    "ceramic-false" to listOf(0xFF9BCFD2, 0xFFD3B8EB, 0xFFF0BE8C, 0xFFEEA2AE, 0xFFA5BFF4, 0xFFA89077, 0xFFF7DDD0, 0xFF8497AB).map { Color(it) },
    "copper-true" to listOf(0xFF346C64, 0xFF78558A, 0xFF416879, 0xFF994B58, 0xFF955137, 0xFF523E2E, 0xFF42374C, 0xFF521B14).map { Color(it) },
    "copper-false" to listOf(0xFFAED1B3, 0xFFD4B8E7, 0xFFA4CDD9, 0xFFEAA6AE, 0xFFE4B092, 0xFFF3DBD9, 0xFF988BA2, 0xFFA08D77).map { Color(it) },
)

internal fun folioChartPalette(id: String, light: Boolean): List<Color> =
    checkNotNull(authoredChartPalettes["$id-$light"]) { "Missing chart palette: $id/$light" }
