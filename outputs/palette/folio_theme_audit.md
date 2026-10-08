# Folio theme rebuild — coloraide audit

coloraide verification of 10 themes x 2 faces = 20 faces, 25 roles each.

## Verdict

- **failures: 0**
- warnings: 9

### Warnings

- cross-theme light: gallery vs silver primaryAccent dE2000 = 13.4 (tight)
- cross-theme light: silver vs dawn primaryAccent dE2000 = 13.5 (tight)
- cross-theme dark: gallery vs arctic primaryAccent dE2000 = 12.9 (tight)
- cross-theme dark: manuscript vs honey primaryAccent dE2000 = 13.5 (tight)
- cross-theme dark: silver vs dawn primaryAccent dE2000 = 12.1 (tight)
- cross-theme dark: arctic vs lagoon primaryAccent dE2000 = 13.6 (tight)
- cross-theme dark: sakura vs dawn primaryAccent dE2000 = 13.6 (tight)
- cross-theme dark: sakura vs rouge primaryAccent dE2000 = 13.5 (tight)
- cross-theme dark: honey vs rouge primaryAccent dE2000 = 13.8 (tight)

## Theme identities and shader assignment

| theme | light / dark | hue territory (OKLCH h) | Mirage shader |
|---|---|---|---|
| `gallery` | Gallery / Dark | 248° / 250° | `GrainGradient` |
| `manuscript` | Manuscript / Espresso | 42° / 62° | `Warp` |
| `silver` | Silver / Graphite | 276° / 278° | `Dithering` |
| `arctic` | Arctic / Midnight | 218° / 218° | `Swirl` |
| `sakura` | Sakura / Blossom | 350° / 344° | `DotOrbit` |
| `honey` | Honey / Ember | 80° / 36° | `Metaballs` |
| `moss` | Moss / Peacock | 145° / 164° | `Voronoi` |
| `dawn` | Dawn / Dusk | 310° / 308° | `GodRays` |
| `lagoon` | Lagoon / Abyss | 188° / 193° | `MeshGradient` |
| `rouge` | Rouge / Garnet | 18° / 14° | `SmokeRing` |

### Gallery / Dark (`gallery`)

White-cube gallery — plaster walls, a cobalt placard, the red sold-dot, a brass rail

**Shader — `GrainGradient`.** A gallery is still. GrainGradient with Corners shape and high softness is a vast quiet wall whose light drifts imperceptibly, with canvas grain over it — motion you feel rather than watch.

### Manuscript / Espresso (`manuscript`)

Vellum codex — rubric sienna initials, iron-gall indigo, verdigris, gold leaf

**Shader — `Warp`.** Warp on Stripes with moderate distortion and swirl reads as ink bleeding through laid paper and the ruled lines of a scribe's page pulling out of true.

### Silver / Graphite (`silver`)

Gelatin-silver print — cool platinum, steel-violet, an iridescent magenta flash

**Shader — `Dithering`.** Silver is a photographic process before it is a colour. Dithering with a Bayer 4x4 matrix over a Swirl is the silver-halide grain of a darkroom print, which is the one shader that names the theme instead of decorating it.

### Arctic / Midnight (`arctic`)

Ice and aurora — glacier blue, snow-shadow, aurora green, low-sun gold

**Shader — `Swirl`.** Swirl's concentric twisted bands at low twist and high softness are aurora curtains over ice strata — the only shader whose geometry is literally the sky this theme is named for.

### Sakura / Blossom (`sakura`)

Cherry blossom — petal rose, young leaf, branch umber, spring sky

**Shader — `DotOrbit`.** DotOrbit's dots orbiting a repeating field are petals on the wind. Nothing else in the set drifts as discrete soft bodies, which is exactly what sakura is.

### Honey / Ember (`honey`)

Honey — amber gold, hive-shade teal, propolis, clover

**Shader — `Metaballs`.** Metaballs are merging gooey bodies: honey drops coalescing. Viscosity is the whole character of the name and this is the viscous shader.

### Moss / Peacock (`moss`)

Moss — damp forest green, lichen chartreuse, bark, fungus rose

