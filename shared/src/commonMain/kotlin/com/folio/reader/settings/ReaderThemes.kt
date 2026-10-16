package com.folio.reader.settings

/**
 * The built-in reader themes: 7 iconic packs, each with a light and dark variant.
 * These are carefully designed to differ across ALL attributes (background, ink, accents),
 * not just background hue. Each pack has distinct personality for how reading should feel.
 */
internal val ReaderThemePresets: Map<String, Theme> = mapOf(
    // ── CLASSIC / CHARCOAL PACK (Default Clean) ───────────────────
    "classic" to Theme(
        id = "classic", name = "Classic",
        background = 0xFFFFFFFF.toInt(), surface = 0xFFFAFAFA.toInt(),
        primaryText = 0xFF1A1A1A.toInt(), secondaryText = 0xFF6B6B6B.toInt(),
        headingText = 0xFF0A0A0A.toInt(), link = 0xFF2979FF.toInt(),
        selection = 0xFFE3ECFF.toInt(), bookmark = 0xFF4285F4.toInt(),
        highlightColors = listOf(
            0xFFFFD700.toInt(), 0xFF00C853.toInt(), 0xFF935602.toInt(), 0xFFD50000.toInt(),
            0xFFAA00FF.toInt(), 0xFFE8EAED.toInt(), 0xFF707070.toInt(), 0xFF1A73E8.toInt()
        ),
        progress = 0xFF2979FF.toInt(), divider = 0xFFE0E0E0.toInt(),
        isDark = false
    ),
    "charcoal" to Theme(
        id = "charcoal", name = "Charcoal",
        background = 0xFF0F0F0F.toInt(), surface = 0xFF1A1A1A.toInt(),
        primaryText = 0xFFE8E6E4.toInt(), secondaryText = 0xFF9CA0A4.toInt(),
        headingText = 0xFFF5F5F5.toInt(), link = 0xFF64B5FF.toInt(),
        selection = 0xFF1A4F73.toInt(), bookmark = 0xFF1976D2.toInt(),
        highlightColors = listOf(
            0xFFFFEE00.toInt(), 0xFF00FF64.toInt(), 0xFFFFB74D.toInt(), 0xFFFF5252.toInt(),
            0xFFD500F9.toInt(), 0xFFECEFF4.toInt(), 0xFF707070.toInt(), 0xFF4285F4.toInt()
        ),
        progress = 0xFF64B5FF.toInt(), divider = 0xFF333333.toInt(),
        isDark = true
    ),

    // ── PAPER / EMBER PACK (Warm Analog) ─────────────────────────
    "paper" to Theme(
        id = "paper", name = "Paper",
        background = 0xFFF9F4EB.toInt(), surface = 0xFFF0EBDF.toInt(),
        primaryText = 0xFF2B2620.toInt(), secondaryText = 0xFF5D5850.toInt(),
        headingText = 0xFF1C1814.toInt(), link = 0xFF8B4513.toInt(),
        selection = 0xFFE8DECE.toInt(), bookmark = 0xFF8B5A3C.toInt(),
        highlightColors = listOf(
            0xFFE0C9A0.toInt(), 0xFF7CB342.toInt(), 0xFFBA6828.toInt(), 0xFFBF360C.toInt(),
            0xFF9A72A4.toInt(), 0xFFF5EFDC.toInt(), 0xFF8B7D6B.toInt(), 0xFFA97142.toInt()
        ),
        progress = 0xFF8B5A3C.toInt(), divider = 0xFFDBD3C4.toInt(),
        isDark = false
    ),
    "ember" to Theme(
        id = "ember", name = "Ember",
        background = 0xFF1C1210.toInt(), surface = 0xFF2A1C18.toInt(),
        primaryText = 0xFFF2E8D9.toInt(), secondaryText = 0xFFB8A896.toInt(),
        headingText = 0xFFF8F0E4.toInt(), link = 0xFFFFB74D.toInt(),
        selection = 0xFF4A3020.toInt(), bookmark = 0xFFE67E22.toInt(),
        highlightColors = listOf(
            0xFFF4D085.toInt(), 0xFF7CB342.toInt(), 0xFFFFA726.toInt(), 0xFFFF5722.toInt(),
            0xFFAB47BC.toInt(), 0xFF5D4037.toInt(), 0xFFA1887F.toInt(), 0xFFD4A56A.toInt()
        ),
        progress = 0xFFE67E22.toInt(), divider = 0xFF4A3020.toInt(),
        isDark = true
    ),

    // ── FOREST / MIDNIGHT PACK (Cool Natural) ─────────────────────
    "forest" to Theme(
        id = "forest", name = "Forest",
        background = 0xFFF0F7EF.toInt(), surface = 0xFFE3EBDF.toInt(),
        primaryText = 0xFF0A2F1E.toInt(), secondaryText = 0xFF3D6349.toInt(),
        headingText = 0xFF051C14.toInt(), link = 0xFF2E7D32.toInt(),
        selection = 0xFFC8DBC8.toInt(), bookmark = 0xFF43A047.toInt(),
        highlightColors = listOf(
            0xFF8BC34A.toInt(), 0xFF00E676.toInt(), 0xFFFFC107.toInt(), 0xFFFF1744.toInt(),
            0xFFAA00FF.toInt(), 0xFFDEF0DD.toInt(), 0xFF5D7065.toInt(), 0xFF1B5E20.toInt()
        ),
        progress = 0xFF43A047.toInt(), divider = 0xFFC5DAC5.toInt(),
        isDark = false
    ),
    "midnight" to Theme(
        id = "midnight", name = "Midnight",
        background = 0xFF020F0E.toInt(), surface = 0xFF0A1F1A.toInt(),
        primaryText = 0xFFCCFFE8.toInt(), secondaryText = 0xFF88AAA0.toInt(),
        headingText = 0xFFE8FFF4.toInt(), link = 0xFF69F0AE.toInt(),
        selection = 0xFF1A4A40.toInt(), bookmark = 0xFF00C853.toInt(),
        highlightColors = listOf(
            0xFFB7FF60.toInt(), 0xFF00E5FF.toInt(), 0xFFFFDD00.toInt(), 0xFFFF4081.toInt(),
            0xFFEA80FC.toInt(), 0xFF263238.toInt(), 0xFF667570.toInt(), 0xFF1B5E20.toInt()
        ),
        progress = 0xFF00C853.toInt(), divider = 0xFF1A2A26.toInt(),
        isDark = true
    ),

    // ── OCEAN / ABYSS PACK (Deep Cool) ───────────────────────────
    "ocean" to Theme(
        id = "ocean", name = "Ocean",
        background = 0xFFEAF7F9.toInt(), surface = 0xFFD7E8ED.toInt(),
        primaryText = 0xFF0A1F3D.toInt(), secondaryText = 0xFF3D5C78.toInt(),
        headingText = 0xFF051024.toInt(), link = 0xFF1565C0.toInt(),
        selection = 0xFFB3D7ED.toInt(), bookmark = 0xFF0277BD.toInt(),
        highlightColors = listOf(
            0xFF448AFF.toInt(), 0xFF00E5FF.toInt(), 0xFFFFD54F.toInt(), 0xFFFF1744.toInt(),
            0xFF651FFF.toInt(), 0xFFCFEAFE.toInt(), 0xFF7A9CAC.toInt(), 0xFF0D47A1.toInt()
        ),
        progress = 0xFF0277BD.toInt(), divider = 0xFFB3D7ED.toInt(),
        isDark = false
    ),
    "abyss" to Theme(
        id = "abyss", name = "Abyss",
        background = 0xFF050814.toInt(), surface = 0xFF0D1426.toInt(),
        primaryText = 0xFFD1E8F7.toInt(), secondaryText = 0xFF889BAD.toInt(),
        headingText = 0xFFEFF7FB.toInt(), link = 0xFF4DD0E1.toInt(),
        selection = 0xFF1A2A4A.toInt(), bookmark = 0xFF00B0FF.toInt(),
        highlightColors = listOf(
            0xFF2979FF.toInt(), 0xFF18FFFF.toInt(), 0xFFFFEB3B.toInt(), 0xFFFF5252.toInt(),
            0xFFEA80FC.toInt(), 0xFF102A43.toInt(), 0xFF5A6E8A.toInt(), 0xFF0D47A1.toInt()
        ),
        progress = 0xFF00B0FF.toInt(), divider = 0xFF1A2A43.toInt(),
        isDark = true
    ),

    // ── EDITORIAL / CRIMSON PACK (Bold Statement) ────────────────
    "editorial" to Theme(
        id = "editorial", name = "Editorial",
        background = 0xFFF8F5F0.toInt(), surface = 0xFFF0EBE0.toInt(),
        primaryText = 0xFF2D2420.toInt(), secondaryText = 0xFF63584E.toInt(),
        headingText = 0xFF1C1814.toInt(), link = 0xFF8B4513.toInt(),
        selection = 0xFFE8DED0.toInt(), bookmark = 0xFFA0522D.toInt(),
        highlightColors = listOf(
            0xFFC68E12.toInt(), 0xFF2E7D32.toInt(), 0xFFE65100.toInt(), 0xFFC62828.toInt(),
            0xFF7B1FA2.toInt(), 0xFFEFEFEF.toInt(), 0xFF8B7D6B.toInt(), 0xFF8D6E63.toInt()
        ),
        progress = 0xFFA0522D.toInt(), divider = 0xFFDBD3C4.toInt(),
        isDark = false
    ),
    "crimson" to Theme(
        id = "crimson", name = "Crimson",
        background = 0xFF1A050A.toInt(), surface = 0xFF2E0C14.toInt(),
        primaryText = 0xFFF5DBC8.toInt(), secondaryText = 0xFFB88A7E.toInt(),
        headingText = 0xFFFFF0E4.toInt(), link = 0xFFFF7043.toInt(),
        selection = 0xFF4A1C24.toInt(), bookmark = 0xFFFF5722.toInt(),
        highlightColors = listOf(
            0xFFFFB74D.toInt(), 0xFF69F0AE.toInt(), 0xFFFF9800.toInt(), 0xFFFF5252.toInt(),
            0xFFEA80FC.toInt(), 0xFF3E121C.toInt(), 0xFF8B5A52.toInt(), 0xFFA0522D.toInt()
        ),
        progress = 0xFFFF5722.toInt(), divider = 0xFF4A1C24.toInt(),
        isDark = true
    ),

    // ── BLUSH / VAPORWAVE PACK (Pink Future) ✨ NEW ───────────────
    "blush" to Theme(
        id = "blush", name = "Blush",
        background = 0xFFffeaf2.toInt(), surface = 0xFFE8D9E8.toInt(),
        primaryText = 0xFF8B4D66.toInt(), secondaryText = 0xFFAB6B85.toInt(),
        headingText = 0xFF6B3E50.toInt(), link = 0xFFC44569.toInt(),
        selection = 0xFFF5C9DB.toInt(), bookmark = 0xFFFF69B4.toInt(),
        highlightColors = listOf(
            0xFFFFB6C1.toInt(), 0xFF00CED1.toInt(), 0xFFFFD700.toInt(), 0xFFFF6347.toInt(),
            0xFFBA55D3.toInt(), 0xFFE6E6FA.toInt(), 0xFFD8BFD8.toInt(), 0xFFC71585.toInt()
        ),
        progress = 0xFFE91E63.toInt(), divider = 0xFFD4BECD.toInt(),
        isDark = false
    ),
    "vaporwave" to Theme(
        id = "vaporwave", name = "Vaporwave",
        background = 0xFF2E0F26.toInt(), surface = 0xFF4A1C3B.toInt(),
        primaryText = 0xFFE8C4F7.toInt(), secondaryText = 0xBB9FA5.toInt(),
        headingText = 0xFFFFF2FF.toInt(), link = 0xFFFF80AB.toInt(),
        selection = 0xFF5C2A4A.toInt(), bookmark = 0xFFFF00FF.toInt(),
        highlightColors = listOf(
            0xFFFF69B4.toInt(), 0xFF00FFFF.toInt(), 0xFFFFFF00.toInt(), 0xFFFF4500.toInt(),
            0xFFD8BFD8.toInt(), 0xFF3D1C33.toInt(), 0xFF9A6B9A.toInt(), 0xFFC71585.toInt()
        ),
        progress = 0xFFE040FB.toInt(), divider = 0xFF5C2A4A.toInt(),
        isDark = true
    ),

    // ── ACID RAVE / SYNTHWAVE PACK (Neon High-Energy) ⚡ NEW ─────
    "acid" to Theme(
        id = "acid", name = "Acid Rave",
        background = 0xFFF0FCE8.toInt(), surface = 0xFFE3F9D9.toInt(),
        primaryText = 0xFF1A2608.toInt(), secondaryText = 0xFF4A6342.toInt(),
        headingText = 0xFF0D1604.toInt(), link = 0xFFCCFF00.toInt(),
        selection = 0xFFD4F0C8.toInt(), bookmark = 0xFFA4D100.toInt(),
        highlightColors = listOf(
            0xFFCCFF00.toInt(), 0xFFFF00FF.toInt(), 0xFF00FFAA.toInt(), 0xFF3D8BFF.toInt(),
            0xFFFFE600.toInt(), 0xFFFF5C1E.toInt(), 0xFFB04DFF.toInt(), 0xFF6BFF3D.toInt()
        ),
        progress = 0xFF00FFAA.toInt(), divider = 0xFFD4F0C8.toInt(),
        isDark = false
    ),
    "synthwave" to Theme(
        id = "synthwave", name = "Synthwave",
        background = 0xFF0A0A0A.toInt(), surface = 0xFF141414.toInt(),
        primaryText = 0xFFFF6FD8.toInt(), secondaryText = 0xFF9D8BC4.toInt(),
        headingText = 0xFFFF7BE5.toInt(), link = 0xFF00E5FF.toInt(),
        selection = 0xFF2A1A33.toInt(), bookmark = 0xFFFF2FD6.toInt(),
        highlightColors = listOf(
            0xFFFF2FD6.toInt(), 0xFF00E5FF.toInt(), 0xFF7C4DFF.toInt(), 0xFF00FF9F.toInt(),
            0xFFFFE600.toInt(), 0xFFFF6B35.toInt(), 0xFF4D9DFF.toInt(), 0xFFFF8AE2.toInt()
        ),
        progress = 0xFFFF2FD6.toInt(), divider = 0xFF2A1A33.toInt(),
        isDark = true
    )
)
