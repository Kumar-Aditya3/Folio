# Folio — concept fidelity checklist, all 18 themes
Every hue in this document is **Oklab**, the space `FolioAtmosphere` places in. (`palette_lib.lab` is CIELAB and `palette_lib.hue` is HSV — either would move these numbers by up to ~30° and can invert a conclusion.)
**How to read it.** The reference mockups are translucent glass over artwork, so an absolute colour match is not available — the artwork, not the palette, sets the brightness. What *is* reliable, and what the derivation has to reproduce, is the **hue relationship** between a face's page and its surfaces. So each row compares the concept's measured hue with the hue the live Kotlin actually renders, before and after the fix.
| status | meaning |
|---|---|
| OK | ≤ 15° |
| close | ≤ 30° |
| OFF | > 30° |

### The faces that stay off, and why
The anchor is `backgroundBase`. Measured against the concepts over the 18 faces it predicts the page hue to **13.1° mean / 11.8° median**. The faces above 25° are the same case every time: an authored background that is **very nearly achromatic**, where hue is barely a measurable quantity.
| family | face | concept page | authored bg | Δ | bg Oklab chroma |
|---|---|---|---|---|---|
| jade | light | h11.6 | h330.9 | 40.6° | 0.009 |
| forest | dark | h199.6 | h172.5 | 27.1° | 0.026 |
| mintcoral | light | h134.9 | h161.4 | 26.5° | 0.009 |

At chroma ~0.01 a 40° rotation moves the colour by about one 8-bit step, which is why these are reported rather than chased: closing them means editing `backgroundBase` *values* — a palette decision, and the one place the reference strips are not a reliable target — not the derivation.

### The worst rows after the fix, and why
| family | face | element | Δ after | cause |
|---|---|---|---|---|
| solar | dark | hero_card | 76° | **artwork** — the mockup contradicts itself here: this element sits 56° from its *own* page |
| mintcoral | light | streak_card | 66° | **artwork** — the mockup contradicts itself here: this element sits 28° from its *own* page |
| jade | light | week_card | 47° | **inherited** — this face's page is the achromatic case above, so every derived hue carries the room's 41° error |
| jade | light | nav_pill | 44° | **inherited** — this face's page is the achromatic case above, so every derived hue carries the room's 41° error |
| jade | light | hero_card | 44° | **inherited** — this face's page is the achromatic case above, so every derived hue carries the room's 41° error |
| jade | light | today_card | 44° | **inherited** — this face's page is the achromatic case above, so every derived hue carries the room's 41° error |

### The guilloche — the bound, and its residuals
The plate draws **three** threads, so the unit is the set's *span*, not one thread. Measured over two clean field bands in all nine mockups, every line pixel spans **p10–p90 ≈ 8–22°** (light) and **7–20°** (dark) from the room's hue — about 20° of span, i.e. **10° per side**, which is `MAX_GUILLOCHE_HUE_DEV`. The bound is applied by **re-anchoring the set** (scaling the offsets), not by clamping each thread: a per-thread clamp collapses all three to one colour whenever they sit beyond the bound on the same side, which is a test failure, not a subtlety.

Worst-thread error over the 18 faces: **20.0° mean / 50.6° max** (was **100.6° / 171.6°** before). 3 of 18 faces remain above 30°:

| family | face | concept thread | room hue | room chroma | Δ |
|---|---|---|---|---|---|
| mintcoral | light | h135 | h161 | **0.009** | 36° |
| solar | dark | h252 | h262 | **0.030** | 34° |
| jade | light | h12 | h331 | **0.009** | 51° |

All of these are the *same* residual as the page rows: their room hue is barely defined, so the bound is being measured from an anchor that is not itself a colour. On every other face the plate lands within 30°.

### The card on the other two axes
Hue is the axis a translucent mockup can settle, and it is settled. The other two are worth stating because a card can also read as *out of place* for reasons hue cannot see: a pane far more saturated than the room it sits in reads as a sticker rather than as part of the room.
| card vs page | concept | app (after) |
|---|---|---|
| **light** faces — Δlightness | +0.005 | -0.051 |
| **dark** faces — Δlightness | +0.083 | +0.053 |