**Shader — `Voronoi`.** Voronoi cells with a gap and a slow glow are a colony: lichen and moss spreading over stone. Cellular growth is the shader's literal subject.

### Dawn / Dusk (`dawn`)

Dawn — lilac sky, apricot horizon, morning blue, pale gold

**Shader — `GodRays`.** GodRays are volumetric shafts from an off-screen source with bloom — sunrise, unambiguously. Dawn's source sits low and below; Dusk's is lower still and warmer.

### Lagoon / Abyss (`lagoon`)

Lagoon — turquoise shallows, coral, sand, the deep beyond the reef

**Shader — `MeshGradient`.** MeshGradient's spots travel on organic trajectories and blend without ever climbing to white — moving water. Turquoise, sand and coral drifting through each other is a shallow reef seen from above.

### Rouge / Garnet (`rouge`)

Rouge — theatrical crimson, gilt, velvet plum, a cold counterpoint

**Shader — `SmokeRing`.** A turbulent ring of smoke in a red spotlight: the house before curtain. SmokeRing is the only shader with a stage's single luminous centre and soft falloff to the wings.

## Contrast detail

| face | ink on page | ink2 on page | ink3 on page | progress:track | page chroma |
|---|---|---|---|---|---|
| Gallery | 14.50 | 5.63 | 3.44 | 4.37 | 0.0060 |
| Dark | 17.26 | 8.54 | 4.50 | 6.94 | 0.0130 |
| Manuscript | 13.84 | 5.38 | 3.45 | 4.68 | 0.0340 |
| Espresso | 17.46 | 8.62 | 4.54 | 7.02 | 0.0280 |
| Silver | 14.37 | 5.58 | 3.43 | 4.52 | 0.0070 |
| Graphite | 17.03 | 8.42 | 4.44 | 6.68 | 0.0130 |
| Arctic | 14.10 | 5.47 | 3.40 | 4.22 | 0.0240 |
| Midnight | 17.64 | 8.73 | 4.61 | 7.65 | 0.0280 |
| Sakura | 14.30 | 5.57 | 3.44 | 4.72 | 0.0203 |
| Blossom | 17.36 | 8.56 | 4.50 | 6.73 | 0.0300 |
| Honey | 14.05 | 5.46 | 3.34 | 4.49 | 0.0460 |
| Ember | 17.61 | 8.69 | 4.58 | 6.97 | 0.0320 |
| Moss | 14.13 | 5.48 | 3.42 | 4.28 | 0.0300 |
| Peacock | 17.49 | 8.67 | 4.58 | 7.54 | 0.0266 |
| Dawn | 14.26 | 5.56 | 3.46 | 4.68 | 0.0292 |
| Dusk | 17.62 | 8.70 | 4.58 | 7.05 | 0.0320 |
| Lagoon | 14.23 | 5.51 | 3.44 | 4.16 | 0.0340 |
| Abyss | 18.07 | 8.95 | 4.73 | 8.41 | 0.0190 |
| Rouge | 14.23 | 5.55 | 3.41 | 4.72 | 0.0181 |
| Garnet | 17.71 | 8.73 | 4.59 | 7.10 | 0.0300 |

## Cross-theme primary separation (dE2000)

### light faces

| | gallery | manuscript | silver | arctic | sakura | honey | moss | dawn | lagoon | rouge |
|---|---|---|---|---|---|---|---|---|---|---|
| gallery | — | 47 | 13 | 15 | 38 | 51 | 50 | 27 | 30 | 43 |
| manuscript | 47 | — | 41 | 52 | 31 | 18 | 52 | 40 | 50 | 17 |
| silver | 13 | 41 | — | 21 | 25 | 47 | 45 | 13 | 30 | 33 |
| arctic | 15 | 52 | 21 | — | 51 | 45 | 37 | 33 | 16 | 56 |
| sakura | 38 | 31 | 25 | 51 | — | 45 | 67 | 15 | 50 | 15 |
| honey | 51 | 18 | 47 | 45 | 45 | — | 35 | 52 | 39 | 33 |
| moss | 50 | 52 | 45 | 37 | 67 | 35 | — | 48 | 20 | 63 |
| dawn | 27 | 40 | 13 | 33 | 15 | 52 | 48 | — | 39 | 27 |
| lagoon | 30 | 50 | 30 | 16 | 50 | 39 | 20 | 39 | — | 56 |
| rouge | 43 | 17 | 33 | 56 | 15 | 33 | 63 | 27 | 56 | — |

