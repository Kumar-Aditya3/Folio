# Folio palette audit

Scored against the real authored tokens in `FolioArtDirection.kt`, using the app's
own OKLab / chrome-glass / pool-harmony maths to reconstruct each screen surface.

**20/20 faces clean, 0 with issues.**

| face | concept | animation | min accent hue° | min ΔE | CVD worst | issues |
|---|---|---|---|---|---|---|
| ocean light | Aurora Ocean | MeshGradient | 32.1 | 16.6 | 12.9 | 0 |
| ocean dark | Aurora Ocean | MeshGradient | 27.4 | 15.8 | 8.7 | 0 |
| nebula light | Nebula Electric | NeuroNoise | 66.7 | 33.7 | 9.1 | 0 |
| nebula dark | Nebula Electric | NeuroNoise | 70.5 | 31.5 | 7.0 | 0 |
| forest light | Bioluminescent Forest | Voronoi | 40.8 | 26.0 | 10.2 | 0 |
| forest dark | Bioluminescent Forest | Voronoi | 44.8 | 28.7 | 10.8 | 0 |
| terracotta light | Terracotta Tide | GrainGradient(Wave) | 50.0 | 30.5 | 8.8 | 0 |
| terracotta dark | Terracotta Tide | GrainGradient(Wave) | 54.6 | 32.1 | 6.8 | 0 |
| arctic light | Arctic Glass | Swirl | 31.5 | 17.1 | 7.0 | 0 |
| arctic dark | Arctic Glass | Swirl | 43.9 | 25.8 | 8.7 | 0 |
| solar light | Solar Eclipse | GodRays | 35.8 | 14.9 | 11.5 | 0 |
| solar dark | Solar Eclipse | GodRays | 33.4 | 15.8 | 13.0 | 0 |
| jade light | Jade Noir | SmokeRing | 32.4 | 18.2 | 9.2 | 0 |
| jade dark | Jade Noir | SmokeRing | 32.1 | 19.2 | 9.5 | 0 |
| mintcoral light | Mint & Coral Editorial | ColorPanels | 69.3 | 33.5 | 9.7 | 0 |
| mintcoral dark | Mint & Coral Editorial | ColorPanels | 74.8 | 32.8 | 9.5 | 0 |
| moonstone light | Moonstone | PerlinNoise | 61.0 | 35.1 | 6.3 | 0 |
| moonstone dark | Moonstone | PerlinNoise | 65.1 | 38.3 | 7.6 | 0 |
| system light | System (achromatic) | Metaballs | 62.8 | 28.4 | 12.4 | 0 |
| system dark | System (achromatic) | Metaballs | 66.9 | 28.6 | 10.9 | 0 |

## ocean — light (Aurora Ocean)
- animation: **MeshGradient** — intent: teal + azure + mint + whisper of gold
- contrast: page 13.3/4.87, card 13.96/5.12, panel 14.64/5.37, nav 13.0/4.76, topbar 8.25/3.02
- hierarchy L: {'page': 0.956, 'card': 0.975, 'panel': 0.991}
- accents: min hue gap 32.1°, min ΔE 16.6
- animation pools vs field: #fbf997(ΔE 26.5), #35c99e(ΔE 16.4), #7ab591(ΔE 15.3)
- CVD worst accent ΔE: {'protan': 15.3, 'deutan': 12.9, 'tritan': 13.0}
  - clean

## ocean — dark (Aurora Ocean)
- animation: **MeshGradient** — intent: teal + azure + mint + whisper of gold
- contrast: page 16.88/7.9, card 13.3/6.22, panel 12.4/5.8, nav 16.64/7.79, topbar 10.28/4.81
- hierarchy L: {'page': 0.159, 'card': 0.275, 'panel': 0.297}
- accents: min hue gap 27.4°, min ΔE 15.8
- animation pools vs field: #faff8c(ΔE 66.2), #00cea4(ΔE 57.4), #70e6b2(ΔE 62.2)
- CVD worst accent ΔE: {'protan': 12.5, 'deutan': 8.7, 'tritan': 8.7}
  - clean

