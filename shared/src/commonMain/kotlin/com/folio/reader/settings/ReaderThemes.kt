package com.folio.reader.settings

/**
 * The built-in reader themes, in picker order. Pure data, split out of
 * ReaderSettings.kt so that file stays within its §6 budget.
 */
internal val ReaderThemePresets: Map<String, Theme> = mapOf(
    // ── LIGHT READER THEMES ─────────────────────────────────────
    "white" to Theme(
        id = "white", name = "Gallery",
        background = 0xFFFBF9F6.toInt(), surface = 0xFFEEEAE6.toInt(),
        primaryText = 0xFF231A0C.toInt(), secondaryText = 0xFF5F5547.toInt(),
        headingText = 0xFF1C1000.toInt(), link = 0xFF0066B5.toInt(),
        selection = 0xFFA5D1FC.toInt(), bookmark = 0xFFC1453A.toInt(),
        highlightColors = listOf(
            0xFFA0D2FF.toInt(), 0xFFCEC2FF.toInt(), 0xFFF2B7E0.toInt(), 0xFFFFB7B2.toInt(),
            0xFFF2C290.toInt(), 0xFFCCD392.toInt(), 0xFF9DDEB7.toInt(), 0xFF86DDE5.toInt()
        ),
        progress = 0xFF006CC0.toInt(), divider = 0xFFD6D2CC.toInt(),
        isDark = false
    ),
    "paper" to Theme(
        id = "paper", name = "Sakura",
        background = 0xFFFFF3F9.toInt(), surface = 0xFFFDE2EB.toInt(),
        primaryText = 0xFF26161C.toInt(), secondaryText = 0xFF635157.toInt(),
        headingText = 0xFF1F0B13.toInt(), link = 0xFF973F6E.toInt(),
        selection = 0xFFEEBBD2.toInt(), bookmark = 0xFF438733.toInt(),
        highlightColors = listOf(
            0xFFF8B6D4.toInt(), 0xFFFEB9A7.toInt(), 0xFFEAC78C.toInt(), 0xFFBFD699.toInt(),
            0xFF92DFC4.toInt(), 0xFF88DBEF.toInt(), 0xFFACCEFF.toInt(), 0xFFD9BFF9.toInt()
        ),
        progress = 0xFFA14274.toInt(), divider = 0xFFE8C9D4.toInt(),
        isDark = false
    ),
    "sepia" to Theme(
        id = "sepia", name = "Manuscript",
        background = 0xFFFEF3E0.toInt(), surface = 0xFFF6E4C6.toInt(),
        primaryText = 0xFF231A0C.toInt(), secondaryText = 0xFF5E5547.toInt(),
        headingText = 0xFF1B1000.toInt(), link = 0xFFA04318.toInt(),
        selection = 0xFFF3BEAA.toInt(), bookmark = 0xFF4B6FC7.toInt(),
        highlightColors = listOf(
            0xFFFDBAA1.toInt(), 0xFFE4C98B.toInt(), 0xFFB8D89F.toInt(), 0xFF8DDFCB.toInt(),
            0xFF8CD9F4.toInt(), 0xFFB3CBFF.toInt(), 0xFFDFBDF5.toInt(), 0xFFFAB5CD.toInt()
        ),
        progress = 0xFFA94618.toInt(), divider = 0xFFE6CFAB.toInt(),
        isDark = false
    ),
    "honey" to Theme(
        id = "honey", name = "Honey",
        background = 0xFFFFF5DA.toInt(), surface = 0xFFF9E6BB.toInt(),
        primaryText = 0xFF211B0C.toInt(), secondaryText = 0xFF5D5646.toInt(),
        headingText = 0xFF1A1100.toInt(), link = 0xFF875900.toInt(),
        selection = 0xFFE4C89A.toInt(), bookmark = 0xFF00888A.toInt(),
        highlightColors = listOf(
            0xFFEAC78C.toInt(), 0xFFBFD699.toInt(), 0xFF92DFC4.toInt(), 0xFF88DBEF.toInt(),
            0xFFACCEFF.toInt(), 0xFFD9BFF9.toInt(), 0xFFF8B6D4.toInt(), 0xFFFEB9A7.toInt()
        ),
        progress = 0xFF8E5E00.toInt(), divider = 0xFFE7D19B.toInt(),
        isDark = false
    ),
    "gray" to Theme(
        id = "gray", name = "Silver",
        background = 0xFFF6F9FC.toInt(), surface = 0xFFE6EBF1.toInt(),
        primaryText = 0xFF131C29.toInt(), secondaryText = 0xFF4D5865.toInt(),
        headingText = 0xFF071323.toInt(), link = 0xFF565E94.toInt(),
        selection = 0xFFC4CAE9.toInt(), bookmark = 0xFF208295.toInt(),
        highlightColors = listOf(
            0xFFBDC8FF.toInt(), 0xFFE6BAEE.toInt(), 0xFFFDB5C4.toInt(), 0xFFFABD9A.toInt(),
            0xFFDCCD8C.toInt(), 0xFFAEDBA6.toInt(), 0xFF89DFD4.toInt(), 0xFF92D7FA.toInt()
        ),
        progress = 0xFF5B649D.toInt(), divider = 0xFFCED3DA.toInt(),
        isDark = false
    ),
    "matcha" to Theme(
        id = "matcha", name = "Matcha",
        background = 0xFFEAFBE0.toInt(), surface = 0xFFD2F3C0.toInt(),
        primaryText = 0xFF0F2A08.toInt(), secondaryText = 0xFF4A7A34.toInt(),
        headingText = 0xFF2FB121.toInt(), link = 0xFF00A97A.toInt(),
        selection = 0xFFBFF58C.toInt(), bookmark = 0xFFFF6B2C.toInt(),
        highlightColors = listOf(
            0xFFB6F03C.toInt(), 0xFF12DCA0.toInt(), 0xFFFFD400.toInt(), 0xFFFF6B4A.toInt(),
            0xFFB46BFF.toInt(), 0xFF39DE7A.toInt(), 0xFF1FC7E6.toInt(), 0xFFFF6FD8.toInt()
        ),
        progress = 0xFF14B864.toInt(), divider = 0xFFC9ECB4.toInt(),
        isDark = false
    ),
    "arctic" to Theme(
        id = "arctic", name = "Arctic",
        background = 0xFFE9FAFE.toInt(), surface = 0xFFD2EFF5.toInt(),
        primaryText = 0xFF0A1F23.toInt(), secondaryText = 0xFF465B5F.toInt(),
        headingText = 0xFF00171C.toInt(), link = 0xFF00718D.toInt(),
        selection = 0xFF9CD7E9.toInt(), bookmark = 0xFF008C54.toInt(),
        highlightColors = listOf(
            0xFF8ADAF1.toInt(), 0xFFAFCDFF.toInt(), 0xFFDBBEF7.toInt(), 0xFFF9B6D1.toInt(),
            0xFFFEB9A4.toInt(), 0xFFE7C88C.toInt(), 0xFFBCD79C.toInt(), 0xFF90DFC7.toInt()
        ),
        progress = 0xFF007794.toInt(), divider = 0xFFB7DAE1.toInt(),
        isDark = false
    ),
    "moss" to Theme(
        id = "moss", name = "Moss",
        background = 0xFFF1FAEA.toInt(), surface = 0xFFDFEFD4.toInt(),
        primaryText = 0xFF171F11.toInt(), secondaryText = 0xFF515A4B.toInt(),
        headingText = 0xFF0C1604.toInt(), link = 0xFF2E7534.toInt(),
        selection = 0xFFB4D7B4.toInt(), bookmark = 0xFF7A7A00.toInt(),
        highlightColors = listOf(
            0xFFAADBAA.toInt(), 0xFF87DED8.toInt(), 0xFF95D6FC.toInt(), 0xFFC1C7FF.toInt(),
            0xFFE9BAEB.toInt(), 0xFFFEB6BF.toInt(), 0xFFF8BE97.toInt(), 0xFFD9CE8D.toInt()
        ),
        progress = 0xFF2F7B36.toInt(), divider = 0xFFC7DAB9.toInt(),
        isDark = false
    ),
    "solarized_light" to Theme(
        id = "solarized_light", name = "Solarized Light",
        background = 0xFFFDF6E3.toInt(), surface = 0xFFEEE8D5.toInt(),
        primaryText = 0xFF586E75.toInt(), secondaryText = 0xFF839496.toInt(),
        headingText = 0xFF073642.toInt(), link = 0xFF268BD2.toInt(),
        selection = 0xFF93A1A1.toInt(), bookmark = 0xFFD33682.toInt(),
        highlightColors = listOf(
            0xFFB58900.toInt(), 0xFF268BD2.toInt(), 0xFF859900.toInt(), 0xFFDC322F.toInt(),
            0xFFD33682.toInt(), 0xFFCB4B16.toInt(), 0xFF2AA198.toInt(), 0xFF6C71C4.toInt()
        ),
        progress = 0xFF268BD2.toInt(), divider = 0xFFEEE8D5.toInt(),
        isDark = false
    ),
    "nord_light" to Theme(
        id = "nord_light", name = "Nord Light",
        background = 0xFFECEFF4.toInt(), surface = 0xFFE5E9F0.toInt(),
        primaryText = 0xFF2E3440.toInt(), secondaryText = 0xFF4C566A.toInt(),
        headingText = 0xFF3B4252.toInt(), link = 0xFF5E81AC.toInt(),
        selection = 0xFFD8DEE9.toInt(), bookmark = 0xFFBF616A.toInt(),
        highlightColors = listOf(
            0xFFBF616A.toInt(), 0xFFD08770.toInt(), 0xFFEBCB8B.toInt(), 0xFFA3BE8C.toInt(),
            0xFFB48EAD.toInt(), 0xFF88C0D0.toInt(), 0xFF81A1C1.toInt(), 0xFF5E81AC.toInt()
        ),
        progress = 0xFF5E81AC.toInt(), divider = 0xFFD8DEE9.toInt(),
        isDark = false
    ),
    "gruvbox_light" to Theme(
        id = "gruvbox_light", name = "Gruvbox Light",
        background = 0xFFFBF1C7.toInt(), surface = 0xFFEBDBB2.toInt(),
        primaryText = 0xFF3C3836.toInt(), secondaryText = 0xFF7C6F64.toInt(),
        headingText = 0xFF282828.toInt(), link = 0xFFAF3A03.toInt(),
        selection = 0xFFD5C4A1.toInt(), bookmark = 0xFF9D0006.toInt(),
        highlightColors = listOf(
            0xFFCC241D.toInt(), 0xFF98971A.toInt(), 0xFFD79921.toInt(), 0xFF458588.toInt(),
            0xFFB16286.toInt(), 0xFF689D6A.toInt(), 0xFFD65D0E.toInt(), 0xFF7C6F64.toInt()
        ),
        progress = 0xFFAF3A03.toInt(), divider = 0xFFD5C4A1.toInt(),
        isDark = false
    ),
    "dawn" to Theme(
        id = "dawn", name = "Dawn",
        background = 0xFFFFF2FF.toInt(), surface = 0xFFFAE1F7.toInt(),
        primaryText = 0xFF231722.toInt(), secondaryText = 0xFF5F525E.toInt(),
        headingText = 0xFF1C0C1B.toInt(), link = 0xFF794C98.toInt(),
        selection = 0xFFD9C1EC.toInt(), bookmark = 0xFFB15822.toInt(),
        highlightColors = listOf(
            0xFFDDBDF6.toInt(), 0xFFFAB5CF.toInt(), 0xFFFDBAA2.toInt(), 0xFFE6C88B.toInt(),
            0xFFBAD89D.toInt(), 0xFF8FDFC9.toInt(), 0xFF8BDAF3.toInt(), 0xFFB1CCFF.toInt()
        ),
        progress = 0xFF8050A1.toInt(), divider = 0xFFE6C8E2.toInt(),
        isDark = false
    ),
    // ── DARK READER THEMES ──────────────────────────────────────
    "dark" to Theme(
        id = "dark", name = "Dark",
        background = 0xFF05070C.toInt(), surface = 0xFF10141A.toInt(),
        primaryText = 0xFFE0E7F0.toInt(), secondaryText = 0xFF9CA4AE.toInt(),
        headingText = 0xFFF1F6FC.toInt(), link = 0xFF76C0FF.toInt(),
        selection = 0xFF1A4268.toInt(), bookmark = 0xFFFF9688.toInt(),
        highlightColors = listOf(
            0xFF1D4F7E.toInt(), 0xFF51417B.toInt(), 0xFF6E365D.toInt(), 0xFF793532.toInt(),
            0xFF6D4200.toInt(), 0xFF4C5200.toInt(), 0xFF005C3B.toInt(), 0xFF005A65.toInt()
        ),
        progress = 0xFF6CBBFF.toInt(), divider = 0xFF25292F.toInt(),
        isDark = true
    ),
    "midnight" to Theme(
        id = "midnight", name = "Midnight",
        background = 0xFF00050C.toInt(), surface = 0xFF01121B.toInt(),
        primaryText = 0xFFDDE8EE.toInt(), secondaryText = 0xFF98A5AD.toInt(),
        headingText = 0xFFEFF6FB.toInt(), link = 0xFF16CFF6.toInt(),
        selection = 0xFF00495D.toInt(), bookmark = 0xFF57D796.toInt(),
        highlightColors = listOf(
            0xFF00586F.toInt(), 0xFF314B81.toInt(), 0xFF5B3D75.toInt(), 0xFF733552.toInt(),
            0xFF783824.toInt(), 0xFF664700.toInt(), 0xFF3E5615.toInt(), 0xFF005D48.toInt()
        ),
        progress = 0xFF00CBF4.toInt(), divider = 0xFF142730.toInt(),
        isDark = true
    ),
    "oled_black" to Theme(
        id = "oled_black", name = "OLED Black",
        background = 0xFF000000.toInt(), surface = 0xFF0A0A0A.toInt(),
        primaryText = 0xFFECEFF1.toInt(), secondaryText = 0xFF90A4AE.toInt(),
        headingText = 0xFFFFFFFF.toInt(), link = 0xFF82B1FF.toInt(),
        selection = 0xFF1A1A1A.toInt(), bookmark = 0xFFF06292.toInt(),
        highlightColors = listOf(
            0xFFFFEB3B.toInt(), 0xFF64B5F6.toInt(), 0xFFA5D6A7.toInt(), 0xFFEF9A9A.toInt(),
            0xFFD1C4E9.toInt(), 0xFFFFCC80.toInt(), 0xFF80DEEA.toInt(), 0xFFF8BBD0.toInt()
        ),
        progress = 0xFF82B1FF.toInt(), divider = 0xFF1A1A1A.toInt(),
        isDark = true
    ),
    "graphite" to Theme(
        id = "graphite", name = "Graphite",
        background = 0xFF07090E.toInt(), surface = 0xFF12161D.toInt(),
        primaryText = 0xFFE1E7F0.toInt(), secondaryText = 0xFF9CA4AF.toInt(),
        headingText = 0xFFF1F5FC.toInt(), link = 0xFFADB5F3.toInt(),
        selection = 0xFF393D5C.toInt(), bookmark = 0xFF6ECAE0.toInt(),
        highlightColors = listOf(
            0xFF424680.toInt(), 0xFF65396B.toInt(), 0xFF773443.toInt(), 0xFF743C11.toInt(),
            0xFF5B4D00.toInt(), 0xFF2A5A28.toInt(), 0xFF005C56.toInt(), 0xFF005578.toInt()
        ),
        progress = 0xFFA7B0F1.toInt(), divider = 0xFF272C32.toInt(),
        isDark = true
    ),
    "blossom" to Theme(
        id = "blossom", name = "Blossom",
        background = 0xFF0D0309.toInt(), surface = 0xFF1C0D17.toInt(),
        primaryText = 0xFFEDE3E9.toInt(), secondaryText = 0xFFAB9FA7.toInt(),
        headingText = 0xFFFAF3F7.toInt(), link = 0xFFF796D0.toInt(),
        selection = 0xFF5D2C4A.toInt(), bookmark = 0xFF87D177.toInt(),
        highlightColors = listOf(
            0xFF70365A.toInt(), 0xFF78362E.toInt(), 0xFF6B4300.toInt(), 0xFF485407.toInt(),
            0xFF005D3F.toInt(), 0xFF005A68.toInt(), 0xFF244E7F.toInt(), 0xFF554079.toInt()
        ),
        progress = 0xFFF58FCC.toInt(), divider = 0xFF32222C.toInt(),
        isDark = true
    ),
    "high_contrast" to Theme(
        id = "high_contrast", name = "High Contrast",
        background = 0xFF050505.toInt(), surface = 0xFF1A1A1A.toInt(),
        primaryText = 0xFFFFFFFF.toInt(), secondaryText = 0xFFCCCCCC.toInt(),
        headingText = 0xFFFFFF00.toInt(), link = 0xFF00FFFF.toInt(),
        selection = 0xFF333333.toInt(), bookmark = 0xFFFF00FF.toInt(),
        highlightColors = listOf(
            0xFFFFFF00.toInt(), 0xFF00FFFF.toInt(), 0xFF00FF00.toInt(), 0xFFFF0000.toInt(),
            0xFFFF00FF.toInt(), 0xFFFFA500.toInt(), 0xFF00F5FF.toInt(), 0xFFFF69B4.toInt()
        ),
        progress = 0xFF00FFFF.toInt(), divider = 0xFF444444.toInt(),
        isDark = true
    ),
    "dusk" to Theme(
        id = "dusk", name = "Dusk",
        background = 0xFF05030E.toInt(), surface = 0xFF100D1E.toInt(),
        primaryText = 0xFFE6E5EF.toInt(), secondaryText = 0xFFA2A1AE.toInt(),
        headingText = 0xFFF5F4FC.toInt(), link = 0xFFD2A3FB.toInt(),
        selection = 0xFF4B3360.toInt(), bookmark = 0xFFFF9688.toInt(),
        highlightColors = listOf(
            0xFF5B3D75.toInt(), 0xFF733552.toInt(), 0xFF783824.toInt(), 0xFF664700.toInt(),
            0xFF3E5615.toInt(), 0xFF005D48.toInt(), 0xFF00586F.toInt(), 0xFF314B81.toInt()
        ),
        progress = 0xFFCE9DF9.toInt(), divider = 0xFF242233.toInt(),
        isDark = true
    ),
    "espresso" to Theme(
        id = "espresso", name = "Espresso",
        background = 0xFF0D0401.toInt(), surface = 0xFF1C0D05.toInt(),
        primaryText = 0xFFEEE4DE.toInt(), secondaryText = 0xFFADA099.toInt(),
        headingText = 0xFFFBF3EF.toInt(), link = 0xFFF0A968.toInt(),
        selection = 0xFF5A3612.toInt(), bookmark = 0xFF88BCFF.toInt(),
        highlightColors = listOf(
            0xFF713F03.toInt(), 0xFF535000.toInt(), 0xFF195B32.toInt(), 0xFF005B5E.toInt(),
            0xFF0A527C.toInt(), 0xFF4A437E.toInt(), 0xFF6A3864.toInt(), 0xFF78343A.toInt()
        ),
        progress = 0xFFEEA35E.toInt(), divider = 0xFF32231A.toInt(),
        isDark = true
    ),
    "ember" to Theme(
        id = "ember", name = "Ember",
        background = 0xFF0C0200.toInt(), surface = 0xFF1C0A03.toInt(),
        primaryText = 0xFFEFE4DE.toInt(), secondaryText = 0xFFADA09A.toInt(),
        headingText = 0xFFFBF3EF.toInt(), link = 0xFFFF9778.toInt(),
        selection = 0xFF662C1C.toInt(), bookmark = 0xFF00D7DA.toInt(),
        highlightColors = listOf(
            0xFF783726.toInt(), 0xFF674600.toInt(), 0xFF405612.toInt(), 0xFF005D46.toInt(),
            0xFF00596E.toInt(), 0xFF2E4C80.toInt(), 0xFF5A3E76.toInt(), 0xFF733553.toInt()
        ),
        progress = 0xFFFF8F6F.toInt(), divider = 0xFF312017.toInt(),
        isDark = true
    ),
    "solarized_dark" to Theme(
        id = "solarized_dark", name = "Solarized Dark",
        background = 0xFF002B36.toInt(), surface = 0xFF073642.toInt(),
        primaryText = 0xFF93A1A1.toInt(), secondaryText = 0xFF657B83.toInt(),
        headingText = 0xFFEEE8D5.toInt(), link = 0xFF2AA198.toInt(),
        selection = 0xFF586E75.toInt(), bookmark = 0xFFD33682.toInt(),
        highlightColors = listOf(
            0xFFB58900.toInt(), 0xFF268BD2.toInt(), 0xFF859900.toInt(), 0xFFDC322F.toInt(),
            0xFFD33682.toInt(), 0xFFCB4B16.toInt(), 0xFF2AA198.toInt(), 0xFF6C71C4.toInt()
        ),
        progress = 0xFF2AA198.toInt(), divider = 0xFF073642.toInt(),
        isDark = true
    ),
    "nord_dark" to Theme(
        id = "nord_dark", name = "Nord Dark",
        background = 0xFF2E3440.toInt(), surface = 0xFF3B4252.toInt(),
        primaryText = 0xFFECEFF4.toInt(), secondaryText = 0xFFD8DEE9.toInt(),
        headingText = 0xFFECEFF4.toInt(), link = 0xFF88C0D0.toInt(),
        selection = 0xFF434C5E.toInt(), bookmark = 0xFFBF616A.toInt(),
        highlightColors = listOf(
            0xFFBF616A.toInt(), 0xFFD08770.toInt(), 0xFFEBCB8B.toInt(), 0xFFA3BE8C.toInt(),
            0xFFB48EAD.toInt(), 0xFF88C0D0.toInt(), 0xFF81A1C1.toInt(), 0xFF5E81AC.toInt()
        ),
        progress = 0xFF88C0D0.toInt(), divider = 0xFF434C5E.toInt(),
        isDark = true
    ),
    "dracula" to Theme(
        id = "dracula", name = "Dracula",
        background = 0xFF282A36.toInt(), surface = 0xFF44475A.toInt(),
        primaryText = 0xFFF8F8F2.toInt(), secondaryText = 0xFF6272A4.toInt(),
        headingText = 0xFFF8F8F2.toInt(), link = 0xFFBD93F9.toInt(),
        selection = 0xFF44475A.toInt(), bookmark = 0xFFFF79C6.toInt(),
        highlightColors = listOf(
            0xFFFF5555.toInt(), 0xFFFFB86C.toInt(), 0xFFF1FA8C.toInt(), 0xFF50FA7B.toInt(),
            0xFFBD93F9.toInt(), 0xFFFF79C6.toInt(), 0xFF8BE9FD.toInt(), 0xFFF8F8F2.toInt()
        ),
        progress = 0xFFBD93F9.toInt(), divider = 0xFF44475A.toInt(),
        isDark = true
    ),
    "monokai" to Theme(
        id = "monokai", name = "Monokai",
        background = 0xFF1E1F1C.toInt(), surface = 0xFF2E2D26.toInt(),
        primaryText = 0xFFF8F8F2.toInt(), secondaryText = 0xFF75715E.toInt(),
        headingText = 0xFFF8F8F2.toInt(), link = 0xFFA6E22E.toInt(),
        selection = 0xFF49483E.toInt(), bookmark = 0xFFF92672.toInt(),
        highlightColors = listOf(
            0xFFF92672.toInt(), 0xFFFD971F.toInt(), 0xFFE6DB74.toInt(), 0xFFA6E22E.toInt(),
            0xFFAE81FF.toInt(), 0xFF66D9EF.toInt(), 0xFFF92672.toInt(), 0xFFFD5FF0.toInt()
        ),
        progress = 0xFFA6E22E.toInt(), divider = 0xFF3E3D32.toInt(),
        isDark = true
    ),
    "gruvbox_dark" to Theme(
        id = "gruvbox_dark", name = "Gruvbox Dark",
        background = 0xFF282828.toInt(), surface = 0xFF3C3836.toInt(),
        primaryText = 0xFFEBDBB2.toInt(), secondaryText = 0xFFA89984.toInt(),
        headingText = 0xFFFBF1C7.toInt(), link = 0xFFFB4934.toInt(),
        selection = 0xFF504945.toInt(), bookmark = 0xFFFB4934.toInt(),
        highlightColors = listOf(
            0xFFFB4934.toInt(), 0xFFB8BB26.toInt(), 0xFFFABD2F.toInt(), 0xFF83A598.toInt(),
            0xFFD3869B.toInt(), 0xFF8EC07C.toInt(), 0xFFFE8019.toInt(), 0xFFA89984.toInt()
        ),
        progress = 0xFFFB4934.toInt(), divider = 0xFF3C3836.toInt(),
        isDark = true
    ),
    "synthwave" to Theme(
        id = "synthwave", name = "Synthwave",
        background = 0xFF150430.toInt(), surface = 0xFF230B4A.toInt(),
        primaryText = 0xFFF0E6FF.toInt(), secondaryText = 0xFF9D8BC4.toInt(),
        headingText = 0xFFFF7BE5.toInt(), link = 0xFF00E5FF.toInt(),
        selection = 0xFF3D1A6E.toInt(), bookmark = 0xFFFF2FD6.toInt(),
        highlightColors = listOf(
            0xFFFF2FD6.toInt(), 0xFF00E5FF.toInt(), 0xFF7C4DFF.toInt(), 0xFF00FF9F.toInt(),
            0xFFFFE600.toInt(), 0xFFFF6B35.toInt(), 0xFF4D9DFF.toInt(), 0xFFFF8AE2.toInt()
        ),
        progress = 0xFFFF2FD6.toInt(), divider = 0xFF2A1454.toInt(),
        isDark = true
    ),
    "bubblegum" to Theme(
        id = "bubblegum", name = "Bubblegum",
        background = 0xFFFFF0F6.toInt(), surface = 0xFFFFE3EF.toInt(),
        primaryText = 0xFF4A1033.toInt(), secondaryText = 0xFF9A5A78.toInt(),
        headingText = 0xFFD8467A.toInt(), link = 0xFF2ECFA0.toInt(),
        selection = 0xFFFFC6DE.toInt(), bookmark = 0xFFFF4FA3.toInt(),
        highlightColors = listOf(
            0xFFFF4FA3.toInt(), 0xFF2ECFA0.toInt(), 0xFFFFB300.toInt(), 0xFF7A6CFF.toInt(),
            0xFF4DC3FF.toInt(), 0xFFFF85B5.toInt(), 0xFF9CE86B.toInt(), 0xFFE88AFF.toInt()
        ),
        progress = 0xFFFF4FA3.toInt(), divider = 0xFFF5CCDE.toInt(),
        isDark = false
    ),
    "acid" to Theme(
        id = "acid", name = "Acid Rave",
        background = 0xFF0A1408.toInt(), surface = 0xFF12200C.toInt(),
        primaryText = 0xFFE8FFD6.toInt(), secondaryText = 0xFF98B878.toInt(),
        headingText = 0xFFCCFF00.toInt(), link = 0xFFFF2ED1.toInt(),
        selection = 0xFF2A3A18.toInt(), bookmark = 0xFFCCFF00.toInt(),
        highlightColors = listOf(
            0xFFCCFF00.toInt(), 0xFFFF2ED1.toInt(), 0xFF00FFAA.toInt(), 0xFF3D8BFF.toInt(),
            0xFFFFE600.toInt(), 0xFFFF5C1E.toInt(), 0xFFB04DFF.toInt(), 0xFF6BFF3D.toInt()
        ),
        progress = 0xFFCCFF00.toInt(), divider = 0xFF1A2414.toInt(),
        isDark = true
    ),
    "lava" to Theme(
        id = "lava", name = "Lava",
        background = 0xFF160504.toInt(), surface = 0xFF240B06.toInt(),
        primaryText = 0xFFFFE4D4.toInt(), secondaryText = 0xFFC08870.toInt(),
        headingText = 0xFFFF8A3D.toInt(), link = 0xFFFF2E63.toInt(),
        selection = 0xFF4A1808.toInt(), bookmark = 0xFFFF5C1E.toInt(),
        highlightColors = listOf(
            0xFFFF5C1E.toInt(), 0xFFFF2E63.toInt(), 0xFFFFB300.toInt(), 0xFFFF8A3D.toInt(),
            0xFFD84040.toInt(), 0xFFFF6B6B.toInt(), 0xFFE8960A.toInt(), 0xFFFF3D9A.toInt()
        ),
        progress = 0xFFFF5C1E.toInt(), divider = 0xFF38120A.toInt(),
        isDark = true
    ),
    "sherbet" to Theme(
        id = "sherbet", name = "Sherbet",
        background = 0xFFFCEBDD.toInt(), surface = 0xFFF8E0CB.toInt(),
        primaryText = 0xFF4A2E14.toInt(), secondaryText = 0xFF9A7854.toInt(),
        headingText = 0xFFE05A2B.toInt(), link = 0xFF2ED9A3.toInt(),
        selection = 0xFFFFDCB8.toInt(), bookmark = 0xFFFF7A4D.toInt(),
        highlightColors = listOf(
            0xFFFF7A4D.toInt(), 0xFF2ED9A3.toInt(), 0xFF4DC3FF.toInt(), 0xFFFFB340.toInt(),
            0xFFFF5C8A.toInt(), 0xFF9CE86B.toInt(), 0xFFB085FF.toInt(), 0xFF40D0E8.toInt()
        ),
        progress = 0xFFFF7A4D.toInt(), divider = 0xFFEDD5B8.toInt(),
        isDark = false
    ),
    // ── VIBRANT READER THEMES (mirror the vibrant app palettes) ──
    "vaporwave" to Theme(
        id = "vaporwave", name = "Vaporwave",
        background = 0xFF1A0733.toInt(), surface = 0xFF260E47.toInt(),
        primaryText = 0xFFF2DCFF.toInt(), secondaryText = 0xFFBE9BF0.toInt(),
        headingText = 0xFFFF5AC8.toInt(), link = 0xFF00E5FF.toInt(),
        selection = 0xFF4A1E94.toInt(), bookmark = 0xFFB86BFF.toInt(),
        highlightColors = listOf(
            0xFFFF5AC8.toInt(), 0xFF00E5FF.toInt(), 0xFFB86BFF.toInt(), 0xFFFFE600.toInt(),
            0xFF00FF9F.toInt(), 0xFFFF6B35.toInt(), 0xFF4D9DFF.toInt(), 0xFFFF8AE2.toInt()
        ),
        progress = 0xFFFF5AC8.toInt(), divider = 0xFF341660.toInt(),
        isDark = true
    ),
    "neontokyo" to Theme(
        id = "neontokyo", name = "Neon Tokyo",
        background = 0xFF0A0620.toInt(), surface = 0xFF150B33.toInt(),
        primaryText = 0xFFE6F0FF.toInt(), secondaryText = 0xFF8A9AC8.toInt(),
        headingText = 0xFF00E5FF.toInt(), link = 0xFFFF2FD6.toInt(),
        selection = 0xFF2A1A5E.toInt(), bookmark = 0xFFFFD400.toInt(),
        highlightColors = listOf(
            0xFF00E5FF.toInt(), 0xFFFF2FD6.toInt(), 0xFF7C4DFF.toInt(), 0xFF00FF9F.toInt(),
            0xFFFFE600.toInt(), 0xFFFF6B35.toInt(), 0xFFFF4D9D.toInt(), 0xFF4DFFB0.toInt()
        ),
        progress = 0xFF00E5FF.toInt(), divider = 0xFF1F1145.toInt(),
        isDark = true
    ),
    "toxiclime" to Theme(
        id = "toxiclime", name = "Toxic Lime",
        background = 0xFF040C14.toInt(), surface = 0xFF071420.toInt(),
        primaryText = 0xFFE2FFD0.toInt(), secondaryText = 0xFF88B060.toInt(),
        headingText = 0xFFB6FF00.toInt(), link = 0xFF32E6A0.toInt(),
        selection = 0xFF223A0A.toInt(), bookmark = 0xFFFF00A0.toInt(),
        highlightColors = listOf(
            0xFFB6FF00.toInt(), 0xFF32E6A0.toInt(), 0xFF00E5FF.toInt(), 0xFFFFFF00.toInt(),
            0xFFFF4FD8.toInt(), 0xFFFF9E2C.toInt(), 0xFF7CFF3D.toInt(), 0xFF00FFD1.toInt()
        ),
        progress = 0xFFB6FF00.toInt(), divider = 0xFF102018.toInt(),
        isDark = true
    ),
    "retrosunset" to Theme(
        id = "retrosunset", name = "Retro Sunset",
        background = 0xFF1C0A18.toInt(), surface = 0xFF2C0F26.toInt(),
        primaryText = 0xFFFFE8DC.toInt(), secondaryText = 0xFFB87F92.toInt(),
        headingText = 0xFFFF6B35.toInt(), link = 0xFFFFD400.toInt(),
        selection = 0xFF4A1638.toInt(), bookmark = 0xFFFF2E63.toInt(),
        highlightColors = listOf(
            0xFFFF6B35.toInt(), 0xFFFFD400.toInt(), 0xFFFF2E63.toInt(), 0xFF00E5FF.toInt(),
            0xFFB6FF00.toInt(), 0xFFFF7BE5.toInt(), 0xFFFF9E2C.toInt(), 0xFF4DC3FF.toInt()
        ),
        progress = 0xFFFF6B35.toInt(), divider = 0xFF3A1130.toInt(),
        isDark = true
    ),
    "peacock" to Theme(
        id = "peacock", name = "Peacock",
        background = 0xFF000805.toInt(), surface = 0xFF001610.toInt(),
        primaryText = 0xFFDDE9E6.toInt(), secondaryText = 0xFF98A7A3.toInt(),
        headingText = 0xFFEEF7F5.toInt(), link = 0xFF56D6A3.toInt(),
        selection = 0xFF004D34.toInt(), bookmark = 0xFF0DCFF9.toInt(),
        highlightColors = listOf(
            0xFF005D3F.toInt(), 0xFF005A68.toInt(), 0xFF244E7F.toInt(), 0xFF554079.toInt(),
            0xFF70365A.toInt(), 0xFF78362E.toInt(), 0xFF6B4300.toInt(), 0xFF485407.toInt()
        ),
        progress = 0xFF46D29D.toInt(), divider = 0xFF102B25.toInt(),
        isDark = true
    ),
    "midnightneon" to Theme(
        id = "midnightneon", name = "Midnight Neon",
        background = 0xFF020412.toInt(), surface = 0xFF0A0E26.toInt(),
        primaryText = 0xFFE0E8FF.toInt(), secondaryText = 0xFF7A88B8.toInt(),
        headingText = 0xFF5B8CFF.toInt(), link = 0xFF00E5FF.toInt(),
        selection = 0xFF1A2450.toInt(), bookmark = 0xFFFF4FA3.toInt(),
        highlightColors = listOf(
            0xFF5B8CFF.toInt(), 0xFF00E5FF.toInt(), 0xFFFF4FA3.toInt(), 0xFFB14DFF.toInt(),
            0xFF3DDE8A.toInt(), 0xFFFFD400.toInt(), 0xFFFF6B35.toInt(), 0xFFFF8AE2.toInt()
        ),
        progress = 0xFF5B8CFF.toInt(), divider = 0xFF121838.toInt(),
        isDark = true
    ),
    "candypop" to Theme(
        id = "candypop", name = "Candy Pop",
        background = 0xFFF3E5FF.toInt(), surface = 0xFFEAD3FA.toInt(),
        primaryText = 0xFF3A0A2A.toInt(), secondaryText = 0xFF9A5A80.toInt(),
        headingText = 0xFFFF1F8E.toInt(), link = 0xFF0091FF.toInt(),
        selection = 0xFFFFC2E5.toInt(), bookmark = 0xFF7A2EFF.toInt(),
        highlightColors = listOf(
            0xFFFF1F8E.toInt(), 0xFF00A3FF.toInt(), 0xFF7A2EFF.toInt(), 0xFFFFD400.toInt(),
            0xFF00D9A3.toInt(), 0xFFFF6B35.toInt(), 0xFF4DC3FF.toInt(), 0xFF3DDE8A.toInt()
        ),
        progress = 0xFFFF1F8E.toInt(), divider = 0xFFE9C7F2.toInt(),
        isDark = false
    ),
    "rainbow" to Theme(
        id = "rainbow", name = "Rainbow",
        background = 0xFFF7FAF0.toInt(), surface = 0xFFEAF2DE.toInt(),
        primaryText = 0xFF2A1A08.toInt(), secondaryText = 0xFF8A6A48.toInt(),
        headingText = 0xFFFF2E63.toInt(), link = 0xFF0091FF.toInt(),
        selection = 0xFFFFE08A.toInt(), bookmark = 0xFF8A2EFF.toInt(),
        highlightColors = listOf(
            0xFFFF2E63.toInt(), 0xFFFF9E2C.toInt(), 0xFFFFD400.toInt(), 0xFF3DDE8A.toInt(),
            0xFF00A3FF.toInt(), 0xFF8A2EFF.toInt(), 0xFFFF4FD8.toInt(), 0xFF00C2C7.toInt()
        ),
        progress = 0xFFFF2E63.toInt(), divider = 0xFFE3E8CC.toInt(),
        isDark = false
    ),
    // ── CLAY / COCOA (2026-09 editorial pack) ───────────────────
    "clay" to Theme(
        id = "clay", name = "Clay",
        background = 0xFFF4ECDC.toInt(), surface = 0xFFEAD7C2.toInt(),
        primaryText = 0xFF33261A.toInt(), secondaryText = 0xFF6A5636.toInt(),
        headingText = 0xFFA83E0A.toInt(), link = 0xFFA83E0A.toInt(),
        selection = 0xFFE0CBA8.toInt(), bookmark = 0xFF7B3F8F.toInt(),
        highlightColors = listOf(
            0xFFD79A3D.toInt(), 0xFF4D8FC7.toInt(), 0xFF5DAE5D.toInt(), 0xFFD65F5F.toInt(),
            0xFF9B7BC7.toInt(), 0xFFA83E0A.toInt(), 0xFF2E7D5B.toInt(), 0xFFD67AB5.toInt()
        ),
        progress = 0xFFA83E0A.toInt(), divider = 0xFFDCC8AC.toInt(),
        isDark = false
    ),
    "cocoa" to Theme(
        id = "cocoa", name = "Cocoa",
        background = 0xFF1E110A.toInt(), surface = 0xFF2C1E0E.toInt(),
        primaryText = 0xFFF0E2D2.toInt(), secondaryText = 0xFFD0B896.toInt(),
        headingText = 0xFFFF9E4D.toInt(), link = 0xFFFF9E4D.toInt(),
        selection = 0xFF4A3620.toInt(), bookmark = 0xFFD9A0E0.toInt(),
        highlightColors = listOf(
            0xFFFF9E4D.toInt(), 0xFFFF7A9C.toInt(), 0xFF6FE0A8.toInt(), 0xFFE8C070.toInt(),
            0xFFD9A0E0.toInt(), 0xFF7FB0E8.toInt(), 0xFFF0B060.toInt(), 0xFF9CE0C0.toInt()
        ),
        progress = 0xFFFF9E4D.toInt(), divider = 0xFF3C2E1A.toInt(),
        isDark = true
    ),
    // ── LAGOON / ABYSS (2026-09 tidewater pack) ─────────────────
    "lagoon" to Theme(
        id = "lagoon", name = "Lagoon",
        background = 0xFFE3FDFD.toInt(), surface = 0xFFC8F3F3.toInt(),
        primaryText = 0xFF0A2020.toInt(), secondaryText = 0xFF465B5B.toInt(),
        headingText = 0xFF001818.toInt(), link = 0xFF00766E.toInt(),
        selection = 0xFF9DDAD4.toInt(), bookmark = 0xFFB55143.toInt(),
        highlightColors = listOf(
            0xFF88DFD6.toInt(), 0xFF93D6FB.toInt(), 0xFFBFC7FF.toInt(), 0xFFE8BAED.toInt(),
            0xFFFEB5C1.toInt(), 0xFFF9BD98.toInt(), 0xFFDBCD8C.toInt(), 0xFFACDBA8.toInt()
        ),
        progress = 0xFF007C75.toInt(), divider = 0xFFA9DEDE.toInt(),
        isDark = false
    ),
    "abyss" to Theme(
        id = "abyss", name = "Abyss",
        background = 0xFF000204.toInt(), surface = 0xFF000C10.toInt(),
        primaryText = 0xFFDCE9EB.toInt(), secondaryText = 0xFF97A6A9.toInt(),
        headingText = 0xFFEEF7F9.toInt(), link = 0xFF00D7D4.toInt(),
        selection = 0xFF004D4C.toInt(), bookmark = 0xFFADAFFF.toInt(),
        highlightColors = listOf(
            0xFF005C5B.toInt(), 0xFF00537A.toInt(), 0xFF47457F.toInt(), 0xFF683867.toInt(),
            0xFF78343E.toInt(), 0xFF723E09.toInt(), 0xFF574E00.toInt(), 0xFF215B2D.toInt()
        ),
        progress = 0xFF00D3D0.toInt(), divider = 0xFF092024.toInt(),
        isDark = true
    ),
    // ── ROUGE / GARNET (2026-09 crimson editorial pack) ─────────
    "rouge" to Theme(
        id = "rouge", name = "Rouge",
        background = 0xFFFFF3F3.toInt(), surface = 0xFFFDE3E2.toInt(),
        primaryText = 0xFF281616.toInt(), secondaryText = 0xFF655151.toInt(),
        headingText = 0xFF210B0B.toInt(), link = 0xFFA33A44.toInt(),
        selection = 0xFFF7BABB.toInt(), bookmark = 0xFF986C00.toInt(),
        highlightColors = listOf(
            0xFFFFB6B7.toInt(), 0xFFF4C192.toInt(), 0xFFD1D190.toInt(), 0xFFA2DDB2.toInt(),
            0xFF86DEE0.toInt(), 0xFF9BD3FF.toInt(), 0xFFC9C4FF.toInt(), 0xFFEEB8E4.toInt()
        ),
        progress = 0xFFAD3C48.toInt(), divider = 0xFFE9CAC9.toInt(),
        isDark = false
    ),
    "garnet" to Theme(
        id = "garnet", name = "Garnet",
        background = 0xFF0A0103.toInt(), surface = 0xFF1A080C.toInt(),
        primaryText = 0xFFEFE3E4.toInt(), secondaryText = 0xFFAE9FA1.toInt(),
        headingText = 0xFFFCF2F4.toInt(), link = 0xFFFF94A0.toInt(),
        selection = 0xFF642B32.toInt(), bookmark = 0xFFECAE3E.toInt(),
        highlightColors = listOf(
            0xFF78343D.toInt(), 0xFF723E08.toInt(), 0xFF564F00.toInt(), 0xFF1F5B2F.toInt(),
            0xFF005C5C.toInt(), 0xFF00537B.toInt(), 0xFF48447F.toInt(), 0xFF693866.toInt()
        ),
        progress = 0xFFFF8D9A.toInt(), divider = 0xFF2F1D20.toInt(),
        isDark = true
    ),
    // ── CITRINE / ONYX (2026-09 golden pack) ────────────────────
    "citrine" to Theme(
        id = "citrine", name = "Citrine",
        // Deepened to a truer pale citrine yellow (FFFBDC): at FFFBE8 the page was
        // only 7 channel-steps from Paper's warm cream. Lowering blue to DC clears
        // Paper (19) and Solarized Light (14) and reads more like the gemstone.
        background = 0xFFFFFBDC.toInt(), surface = 0xFFEEF0C0.toInt(),
        primaryText = 0xFF322A08.toInt(), secondaryText = 0xFF6A5E30.toInt(),
        headingText = 0xFF8A6D00.toInt(), link = 0xFF7B3F8F.toInt(),
        selection = 0xFFE8E49C.toInt(), bookmark = 0xFF9A3A8F.toInt(),
        highlightColors = listOf(
            0xFFD4A843.toInt(), 0xFF4D8FC7.toInt(), 0xFF5DAE5D.toInt(), 0xFFD65F5F.toInt(),
            0xFF9B7BC7.toInt(), 0xFF009C9C.toInt(), 0xFFC79A00.toInt(), 0xFFD67AB5.toInt()
        ),
        progress = 0xFF8A6D00.toInt(), divider = 0xFFE0DAA8.toInt(),
        isDark = false
    ),
    "onyx" to Theme(
        id = "onyx", name = "Onyx",
        background = 0xFF16130C.toInt(), surface = 0xFF1C1E10.toInt(),
        primaryText = 0xFFF2E8CC.toInt(), secondaryText = 0xFFC0B48C.toInt(),
        headingText = 0xFFF0C860.toInt(), link = 0xFF9CC7E0.toInt(),
        selection = 0xFF3A3418.toInt(), bookmark = 0xFFE0A0D8.toInt(),
        highlightColors = listOf(
            0xFFF0C860.toInt(), 0xFF7FB0FF.toInt(), 0xFF6FE0C0.toInt(), 0xFFE0A0D8.toInt(),
            0xFFFFB3A0.toInt(), 0xFFD8C89A.toInt(), 0xFF9CC7E0.toInt(), 0xFFC0E070.toInt()
        ),
        progress = 0xFFF0C860.toInt(), divider = 0xFF33301E.toInt(),
        isDark = true
    )
)