### dark faces

| | gallery | manuscript | silver | arctic | sakura | honey | moss | dawn | lagoon | rouge |
|---|---|---|---|---|---|---|---|---|---|---|
| gallery | — | 44 | 15 | 13 | 41 | 44 | 36 | 27 | 24 | 43 |
| manuscript | 44 | — | 41 | 46 | 38 | 14 | 43 | 44 | 44 | 26 |
| silver | 15 | 41 | — | 26 | 24 | 36 | 38 | 12 | 31 | 30 |
| arctic | 13 | 46 | 26 | — | 58 | 58 | 29 | 39 | 14 | 63 |
| sakura | 41 | 38 | 24 | 58 | — | 27 | 50 | 14 | 57 | 13 |
| honey | 44 | 14 | 36 | 58 | 27 | — | 55 | 35 | 52 | 14 |
| moss | 36 | 43 | 38 | 29 | 50 | 55 | — | 44 | 15 | 62 |
| dawn | 27 | 44 | 12 | 39 | 14 | 35 | 44 | — | 42 | 24 |
| lagoon | 24 | 44 | 31 | 14 | 57 | 52 | 15 | 42 | — | 58 |
| rouge | 43 | 26 | 30 | 63 | 13 | 14 | 62 | 24 | 58 | — |

## Palettes

### Gallery (`gallery`, light)

| role | hex |
|---|---|
| backgroundBase | `#F7F5F1` |
| backgroundSecondary | `#EDE4DF` |
| surface | `#FCFBF9` |
| surfaceElevated | `#FFFFFF` |
| primaryAccent | `#0077D2` |
| secondaryAccent | `#BD5348` |
| tertiaryAccent | `#A27700` |
| warmAccent | `#4A9A5F` |
| guillochePrimary | `#AF3D34` |
| guillocheSecondary | `#8A6100` |
| guillocheHighlight | `#0076D0` |
| glowPrimary | `#68BEFF` |
| glowSecondary | `#FFA194` |
| progress | `#0065B9` |
| progressTrack | `#C2E2FF` |
| textPrimary | `#2B2112` |
| textSecondary | `#6B6052` |
| textTertiary | `#8C8276` |
| border | `#D3CBC0` |
| borderHighlight | `#E8E2DA` |
| navigationSelected | `#006BC4` |
| accentProgress | `#006BC4` |
| accentStreak | `#008032` |
| accentDiscovery | `#B3372F` |
| accentAnnotation | `#8A6100` |

### Dark (`gallery`, dark)

| role | hex |
|---|---|
| backgroundBase | `#080B11` |
| backgroundSecondary | `#0E141C` |
| surface | `#13181E` |
| surfaceElevated | `#21262C` |
| primaryAccent | `#76C0FF` |
| secondaryAccent | `#FEA195` |
| tertiaryAccent | `#E2BD71` |
| warmAccent | `#9DDAA9` |
| guillochePrimary | `#EB776A` |
| guillocheSecondary | `#C49724` |
| guillocheHighlight | `#52B1FF` |
| glowPrimary | `#48A8FF` |
| glowSecondary | `#F58679` |
| progress | `#5EB2FF` |
| progressTrack | `#09243D` |
| textPrimary | `#EBF1F8` |
| textSecondary | `#A4ACB5` |
| textTertiary | `#727A84` |
| border | `#33373C` |
| borderHighlight | `#1B1E22` |
| navigationSelected | `#74C0FF` |
| accentProgress | `#7BC3FF` |
| accentStreak | `#7AD68F` |
| accentDiscovery | `#FF998B` |
| accentAnnotation | `#E4B750` |

### Manuscript (`manuscript`, light)