**The light-face step is forced, not chosen.** Every concept puts the card *level* with its page (Δlightness ≈ +0.00); the app puts it 0.05 **below**. That is not a derivation error — it is the app's field being at the top of the gamut. The light field sits at Oklab L ≈ 0.97–0.98, and white is L = 1.0, so there is only ~0.02 of headroom above it: a card that must be *visible* has nowhere to go but down. The concepts can sit level only because their "page" is artwork at L ≈ 0.93, not a near-white plane. Flipping the sign would clip every light card to white.

**The chroma was a second, independent defect. On the light faces it is fixed; on the dark faces it turns out to be a *field* question, not a card one.**

Measured absolutely, the concept's hero card carries Oklab chroma **2.4 (light) / 5.2 (dark)**. The shipped app carried **5.3 / 7.7** — but the mean is not the interesting part. The **spread** across the nine families was **8.3 / 15.2** against the concept's **2.8 / 8.7**, and that inconsistency is the real defect. It has a cause: a fixed chroma *target* is not a fixed *appearance*. At high lightness the cyan/green region of sRGB affords far more chroma than red or violet, so one constant drew full-saturation slabs on mintcoral (`#92FCA8`), forest (`#94FC91`) and ocean (`#4AFBF8`) while arctic, nebula and moonstone came out near-neutral. The three tier targets were lowered to match — `CARD_CHROMA` 0.19 → 0.04, `PANEL_CHROMA` 0.16 → 0.035, `WELL_CHROMA` 0.10 → 0.03.

| hero card chroma | concept | before | after |
|---|---|---|---|
| **light** — mean (spread) | 2.4 (2.8) | 5.3 (8.3) | **3.1 (0.6)** |
| **dark** — mean (spread) | 5.2 (8.7) | 7.7 (15.2) | **7.8 (8.0)** |

On the **light** faces that is a clean fix: the mean error against the concept drops from **2.92 to 0.64**, and the spread collapses from 8.3 to **0.6** — tighter than the concept's own 2.8. The loud slabs are gone.