## nebula — light (Nebula Electric)
- animation: **NeuroNoise** — intent: indigo + electric cyan + violet, magenta surprise
- contrast: page 13.17/4.84, card 14.1/5.18, panel 12.64/4.65, nav 12.78/4.7, topbar 8.69/3.2
- hierarchy L: {'page': 0.953, 'card': 0.975, 'panel': 0.937}
- accents: min hue gap 66.7°, min ΔE 33.7
- animation pools vs field: #7dcf56(ΔE 52.9), #8cd5fe(ΔE 34.1), #00ab9f(ΔE 44.7)
- CVD worst accent ΔE: {'protan': 18.1, 'deutan': 9.9, 'tritan': 9.1}
  - clean

## nebula — dark (Nebula Electric)
- animation: **NeuroNoise** — intent: indigo + electric cyan + violet, magenta surprise
- contrast: page 16.84/7.81, card 12.87/5.97, panel 11.98/5.55, nav 16.92/7.84, topbar 9.79/4.54
- hierarchy L: {'page': 0.156, 'card': 0.273, 'panel': 0.295}
- accents: min hue gap 70.5°, min ΔE 31.5
- animation pools vs field: #61d629(ΔE 60.7), #84d7ff(ΔE 61.9), #21d1c3(ΔE 59.7)
- CVD worst accent ΔE: {'protan': 18.2, 'deutan': 13.1, 'tritan': 7.0}
  - clean

## forest — light (Bioluminescent Forest)
- animation: **Voronoi** — intent: moss + teal + chartreuse + amber glow
- contrast: page 13.14/4.81, card 13.75/5.03, panel 14.47/5.3, nav 12.71/4.65, topbar 8.29/3.03
- hierarchy L: {'page': 0.955, 'card': 0.974, 'panel': 0.991}
- accents: min hue gap 40.8°, min ΔE 26.0
- animation pools vs field: #f79f75(ΔE 27.2), #e78cfe(ΔE 51.4), #cb8836(ΔE 27.0)
- CVD worst accent ΔE: {'protan': 17.9, 'deutan': 10.2, 'tritan': 14.0}
  - clean

## forest — dark (Bioluminescent Forest)
- animation: **Voronoi** — intent: moss + teal + chartreuse + amber glow
- contrast: page 16.96/7.9, card 13.19/6.15, panel 12.28/5.72, nav 16.88/7.87, topbar 10.15/4.73
- hierarchy L: {'page': 0.155, 'card': 0.274, 'panel': 0.295}
- accents: min hue gap 44.8°, min ΔE 28.7
- animation pools vs field: #ff9f61(ΔE 47.5), #eb84ff(ΔE 59.6), #f7a200(ΔE 48.2)
- CVD worst accent ΔE: {'protan': 16.3, 'deutan': 10.8, 'tritan': 10.9}
  - clean

## terracotta — light (Terracotta Tide)
- animation: **GrainGradient(Wave)** — intent: terracotta + cream + blue-green + turquoise
- contrast: page 13.07/4.8, card 14.11/5.18, panel 14.76/5.42, nav 13.14/4.82, topbar 8.29/3.04
- hierarchy L: {'page': 0.951, 'card': 0.975, 'panel': 0.99}
- accents: min hue gap 50.0°, min ΔE 30.5
- animation pools vs field: #fb90d5(ΔE 34.9), #3171c9(ΔE 44.4), #a07cdb(ΔE 39.7)
- CVD worst accent ΔE: {'protan': 16.9, 'deutan': 9.1, 'tritan': 8.8}
  - clean

## terracotta — dark (Terracotta Tide)
- animation: **GrainGradient(Wave)** — intent: terracotta + cream + blue-green + turquoise
- contrast: page 16.98/7.96, card 13.32/6.24, panel 12.4/5.81, nav 16.72/7.83, topbar 10.21/4.79
- hierarchy L: {'page': 0.156, 'card': 0.274, 'panel': 0.296}
- accents: min hue gap 54.6°, min ΔE 32.1
- animation pools vs field: #ff86d6(ΔE 54.6), #0059ce(ΔE 37.4), #c4a4fe(ΔE 55.9)
- CVD worst accent ΔE: {'protan': 18.1, 'deutan': 11.0, 'tritan': 6.8}
  - clean