| role | hex |
|---|---|
| backgroundBase | `#FCEED7` |
| backgroundSecondary | `#F8DDB1` |
| surface | `#FDF5E8` |
| surfaceElevated | `#FEF9F3` |
| primaryAccent | `#BA4F1F` |
| secondaryAccent | `#5576C1` |
| tertiaryAccent | `#24946E` |
| warmAccent | `#9D8431` |
| guillochePrimary | `#4364B4` |
| guillocheSecondary | `#007E57` |
| guillocheHighlight | `#B84E1D` |
| glowPrimary | `#FC9B75` |
| glowSecondary | `#9FC0FF` |
| progress | `#A3400F` |
| progressTrack | `#FCD4C5` |
| textPrimary | `#2A2112` |
| textSecondary | `#6A6051` |
| textTertiary | `#887F72` |
| border | `#D2CBBF` |
| borderHighlight | `#E7E2DA` |
| navigationSelected | `#AC430E` |
| accentProgress | `#AC430E` |
| accentStreak | `#836500` |
| accentDiscovery | `#4063BA` |
| accentAnnotation | `#008055` |

### Espresso (`manuscript`, dark)

| role | hex |
|---|---|
| backgroundBase | `#120602` |
| backgroundSecondary | `#1E0C03` |
| surface | `#201007` |
| surfaceElevated | `#2E1F16` |
| primaryAccent | `#F0A968` |
| secondaryAccent | `#95C0FF` |
| tertiaryAccent | `#83D6B7` |
| warmAccent | `#FAB6A5` |
| guillochePrimary | `#6A9FEE` |
| guillocheSecondary | `#40B690` |
| guillocheHighlight | `#EC9540` |
| glowPrimary | `#E18C34` |
| glowSecondary | `#79ACF8` |
| progress | `#E6984E` |
| progressTrack | `#321A03` |
| textPrimary | `#F7EEE9` |
| textSecondary | `#B4A8A2` |
| textTertiary | `#82766F` |
| border | `#3E3129` |
| borderHighlight | `#231913` |
| navigationSelected | `#F6A65D` |
| accentProgress | `#F9AA60` |
| accentStreak | `#FFA189` |
| accentDiscovery | `#8BBFFF` |
| accentAnnotation | `#67D6AF` |

### Silver (`silver`, light)

| role | hex |
|---|---|
| backgroundBase | `#F1F5F9` |
| backgroundSecondary | `#DEE7ED` |
| surface | `#F9FBFD` |
| surfaceElevated | `#FDFEFF` |
| primaryAccent | `#656FAC` |
| secondaryAccent | `#398596` |
| tertiaryAccent | `#986F93` |
| warmAccent | `#978457` |
| guillochePrimary | `#1C7587` |
| guillocheSecondary | `#82597E` |
| guillocheHighlight | `#646EAA` |
| glowPrimary | `#A9B4EB` |
| glowSecondary | `#8DCDDD` |
| progress | `#555E96` |
| progressTrack | `#D8DDF3` |
| textPrimary | `#192431` |
| textSecondary | `#586371` |
| textTertiary | `#7B8591` |
| border | `#C4CDD8` |
| borderHighlight | `#DEE3EB` |
| navigationSelected | `#5A639F` |
| accentProgress | `#5A639F` |
| accentStreak | `#7E6625` |
| accentDiscovery | `#077689` |
| accentAnnotation | `#855680` |

### Graphite (`silver`, dark)

| role | hex |
|---|---|
| backgroundBase | `#0A0D13` |
| backgroundSecondary | `#11161E` |
| surface | `#151A20` |
| surfaceElevated | `#24282E` |
| primaryAccent | `#ADB5F3` |
| secondaryAccent | `#82CBDD` |
| tertiaryAccent | `#DDB3D9` |
| warmAccent | `#DAC79B` |
| guillochePrimary | `#49AEC4` |
| guillocheSecondary | `#BF8AB9` |
| guillocheHighlight | `#9CA6F0` |
| glowPrimary | `#939CE6` |
| glowSecondary | `#5DBACF` |
| progress | `#9EA6EA` |
| progressTrack | `#212338` |
| textPrimary | `#EBF1F8` |
| textSecondary | `#A5ABB5` |
| textTertiary | `#727984` |
| border | `#36393F` |
| borderHighlight | `#1E2125` |
| navigationSelected | `#ABB5F9` |
| accentProgress | `#AFB8FD` |
| accentStreak | `#D6BB79` |
| accentDiscovery | `#71CDE3` |
| accentAnnotation | `#DFA9D9` |

