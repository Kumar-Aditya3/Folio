package com.folio.reader.settings

/** Nine paired reading stocks. Stable existing IDs preserve saved preferences. */
internal val ReaderThemePresets: Map<String, Theme> = mapOf(
    "classic" to Theme(
        id = "classic", name = "Classic",
        background = 0xFFF9F9F9.toInt(), surface = 0xFFFDFDFD.toInt(), primaryText = 0xFF171717.toInt(),
        secondaryText = 0xFF555555.toInt(), headingText = 0xFF171717.toInt(), link = 0xFF252525.toInt(),
        selection = 0xFFDBDBDB.toInt(), bookmark = 0xFF3D3D3D.toInt(), progress = 0xFF505050.toInt(),
        divider = 0xFFC7C7C7.toInt(),
        highlightColors = listOf(0xFFE5C56A.toInt(),0xFF83B89C.toInt(),0xFFD3A16E.toInt(),0xFFDC8F8A.toInt(),0xFFB9A2D0.toInt(),0xFF99BACC.toInt(),0xFFA7BB79.toInt(),0xFFD4A7BE.toInt()),
        isDark = false
    ),
    "charcoal" to Theme(
        id = "charcoal", name = "Charcoal",
        background = 0xFF121212.toInt(), surface = 0xFF1C1C1C.toInt(), primaryText = 0xFFF1F1F1.toInt(),
        secondaryText = 0xFFBEBEBE.toInt(), headingText = 0xFFF1F1F1.toInt(), link = 0xFFE4E4E4.toInt(),
        selection = 0xFF424242.toInt(), bookmark = 0xFFD8D8D8.toInt(), progress = 0xFFC8C8C8.toInt(),
        divider = 0xFF505050.toInt(),
        highlightColors = listOf(0xFFE5C56A.toInt(),0xFF83B89C.toInt(),0xFFD3A16E.toInt(),0xFFDC8F8A.toInt(),0xFFB9A2D0.toInt(),0xFF99BACC.toInt(),0xFFA7BB79.toInt(),0xFFD4A7BE.toInt()),
        isDark = true
    ),
    "paper" to Theme(
        id = "paper", name = "Paper",
        background = 0xFFF8F4E9.toInt(), surface = 0xFFFCF8ED.toInt(), primaryText = 0xFF27251F.toInt(),
        secondaryText = 0xFF5A574E.toInt(), headingText = 0xFF27251F.toInt(), link = 0xFF87542E.toInt(),
        selection = 0xFFE3DADA.toInt(), bookmark = 0xFF72548B.toInt(), progress = 0xFF775C13.toInt(),
        divider = 0xFFC9BBA4.toInt(),
        highlightColors = listOf(0xFFE5C56A.toInt(),0xFF83B89C.toInt(),0xFFD3A16E.toInt(),0xFFDC8F8A.toInt(),0xFFB9A2D0.toInt(),0xFF99BACC.toInt(),0xFFA7BB79.toInt(),0xFFD4A7BE.toInt()),
        isDark = false
    ),
    "ember" to Theme(
        id = "ember", name = "Ember",
        background = 0xFF201E18.toInt(), surface = 0xFF2C2920.toInt(), primaryText = 0xFFF1EADC.toInt(),
        secondaryText = 0xFFC1B6A0.toInt(), headingText = 0xFFF1EADC.toInt(), link = 0xFFE4B17C.toInt(),
        selection = 0xFF474147.toInt(), bookmark = 0xFFC4AEDC.toInt(), progress = 0xFFD5C27D.toInt(),
        divider = 0xFF645B48.toInt(),
        highlightColors = listOf(0xFFE5C56A.toInt(),0xFF83B89C.toInt(),0xFFD3A16E.toInt(),0xFFDC8F8A.toInt(),0xFFB9A2D0.toInt(),0xFF99BACC.toInt(),0xFFA7BB79.toInt(),0xFFD4A7BE.toInt()),
        isDark = true
    ),
    "forest" to Theme(
        id = "forest", name = "Forest",
        background = 0xFFEFF4ED.toInt(), surface = 0xFFF4F9F1.toInt(), primaryText = 0xFF172B28.toInt(),
        secondaryText = 0xFF45625B.toInt(), headingText = 0xFF172B28.toInt(), link = 0xFF286453.toInt(),
        selection = 0xFFDBDBDE.toInt(), bookmark = 0xFF72578D.toInt(), progress = 0xFF765B1C.toInt(),
        divider = 0xFFADBCB1.toInt(),
        highlightColors = listOf(0xFFE5C56A.toInt(),0xFF83B89C.toInt(),0xFFD3A16E.toInt(),0xFFDC8F8A.toInt(),0xFFB9A2D0.toInt(),0xFF99BACC.toInt(),0xFFA7BB79.toInt(),0xFFD4A7BE.toInt()),
        isDark = false
    ),
    "midnight" to Theme(
        id = "midnight", name = "Midnight",
        background = 0xFF142424.toInt(), surface = 0xFF1C3030.toInt(), primaryText = 0xFFE2F1E5.toInt(),
        secondaryText = 0xFFA3C1B5.toInt(), headingText = 0xFFE2F1E5.toInt(), link = 0xFFA0D9B6.toInt(),
        selection = 0xFF404753.toInt(), bookmark = 0xFFCAB5E7.toInt(), progress = 0xFFD9C489.toInt(),
        divider = 0xFF4A6563.toInt(),
        highlightColors = listOf(0xFFE5C56A.toInt(),0xFF83B89C.toInt(),0xFFD3A16E.toInt(),0xFFDC8F8A.toInt(),0xFFB9A2D0.toInt(),0xFF99BACC.toInt(),0xFFA7BB79.toInt(),0xFFD4A7BE.toInt()),
        isDark = true
    ),
    "ocean" to Theme(
        id = "ocean", name = "Ocean",
        background = 0xFFF0F6F9.toInt(), surface = 0xFFF6FAFC.toInt(), primaryText = 0xFF142C46.toInt(),
        secondaryText = 0xFF49627C.toInt(), headingText = 0xFF142C46.toInt(), link = 0xFF285F9A.toInt(),
        selection = 0xFFDCDCE7.toInt(), bookmark = 0xFF76558B.toInt(), progress = 0xFF825C24.toInt(),
        divider = 0xFFAFBFCE.toInt(),
        highlightColors = listOf(0xFFE5C56A.toInt(),0xFF83B89C.toInt(),0xFFD3A16E.toInt(),0xFFDC8F8A.toInt(),0xFFB9A2D0.toInt(),0xFF99BACC.toInt(),0xFFA7BB79.toInt(),0xFFD4A7BE.toInt()),
        isDark = false
    ),
    "abyss" to Theme(
        id = "abyss", name = "Abyss",
        background = 0xFF121C2E.toInt(), surface = 0xFF1B2940.toInt(), primaryText = 0xFFE6F0FA.toInt(),
        secondaryText = 0xFFA3B8D1.toInt(), headingText = 0xFFE6F0FA.toInt(), link = 0xFF90BFF3.toInt(),
        selection = 0xFF3E405C.toInt(), bookmark = 0xFFC9B1ED.toInt(), progress = 0xFFE0BC7B.toInt(),
        divider = 0xFF465C7B.toInt(),
        highlightColors = listOf(0xFFE5C56A.toInt(),0xFF83B89C.toInt(),0xFFD3A16E.toInt(),0xFFDC8F8A.toInt(),0xFFB9A2D0.toInt(),0xFF99BACC.toInt(),0xFFA7BB79.toInt(),0xFFD4A7BE.toInt()),
        isDark = true
    ),
    "editorial" to Theme(
        id = "editorial", name = "Editorial",
        background = 0xFFF6EFDE.toInt(), surface = 0xFFFFFAEB.toInt(), primaryText = 0xFF2A2428.toInt(),
        secondaryText = 0xFF66535B.toInt(), headingText = 0xFF2A2428.toInt(), link = 0xFF963C50.toInt(),
        selection = 0xFFDFD7D2.toInt(), bookmark = 0xFF685A95.toInt(), progress = 0xFF725C20.toInt(),
        divider = 0xFFC6B7B3.toInt(),
        highlightColors = listOf(0xFFE5C56A.toInt(),0xFF83B89C.toInt(),0xFFD3A16E.toInt(),0xFFDC8F8A.toInt(),0xFFB9A2D0.toInt(),0xFF99BACC.toInt(),0xFFA7BB79.toInt(),0xFFD4A7BE.toInt()),
        isDark = false
    ),
    "crimson" to Theme(
        id = "crimson", name = "Crimson",
        background = 0xFF241D26.toInt(), surface = 0xFF30252F.toInt(), primaryText = 0xFFF2E8DD.toInt(),
        secondaryText = 0xFFC5B5B5.toInt(), headingText = 0xFFF2E8DD.toInt(), link = 0xFFED9AA9.toInt(),
        selection = 0xFF494355.toInt(), bookmark = 0xFFBEBAEB.toInt(), progress = 0xFFD5C385.toInt(),
        divider = 0xFF695361.toInt(),
        highlightColors = listOf(0xFFE5C56A.toInt(),0xFF83B89C.toInt(),0xFFD3A16E.toInt(),0xFFDC8F8A.toInt(),0xFFB9A2D0.toInt(),0xFF99BACC.toInt(),0xFFA7BB79.toInt(),0xFFD4A7BE.toInt()),
        isDark = true
    ),
    "blush" to Theme(
        id = "blush", name = "Blush",
        background = 0xFFF8F0F6.toInt(), surface = 0xFFFCF6FA.toInt(), primaryText = 0xFF35263B.toInt(),
        secondaryText = 0xFF6B536E.toInt(), headingText = 0xFF35263B.toInt(), link = 0xFF9A3C70.toInt(),
        selection = 0xFFE1D7E7.toInt(), bookmark = 0xFF675297.toInt(), progress = 0xFF7D5B20.toInt(),
        divider = 0xFFC8B6CB.toInt(),
        highlightColors = listOf(0xFFE5C56A.toInt(),0xFF83B89C.toInt(),0xFFD3A16E.toInt(),0xFFDC8F8A.toInt(),0xFFB9A2D0.toInt(),0xFF99BACC.toInt(),0xFFA7BB79.toInt(),0xFFD4A7BE.toInt()),
        isDark = false
    ),
    "vaporwave" to Theme(
        id = "vaporwave", name = "Vaporwave",
        background = 0xFF2B2136.toInt(), surface = 0xFF352A43.toInt(), primaryText = 0xFFF6EAF3.toInt(),
        secondaryText = 0xFFC6B2CF.toInt(), headingText = 0xFFF6EAF3.toInt(), link = 0xFFE9A3CF.toInt(),
        selection = 0xFF504563.toInt(), bookmark = 0xFFC7B7F0.toInt(), progress = 0xFFE5C887.toInt(),
        divider = 0xFF685374.toInt(),
        highlightColors = listOf(0xFFE5C56A.toInt(),0xFF83B89C.toInt(),0xFFD3A16E.toInt(),0xFFDC8F8A.toInt(),0xFFB9A2D0.toInt(),0xFF99BACC.toInt(),0xFFA7BB79.toInt(),0xFFD4A7BE.toInt()),
        isDark = true
    ),
    "acid" to Theme(
        id = "acid", name = "Acid Rave",
        background = 0xFFF4F6E5.toInt(), surface = 0xFFF9FBEF.toInt(), primaryText = 0xFF202919.toInt(),
        secondaryText = 0xFF56634B.toInt(), headingText = 0xFF202919.toInt(), link = 0xFF53691A.toInt(),
        selection = 0xFFE0DCD8.toInt(), bookmark = 0xFF785395.toInt(), progress = 0xFF845C17.toInt(),
        divider = 0xFFB7C2A3.toInt(),
        highlightColors = listOf(0xFFE5C56A.toInt(),0xFF83B89C.toInt(),0xFFD3A16E.toInt(),0xFFDC8F8A.toInt(),0xFFB9A2D0.toInt(),0xFF99BACC.toInt(),0xFFA7BB79.toInt(),0xFFD4A7BE.toInt()),
        isDark = false
    ),
    "synthwave" to Theme(
        id = "synthwave", name = "Synthwave",
        background = 0xFF1B221A.toInt(), surface = 0xFF253027.toInt(), primaryText = 0xFFEDF2DF.toInt(),
        secondaryText = 0xFFB9C7A7.toInt(), headingText = 0xFFEDF2DF.toInt(), link = 0xFFCEE880.toInt(),
        selection = 0xFF43444C.toInt(), bookmark = 0xFFC1B1EC.toInt(), progress = 0xFFE3BF73.toInt(),
        divider = 0xFF536646.toInt(),
        highlightColors = listOf(0xFFE5C56A.toInt(),0xFF83B89C.toInt(),0xFFD3A16E.toInt(),0xFFDC8F8A.toInt(),0xFFB9A2D0.toInt(),0xFF99BACC.toInt(),0xFFA7BB79.toInt(),0xFFD4A7BE.toInt()),
        isDark = true
    ),
    "porcelain" to Theme(
        id = "porcelain", name = "Porcelain",
        background = 0xFFEAEEF8.toInt(), surface = 0xFFF8F9FF.toInt(), primaryText = 0xFF202A3F.toInt(),
        secondaryText = 0xFF52627C.toInt(), headingText = 0xFF202A3F.toInt(), link = 0xFF3557A0.toInt(),
        selection = 0xFFD8D5E8.toInt(), bookmark = 0xFF795395.toInt(), progress = 0xFF95501F.toInt(),
        divider = 0xFFB2BDD0.toInt(),
        highlightColors = listOf(0xFFE5C56A.toInt(),0xFF83B89C.toInt(),0xFFD3A16E.toInt(),0xFFDC8F8A.toInt(),0xFFB9A2D0.toInt(),0xFF99BACC.toInt(),0xFFA7BB79.toInt(),0xFFD4A7BE.toInt()),
        isDark = false
    ),
    "cobalt" to Theme(
        id = "cobalt", name = "Cobalt",
        background = 0xFF1E2634.toInt(), surface = 0xFF293446.toInt(), primaryText = 0xFFEDF1F9.toInt(),
        secondaryText = 0xFFB5C1D4.toInt(), headingText = 0xFFEDF1F9.toInt(), link = 0xFFA5BFF4.toInt(),
        selection = 0xFF494960.toInt(), bookmark = 0xFFD3B8EB.toInt(), progress = 0xFFF0BE8C.toInt(),
        divider = 0xFF546784.toInt(),
        highlightColors = listOf(0xFFE5C56A.toInt(),0xFF83B89C.toInt(),0xFFD3A16E.toInt(),0xFFDC8F8A.toInt(),0xFFB9A2D0.toInt(),0xFF99BACC.toInt(),0xFFA7BB79.toInt(),0xFFD4A7BE.toInt()),
        isDark = true
    ),
    "linen" to Theme(
        id = "linen", name = "Linen",
        background = 0xFFF2EDE6.toInt(), surface = 0xFFFAF8F3.toInt(), primaryText = 0xFF2D292A.toInt(),
        secondaryText = 0xFF635B5C.toInt(), headingText = 0xFF2D292A.toInt(), link = 0xFF955137.toInt(),
        selection = 0xFFDED5D7.toInt(), bookmark = 0xFF78558A.toInt(), progress = 0xFF416879.toInt(),
        divider = 0xFFBEB4AD.toInt(),
        highlightColors = listOf(0xFFE5C56A.toInt(),0xFF83B89C.toInt(),0xFFD3A16E.toInt(),0xFFDC8F8A.toInt(),0xFFB9A2D0.toInt(),0xFF99BACC.toInt(),0xFFA7BB79.toInt(),0xFFD4A7BE.toInt()),
        isDark = false
    ),
    "copper" to Theme(
        id = "copper", name = "Copper",
        background = 0xFF21282B.toInt(), surface = 0xFF2B3539.toInt(), primaryText = 0xFFF1EBE3.toInt(),
        secondaryText = 0xFFC3BAB0.toInt(), headingText = 0xFFF1EBE3.toInt(), link = 0xFFE4B092.toInt(),
        selection = 0xFF4C4B58.toInt(), bookmark = 0xFFD4B8E7.toInt(), progress = 0xFFA4CDD9.toInt(),
        divider = 0xFF5D6869.toInt(),
        highlightColors = listOf(0xFFE5C56A.toInt(),0xFF83B89C.toInt(),0xFFD3A16E.toInt(),0xFFDC8F8A.toInt(),0xFFB9A2D0.toInt(),0xFF99BACC.toInt(),0xFFA7BB79.toInt(),0xFFD4A7BE.toInt()),
        isDark = true
    ),
)
