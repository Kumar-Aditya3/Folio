# What each swatch is — the role assignment

**This is an authored art decision, not a derivation.** The sheet gives each face six
painted colours. Six colours do not fill twenty-four roles, so the decision is *what each of
the six is* — and then the roles that are genuinely relational (an ink ladder, an edge, a
container) are mixed from them.

Read with `docs/theme-art-direction-sheet.md` (the hexes) and `sheet.py` (the machine copy).

---

## The uniform reading

The sheet's own order already implies the jobs, because the order is the same on all
eighteen faces and the swatches are *chosen* to be those jobs.

### Light face — six swatches, in sheet order

| # | swatch | role | why it is that swatch |
|---|---|---|---|
| 1 | **page** | `backgroundBase` | the room. Every light page on the sheet is a cream near-white, so the light room is cream and the *theme* is not the page |
| 2 | **ink** | `textPrimary` | the darkest swatch, and the only one dark enough to read as text. It is a *tinted* near-black — navy, violet, green, charcoal — which is where a light theme's colour actually lives |
| 3 | **signature** | `primaryAccent` | the face's own colour: the one the family is named for |
| 4 | **sup 1** | `tertiaryAccent` | the four shelf tiles. `tertiary` is the role Home actually reads as a large block, so the *largest* supporting swatch goes here |
| 5 | **sup 2** | `secondaryAccent` | the seven week bars |
| 6 | **sup 3** | `warmAccent` | the §30 warm note |

### Dark face — six swatches, in sheet order

| # | swatch | role | why it is that swatch |
|---|---|---|---|
| 1 | **page** | `backgroundBase` | the room: a near-black carrying a whisper of the family's hue |
| 2 | **accent A** | `primaryAccent` | the luminous form of the signature |
| 3 | **accent B** | `secondaryAccent` | the second voice, and the week bars |
| 4 | **sup 1** | `tertiaryAccent` | the shelf tiles |
| 5 | **sup 2** | `warmAccent` | the warm note |
| 6 | **sup 3** | *the sixth is read, not assumed* | on seven families it is a dark plane (`#2A2F4A`, `#1F2A22`, `#2A3136`, `#2A333B`, `#2A2036`, `#252A28`, `#2A2F36`) — a near-black one step **up** from the page, which is exactly a card. On `ocean` and `moonstone` it is a light colour (`#E6F4F1`, `#F8F6EC`) — those two are the **ink**, and their card is mixed from the page instead |

That last row is the one judgement the sheet forces, and it is why this is a reading rather
than a formula: a dark face's sixth swatch is a *plane* on seven families and an *ink* on
two, and the two are told apart by whether it is dark or light.

### The one rule that is not a reading

Home has **seven** places that want their own hue — the four shelf tiles, the seven week
bars, the two ledger numerals, the fourth nav dot — and the sheet gives six colours. So one
of the seven is derived: the nav's fourth dot takes sup 1 **turned 45°** in Oklab, holding
its lightness and chroma, so it stays inside the palette rather than being invented.

And the tiles and the bars must never land on the same swatch. `solar`'s dark face is the
one family where the sheet itself collides (`#E08A52` and `#E17A3F`, 4° apart); the bars
move, because the tiles are the larger block, and they turn toward the plum the sheet
already carries rather than to a new hue.


### What is mixed, not chosen

The remaining roles are relationships, and the sheet says so by not painting them:

- `backgroundSecondary` — the second page plane: **page leaning 14% toward the signature**.
  The page has to have somewhere to go, and the accent is the only direction it can go that
  is not itself.
- `surface` / `surfaceElevated` — the card. On paper it is the page **lifted toward white**
  (a pane is brighter than the sheet it lies on); on a dark face it is the page lifted a
  step, or the sixth swatch when that swatch is a plane.
- `textSecondary` / `textTertiary` — the ink **laid back into the page** (42% / 62%). One
  ink, three readings, which is what a text ladder is.