### Arctic (`arctic`, light)

| role | hex |
|---|---|
| backgroundBase | `#E1F7FC` |
| backgroundSecondary | `#C3EEE7` |
| surface | `#EFFBFE` |
| surfaceElevated | `#F7FEFF` |
| primaryAccent | `#0084A3` |
| secondaryAccent | `#288E5D` |
| tertiaryAccent | `#7C73BC` |
| warmAccent | `#AC7B3A` |
| guillochePrimary | `#007E4B` |
| guillocheSecondary | `#675CA7` |
| guillocheHighlight | `#0082A1` |
| glowPrimary | `#41CAEC` |
| glowSecondary | `#84D6A7` |
| progress | `#00718D` |
| progressTrack | `#BBE6F3` |
| textPrimary | `#10272B` |
| textSecondary | `#51666A` |
| textTertiary | `#74878B` |
| border | `#C0D0D3` |
| borderHighlight | `#DAE5E7` |
| navigationSelected | `#007794` |
| accentProgress | `#007794` |
| accentStreak | `#965900` |
| accentDiscovery | `#007F49` |
| accentAnnotation | `#675AAF` |

### Midnight (`arctic`, dark)

| role | hex |
|---|---|
| backgroundBase | `#000911` |
| backgroundSecondary | `#00121E` |
| surface | `#02151F` |
| surfaceElevated | `#11232C` |
| primaryAccent | `#16CFF6` |
| secondaryAccent | `#74D5A0` |
| tertiaryAccent | `#BFB7FF` |
| warmAccent | `#F1BF7F` |
| guillochePrimary | `#24BA78` |
| guillocheSecondary | `#9B8EF1` |
| guillocheHighlight | `#00C2ED` |
| glowPrimary | `#00B8E0` |
| glowSecondary | `#44C686` |
| progress | `#00C2ED` |
| progressTrack | `#002431` |
| textPrimary | `#E9F2F7` |
| textSecondary | `#A1ADB3` |
| textTertiary | `#6F7B82` |
| border | `#24343C` |
| borderHighlight | `#0F1B22` |
| navigationSelected | `#00D1FC` |
| accentProgress | `#00D4FF` |
| accentStreak | `#F6AD47` |
| accentDiscovery | `#5BDA99` |
| accentAnnotation | `#BAB0FF` |

### Sakura (`sakura`, light)

| role | hex |
|---|---|
| backgroundBase | `#FFEEF5` |
| backgroundSecondary | `#FFDAE7` |
| surface | `#FFF7FB` |
| surfaceElevated | `#FFFDFF` |
| primaryAccent | `#B04B80` |
| secondaryAccent | `#508A43` |
| tertiaryAccent | `#B06B39` |
| warmAccent | `#468EBD` |
| guillochePrimary | `#3C7A2D` |
| guillocheSecondary | `#9B5419` |
| guillocheHighlight | `#AF497F` |
| glowPrimary | `#F296C2` |
| glowSecondary | `#9DD291` |
| progress | `#9A3C6E` |
| progressTrack | `#F8D2E2` |
| textPrimary | `#2F1D23` |
| textSecondary | `#6F5C62` |
| textTertiary | `#8F7E84` |
| border | `#D6C8CD` |
| borderHighlight | `#EAE0E3` |
| navigationSelected | `#A33F75` |
| accentProgress | `#A33F75` |
| accentStreak | `#0070AD` |
| accentDiscovery | `#377B26` |
| accentAnnotation | `#A05000` |

### Blossom (`sakura`, dark)

