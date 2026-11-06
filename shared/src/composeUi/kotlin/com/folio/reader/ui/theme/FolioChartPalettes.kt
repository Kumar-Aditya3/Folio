package com.folio.reader.ui.theme

import androidx.compose.ui.graphics.Color

/** Eight categorical inks per face, reviewed offline for separation and graphical contrast.
 * Variations stay within that material's authored hues; Classic remains achromatic.
 */
private val authoredChartPalettes = mapOf(
    "gallery-true" to listOf(0xFF2E5D8A, 0xFF4F6C3C, 0xFF7E601C, 0xFF661734, 0xFF2C2618, 0xFF966174, 0xFF1F3040, 0xFF331721).map { Color(it) },
    "gallery-false" to listOf(0xFF9BCDEB, 0xFFE8A4BC, 0xFF9BD6BE, 0xFFF2C465, 0xFF8B774A, 0xFF5E8078, 0xFFF1E1BF, 0xFFEED5DE).map { Color(it) },
    "manuscript-true" to listOf(0xFF87542E, 0xFF2B6A69, 0xFF9A3D76, 0xFF473C87, 0xFF3E2A30, 0xFF8178A8, 0xFF383155, 0xFF283E3A).map { Color(it) },
    "manuscript-false" to listOf(0xFFFFC36B, 0xFFC9BBFF, 0xFFFFA394, 0xFF98D6EC, 0xFFFFE8C5, 0xFF6E8284, 0xFFA37941, 0xFFE3D5EE).map { Color(it) },
    "forest-true" to listOf(0xFF23603A, 0xFF3D4A92, 0xFF6C621D, 0xFF974426, 0xFF253646, 0xFF45331F, 0xFF193521, 0xFF6E3C22).map { Color(it) },
    "forest-false" to listOf(0xFF88D0A0, 0xFFAEC2FF, 0xFFE4CF77, 0xFFFEAF8B, 0xFFDDE9E3, 0xFF878953, 0xFFB88B6C, 0xFFE7E6B8).map { Color(it) },
    "ocean-true" to listOf(0xFF006B84, 0xFF665493, 0xFF6D642A, 0xFF9A2E4F, 0xFF432F41, 0xFF334334, 0xFF6E2F48, 0xFF0E3C48).map { Color(it) },
    "ocean-false" to listOf(0xFF50DAE5, 0xFFC1BEFF, 0xFFE9D18B, 0xFFFDA3AE, 0xFF838A6B, 0xFFE9E7E8, 0xFFB3828D, 0xFFE7E4BF).map { Color(it) },
    "editorial-true" to listOf(0xFFA02B35, 0xFF6E5687, 0xFF7A5B22, 0xFF075F5D, 0xFF422227, 0xFF1E3738, 0xFF543F22, 0xFF68262C).map { Color(it) },
    "editorial-false" to listOf(0xFFFF8984, 0xFFCCB8F3, 0xFFE8C77C, 0xFF86E2C1, 0xFFFDD9D3, 0xFF72A691, 0xFFD2E9D9, 0xFFB59362).map { Color(it) },
    "blush-true" to listOf(0xFFA52977, 0xFF654BA0, 0xFF9C4921, 0xFF086065, 0xFF47272D, 0xFF442F58, 0xFF233643, 0xFF6E2455).map { Color(it) },
    "blush-false" to listOf(0xFFFF7BD4, 0xFFC5ACFF, 0xFFFFB381, 0xFF85E3D7, 0xFF658694, 0xFFFDDFDF, 0xFFA56D68, 0xFFFEB0E5).map { Color(it) },
    "acid-true" to listOf(0xFF315B9F, 0xFF596D15, 0xFFA34919, 0xFF942F84, 0xFF3B3025, 0xFF5F2F4B, 0xFF673D13, 0xFF2B4659).map { Color(it) },
    "acid-false" to listOf(0xFF83BFFF, 0xFFDAED75, 0xFFFFA66B, 0xFFFE91D5, 0xFFDBEDDD, 0xFF849547, 0xFFF8C3D5, 0xFFE9F5B2).map { Color(it) },
    "ceramic-true" to listOf(0xFF2549B8, 0xFF8D5064, 0xFF316C68, 0xFF7E5909, 0xFF21315C, 0xFF514326, 0xFF333439, 0xFF593E55).map { Color(it) },
    "ceramic-false" to listOf(0xFF8BA9FF, 0xFFEFA2BC, 0xFF8FDBC8, 0xFFF8D070, 0xFF928763, 0xFFD9E3FF, 0xFFF2E0B5, 0xFF6DA7A3).map { Color(it) },
    "copper-true" to listOf(0xFF2D6C61, 0xFF5D6927, 0xFF5A4D9A, 0xFF8F4537, 0xFF382F38, 0xFF403F22, 0xFF523228, 0xFF463B5F).map { Color(it) },
    "copper-false" to listOf(0xFF7ED5C5, 0xFFC0D096, 0xFFA9C8F4, 0xFFEEB38B, 0xFFE9E6E2, 0xFF828766, 0xFF6AA296, 0xFF879AB6).map { Color(it) },
    "risograph-true" to listOf(0xFFB5381E, 0xFF5D4E91, 0xFF716124, 0xFF1B507A, 0xFF452928, 0xFF64849E, 0xFF722F24, 0xFF4E4427).map { Color(it) },
    "risograph-false" to listOf(0xFFDAD39A, 0xFF82DFD4, 0xFFC4B4FF, 0xFFFDA57A, 0xFF7C8875, 0xFFE3DAEA, 0xFFB3816C, 0xFF4E8E93).map { Color(it) },
    "celadon-true" to listOf(0xFF236454, 0xFF924A65, 0xFF785E25, 0xFF6E3E87, 0xFF372C44, 0xFF453B2E, 0xFF273D3F, 0xFF60394D).map { Color(it) },
    "celadon-false" to listOf(0xFFD4A4F4, 0xFFF3A0B8, 0xFFE2D6A0, 0xFF82E0B8, 0xFFDDE7E9, 0xFF6BA394, 0xFFAD9C84, 0xFFE8D0F5).map { Color(it) },
    "mineral-true" to listOf(0xFF7C5E00, 0xFF256C81, 0xFF9E4A43, 0xFF673E97, 0xFF423528, 0xFF463256, 0xFF594618, 0xFF2E424E).map { Color(it) },
    "mineral-false" to listOf(0xFFCEACFF, 0xFF79D9EC, 0xFFFFAB86, 0xFFF7D843, 0xFF9F696F, 0xFF5981A4, 0xFFF0E3E5, 0xFFF8E8A6).map { Color(it) },
)

internal fun folioChartPalette(id: String, light: Boolean): List<Color> =
    checkNotNull(authoredChartPalettes["$id-$light"]) { "Missing chart palette: $id/$light" }
