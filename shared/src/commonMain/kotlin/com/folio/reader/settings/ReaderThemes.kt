package com.folio.reader.settings

/** Paired reading stocks. Stable existing IDs preserve saved preferences. */
internal val ReaderThemePresets: Map<String, Theme> = mapOf(
    "classic" to Theme(
        id = "classic", name = "Classic",
        background = 0xFFFCFCFC.toInt(), surface = 0xFFFFFFFF.toInt(), primaryText = 0xFF171717.toInt(),
        secondaryText = 0xFF555555.toInt(), headingText = 0xFF171717.toInt(), link = 0xFF6B1736.toInt(),
        selection = 0xFFDFCED4.toInt(), bookmark = 0xFF557440.toInt(), progress = 0xFF6B1736.toInt(),
        divider = 0xFFB7B7B7.toInt(),
        highlightColors = listOf(0xFFE5C56A.toInt(), 0xFF83B89C.toInt(), 0xFFD3A16E.toInt(), 0xFFDC8F8A.toInt(), 0xFFB9A2D0.toInt(), 0xFF99BACC.toInt(), 0xFFA7BB79.toInt(), 0xFFD4A7BE.toInt()),
        isDark = false
    ),
    "charcoal" to Theme(
        id = "charcoal", name = "Charcoal",
        background = 0xFF2B2923.toInt(), surface = 0xFF2B2923.toInt(), primaryText = 0xFFF1F1F1.toInt(),
        secondaryText = 0xFFBEBEBE.toInt(), headingText = 0xFFF1F1F1.toInt(), link = 0xFFF2C15C.toInt(),
        selection = 0xFF5D4F31.toInt(), bookmark = 0xFFE8A4BC.toInt(), progress = 0xFFF2C15C.toInt(),
        divider = 0xFF666561.toInt(),
        highlightColors = listOf(0xFFE5C56A.toInt(), 0xFF83B89C.toInt(), 0xFFD3A16E.toInt(), 0xFFDC8F8A.toInt(), 0xFFB9A2D0.toInt(), 0xFF99BACC.toInt(), 0xFFA7BB79.toInt(), 0xFFD4A7BE.toInt()),
        isDark = true
    ),
    "paper" to Theme(
        id = "paper", name = "Paper",
        background = 0xFFFFF8E8.toInt(), surface = 0xFFFFFCF5.toInt(), primaryText = 0xFF27251F.toInt(),
        secondaryText = 0xFF5A574E.toInt(), headingText = 0xFF27251F.toInt(), link = 0xFF493E8E.toInt(),
        selection = 0xFFDBD3D6.toInt(), bookmark = 0xFF2B6A69.toInt(), progress = 0xFF493E8E.toInt(),
        divider = 0xFFBEB9AC.toInt(),
        highlightColors = listOf(0xFFE5C56A.toInt(), 0xFF83B89C.toInt(), 0xFFD3A16E.toInt(), 0xFFDC8F8A.toInt(), 0xFFB9A2D0.toInt(), 0xFF99BACC.toInt(), 0xFFA7BB79.toInt(), 0xFFD4A7BE.toInt()),
        isDark = false
    ),
    "ember" to Theme(
        id = "ember", name = "Ember",
        background = 0xFF3B2518.toInt(), surface = 0xFF3B2518.toInt(), primaryText = 0xFFFFF1DB.toInt(),
        secondaryText = 0xFFCEBCA3.toInt(), headingText = 0xFFFFF1DB.toInt(), link = 0xFF91D4ED.toInt(),
        selection = 0xFF50514D.toInt(), bookmark = 0xFFC9BBFF.toInt(), progress = 0xFF91D4ED.toInt(),
        divider = 0xFF766252.toInt(),
        highlightColors = listOf(0xFFE5C56A.toInt(), 0xFF83B89C.toInt(), 0xFFD3A16E.toInt(), 0xFFDC8F8A.toInt(), 0xFFB9A2D0.toInt(), 0xFF99BACC.toInt(), 0xFFA7BB79.toInt(), 0xFFD4A7BE.toInt()),
        isDark = true
    ),
    "forest" to Theme(
        id = "forest", name = "Forest",
        background = 0xFFEEF3D8.toInt(), surface = 0xFFF8FBEF.toInt(), primaryText = 0xFF172A1B.toInt(),
        secondaryText = 0xFF4B604C.toInt(), headingText = 0xFF172A1B.toInt(), link = 0xFFAB4828.toInt(),
        selection = 0xFFE1D1B5.toInt(), bookmark = 0xFF3D4A92.toInt(), progress = 0xFFAB4828.toInt(),
        divider = 0xFFAEB79F.toInt(),
        highlightColors = listOf(0xFFE5C56A.toInt(), 0xFF83B89C.toInt(), 0xFFD3A16E.toInt(), 0xFFDC8F8A.toInt(), 0xFFB9A2D0.toInt(), 0xFF99BACC.toInt(), 0xFFA7BB79.toInt(), 0xFFD4A7BE.toInt()),
        isDark = false
    ),
    "midnight" to Theme(
        id = "midnight", name = "Midnight",
        background = 0xFF303925.toInt(), surface = 0xFF303925.toInt(), primaryText = 0xFFE9F3DC.toInt(),
        secondaryText = 0xFFAEC4AD.toInt(), headingText = 0xFFE9F3DC.toInt(), link = 0xFFFFAB86.toInt(),
        selection = 0xFF64563D.toInt(), bookmark = 0xFFAEC2FF.toInt(), progress = 0xFFFFAB86.toInt(),
        divider = 0xFF68715C.toInt(),
        highlightColors = listOf(0xFFE5C56A.toInt(), 0xFF83B89C.toInt(), 0xFFD3A16E.toInt(), 0xFFDC8F8A.toInt(), 0xFFB9A2D0.toInt(), 0xFF99BACC.toInt(), 0xFFA7BB79.toInt(), 0xFFD4A7BE.toInt()),
        isDark = true
    ),
    "ocean" to Theme(
        id = "ocean", name = "Ocean",
        background = 0xFFE6F7F4.toInt(), surface = 0xFFF9FFFF.toInt(), primaryText = 0xFF123039.toInt(),
        secondaryText = 0xFF46626A.toInt(), headingText = 0xFF123039.toInt(), link = 0xFFA32E50.toInt(),
        selection = 0xFFD9CFD3.toInt(), bookmark = 0xFF665493.toInt(), progress = 0xFFA32E50.toInt(),
        divider = 0xFFA6BBBC.toInt(),
        highlightColors = listOf(0xFFE5C56A.toInt(), 0xFF83B89C.toInt(), 0xFFD3A16E.toInt(), 0xFFDC8F8A.toInt(), 0xFFB9A2D0.toInt(), 0xFF99BACC.toInt(), 0xFFA7BB79.toInt(), 0xFFD4A7BE.toInt()),
        isDark = false
    ),
    "abyss" to Theme(
        id = "abyss", name = "Abyss",
        background = 0xFF1E3942.toInt(), surface = 0xFF1E3942.toInt(), primaryText = 0xFFE4F8F7.toInt(),
        secondaryText = 0xFFA5CDD0.toInt(), headingText = 0xFFE4F8F7.toInt(), link = 0xFFFF9EA9.toInt(),
        selection = 0xFF56525C.toInt(), bookmark = 0xFFC1BEFF.toInt(), progress = 0xFFFF9EA9.toInt(),
        divider = 0xFF597278.toInt(),
        highlightColors = listOf(0xFFE5C56A.toInt(), 0xFF83B89C.toInt(), 0xFFD3A16E.toInt(), 0xFFDC8F8A.toInt(), 0xFFB9A2D0.toInt(), 0xFF99BACC.toInt(), 0xFFA7BB79.toInt(), 0xFFD4A7BE.toInt()),
        isDark = true
    ),
    "editorial" to Theme(
        id = "editorial", name = "Editorial",
        background = 0xFFF1F1ED.toInt(), surface = 0xFFFFFFFC.toInt(), primaryText = 0xFF2B2023.toInt(),
        secondaryText = 0xFF675052.toInt(), headingText = 0xFF2B2023.toInt(), link = 0xFF006B67.toInt(),
        selection = 0xFFC1D6D2.toInt(), bookmark = 0xFF6E5687.toInt(), progress = 0xFF006B67.toInt(),
        divider = 0xFFB6B2B0.toInt(),
        highlightColors = listOf(0xFFE5C56A.toInt(), 0xFF83B89C.toInt(), 0xFFD3A16E.toInt(), 0xFFDC8F8A.toInt(), 0xFFB9A2D0.toInt(), 0xFF99BACC.toInt(), 0xFFA7BB79.toInt(), 0xFFD4A7BE.toInt()),
        isDark = false
    ),
    "crimson" to Theme(
        id = "crimson", name = "Crimson",
        background = 0xFF3B252B.toInt(), surface = 0xFF3B252B.toInt(), primaryText = 0xFFFCEDE7.toInt(),
        secondaryText = 0xFFD4B4B2.toInt(), headingText = 0xFFFCEDE7.toInt(), link = 0xFF7FE1BF.toInt(),
        selection = 0xFF4C5450.toInt(), bookmark = 0xFFCCB8F3.toInt(), progress = 0xFF7FE1BF.toInt(),
        divider = 0xFF756163.toInt(),
        highlightColors = listOf(0xFFE5C56A.toInt(), 0xFF83B89C.toInt(), 0xFFD3A16E.toInt(), 0xFFDC8F8A.toInt(), 0xFFB9A2D0.toInt(), 0xFF99BACC.toInt(), 0xFFA7BB79.toInt(), 0xFFD4A7BE.toInt()),
        isDark = true
    ),
    "blush" to Theme(
        id = "blush", name = "Blush",
        background = 0xFFFAE8F1.toInt(), surface = 0xFFFFF9FC.toInt(), primaryText = 0xFF321F30.toInt(),
        secondaryText = 0xFF70536A.toInt(), headingText = 0xFF321F30.toInt(), link = 0xFF006D6F.toInt(),
        selection = 0xFFC8CFD7.toInt(), bookmark = 0xFF654BA0.toInt(), progress = 0xFF006D6F.toInt(),
        divider = 0xFFBEACB7.toInt(),
        highlightColors = listOf(0xFFE5C56A.toInt(), 0xFF83B89C.toInt(), 0xFFD3A16E.toInt(), 0xFFDC8F8A.toInt(), 0xFFB9A2D0.toInt(), 0xFF99BACC.toInt(), 0xFFA7BB79.toInt(), 0xFFD4A7BE.toInt()),
        isDark = false
    ),
    "vaporwave" to Theme(
        id = "vaporwave", name = "Vaporwave",
        background = 0xFF391D35.toInt(), surface = 0xFF391D35.toInt(), primaryText = 0xFFFCEAF7.toInt(),
        secondaryText = 0xFFDAB6D4.toInt(), headingText = 0xFFFCEAF7.toInt(), link = 0xFF7DE3D5.toInt(),
        selection = 0xFF4A4E5D.toInt(), bookmark = 0xFFC5ACFF.toInt(), progress = 0xFF7DE3D5.toInt(),
        divider = 0xFF745A6F.toInt(),
        highlightColors = listOf(0xFFE5C56A.toInt(), 0xFF83B89C.toInt(), 0xFFD3A16E.toInt(), 0xFFDC8F8A.toInt(), 0xFFB9A2D0.toInt(), 0xFF99BACC.toInt(), 0xFFA7BB79.toInt(), 0xFFD4A7BE.toInt()),
        isDark = true
    ),
    "acid" to Theme(
        id = "acid", name = "Acid Rave",
        background = 0xFFF4FFD8.toInt(), surface = 0xFFFEFFF0.toInt(), primaryText = 0xFF25300D.toInt(),
        secondaryText = 0xFF56623B.toInt(), headingText = 0xFF25300D.toInt(), link = 0xFF9B2F8C.toInt(),
        selection = 0xFFE2D5C9.toInt(), bookmark = 0xFF5C7115.toInt(), progress = 0xFF9B2F8C.toInt(),
        divider = 0xFFB6C19B.toInt(),
        highlightColors = listOf(0xFFE5C56A.toInt(), 0xFF83B89C.toInt(), 0xFFD3A16E.toInt(), 0xFFDC8F8A.toInt(), 0xFFB9A2D0.toInt(), 0xFF99BACC.toInt(), 0xFFA7BB79.toInt(), 0xFFD4A7BE.toInt()),
        isDark = false
    ),
    "synthwave" to Theme(
        id = "synthwave", name = "Synthwave",
        background = 0xFF273400.toInt(), surface = 0xFF273400.toInt(), primaryText = 0xFFF1F9D5.toInt(),
        secondaryText = 0xFFC2CFA1.toInt(), headingText = 0xFFF1F9D5.toInt(), link = 0xFFFF8AD5.toInt(),
        selection = 0xFF5D4A35.toInt(), bookmark = 0xFFDAED75.toInt(), progress = 0xFFFF8AD5.toInt(),
        divider = 0xFF646F40.toInt(),
        highlightColors = listOf(0xFFE5C56A.toInt(), 0xFF83B89C.toInt(), 0xFFD3A16E.toInt(), 0xFFDC8F8A.toInt(), 0xFFB9A2D0.toInt(), 0xFF99BACC.toInt(), 0xFFA7BB79.toInt(), 0xFFD4A7BE.toInt()),
        isDark = true
    ),
    "porcelain" to Theme(
        id = "porcelain", name = "Porcelain",
        background = 0xFFF4F7FF.toInt(), surface = 0xFFFCFEFF.toInt(), primaryText = 0xFF202B45.toInt(),
        secondaryText = 0xFF4D6085.toInt(), headingText = 0xFF202B45.toInt(), link = 0xFF8D6000.toInt(),
        selection = 0xFFDFD9CC.toInt(), bookmark = 0xFF975367.toInt(), progress = 0xFF8D6000.toInt(),
        divider = 0xFFB4BAC7.toInt(),
        highlightColors = listOf(0xFFE5C56A.toInt(), 0xFF83B89C.toInt(), 0xFFD3A16E.toInt(), 0xFFDC8F8A.toInt(), 0xFFB9A2D0.toInt(), 0xFF99BACC.toInt(), 0xFFA7BB79.toInt(), 0xFFD4A7BE.toInt()),
        isDark = false
    ),
    "cobalt" to Theme(
        id = "cobalt", name = "Cobalt",
        background = 0xFF202F4B.toInt(), surface = 0xFF202F4B.toInt(), primaryText = 0xFFECF2FF.toInt(),
        secondaryText = 0xFFB4C6E5.toInt(), headingText = 0xFFECF2FF.toInt(), link = 0xFFF9CE67.toInt(),
        selection = 0xFF565752.toInt(), bookmark = 0xFFEFA2BC.toInt(), progress = 0xFFF9CE67.toInt(),
        divider = 0xFF5D6A81.toInt(),
        highlightColors = listOf(0xFFE5C56A.toInt(), 0xFF83B89C.toInt(), 0xFFD3A16E.toInt(), 0xFFDC8F8A.toInt(), 0xFFB9A2D0.toInt(), 0xFF99BACC.toInt(), 0xFFA7BB79.toInt(), 0xFFD4A7BE.toInt()),
        isDark = true
    ),
    "linen" to Theme(
        id = "linen", name = "Linen",
        background = 0xFFF5E3C7.toInt(), surface = 0xFFF8FCF1.toInt(), primaryText = 0xFF30271F.toInt(),
        secondaryText = 0xFF6D5C4F.toInt(), headingText = 0xFF30271F.toInt(), link = 0xFFA04A3B.toInt(),
        selection = 0xFFE4C5AB.toInt(), bookmark = 0xFF5D6927.toInt(), progress = 0xFFA24B3C.toInt(),
        divider = 0xFFBAAB95.toInt(),
        highlightColors = listOf(0xFFE5C56A.toInt(), 0xFF83B89C.toInt(), 0xFFD3A16E.toInt(), 0xFFDC8F8A.toInt(), 0xFFB9A2D0.toInt(), 0xFF99BACC.toInt(), 0xFFA7BB79.toInt(), 0xFFD4A7BE.toInt()),
        isDark = false
    ),
    "copper" to Theme(
        id = "copper", name = "Copper",
        background = 0xFF44312A.toInt(), surface = 0xFF44312A.toInt(), primaryText = 0xFFF9EDDD.toInt(),
        secondaryText = 0xFFCFBDA8.toInt(), headingText = 0xFFF9EDDD.toInt(), link = 0xFFEDAF86.toInt(),
        selection = 0xFF6E5041.toInt(), bookmark = 0xFFC0D096.toInt(), progress = 0xFFEDAF86.toInt(),
        divider = 0xFF7A6960.toInt(),
        highlightColors = listOf(0xFFE5C56A.toInt(), 0xFF83B89C.toInt(), 0xFFD3A16E.toInt(), 0xFFDC8F8A.toInt(), 0xFFB9A2D0.toInt(), 0xFF99BACC.toInt(), 0xFFA7BB79.toInt(), 0xFFD4A7BE.toInt()),
        isDark = true
    ),
    "risograph" to Theme(
        id = "risograph", name = "Risograph",
        background = 0xFFFFE7D3.toInt(), surface = 0xFFFFFBF3.toInt(), primaryText = 0xFF29252B.toInt(),
        secondaryText = 0xFF665660.toInt(), headingText = 0xFF29252B.toInt(), link = 0xFF1A537F.toInt(),
        selection = 0xFFD1C9C2.toInt(), bookmark = 0xFF5D4E91.toInt(), progress = 0xFF1A537F.toInt(),
        divider = 0xFFBFADA1.toInt(),
        highlightColors = listOf(0xFFE5C56A.toInt(), 0xFF83B89C.toInt(), 0xFFD3A16E.toInt(), 0xFFDC8F8A.toInt(), 0xFFB9A2D0.toInt(), 0xFF99BACC.toInt(), 0xFFA7BB79.toInt(), 0xFFD4A7BE.toInt()),
        isDark = false
    ),
    "afterprint" to Theme(
        id = "afterprint", name = "Afterprint",
        background = 0xFF263B51.toInt(), surface = 0xFF263B51.toInt(), primaryText = 0xFFF4F0DE.toInt(),
        secondaryText = 0xFFAEC9D4.toInt(), headingText = 0xFFF4F0DE.toInt(), link = 0xFFFF9564.toInt(),
        selection = 0xFF5C5256.toInt(), bookmark = 0xFF82DFD4.toInt(), progress = 0xFFFF9564.toInt(),
        divider = 0xFF64717B.toInt(),
        highlightColors = listOf(0xFFE5C56A.toInt(), 0xFF83B89C.toInt(), 0xFFD3A16E.toInt(), 0xFFDC8F8A.toInt(), 0xFFB9A2D0.toInt(), 0xFF99BACC.toInt(), 0xFFA7BB79.toInt(), 0xFFD4A7BE.toInt()),
        isDark = true
    ),
    "celadon" to Theme(
        id = "celadon", name = "Celadon",
        background = 0xFFE9F1E5.toInt(), surface = 0xFFFAFFF6.toInt(), primaryText = 0xFF292733.toInt(),
        secondaryText = 0xFF5C5D6C.toInt(), headingText = 0xFF292733.toInt(), link = 0xFF72408C.toInt(),
        selection = 0xFFD1CED3.toInt(), bookmark = 0xFF9A4D69.toInt(), progress = 0xFF72408C.toInt(),
        divider = 0xFFAFB4B0.toInt(),
        highlightColors = listOf(0xFFE5C56A.toInt(), 0xFF83B89C.toInt(), 0xFFD3A16E.toInt(), 0xFFDC8F8A.toInt(), 0xFFB9A2D0.toInt(), 0xFF99BACC.toInt(), 0xFFA7BB79.toInt(), 0xFFD4A7BE.toInt()),
        isDark = false
    ),
    "amethyst" to Theme(
        id = "amethyst", name = "Amethyst",
        background = 0xFF3D3534.toInt(), surface = 0xFF3D3534.toInt(), primaryText = 0xFFF4E9F5.toInt(),
        secondaryText = 0xFFC9B4D3.toInt(), headingText = 0xFFF4E9F5.toInt(), link = 0xFF7BDFB4.toInt(),
        selection = 0xFF4C6054.toInt(), bookmark = 0xFFF3A0B8.toInt(), progress = 0xFF7BDFB4.toInt(),
        divider = 0xFF746B6E.toInt(),
        highlightColors = listOf(0xFFE5C56A.toInt(), 0xFF83B89C.toInt(), 0xFFD3A16E.toInt(), 0xFFDC8F8A.toInt(), 0xFFB9A2D0.toInt(), 0xFF99BACC.toInt(), 0xFFA7BB79.toInt(), 0xFFD4A7BE.toInt()),
        isDark = true
    ),
    "citrineglass" to Theme(
        id = "citrineglass", name = "Citrine",
        background = 0xFFFFF6C5.toInt(), surface = 0xFFFFFEEC.toInt(), primaryText = 0xFF332B32.toInt(),
        secondaryText = 0xFF6D5E64.toInt(), headingText = 0xFF332B32.toInt(), link = 0xFF6A3F9D.toInt(),
        selection = 0xFFE1D1BD.toInt(), bookmark = 0xFF256D82.toInt(), progress = 0xFF6A3F9D.toInt(),
        divider = 0xFFC2B999.toInt(),
        highlightColors = listOf(0xFFE5C56A.toInt(), 0xFF83B89C.toInt(), 0xFFD3A16E.toInt(), 0xFFDC8F8A.toInt(), 0xFFB9A2D0.toInt(), 0xFF99BACC.toInt(), 0xFFA7BB79.toInt(), 0xFFD4A7BE.toInt()),
        isDark = false
    ),
    "blacklight" to Theme(
        id = "blacklight", name = "Blacklight",
        background = 0xFF30334A.toInt(), surface = 0xFF30334A.toInt(), primaryText = 0xFFF8F1DE.toInt(),
        secondaryText = 0xFFC7BBDD.toInt(), headingText = 0xFFF8F1DE.toInt(), link = 0xFFF7D639.toInt(),
        selection = 0xFF625C46.toInt(), bookmark = 0xFF79D9EC.toInt(), progress = 0xFFF7D639.toInt(),
        divider = 0xFF6C6C76.toInt(),
        highlightColors = listOf(0xFFE5C56A.toInt(), 0xFF83B89C.toInt(), 0xFFD3A16E.toInt(), 0xFFDC8F8A.toInt(), 0xFFB9A2D0.toInt(), 0xFF99BACC.toInt(), 0xFFA7BB79.toInt(), 0xFFD4A7BE.toInt()),
        isDark = true
    ),

)