| role | hex |
|---|---|
| backgroundBase | `#12050E` |
| backgroundSecondary | `#1E0B18` |
| surface | `#20101B` |
| surfaceElevated | `#2E1E29` |
| primaryAccent | `#F796D0` |
| secondaryAccent | `#95D088` |
| tertiaryAccent | `#F9B07E` |
| warmAccent | `#8ED2FF` |
| guillochePrimary | `#68B457` |
| guillocheSecondary | `#DF8540` |
| guillocheHighlight | `#F37EC6` |
| glowPrimary | `#E874BC` |
| glowSecondary | `#78C067` |
| progress | `#ED83C4` |
| progressTrack | `#351429` |
| textPrimary | `#F6EDF3` |
| textSecondary | `#B2A7AE` |
| textTertiary | `#81757C` |
| border | `#3F3039` |
| borderHighlight | `#241920` |
| navigationSelected | `#FD91D2` |
| accentProgress | `#FF94D5` |
| accentStreak | `#5FC9FF` |
| accentDiscovery | `#8AD47A` |
| accentAnnotation | `#FFA564` |

### Honey (`honey`, light)

| role | hex |
|---|---|
| backgroundBase | `#FFF1CF` |
| backgroundSecondary | `#C2F2BD` |
| surface | `#FFF7E5` |
| surfaceElevated | `#FFFBF2` |
| primaryAccent | `#9D6900` |
| secondaryAccent | `#008D8F` |
| tertiaryAccent | `#B96352` |
| warmAccent | `#74924A` |
| guillochePrimary | `#007B7D` |
| guillocheSecondary | `#A24B3B` |
| guillocheHighlight | `#9B6800` |
| glowPrimary | `#E2AE4E` |
| glowSecondary | `#5DD7D8` |
| progress | `#875900` |
| progressTrack | `#F0DBBA` |
| textPrimary | `#292211` |
| textSecondary | `#686151` |
| textTertiary | `#898375` |
| border | `#D1CCBF` |
| borderHighlight | `#E6E3DA` |
| navigationSelected | `#8E5E00` |
| accentProgress | `#8E5E00` |
| accentStreak | `#517700` |
| accentDiscovery | `#007B7D` |
| accentAnnotation | `#A84533` |

### Ember (`honey`, dark)

| role | hex |
|---|---|
| backgroundBase | `#120401` |
| backgroundSecondary | `#1E0901` |
| surface | `#200D05` |
| surfaceElevated | `#2E1C13` |
| primaryAccent | `#FF9778` |
| secondaryAccent | `#39D6D8` |
| tertiaryAccent | `#F8B172` |
| warmAccent | `#B6D68D` |
| guillochePrimary | `#00B8BA` |
| guillocheSecondary | `#DE8628` |
| guillocheHighlight | `#FF7D58` |
| glowPrimary | `#FD734F` |
| glowSecondary | `#00C6C9` |
| progress | `#FF8361` |
| progressTrack | `#371107` |
| textPrimary | `#F7EEE9` |
| textSecondary | `#B4A8A2` |
| textTertiary | `#827670` |
| border | `#3E2E26` |
| borderHighlight | `#231711` |
| navigationSelected | `#FF9576` |
| accentProgress | `#FF9B7D` |
| accentStreak | `#A2CF62` |
| accentDiscovery | `#00DBDE` |
| accentAnnotation | `#FFA752` |

### Moss (`moss`, light)

| role | hex |
|---|---|
| backgroundBase | `#EBF7E3` |
| backgroundSecondary | `#DEEAC0` |
| surface | `#F4FBF0` |
| surfaceElevated | `#FAFEF8` |
| primaryAccent | `#37883E` |
| secondaryAccent | `#7D7E23` |
| tertiaryAccent | `#AF6B44` |
| warmAccent | `#B76D7B` |
| guillochePrimary | `#6E6E00` |
| guillocheSecondary | `#995429` |
| guillocheHighlight | `#36873D` |
| glowPrimary | `#87CB89` |
| glowSecondary | `#C4C77C` |
| progress | `#297530` |
| progressTrack | `#CDE6CC` |
| textPrimary | `#1D2617` |
| textSecondary | `#5C6556` |
| textTertiary | `#7E8678` |
| border | `#C8CFC3` |
| borderHighlight | `#E0E5DD` |
| navigationSelected | `#2A7C32` |
| accentProgress | `#2A7C32` |
| accentStreak | `#A2465B` |
| accentDiscovery | `#6E6E00` |
| accentAnnotation | `#9F501C` |