- `border` / `borderHighlight` — the ink laid back further (74% / 88%). An edge is an ink
  that has almost given up.
- `guillochePrimary / Secondary / Highlight` — signature, sup 1, sup 2. The plate is three
  threads; the sheet gives three voices.
- `glowPrimary / Secondary` — signature and sup 1: the room is lit by the colours it was
  named for.
- `progress` / `progressTrack` — the signature, and the page leaning 22% toward it. The
  drawn bar is then pushed only as far as 3:1 against its own track, because a 3dp bar is a
  graphical object and that is its floor.
- `navigationSelected` — the signature.
- `accentProgress / Streak / Discovery / Annotation` — signature, warm, sup 2, sup 1, each
  pushed toward the page's ink only as far as AA requires, because these four are read as
  *text* on the card.

---

## Per-family notes

Nine families, each with a reason for the order it got.

| family | sheet name | the reading |
|---|---|---|
| `ocean` | Aurora Ocean | page cream, ink navy, signature **teal** `#3D8C8C`, then mint / mauve / coral. The two swatches that are the *same* teal (`#3D8C8C` at #3 and in the dark row) are the point: the family's signature does not change between faces, only its luminosity. |
| `nebula` | Nebula Electric | signature **violet** `#7956C7` light / `#A78BFA` dark, with a magenta second (`#E6A5FE`) and a lime surprise (`#B7D94C`). The ink is a violet near-black `#211B32`. |
| `forest` | Bioluminescent Forest | signature **moss** `#708B62` — not a bright green. The light is the *undergrowth*: sage, teal, gold. The dark face's signature is the bioluminescence itself, `#4ED1C6` mint on `#0E1A17`. |
| `terracotta` | Terracotta Tide | signature **terracotta** `#C56748` on cream, with a **deep teal** `#286B6D` as its counter — the one pair on the sheet that is a true split-complement. The dark face keeps the teal (`#2B7A7D`) and lets the clay go luminous (`#E08A65`). |
| `arctic` | Arctic Glass | the *quietest* family: signature is a **pale blue** `#A7CED8`, and the supporting three (lilac, mint, peach) are all at the same low chroma. Ice is not saturated. |
| `solar` | Solar Eclipse | the only family whose light signature is a **dark blue** `#344E78` — the sky behind the eclipse — with the sun as the supporting row: orange `#C96B3D`, gold `#D5A833`, wine `#B14F68`. On the dark face the two swap roles: the sky becomes `#3E5C8E` and the corona takes the signature `#E17A3F`. |
| `jade` | Jade Noir | signature **jade** `#3E7565` on a cream page, ink `#17211F`, supporting bronze / slate / rust. **The dark face is the family's real statement**: `#4ABF7B` jade + `#D5B66A` gold on `#0F1412` noir. The live app ships magenta here; the sheet does not. |
| `mintcoral` | Mint & Coral Editorial | signature **mint** `#91C8B6` with **coral** `#E47D68` as the counter and a blue `#526FA8` third — an editorial trio, not a two-colour joke. |
| `moonstone` | Moonstone | the *softest* family: signature is a **mauve** `#B29BA8`, and the supporting row is dusty rose / gold / plum. Its dark face is the only other one whose sixth swatch is an ink (`#F8F6EC`), which is right — moonstone is the one room that should read as light even at night. |

---

## Where this lands

`shared/src/composeUi/kotlin/com/folio/reader/ui/theme/FolioArtDirection.kt` — the eighteen
faces are regenerated from `sheet.py` by `.workbuddy-ai/tmp/curate/assign.py`, so the sheet's
hexes are in the source verbatim and cannot drift from it. The script is idempotent: run it
again and it reproduces the same file.

The System pair (`LightFolioColors` / `DarkFolioColors`) is **not** on the sheet and is not
touched by the script. It stays the app's own default — which is why a neutral graphite page
is right there and only there.