## arctic — light (Arctic Glass)
- animation: **Swirl** — intent: glacier blue + violet + mint + frost rose
- contrast: page 13.26/4.87, card 13.81/5.08, panel 14.54/5.35, nav 12.75/4.69, topbar 8.26/3.04
- hierarchy L: {'page': 0.954, 'card': 0.972, 'panel': 0.989}
- accents: min hue gap 31.5°, min ΔE 17.1
- animation pools vs field: #eaf97c(ΔE 42.0), #64c88f(ΔE 31.1), #00ab75(ΔE 34.3)
- CVD worst accent ΔE: {'protan': 13.0, 'deutan': 14.3, 'tritan': 7.0}
  - clean

## arctic — dark (Arctic Glass)
- animation: **Swirl** — intent: glacier blue + violet + mint + frost rose
- contrast: page 16.93/7.88, card 13.2/6.15, panel 12.29/5.72, nav 16.93/7.88, topbar 10.28/4.79
- hierarchy L: {'page': 0.157, 'card': 0.273, 'panel': 0.295}
- accents: min hue gap 43.9°, min ΔE 25.8
- animation pools vs field: #e5ff6a(ΔE 71.2), #41ce87(ΔE 61.9), #00fdc9(ΔE 71.6)
- CVD worst accent ΔE: {'protan': 8.7, 'deutan': 11.6, 'tritan': 9.9}
  - clean

## solar — light (Solar Eclipse)
- animation: **GodRays** — intent: deep blue + gold corona + sky + crimson
- contrast: page 13.28/4.84, card 14.02/5.11, panel 14.76/5.37, nav 12.94/4.71, topbar 8.48/3.09
- hierarchy L: {'page': 0.955, 'card': 0.973, 'panel': 0.991}
- accents: min hue gap 35.8°, min ΔE 14.9
- animation pools vs field: #f7758d(ΔE 40.8), #8c73fa(ΔE 24.0), #c392bc(ΔE 29.1)
- CVD worst accent ΔE: {'protan': 11.5, 'deutan': 20.9, 'tritan': 12.5}
  - clean

## solar — dark (Solar Eclipse)
- animation: **GodRays** — intent: deep blue + gold corona + sky + crimson
- contrast: page 16.98/7.9, card 13.14/6.12, panel 12.23/5.7, nav 16.86/7.85, topbar 10.18/4.74
- hierarchy L: {'page': 0.155, 'card': 0.274, 'panel': 0.295}
- accents: min hue gap 33.4°, min ΔE 15.8
- animation pools vs field: #ff6179(ΔE 41.0), #7b5fff(ΔE 36.4), #c095b8(ΔE 41.1)
- CVD worst accent ΔE: {'protan': 13.7, 'deutan': 17.1, 'tritan': 13.0}
  - clean

## jade — light (Jade Noir)
- animation: **SmokeRing** — intent: orchid + jade + periwinkle + amber
- contrast: page 13.23/4.87, card 13.77/5.07, panel 14.53/5.35, nav 12.79/4.71, topbar 8.21/3.02
- hierarchy L: {'page': 0.955, 'card': 0.973, 'panel': 0.991}
- accents: min hue gap 32.4°, min ΔE 18.2
- animation pools vs field: #fb97e0(ΔE 30.8), #738cfa(ΔE 22.9), #a297ff(ΔE 19.6)
- CVD worst accent ΔE: {'protan': 9.2, 'deutan': 11.1, 'tritan': 12.0}
  - clean

## jade — dark (Jade Noir)
- animation: **SmokeRing** — intent: orchid + jade + periwinkle + amber
- contrast: page 16.83/7.9, card 13.29/6.24, panel 12.38/5.81, nav 16.7/7.84, topbar 10.25/4.81
- hierarchy L: {'page': 0.159, 'card': 0.275, 'panel': 0.297}
- accents: min hue gap 32.1°, min ΔE 19.2
- animation pools vs field: #ff8de5(ΔE 50.6), #5f85ff(ΔE 38.8), #c6c3f8(ΔE 53.4)
- CVD worst accent ΔE: {'protan': 9.5, 'deutan': 12.6, 'tritan': 9.9}
  - clean