### Peacock (`moss`, dark)

| role | hex |
|---|---|
| backgroundBase | `#000C08` |
| backgroundSecondary | `#001710` |
| surface | `#001914` |
| surfaceElevated | `#0C2722` |
| primaryAccent | `#56D6A3` |
| secondaryAccent | `#56CFF2` |
| tertiaryAccent | `#FCAD84` |
| warmAccent | `#AEC7FF` |
| guillochePrimary | `#00B2DD` |
| guillocheSecondary | `#E2814C` |
| guillocheHighlight | `#00CC90` |
| glowPrimary | `#00C287` |
| glowSecondary | `#00BEE8` |
| progress | `#28C993` |
| progressTrack | `#002817` |
| textPrimary | `#E8F3F0` |
| textSecondary | `#A1AEAB` |
| textTertiary | `#6E7D79` |
| border | `#223833` |
| borderHighlight | `#0D1E1B` |
| navigationSelected | `#40D8A0` |
| accentProgress | `#44DBA4` |
| accentStreak | `#9CBBFF` |
| accentDiscovery | `#1AD2FD` |
| accentAnnotation | `#FFA26E` |

### Dawn (`dawn`, light)

| role | hex |
|---|---|
| backgroundBase | `#FFEDFE` |
| backgroundSecondary | `#FCD8F7` |
| surface | `#FFF6FF` |
| surfaceElevated | `#FFFCFF` |
| primaryAccent | `#8D5AB1` |
| secondaryAccent | `#AF6237` |
| tertiaryAccent | `#4285BA` |
| warmAccent | `#99853A` |
| guillochePrimary | `#A04F1E` |
| guillocheSecondary | `#246EA5` |
| guillocheHighlight | `#8C58AF` |
| glowPrimary | `#CFA2F0` |
| glowSecondary | `#F7AD87` |
| progress | `#7A4A9B` |
| progressTrack | `#E7D6F6` |
| textPrimary | `#2B1E2A` |
| textSecondary | `#6B5D69` |
| textTertiary | `#8A7E89` |
| border | `#D3C8D2` |
| borderHighlight | `#E8E0E7` |
| navigationSelected | `#814EA4` |
| accentProgress | `#814EA4` |
| accentStreak | `#806600` |
| accentDiscovery | `#A44C12` |
| accentAnnotation | `#0E6EAC` |

### Dusk (`dawn`, dark)

| role | hex |
|---|---|
| backgroundBase | `#080513` |
| backgroundSecondary | `#100C21` |
| surface | `#131022` |
| surfaceElevated | `#211E2F` |
| primaryAccent | `#D2A3FB` |
| secondaryAccent | `#FEA195` |
| tertiaryAccent | `#98C5FF` |
| warmAccent | `#F8B0D1` |
| guillochePrimary | `#EB776A` |
| guillocheSecondary | `#689FF0` |
| guillocheHighlight | `#C88EF9` |
| glowPrimary | `#BF85EF` |
| glowSecondary | `#F58679` |
| progress | `#C692F2` |
| progressTrack | `#261633` |
| textPrimary | `#F0EFF8` |
| textSecondary | `#AAA9B5` |
| textTertiary | `#787783` |
| border | `#32303F` |
| borderHighlight | `#1A1824` |
| navigationSelected | `#D4A0FF` |
| accentProgress | `#D8A3FF` |
| accentStreak | `#FE99CA` |
| accentDiscovery | `#FF998B` |
| accentAnnotation | `#8BBFFF` |

### Lagoon (`lagoon`, light)