On the **dark** faces it barely moves the mean (**7.7 → 7.8** against the concept's 5.2), and that is not a failed fix — it is arithmetic. The drawn card is `0.46 · card + 0.54 · field`, and on the dark faces the field supplies **54–79%** of the result: the card cannot be less chromatic than the room it sits in. What *did* improve there is the consistency — spread **15.2 → 8.0** against the concept's 8.7 — so the outliers (max 18.0 → 12.3) are contained. **If the dark cards read as too vivid, the lever is `FIELD_CHROMA_DARK`, not `CARD_CHROMA`** — the card constant is already at the floor of what the field permits.

### The card's three axes, face by face
| family | face | Δhue concept / app | ΔL concept / app | field L |
|---|---|---|---|---|
| terracotta | light | 3.4° / 9.7° | +0.005 / -0.052 | 0.971 |
| terracotta | dark | 1.0° / 1.0° | +0.077 / +0.044 | 0.312 |
| arctic | light | 1.2° / 8.6° | +0.006 / -0.051 | 0.977 |
| arctic | dark | 1.3° / 1.3° | +0.123 / +0.076 | 0.242 |
| mintcoral | light | 0.0° / 13.0° | +0.003 / -0.053 | 0.981 |
| mintcoral | dark | 4.8° / 5.8° | +0.081 / +0.073 | 0.276 |
| solar | light | 2.2° / 10.4° | -0.001 / -0.049 | 0.982 |
| solar | dark | 56.5° / 4.0° | +0.092 / +0.026 | 0.298 |
| forest | light | 3.7° / 7.4° | -0.005 / -0.053 | 0.978 |
| forest | dark | 10.3° / 3.3° | +0.069 / +0.074 | 0.272 |
| nebula | light | 4.4° / 12.8° | +0.029 / -0.050 | 0.973 |
| nebula | dark | 13.7° / 0.2° | +0.078 / +0.041 | 0.278 |
| moonstone | light | 3.8° / 0.4° | -0.002 / -0.050 | 0.975 |
| moonstone | dark | 7.5° / 7.2° | +0.110 / +0.029 | 0.304 |
| ocean | light | 2.8° / 8.7° | -0.001 / -0.053 | 0.980 |
| ocean | dark | 4.2° / 0.2° | +0.042 / +0.075 | 0.256 |
| jade | light | 6.8° / 13.5° | +0.015 / -0.048 | 0.975 |
| jade | dark | 12.0° / 3.7° | +0.077 / +0.036 | 0.313 |

### The page ramp — a theme is a gradient, not a colour
Each concept page is a **ramp**, and the ramp has two axes: its top and bottom bands differ in lightness *and* in hue. Across the 18 faces the references measure a mean span of **-0.027** Oklab L and **5.5°** of hue. A single authored `backgroundBase` expresses neither, so the field is placed as two stops — the lit top leaning toward `surface` (the card plane), the floor toward `surfaceVariant` — each bounded to 6° of the room so the two stops stay one material.

| family | face | concept ΔL | concept Δhue | app ΔL | app Δhue | app top | app bottom |
|---|---|---|---|---|---|---|---|
| terracotta | light | -0.122 | 3.3° | -0.056 | 2.6° | `#FDF4E9` | `#F1E0CD` |
| terracotta | dark | -0.022 | 0.3° | -0.037 | 8.1° | `#5C1107` | `#4E080E` |
| arctic | light | -0.032 | 2.9° | -0.042 | 8.7° | `#F3F8FF` | `#E1EBF5` |
| arctic | dark | -0.019 | 0.2° | +0.004 | 6.9° | `#04213A` | `#042337` |
| mintcoral | light | -0.015 | 21.2° | -0.041 | 0.2° | `#F3FBF7` | `#E1EFE8` |
| mintcoral | dark | -0.011 | 5.8° | -0.019 | 5.6° | `#072F29` | `#052A22` |
| solar | light | -0.057 | 4.4° | -0.053 | 0.9° | `#FFF9E8` | `#F3E7C7` |
| solar | dark | -0.014 | 14.9° | -0.067 | 7.3° | `#0E1185` | `#031A44` |
| forest | light | -0.033 | 2.7° | -0.041 | 0.0° | `#F4FAF1` | `#E5EDE1` |
| forest | dark | -0.014 | 15.7° | -0.016 | 7.5° | `#062E27` | `#052A20` |
| nebula | light | -0.033 | 6.5° | -0.050 | 4.0° | `#F7F4FF` | `#E6E2F7` |
| nebula | dark | +0.013 | 2.0° | -0.056 | 5.8° | `#300B63` | `#1D064B` |
| moonstone | light | -0.025 | 6.4° | -0.040 | 11.4° | `#F6F6FC` | `#E7E9F1` |
| moonstone | dark | -0.002 | 2.4° | -0.027 | 4.1° | `#1A1185` | `#0C0E78` |
| ocean | light | -0.032 | 1.6° | -0.042 | 5.9° | `#F1FBF9` | `#DDEFED` |
| ocean | dark | -0.016 | 1.2° | +0.000 | 13.5° | `#052735` | `#052830` |
| jade | light | -0.027 | 1.1° | -0.043 | 5.3° | `#FCF4FA` | `#EFE5EE` |
| jade | dark | -0.018 | 7.2° | -0.042 | 1.1° | `#500B53` | `#400744` |

Mean: concept **-0.027 / 5.5°** against app **-0.037 / 5.5°**. Split by polarity: **light** concept -0.042 vs app -0.045; **dark** concept -0.011 vs app -0.029.

**The relationship was inverted, and that was the bug.** The references put the *light* page's span at roughly 4x the dark one's — paper is where a gradient is visible, and a dark page is a near-uniform deep wash. The app had it the other way round (dark ~2x light), so the one place the ramp is most legible was the one place it was weakest. The paper floor is now deepened (its span lands within 0.003 of the reference) and the dark floor carries almost the top's full lift, so the dark span narrowed from -0.058 to -0.029.

**The ramp is 2-D, and that was the second half of the bug.** A `verticalGradient` puts every iso-line flat, so the whole x axis is constant and half the page carries no gradient at all. The references do not do that: sampled on a 2x2 grid of field taps, the horizontal delta at the foot of the page is **40-70% of the vertical one** on every face (terracotta light -0.057 across against -0.152 down; solar light -0.095 against -0.135) — the axis runs corner to corner, darkening toward the bottom-right. The field is now a linear gradient along that diagonal, with one shared projection (`fieldT`) used by the Compose painter, `fieldColorAtPoint`, the glass shader and the guilloche shader, so a sampled tap still equals the pixel.

**And the axis itself is the theme's, so no two themes ramp the same way.** A ramp needs a direction, and a direction taken from the screen — a `verticalGradient`, or a fixed corner-to-corner diagonal — is the *same* geometry on every palette: one shared wash wearing twenty colours. The axis now runs from the pack's own authored light source to its mirror (`FOLIO_LIGHTS`), which gives **10 distinct angles across the 10 packs**, spanning -16.7 deg to +30.2 deg — and `dawn` and `rouge` ramp the *other* way. `sakura` and `moss` used to duplicate `silver`'s and `manuscript`'s seat exactly; they were nudged, source only, so every theme is genuinely its own.

| pack | source (x, y) | axis angle from vertical |
|---|---|---|
| `dawn` | (0.70, 0.14) | -16.7 deg |
| `rouge` | (0.52, 0.14) | -1.7 deg |
| `sakura` | (0.34, 0.15) | +13.9 deg |
| `arctic` | (0.32, 0.14) | +15.1 deg |
| `silver` | (0.30, 0.16) | +17.6 deg |
| `gallery` | (0.28, 0.16) | +19.3 deg |
| `honey` | (0.28, 0.18) | +20.4 deg |
| `lagoon` | (0.26, 0.20) | +23.4 deg |
| `manuscript` | (0.24, 0.22) | +26.6 deg |
| `moss` | (0.22, 0.24) | +30.2 deg |

**The one deliberate residual: the dark span stays ~2.5x the reference's.** A concept's dark page is a JPEG-crushed near-black wash *behind a scrim*, where a 0.011 Oklab-L span sits at the noise floor and part of it is the artwork rather than the page; the app's page is the actual ground plane with no artwork over it, and a span that small would read as a flat fill — the complaint this pass exists to answer. Direction and the hue axis are matched exactly (5.5° against 5.5°); the dark amplitude is kept legible on purpose.

## Summary
| | mean Δhue | max Δhue |
|---|---|---|
| **before** (shipped) | 51.3° | 175.3° |
| **after** (this pass) | 16.2° | 76.0° |

---

## Terracotta Tide — light
App pack: **Manuscript / Espresso** (palette id `warm`)

| element | concept | app before | app after | Δ before | Δ after | |
|---|---|---|---|---|---|---|
| page | `#FAE1C6` h69 | `#FDF4E9` h73 | `#FDF4E9` h73 | 4° | 4° | OK |
| hero_card | `#F3E5D4` h73 | `#92F7F5` h194 | `#EFE3CD` h83 | 121° | 10° | OK |
| today_card | `#FCE9D3` h71 | `#FCD6C8` h41 | `#ECDBCB` h66 | 30° | 6° | OK |
| streak_card | `#FADABD` h64 | `#FCD6C8` h41 | `#ECDBCB` h66 | 23° | 1° | OK |
| week_card | `#DED2C0` h78 | `#FABD95` h54 | `#D7C4B5` h61 | 24° | 17° | close |
| nav_pill | `#F7DBC1` h65 | `#FBCBAD` h54 | `#FBCCA5` h61 | 11° | 4° | OK |
| guilloche thread | `#FAE3C9` h71 | h192 | h83 | 122° | 12° | OK |

---

## Terracotta Tide — dark
App pack: **Manuscript / Espresso** (palette id `espresso`)

| element | concept | app before | app after | Δ before | Δ after | |
|---|---|---|---|---|---|---|
| page | `#130502` h41 | `#431A05` h45 | `#5C1107` h31 | 4° | 10° | OK |
| hero_card | `#231611` h42 | `#2E3829` h135 | `#5F2A20` h32 | 93° | 10° | OK |
| today_card | `#180B07` h39 | `#5D2209` h41 | `#552219` h32 | 2° | 7° | OK |
| streak_card | `#1D0D08` h39 | `#5D2209` h41 | `#552219` h32 | 2° | 7° | OK |
| week_card | `#160C08` h43 | `#401905` h45 | `#4D140B` h32 | 2° | 12° | OK |
| nav_pill | `#19100E` h33 | `#401C05` h50 | `#4F1706` h37 | 17° | 4° | OK |
| guilloche thread | `#362419` h52 | h184 | h26 | 131° | 26° | close |

---

## Arctic Glass — light
App pack: **Arctic / Midnight** (palette id `arctic`)

| element | concept | app before | app after | Δ before | Δ after | |
|---|---|---|---|---|---|---|
| page | `#DAEAFD` h253 | `#F3F8FF` h257 | `#F3F8FF` h257 | 4° | 4° | OK |
| hero_card | `#DDECFD` h251 | `#E0E6FD` h274 | `#DDE7FC` h265 | 23° | 14° | OK |
| today_card | `#E4F0FD` h250 | `#C7E4FC` h243 | `#D2E2F1` h246 | 7° | 4° | OK |
| streak_card | `#DFEEFD` h248 | `#C7E4FC` h243 | `#D2E2F1` h246 | 5° | 2° | OK |
| week_card | `#E3EEFD` h256 | `#7DDBFA` h222 | `#B9CCDB` h242 | 35° | 14° | OK |
| nav_pill | `#DAEAFD` h253 | `#9AE2FB` h222 | `#B1DBFB` h241 | 31° | 11° | OK |
| guilloche thread | `#DEECFD` h253 | h219 | h243 | 35° | 10° | OK |

---

## Arctic Glass — dark
App pack: **Arctic / Midnight** (palette id `midnight`)

| element | concept | app before | app after | Δ before | Δ after | |
|---|---|---|---|---|---|---|
| page | `#001028` h254 | `#04222A` h219 | `#04213A` h248 | 35° | 6° | OK |
| hero_card | `#022C5C` h255 | `#12336E` h261 | `#1D344B` h250 | 5° | 6° | OK |
| today_card | `#001733` h253 | `#083038` h214 | `#122C3F` h243 | 39° | 11° | OK |
| streak_card | `#011A39` h255 | `#083038` h214 | `#122C3F` h243 | 40° | 12° | OK |
| week_card | `#011835` h254 | `#042226` h208 | `#062034` h245 | 47° | 9° | OK |
| nav_pill | `#011836` h255 | `#042322` h192 | `#04222E` h229 | 63° | 26° | close |
| guilloche thread | `#02214C` h257 | h212 | h232 | 45° | 25° | close |

---

## Mint & Coral Editorial — light
App pack: **Sakura / Blossom** (palette id `sakura`)

| element | concept | app before | app after | Δ before | Δ after | |
|---|---|---|---|---|---|---|
| page | `#F1F5EF` h135 | `#F3FBF7` h165 | `#F3FBF7` h165 | 30° | 30° | OFF |
| hero_card | `#F2F6F0` h135 | `#F9E4DE` h37 | `#DAEDDE` h152 | 98° | 17° | close |
| today_card | `#F4F9F3` h141 | `#88FCE3` h178 | `#D0E7DF` h173 | 37° | 32° | OFF |
| streak_card | `#F8F8F2` h107 | `#88FCE3` h178 | `#D0E7DF` h173 | 71° | 66° | OFF |
| week_card | `#F2F8F2` h145 | `#C2C9FA` h278 | `#B7D1C9` h175 | 133° | 30° | close |
| nav_pill | `#D5F1E7` h172 | `#CFD5FB` h278 | `#83EFD1` h174 | 106° | 1° | OK |
| guilloche thread | `#F4F8F2` h135 | h279 | h171 | 145° | 36° | OFF |

---

## Mint & Coral Editorial — dark
App pack: **Sakura / Blossom** (palette id `blossom`)

| element | concept | app before | app after | Δ before | Δ after | |
|---|---|---|---|---|---|---|
| page | `#010E0F` h200 | `#072F27` h176 | `#072F29` h181 | 24° | 20° | close |
| hero_card | `#0D2121` h196 | `#603125` h35 | `#1F4239` h175 | 160° | 21° | close |
| today_card | `#051919` h195 | `#0B3D31` h173 | `#183932` h178 | 22° | 17° | close |
| streak_card | `#051A1A` h195 | `#0B3D31` h173 | `#183932` h178 | 22° | 17° | close |
| week_card | `#04191A` h199 | `#0F2A35` h228 | `#0A2D28` h182 | 28° | 17° | close |
| nav_pill | `#051719` h205 | `#251F60` h281 | `#062E2B` h188 | 76° | 17° | close |
| guilloche thread | `#133232` h195 | h31 | h166 | 164° | 30° | close |

---

## Solar Eclipse — light
App pack: **Honey / Ember** (palette id `honey`)

| element | concept | app before | app after | Δ before | Δ after | |
|---|---|---|---|---|---|---|
| page | `#FAEBD5` h78 | `#FFF9E8` h91 | `#FFF9E8` h91 | 13° | 13° | OK |
| hero_card | `#F2ECE4` h75 | `#FDE6C6` h76 | `#F5E7D0` h80 | 1° | 5° | OK |
| today_card | `#FBEEDA` h79 | `#D4E4F5` h250 | `#E6E3CC` h101 | 171° | 22° | close |
| streak_card | `#F9EBD7` h77 | `#D4E4F5` h250 | `#E6E3CC` h101 | 173° | 24° | close |
| week_card | `#EDE7DC` h83 | `#9ED4FA` h239 | `#CFCDB6` h103 | 157° | 20° | close |
| nav_pill | `#FEF2D8` h86 | `#B0DCFB` h239 | `#E4D981` h102 | 153° | 16° | close |
| guilloche thread | `#FAEFDA` h84 | h255 | h100 | 172° | 16° | close |

---

## Solar Eclipse — dark
App pack: **Honey / Ember** (palette id `ember`)

| element | concept | app before | app after | Δ before | Δ after | |
|---|---|---|---|---|---|---|
| page | `#010610` h252 | `#261903` h79 | `#0E1185` h268 | 173° | 16° | close |
| hero_card | `#0C1C1C` h196 | `#1D3332` h192 | `#202A71` h272 | 4° | 76° | OFF |
| today_card | `#020B17` h250 | `#342506` h82 | `#192167` h271 | 168° | 22° | close |
| streak_card | `#030E1B` h250 | `#342506` h82 | `#192167` h271 | 168° | 21° | close |
| week_card | `#020B16` h248 | `#1D1819` h4 | `#0F1368` h270 | 116° | 22° | close |
| nav_pill | `#020A16` h251 | `#0C1956` h268 | `#0C1956` h268 | 16° | 16° | close |
| guilloche thread | `#14242B` h227 | h85 | h261 | 142° | 34° | OFF |

---

## Bioluminescent Forest — light
App pack: **Moss / Peacock** (palette id `moss`)

| element | concept | app before | app after | Δ before | Δ after | |
|---|---|---|---|---|---|---|
| page | `#DFECD6` h132 | `#F4FAF1` h135 | `#F4FAF1` h135 | 3° | 3° | OK |
| hero_card | `#DBEBD4` h136 | `#B6F4F8` h201 | `#DBECD9` h142 | 65° | 6° | OK |
| today_card | `#E3EFDB` h133 | `#86FBD4` h170 | `#D6E4D4` h142 | 37° | 9° | OK |
| streak_card | `#D8E9D2` h137 | `#86FBD4` h170 | `#D6E4D4` h142 | 32° | 4° | OK |
| week_card | `#DEEDD6` h135 | `#99DCA7` h150 | `#BECFBD` h144 | 16° | 9° | OK |
| nav_pill | `#E7F1DE` h130 | `#9FEAB0` h151 | `#A9E8A6` h143 | 21° | 14° | OK |
| guilloche thread | `#E0ECD6` h131 | h210 | h141 | 79° | 11° | OK |

---

## Bioluminescent Forest — dark
App pack: **Moss / Peacock** (palette id `peacock`)

| element | concept | app before | app after | Δ before | Δ after | |
|---|---|---|---|---|---|---|
| page | `#021415` h200 | `#062E22` h168 | `#062E27` h178 | 31° | 21° | close |
| hero_card | `#042624` h189 | `#0E4543` h191 | `#1C413B` h182 | 2° | 8° | OK |
| today_card | `#041C1E` h203 | `#0B3D2B` h164 | `#18382F` h173 | 38° | 30° | close |
| streak_card | `#041C1D` h199 | `#0B3D2B` h164 | `#18382F` h173 | 35° | 26° | close |
| week_card | `#041B1D` h203 | `#062E1B` h158 | `#0B2C24` h174 | 45° | 29° | close |
| nav_pill | `#041A1A` h195 | `#062E0A` h145 | `#062C1B` h160 | 50° | 36° | OFF |
| guilloche thread | `#0C211B` h173 | h207 | h182 | 34° | 10° | OK |

---

## Nebula Electric — light
App pack: **Dawn / Dusk** (palette id `dawn`)

| element | concept | app before | app after | Δ before | Δ after | |
|---|---|---|---|---|---|---|
| page | `#DAD9FD` h287 | `#F7F4FF` h299 | `#F7F4FF` h299 | 12° | 12° | OK |
| hero_card | `#E6E3FD` h291 | `#C5EBFD` h228 | `#E3E3FA` h286 | 63° | 5° | OK |
| today_card | `#E2E1FD` h287 | `#DADBFC` h284 | `#DCDCF1` h286 | 3° | 1° | OK |
| streak_card | `#E3E4FD` h284 | `#DADBFC` h284 | `#DCDCF1` h286 | 0° | 2° | OK |
| week_card | `#EDE9FD` h295 | `#C4C3FA` h285 | `#C5C6DB` h284 | 10° | 11° | OK |
| nav_pill | `#E5E1FD` h292 | `#D0D0FB` h285 | `#D0D0FB` h285 | 7° | 7° | OK |
| guilloche thread | `#E4DDFC` h296 | h217 | h285 | 79° | 11° | OK |

---

## Nebula Electric — dark
App pack: **Dawn / Dusk** (palette id `dusk`)

| element | concept | app before | app after | Δ before | Δ after | |
|---|---|---|---|---|---|---|
| page | `#0A0624` h283 | `#2E0B65` h292 | `#300B63` h294 | 8° | 11° | OK |
| hero_card | `#0C134F` h270 | `#212B63` h272 | `#37255F` h294 | 2° | 25° | close |
| today_card | `#0D092B` h282 | `#351777` h289 | `#311C55` h297 | 7° | 14° | OK |
| streak_card | `#0D0930` h280 | `#351777` h289 | `#311C55` h297 | 9° | 16° | close |
| week_card | `#0B082B` h280 | `#340954` h305 | `#290D4F` h297 | 25° | 17° | close |
| nav_pill | `#0A0828` h280 | `#4C0836` h345 | `#2E0F4F` h301 | 65° | 21° | close |
| guilloche thread | `#150837` h288 | h215 | h278 | 74° | 10° | OK |

---

## Moonstone — light
App pack: **Silver / Graphite** (palette id `silver`)

| element | concept | app before | app after | Δ before | Δ after | |
|---|---|---|---|---|---|---|
| page | `#E6EBF9` h270 | `#F6F6FC` h286 | `#F6F6FC` h286 | 16° | 16° | close |
| hero_card | `#E6EAF9` h274 | `#E6E3FC` h291 | `#E4E4F9` h286 | 17° | 12° | OK |
| today_card | `#ECEFFA` h274 | `#D5DFFB` h270 | `#D9DFF1` h271 | 4° | 3° | OK |
| streak_card | `#EAEEFA` h271 | `#D5DFFB` h270 | `#D9DFF1` h271 | 1° | 0° | OK |
| week_card | `#E7EBF8` h272 | `#73DFE3` h199 | `#BFC9DD` h264 | 74° | 8° | OK |
| nav_pill | `#E2E8F8` h269 | `#72EDF2` h199 | `#C1D5FB` h263 | 71° | 6° | OK |
| guilloche thread | `#EAEEFA` h271 | h195 | h265 | 77° | 6° | OK |

---

## Moonstone — dark
App pack: **Silver / Graphite** (palette id `graphite`)

| element | concept | app before | app after | Δ before | Δ after | |
|---|---|---|---|---|---|---|
| page | `#070B1A` h270 | `#20107F` h276 | `#1A1185` h272 | 5° | 2° | OK |
| hero_card | `#1F223F` h278 | `#35239A` h280 | `#2C2973` h279 | 2° | 2° | OK |
| today_card | `#0D1425` h266 | `#211F88` h273 | `#232269` h277 | 7° | 11° | OK |
| streak_card | `#0D1426` h267 | `#211F88` h273 | `#232269` h277 | 6° | 10° | OK |
| week_card | `#0E1629` h266 | `#181461` h275 | `#171469` h273 | 9° | 8° | OK |
| nav_pill | `#0E152A` h268 | `#042320` h186 | `#041F3F` h255 | 82° | 14° | OK |
| guilloche thread | `#131831` h273 | h190 | h256 | 83° | 17° | close |

---

## Aurora Ocean — light
App pack: **Lagoon / Abyss** (palette id `lagoon`)

| element | concept | app before | app after | Δ before | Δ after | |
|---|---|---|---|---|---|---|
| page | `#CEEDEF` h201 | `#F1FBF9` h183 | `#F1FBF9` h183 | 18° | 18° | close |
| hero_card | `#CAEEEF` h199 | `#D7EBFB` h242 | `#D1EEEC` h192 | 43° | 7° | OK |
| today_card | `#D8F1F1` h197 | `#83FBF6` h192 | `#CDE7E5` h191 | 5° | 6° | OK |
| streak_card | `#BCEAED` h201 | `#83FBF6` h192 | `#CDE7E5` h191 | 9° | 10° | OK |
| week_card | `#D1F0F1` h199 | `#84E1C2` h170 | `#B8D1C7` h170 | 29° | 29° | close |
| nav_pill | `#C9EDEE` h199 | `#86EFCD` h171 | `#86EFCD` h171 | 28° | 28° | close |
| guilloche thread | `#D3EFEF` h197 | h242 | h179 | 45° | 17° | close |

---

## Aurora Ocean — dark
App pack: **Lagoon / Abyss** (palette id `abyss`)

| element | concept | app before | app after | Δ before | Δ after | |
|---|---|---|---|---|---|---|
| page | `#001C2E` h240 | `#052927` h190 | `#052735` h230 | 51° | 10° | OK |
| hero_card | `#01273A` h236 | `#0C3E54` h232 | `#1C3A48` h230 | 3° | 6° | OK |
| today_card | `#012235` h239 | `#093633` h188 | `#13333C` h219 | 50° | 20° | close |
| streak_card | `#012537` h236 | `#093633` h188 | `#13333C` h219 | 47° | 17° | close |
| week_card | `#012133` h238 | `#052725` h189 | `#072630` h223 | 49° | 15° | OK |
| nav_pill | `#011C2D` h240 | `#042521` h184 | `#04242A` h213 | 56° | 27° | close |
| guilloche thread | `#012A3D` h234 | h167 | h213 | 67° | 21° | close |

---

## Jade Noir — light
App pack: **Rouge / Garnet** (palette id `rouge`)

| element | concept | app before | app after | Δ before | Δ after | |
|---|---|---|---|---|---|---|
| page | `#E9DDDE` h12 | `#FCF4FA` h334 | `#FCF4FA` h334 | 37° | 37° | OFF |
| hero_card | `#EDE2E4` h5 | `#8AF9E4` h180 | `#F0E1F3` h321 | 175° | 44° | OFF |
| today_card | `#EDE4E4` h17 | `#FCD4F3` h334 | `#ECDAE8` h333 | 43° | 44° | OFF |
| streak_card | `#EDE4E5` h10 | `#FCD4F3` h334 | `#ECDAE8` h333 | 36° | 36° | OFF |
| week_card | `#EFE3E5` h6 | `#BAC8FA` h273 | `#D3C4D7` h319 | 93° | 47° | OFF |
| nav_pill | `#ECE2E4` h4 | `#C6D2FB` h272 | `#F0C6FB` h319 | 91° | 44° | OFF |
| guilloche thread | `#ECE0E1` h12 | h179 | h321 | 168° | 51° | OFF |

---

## Jade Noir — dark
App pack: **Rouge / Garnet** (palette id `garnet`)

| element | concept | app before | app after | Δ before | Δ after | |
|---|---|---|---|---|---|---|
| page | `#0B040C` h323 | `#520B4D` h331 | `#500B53` h326 | 8° | 3° | OK |
| hero_card | `#220F1E` h335 | `#362F4D` h294 | `#512657` h323 | 41° | 13° | OK |
| today_card | `#140B15` h323 | `#65145F` h331 | `#4B1E4B` h327 | 8° | 4° | OK |
| streak_card | `#120A13` h323 | `#65145F` h331 | `#4B1E4B` h327 | 9° | 5° | OK |
| week_card | `#110B14` h314 | `#3C104E` h314 | `#420F46` h325 | 0° | 11° | OK |
| nav_pill | `#0D0811` h310 | `#091E59` h265 | `#371150` h308 | 45° | 1° | OK |
| guilloche thread | `#160E16` h326 | h177 | h310 | 150° | 16° | close |