## mintcoral — light (Mint & Coral Editorial)
- animation: **ColorPanels** — intent: mint + coral + periwinkle + ochre
- contrast: page 13.16/4.84, card 14.19/5.22, panel 14.87/5.47, nav 13.22/4.86, topbar 8.34/3.07
- hierarchy L: {'page': 0.952, 'card': 0.975, 'panel': 0.991}
- accents: min hue gap 69.3°, min ΔE 33.5
- animation pools vs field: #de96fa(ΔE 35.7), #52cab8(ΔE 12.4), #00cdff(ΔE 24.9)
- CVD worst accent ΔE: {'protan': 16.4, 'deutan': 9.7, 'tritan': 13.1}
  - clean

## mintcoral — dark (Mint & Coral Editorial)
- animation: **ColorPanels** — intent: mint + coral + periwinkle + ochre
- contrast: page 16.96/7.95, card 13.31/6.24, panel 12.39/5.81, nav 16.68/7.82, topbar 10.22/4.79
- hierarchy L: {'page': 0.156, 'card': 0.275, 'panel': 0.297}
- accents: min hue gap 74.8°, min ΔE 32.8
- animation pools vs field: #f78cff(ΔE 55.0), #04b6cf(ΔE 49.4), #b6cbf2(ΔE 55.5)
- CVD worst accent ΔE: {'protan': 13.3, 'deutan': 9.5, 'tritan': 11.5}
  - clean

## moonstone — light (Moonstone)
- animation: **PerlinNoise** — intent: slate blue + lilac + aqua + dusty rose
- contrast: page 13.2/4.83, card 13.99/5.12, panel 14.76/5.41, nav 12.87/4.71, topbar 8.68/3.18
- hierarchy L: {'page': 0.955, 'card': 0.972, 'panel': 0.99}
- accents: min hue gap 61.0°, min ΔE 35.1
- animation pools vs field: #9adb55(ΔE 42.4), #8ce4fe(ΔE 13.7), #23ac74(ΔE 35.1)
- CVD worst accent ΔE: {'protan': 16.0, 'deutan': 6.3, 'tritan': 8.2}
  - clean

## moonstone — dark (Moonstone)
- animation: **PerlinNoise** — intent: slate blue + lilac + aqua + dusty rose
- contrast: page 16.98/7.89, card 12.92/6.01, panel 12.02/5.59, nav 16.92/7.87, topbar 9.93/4.62
- hierarchy L: {'page': 0.154, 'card': 0.273, 'panel': 0.295}
- accents: min hue gap 65.1°, min ΔE 38.3
- animation pools vs field: #74e328(ΔE 58.2), #84daff(ΔE 59.6), #00f1c6(ΔE 60.0)
- CVD worst accent ΔE: {'protan': 19.1, 'deutan': 15.1, 'tritan': 7.6}
  - clean

## system — light (System (achromatic))
- animation: **Metaballs** — intent: neutral steel/ink; the calm default
- contrast: page 13.11/4.79, card 13.88/5.07, panel 14.57/5.32, nav 12.64/4.62, topbar 8.55/3.12
- hierarchy L: {'page': 0.953, 'card': 0.974, 'panel': 0.99}
- accents: min hue gap 62.8°, min ΔE 28.4
- animation pools vs field: #82d5ff(ΔE 30.4), #64c8ad(ΔE 35.0)
- CVD worst accent ΔE: {'protan': 12.4, 'deutan': 12.7, 'tritan': 12.5}
  - clean

## system — dark (System (achromatic))
- animation: **Metaballs** — intent: neutral steel/ink; the calm default
- contrast: page 16.82/7.8, card 12.91/5.98, panel 12.0/5.56, nav 16.92/7.84, topbar 9.78/4.53
- hierarchy L: {'page': 0.157, 'card': 0.273, 'panel': 0.295}
- accents: min hue gap 66.9°, min ΔE 28.6
- animation pools vs field: #79d5ff(ΔE 55.5), #41ceac(ΔE 57.9)
- CVD worst accent ΔE: {'protan': 11.1, 'deutan': 10.9, 'tritan': 11.0}
  - clean