| role | hex |
|---|---|
| backgroundBase | `#DAFBFB` |
| backgroundSecondary | `#E4EAB8` |
| surface | `#ECFDFD` |
| surfaceElevated | `#F6FFFF` |
| primaryAccent | `#008A81` |
| secondaryAccent | `#B25C4F` |
| tertiaryAccent | `#997A26` |
| warmAccent | `#568ABF` |
| guillochePrimary | `#A4493C` |
| guillocheSecondary | `#846400` |
| guillocheHighlight | `#008880` |
| glowPrimary | `#42D0C5` |
| glowSecondary | `#FBA89A` |
| progress | `#00766E` |
| progressTrack | `#BCE8E4` |
| textPrimary | `#0F2727` |
| textSecondary | `#516767` |
| textTertiary | `#748787` |
| border | `#BFD0D0` |
| borderHighlight | `#DAE6E5` |
| navigationSelected | `#007C75` |
| accentProgress | `#007C75` |
| accentStreak | `#206CAF` |
| accentDiscovery | `#A84538` |
| accentAnnotation | `#876300` |

### Abyss (`lagoon`, dark)

| role | hex |
|---|---|
| backgroundBase | `#000507` |
| backgroundSecondary | `#000D12` |
| surface | `#000F14` |
| surfaceElevated | `#061D21` |
| primaryAccent | `#00D7D4` |
| secondaryAccent | `#B1B3FF` |
| tertiaryAccent | `#8AD89B` |
| warmAccent | `#FFAEAD` |
| guillochePrimary | `#908FFE` |
| guillocheSecondary | `#4FB76B` |
| guillocheHighlight | `#00C8C6` |
| glowPrimary | `#00BEBB` |
| glowSecondary | `#9D9DFF` |
| progress | `#00C8C6` |
| progressTrack | `#001E1D` |
| textPrimary | `#E8F2F5` |
| textSecondary | `#A0AEB0` |
| textTertiary | `#6D7C7F` |
| border | `#1A2D31` |
| borderHighlight | `#061517` |
| navigationSelected | `#00D8D6` |
| accentProgress | `#00DCD9` |
| accentStreak | `#FF9798` |
| accentDiscovery | `#B1B3FF` |
| accentAnnotation | `#73D78B` |

### Rouge (`rouge`, light)

| role | hex |
|---|---|
| backgroundBase | `#FFEEEE` |
| backgroundSecondary | `#FDDECC` |
| surface | `#FFF7F6` |
| surfaceElevated | `#FFFCFC` |
| primaryAccent | `#BE4551` |
| secondaryAccent | `#9B7100` |
| tertiaryAccent | `#A9629A` |
| warmAccent | `#009A9E` |
| guillochePrimary | `#8A6100` |
| guillocheSecondary | `#934A85` |
| guillocheHighlight | `#BC4450` |
| glowPrimary | `#FF9397` |
| glowSecondary | `#E2BB68` |
| progress | `#A63642` |
| progressTrack | `#FED1D1` |
| textPrimary | `#301D1D` |
| textSecondary | `#705C5C` |
| textTertiary | `#907E7E` |
| border | `#D8C8C7` |
| borderHighlight | `#EBE0E0` |
| navigationSelected | `#B03846` |
| accentProgress | `#B03846` |
| accentStreak | `#007B7F` |
| accentDiscovery | `#8A6100` |
| accentAnnotation | `#984588` |

### Garnet (`rouge`, dark)

| role | hex |
|---|---|
| backgroundBase | `#100305` |
| backgroundSecondary | `#1C060B` |
| surface | `#1E0B0F` |
| surfaceElevated | `#2C191C` |
| primaryAccent | `#FF94A0` |
| secondaryAccent | `#E7B461` |
| tertiaryAccent | `#E6AAE9` |
| warmAccent | `#78DDD7` |
| guillochePrimary | `#D19000` |
| guillocheSecondary | `#C97FCD` |
| guillocheHighlight | `#FF7B8C` |
| glowPrimary | `#F87183` |
| glowSecondary | `#DC9E29` |
| progress | `#FC818F` |
| progressTrack | `#340F14` |
| textPrimary | `#F8EDEE` |
| textSecondary | `#B4A7A9` |
| textTertiary | `#837577` |
| border | `#3C2B2E` |
| borderHighlight | `#211416` |
| navigationSelected | `#FF909E` |
| accentProgress | `#FF96A2` |
| accentStreak | `#02D9D1` |
| accentDiscovery | `#F0B142` |
| accentAnnotation | `#E99EED` |
