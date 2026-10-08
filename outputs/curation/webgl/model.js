/*
 * The Kotlin model, ported. Every function here mirrors a named function in the repository
 * and is annotated with where it lives, so a disagreement is a bug in one identifiable
 * place rather than in "the renderer".
 *
 *   guillocheSeed / GuillocheStream / guillocheSpecFor   GuillocheModel.kt
 *   saturate / roomHue / oklabHue / tierColour / ...     FolioAtmosphere.kt
 *   guillocheTiersFor / guillocheWeightFor               GuillocheModel.kt
 *   atmosphereFor                                        FolioAtmosphere.kt
 *   fieldColors / fieldT / poolAlphaAt / poolCenterAt    FolioFieldModel.kt
 *
 * Colours are [r, g, b] in sRGB, 0..1, matching Compose's `Color.red/green/blue`.
 */

// ── the model constants, read out of GuillocheModel.kt by extract.py ──────────
const MODEL = window.FOLIO.agsl.model;
const FACES = window.FOLIO.faces;
const FACES_BY_KEY = new Map(FACES.faces.map((f) => [`${f.family}:${f.light}`, f]));

// ── colour plumbing ───────────────────────────────────────────────────────────

const hexToRgb = (hex) => {
  const v = typeof hex === "string" ? parseInt(hex, 16) : hex;
  return [((v >> 16) & 255) / 255, ((v >> 8) & 255) / 255, (v & 255) / 255];
};

/** `luminanceOf` — the cheap gamma-space weighting the theme derives with. */
const luminanceOf = (c) => c[0] * 0.2126 + c[1] * 0.7152 + c[2] * 0.0722;

/** `relativeLuminance` — the linearised WCAG one. Not interchangeable with the above. */
const srgbToLinear = (u) => (u <= 0.04045 ? u / 12.92 : Math.pow((u + 0.055) / 1.055, 2.4));
const linearToSrgb = (u) => {
  const v = Math.min(Math.max(u, 0), 1);
  return v <= 0.0031308 ? v * 12.92 : 1.055 * Math.pow(v, 1 / 2.4) - 0.055;
};
const relativeLuminance = (c) =>
  0.2126 * srgbToLinear(c[0]) + 0.7152 * srgbToLinear(c[1]) + 0.0722 * srgbToLinear(c[2]);

const mixG = (a, b, t) => [
  Math.min(Math.max(a[0] + (b[0] - a[0]) * t, 0), 1),
  Math.min(Math.max(a[1] + (b[1] - a[1]) * t, 0), 1),
  Math.min(Math.max(a[2] + (b[2] - a[2]) * t, 0), 1),
];
const deepenG = (c, amount) => mixG(c, [0, 0, 0], amount);
const liftG = (c, amount) => mixG(c, [1, 1, 1], amount);

/** `matchLuma` — move to a target lightness without moving the hue. */
function matchLuma(c, target) {
  const l = luminanceOf(c);
  if (l <= 0.0001) return c;
  if (target <= l) {
    const k = target / l;
    return [c[0] * k, c[1] * k, c[2] * k];
  }
  let lo = 0;
  let hi = 1;
  for (let i = 0; i < 24; i++) {
    const mid = (lo + hi) / 2;
    if (luminanceOf(mixG(c, [1, 1, 1], mid)) < target) lo = mid;
    else hi = mid;
  }
  return mixG(c, [1, 1, 1], (lo + hi) / 2);
}

// ── Oklab ─────────────────────────────────────────────────────────────────────

function oklabAb(c) {
  const r = srgbToLinear(c[0]);
  const g = srgbToLinear(c[1]);
  const b = srgbToLinear(c[2]);
  const l = 0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b;
  const m = 0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b;
  const s = 0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b;
  const lc = Math.cbrt(l);
  const mc = Math.cbrt(m);
  const sc = Math.cbrt(s);
  return [
    1.9779984951 * lc - 2.428592205 * mc + 0.4505937099 * sc,
    0.0259040371 * lc + 0.7827717662 * mc - 0.808675766 * sc,
  ];
}

function oklabHue(c) {
  const [oa, ob] = oklabAb(c);
  return (((Math.atan2(ob, oa) * 180) / Math.PI) + 360) % 360;
}

function oklabChroma(c) {
  const [oa, ob] = oklabAb(c);
  return Math.sqrt(oa * oa + ob * ob);
}

/** Oklab -> linear sRGB, unclamped, so gamut membership can be tested. */
function oklabToLinear(l, a, b) {
  const lc = l + 0.3963377774 * a + 0.2158037573 * b;
  const mc = l - 0.1055613458 * a - 0.0638541728 * b;
  const sc = l - 0.0894841775 * a - 1.291485548 * b;
  const l3 = lc * lc * lc;
  const m3 = mc * mc * mc;
  const s3 = sc * sc * sc;
  return [
    4.0767416621 * l3 - 3.3077115913 * m3 + 0.2309699292 * s3,
    -1.2684380046 * l3 + 2.6097574011 * m3 - 0.3413193965 * s3,
    -0.0041960863 * l3 - 0.7034186147 * m3 + 1.707614701 * s3,
  ];
}

function oklchToColor(l, chroma, hueDeg) {
  const rad = (hueDeg * Math.PI) / 180;
  const rgb = oklabToLinear(l, chroma * Math.cos(rad), chroma * Math.sin(rad));
  return [linearToSrgb(rgb[0]), linearToSrgb(rgb[1]), linearToSrgb(rgb[2])];
}

function maxChromaInGamut(l, hueDeg) {
  const rad = (hueDeg * Math.PI) / 180;
  const ca = Math.cos(rad);
  const sa = Math.sin(rad);
  const holds = (c) => {
    const rgb = oklabToLinear(l, c * ca, c * sa);
    return rgb.every((v) => v >= -1e-6 && v <= 1 + 1e-6);
  };
  if (!holds(0)) return 0;
  let lo = 0;
  let hi = 0.45;
  for (let i = 0; i < 30; i++) {
    const mid = (lo + hi) / 2;
    if (holds(mid)) lo = mid;
    else hi = mid;
  }
  return lo;
}

/** `tierColour` — place a tier at a lightness and give it a hue's own colour. */
function tierColour(gammaLuma, hueDeg, chromaTarget) {
  const target = Math.min(Math.max(gammaLuma, 0.001), 0.999);
  const affordable = (l) => Math.min(chromaTarget, maxChromaInGamut(l, hueDeg) * MODEL.GAMUT_SAFETY);
  const solveL = (chroma) => {
    let lo = 0;
    let hi = 1;
    for (let i = 0; i < 40; i++) {
      const mid = (lo + hi) / 2;
      if (luminanceOf(oklchToColor(mid, chroma, hueDeg)) < target) lo = mid;
      else hi = mid;
    }
    return (lo + hi) / 2;
  };
  let l = Math.cbrt(srgbToLinear(target));
  let chroma = affordable(l);
  for (let i = 0; i < 4; i++) {
    l = solveL(chroma);
    chroma = affordable(l);
  }
  return oklchToColor(l, chroma, hueDeg);
}

const signedHueDelta = (to, from) => {
  let d = (to - from) % 360;
  if (d > 180) d -= 360;
  if (d < -180) d += 360;
  return d;
};

/** `roomHue` — NaN when the room has no hue, which is load-bearing, not defensive. */
const roomHue = (colors) =>
  oklabChroma(colors.background) < MODEL.ROOM_HUE_MIN_CHROMA
    ? NaN
    : oklabHue(colors.background);

const withHue = (c, hueDeg) => tierColour(luminanceOf(c), hueDeg, oklabChroma(c));

// ── Oklab in full, for the glass tint ────────────────────────────────────────
// `oklabAb` above returns only (a, b) because that is all the hue maths needs. The glass
// tint has to control L separately from the chroma, so it needs all three.

function oklab(c) {
  const [a, b] = oklabAb(c);
  const [r, g, bl] = [srgbToLinear(c[0]), srgbToLinear(c[1]), srgbToLinear(c[2])];
  const l = 0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * bl;
  const m = 0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * bl;
  const s = 0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * bl;
  return [0.2104542553 * Math.cbrt(l) + 0.793617785 * Math.cbrt(m) - 0.0040720468 * Math.cbrt(s),
          a, b];
}

function fromOklab(L, a, b) {
  const rgb = oklabToLinear(L, a, b);
  return [linearToSrgb(rgb[0]), linearToSrgb(rgb[1]), linearToSrgb(rgb[2])];
}

/**
 * `G = (1 - tau) B + tau P`, with the chroma and the lightness mixed at *different* rates.
 *
 * That split is the whole point of doing this in Oklab rather than in RGB. Glass should gain
 * the chrome's hue and chroma without gaining its lightness -- a big lightness shift is what
 * makes a tinted pane read as a painted rectangle instead of as coloured glass. So `tau` (and
 * the boost `k`) drive the (a, b) mix, and `lambda` -- much smaller -- drives L.
 *
 * `tintGlass(b, p, 0, k, l)` returns `b` exactly, which is what makes the untinted reference
 * render for `GCC` an actual control rather than an approximation of one.
 */
function tintGlass(base, accent, tau, k, lambda) {
  if (tau <= 0) return base.slice();
  const [Lb, ab, bb] = oklab(base);
  const [La, aa, ba] = oklab(accent);
  const a = (1 - tau) * ab + tau * k * aa;
  const b = (1 - tau) * bb + tau * k * ba;
  const L = (1 - lambda) * Lb + lambda * La;
  return fromOklab(L, a, b);
}

/**
 * The glass parameters. Alpha is deliberately NOT one of them: the contract's whole argument
 * is that colour strength should come from the material's tint, not from making the pane more
 * opaque. Alpha stays `topBarFill`'s.
 */
// `lambda` is set by the contract's own lightness-preservation clause: |L_glass - L_untinted|
// <= 0.08. The page-to-accent lightness gap is ~0.52 on a dark face, so lambda must stay under
// ~0.15 for that clause to hold; 0.10 keeps it at ~0.052 with margin. The chroma coefficient
// (tau * k = 0.44) is more than four times lambda, which is the asymmetry the contract asks for.
/**
 * The most Oklab chroma the sRGB gamut holds at this lightness and hue. Mirrors
 * `maxChromaInGamut` in `FolioAtmosphere.kt`.
 */
function maxChromaInGamut(l, hueDeg) {
  const rad = hueDeg * Math.PI / 180, ca = Math.cos(rad), sa = Math.sin(rad);
  const holds = (c) => oklabToLinear(l, c * ca, c * sa)
    .every((v) => v >= -1e-6 && v <= 1 + 1e-6);
  if (!holds(0)) return 0;
  let lo = 0, hi = 0.45;
  for (let i = 0; i < 30; i++) {
    const mid = (lo + hi) / 2;
    if (holds(mid)) lo = mid; else hi = mid;
  }
  return lo;
}

/**
 * The crown's chromatic glass material. Mirrors `chromeGlassMaterial` in `FolioAtmosphere.kt`:
 * the accent's hue, placed at a lightness the gamut can hold chroma at, chroma clamped to what
 * it holds there. The lightness placement is the whole fix -- a plane at the page's lightness
 * cannot carry chroma at all, which is what the gamut sweep proved.
 */
const BAR_GLASS_L_LIGHT = 0.58, BAR_GLASS_L_DARK = 0.65, BAR_GLASS_CHROMA = 0.16;
const GAMUT_SAFETY_JS = 0.96;
const BAR_GLASS_MATERIAL_SHARE = 0.90;

function chromeGlassMaterial(accent, dark) {
  const l = dark ? BAR_GLASS_L_DARK : BAR_GLASS_L_LIGHT;
  const h = oklabHue(accent);
  const c = Math.min(BAR_GLASS_CHROMA, maxChromaInGamut(l, h) * GAMUT_SAFETY_JS);
  return fromOklab(l, c * Math.cos(h * Math.PI / 180), c * Math.sin(h * Math.PI / 180));
}

/** Oklab mix, (a, b) interpolated as a vector so the hue stays coherent. Mirrors `mixOklab`. */
/**
 * The card material. Mirrors `cardGlassMaterial` in `FolioAtmosphere.kt`: the chrome hue at the
 * card's *own* lightness, chroma clamped to the gamut there. Same family as the bar, different
 * placement -- the bar is chosen for chroma, the card stays a card.
 */
const CARD_GLASS_CHROMA = 0.10, CARD_MATERIAL_SHARE = 0.80;
const cardChromeOff = () =>
  typeof window !== "undefined" && window.FOLIO_CARD_OFF === true;

function cardGlassMaterial(accent, base) {
  const l = oklab(base)[0];
  const h = oklabHue(accent);
  const c = Math.min(CARD_GLASS_CHROMA, maxChromaInGamut(l, h) * GAMUT_SAFETY_JS);
  return fromOklab(l, c * Math.cos(h * Math.PI / 180), c * Math.sin(h * Math.PI / 180));
}

function mixOklab(a, b, share) {
  const [La, aa, ba] = oklab(a);
  const [Lb, ab, bb] = oklab(b);
  const x = aa + (ab - aa) * share, y = ba + (bb - ba) * share;
  const L = La + (Lb - La) * share;
  return fromOklab(L, x, y);
}

const GLASS = {
  light: {tau: 0.18, k: 1.25, lambda: 0.08, tauEdge: 0.05, tauSheen: 0.03},
  dark: {tau: 0.30, k: 1.45, lambda: 0.10, tauEdge: 0.06, tauSheen: 0.04},
};

// ── HSV, matching CoverAccent.rgbToHsv and Compose's Color.hsv ────────────────

function rgbToHsv(c) {
  const [r, g, b] = c;
  const maxc = Math.max(r, g, b);
  const minc = Math.min(r, g, b);
  const d = maxc - minc;
  let h;
  if (d <= 0) h = 0;
  else if (maxc === r) {
    const raw = 60 * (((g - b) / d) % 6);
    h = raw < 0 ? raw + 360 : raw;
  } else if (maxc === g) h = 60 * ((b - r) / d + 2);
  else h = 60 * ((r - g) / d + 4);
  return [h, maxc <= 0 ? 0 : d / maxc, maxc];
}

function hsvToRgb(h, s, v) {
  const hp = (((h % 360) + 360) % 360) / 60;
  const c = v * s;
  const x = c * (1 - Math.abs((hp % 2) - 1));
  const m = v - c;
  const seg = Math.floor(hp) % 6;
  const rgb = [[c, x, 0], [x, c, 0], [0, c, x], [0, x, c], [x, 0, c], [c, 0, x]][seg];
  return [rgb[0] + m, rgb[1] + m, rgb[2] + m];
}

/** `saturate` — scale HSV saturation, hue and value untouched. */
function saturate(c, factor) {
  const [h, s, v] = rgbToHsv(c);
  return hsvToRgb(h, Math.min(Math.max(s * factor, 0), 1), v);
}

// ── the plate's seed, spec and colour ─────────────────────────────────────────

/** `guillocheSeed` — FNV-1a over the pack id, folded to 31 bits. */
function guillocheSeed(packId) {
  let h = 2166136261n;
  const PRIME = 16777619n;
  const MASK32 = 0xffffffffn;
  for (let i = 0; i < packId.length; i++) {
    h = h ^ BigInt(packId.charCodeAt(i) & 0xff);
    h = (h * PRIME) & MASK32;
  }
  let x = (h ^ (h >> 31n)) & MASK32;
  x = x & 0x7fffffffn;
  x = (x ^ (x >> 15n)) & MASK32;
  x = (x * 0x2545f491n) & MASK32;
  x = (x ^ (x >> 13n)) & MASK32;
  return Number(x & 0x7fffffffn);
}

/** `GuillocheStream` — xorshift32, integer-only, identical on every platform. */
class GuillocheStream {
  constructor(seed) {
    this.state = seed === 0 ? 0x1234567 : seed;
  }
  next() {
    let x = this.state | 0;
    x = (x ^ (x << 13)) | 0;
    x = (x ^ (x >>> 17)) | 0;
    x = (x ^ (x << 5)) | 0;
    this.state = x & 0x7fffffff;
    if (this.state === 0) this.state = 0x1234567;
    return (this.state & 0xffff) / 65536;
  }
  range(lo, hi) {
    return lo + (hi - lo) * this.next();
  }
}

const GEOMETRY_ORDER = FACES.geometryOrder;

/** `guillocheSpecFor` — the plate for one theme. */
function guillocheSpecFor(packId, light, geometryName) {
  const seed = guillocheSeed(packId);
  const rng = new GuillocheStream(seed);
  const ch = FACES.character[geometryName];
  const base = Math.atan2(light.sourceY - 0.5, light.sourceX - 0.5);
  const character = Math.min(Math.max((light.lean + light.hueRange) * 0.5, 0), 1);
  const walkSign = (seed & 1) === 0 ? 1 : -1;

  const waveWalkSign = (index, baseSign, draw) =>
    index === 0 ? baseSign : index === 1 ? -baseSign : draw < 0.5 ? baseSign : -baseSign;

  const waves = [];
  for (let i = 0; i < MODEL.GUILLOCHE_WAVES; i++) {
    const freqBase =
      (i === 0 ? rng.range(4.6, 6.4) : i === 1 ? rng.range(9.0, 12.5) : rng.range(16.0, 22.0)) *
      ch[0] *
      MODEL.GUILLOCHE_STRUCTURE_SCALE;
    const cross = rng.range(1.9, 3.1) * (1 + 0.45 * character) * ch[1];
    const crossFreq = rng.range(0.8, 1.9);
    const angle = base + (i - 1) * rng.range(0.42, 0.78);
    const weight = ch[2] * rng.range(0.85, 1.15);
    const share = i === 0 ? 1.0 : i === 1 ? 0.45 : 0.25;
    const drift = (i + 1) * waveWalkSign(i, walkSign, rng.next());
    waves.push({ frequency: freqBase, cross, crossFreq, angle, weight, share, drift });
  }

  const fanX = Math.min(Math.max(light.sourceX, 0.72), 0.92);
  const fanY = Math.min(Math.max(light.sourceY, 0.06), 0.22);
  const fan = {
    x: fanX < 0.5 ? 0.86 : fanX,
    y: fanY,
    rays: Math.round(20 + 14 * character + rng.range(-2, 2)),
    reach: (0.44 + 0.16 * character) * Math.max(ch[3], 0.5),
    share: (0.72 + 0.28 * character) * ch[3],
    spin: (seed & 1) === 0 ? 2 : -2,
  };

  return { waves, fan, periodsMs: MODEL.GUILLOCHE_PERIODS_MS, geometry: geometryName };
}

/**
 * `guillocheTiersFor` — the plate's three authored hues, re-anchored as a *set* to within
 * MAX_GUILLOCHE_HUE_DEV of the room. A per-thread clamp would collapse all three onto one
 * colour when they all sit beyond the bound on the same side, which is the defect this
 * scaling form exists to avoid.
 */
function guillocheTiersFor(colors, isDark) {
  const boost = isDark ? 1.15 : 1.25;
  const lifted = [colors.guillochePrimary, colors.guillocheSecondary, colors.guillocheHighlight]
    .map((c) => saturate(c, boost));
  const room = roomHue(colors);
  if (Number.isNaN(room)) return lifted;
  const offsets = lifted.map((c) => signedHueDelta(oklabHue(c), room));
  const span = Math.max(...offsets.map(Math.abs));
  const scale = span <= MODEL.MAX_GUILLOCHE_HUE_DEV || span === 0
    ? 1
    : MODEL.MAX_GUILLOCHE_HUE_DEV / span;
  return lifted.map((c, i) => withHue(c, (room + offsets[i] * scale + 360) % 360));
}

const guillocheWeightFor = (isDark) =>
  isDark ? MODEL.GUILLOCHE_WEIGHT_DARK : MODEL.GUILLOCHE_WEIGHT_LIGHT;

// ── the material ladder and the chrome plane ──────────────────────────────────
// FolioAtmosphere.kt. `card`/`panel`/`well` are authored planes; the rest are the three
// tiers plus the two pieces of chrome that sit over content.

function materialsFor(colors, atmos, isLight) {
  const planeHue = roomHue(colors);
  const card = colors.surface;
  const panel = colors.surfaceElevated;
  const well = mixG(colors.surface, colors.background, 0.45);
  const navPlane = colors.navigationSurface;
  const A = window.FOLIO.agsl.atmosphere;
  const dark = atmos.isDark;

  // `tierHue` -- bound a role's hue to within MAX_TIER_HUE_DEV of the room's own.
  const bounded = (hue) => (Number.isNaN(planeHue)
    ? hue
    : (planeHue + Math.min(Math.max(signedHueDelta(hue, planeHue), -A.MAX_TIER_HUE_DEV),
                           A.MAX_TIER_HUE_DEV) + 360) % 360);

  return {
    card,
    panel,
    well,
    navPlane,
    // `raisedFill = card`, `panelFill = panel` -- the ladder is authored, not derived.
    raisedFill: cardChromeOff()
      ? card
      : mixOklab(card, cardGlassMaterial(colors.primaryAccent, card), CARD_MATERIAL_SHARE),
    panelFill: cardChromeOff()
      ? panel
      : mixOklab(panel, cardGlassMaterial(colors.primaryAccent, panel), CARD_MATERIAL_SHARE),
    // A recess is a layer, not a colour: a dark tint at low alpha in front of the theme.
    sunkenFill: dark ? [...well, 0.30] : [...well, 1],
    // The hero's pane: the room shows through it.
    paneAlpha: dark ? 0.46 : 0.66,
    // The chromatic glass plane, mirroring `atmosphereFor`: the page-derived base blended
    // toward a material placed where the gamut can hold chroma.
    barGlass: [...mixOklab(
      dark ? mixG(atmos.fieldTop, navPlane, 0.35) : mixG(liftG(atmos.fieldTop, 0.42), navPlane, 0.20),
      chromeGlassMaterial(colors.primaryAccent, dark),
      BAR_GLASS_MATERIAL_SHARE,
    ), dark ? 0.36 : 0.40],
    barScrim: dark
      ? [...tierColour(luminanceOf(deepenG(colors.background, 0.30)), bounded(oklabHue(colors.primary)), A.SCRIM_CHROMA), 0.46]
      : [...tierColour(luminanceOf(deepenG(liftG(colors.surface, 0.72), A.PAPER_CHROME_DROP)), bounded(oklabHue(colors.primary)), A.SCRIM_CHROMA), 0.42],
    // Dark surfaces emit at the rim; light surfaces catch a white sheen.
    rimLight: dark
      ? [...tierColour(luminanceOf(liftG(colors.surface, 0.34)), bounded(oklabHue(colors.secondary)), A.RIM_CHROMA), 0.62]
      : [1, 1, 1, 0.92],
    rimShade: dark ? [0, 0, 0, 0.40] : [...deepenG(colors.outline, 0.10), 0.38],
    hairline: colors.border,
  };
}

// ── FolioSurfaceOpacity.kt ────────────────────────────────────────────────────

const OPACITY = window.FOLIO.agsl.opacity;

/**
 * `glassTierAlpha` — the knob's contract: 1 is a lid, and below the designed point the knob
 * keeps its proportional meaning. Only the designed point moves when a site can blur.
 */
function glassTierAlpha(knob, designPoint, glassPoint) {
  const k = Math.min(Math.max(knob, OPACITY.MIN), 1);
  if (k >= 1) return 1;
  const point = Math.min(Math.max(designPoint, OPACITY.MIN), 1);
  return k <= point
    ? Math.min(Math.max(k * (glassPoint / point), OPACITY.MIN), Math.min(glassPoint, 1))
    : glassPoint + (1 - glassPoint) * ((k - point) / (1 - point));
}

const navCapsuleFill = (canBlur) =>
  canBlur ? glassTierAlpha(OPACITY.NAV_GLASS, OPACITY.NAV_GLASS, OPACITY.NAV_GLASS_BLURRED)
          : OPACITY.NAV_GLASS;

const panelFillAlpha = (canBlur) =>
  canBlur ? glassTierAlpha(OPACITY.PANEL_GLASS, OPACITY.PANEL_GLASS, OPACITY.PANEL_GLASS_BLURRED)
          : OPACITY.PANEL_GLASS;

/**
 * `topBarFill` — the masthead's gradient stops. At rest (collapse 0) the bar has no fill at
 * all beyond the scrim; glass fades in as content passes underneath.
 */
function topBarFill(collapse, blurred) {
  const knob = Math.min(Math.max(
    blurred
      ? glassTierAlpha(OPACITY.BAR_GLASS, OPACITY.BAR_GLASS, OPACITY.BAR_GLASS_BLURRED)
      : OPACITY.BAR_GLASS,
    OPACITY.MIN), 1);
  const f = Math.min(Math.max(collapse, 0), 1);
  const solid = Math.min(Math.max((knob - OPACITY.BAR_GLASS) / (1 - OPACITY.BAR_GLASS), 0), 1);
  const presence = Math.max(f, solid);
  const crown = knob * presence;
  return {
    crown,
    waist: crown * (0.52 + (1 - 0.52) * solid),
    foot: crown * (0.19 + (1 - 0.19) * solid),
    scrim: Math.min(knob / OPACITY.BAR_GLASS, 1),
    presence,
  };
}

// ── the atmosphere and the field ──────────────────────────────────────────────

/** `atmosphereFor`, reduced to the values the ground plane actually draws with. */
function atmosphereFor(colors, light) {
  const dark = luminanceOf(colors.background) < 0.45;
  return {
    isDark: dark,
    fieldTop: colors.background,
    fieldBottom: dark
      ? mixG(colors.background, [0, 0, 0], 0.32)
      : mixG(colors.backgroundSecondary, [0, 0, 0], 0.07),
    fieldSourceX: light.sourceX,
    fieldSourceY: light.sourceY,
    pools: [colors.glowPrimary, colors.glowSecondary, colors.warmAccent],
    poolAlpha: dark ? 0.09 : 0.08,
    ink: colors.onSurface,
  };
}

/** DAYLIGHT_POOLS, FolioFieldModel.kt. */
const DAYLIGHT_POOLS = [
  { fx: -0.15, fy: -0.10, fr: 1.15, index: 0, sideX: -1 },
  { fx: 1.10, fy: 0.28, fr: 0.95, index: 1, sideX: 1 },
  { fx: 0.35, fy: 1.15, fr: 1.30, index: 2, sideX: 0 },
];

/** `fieldColors` with no tint: the palette's own room, byte for byte. */
const fieldColors = (atmos) => ({
  top: atmos.fieldTop,
  bottom: atmos.fieldBottom,
  poolColors: atmos.pools,
  poolAlpha: atmos.poolAlpha,
  sourceX: atmos.fieldSourceX,
  sourceY: atmos.fieldSourceY,
});

// ── assembling one face ───────────────────────────────────────────────────────

/**
 * `FolioArtDirection.toFolioColors` — the authored roles pass through byte-for-byte and
 * only the relationships are computed. Only the relationships the ground plane and the
 * plate actually read are derived here; the rest are `mixA`-shaped and unused by this pass.
 */
function toFolioColors(roles, isLight) {
  const c = {};
  for (const [role, hex] of Object.entries(roles)) c[role] = hexToRgb(hex);
  const page = c.backgroundBase;
  const page2 = c.backgroundSecondary;
  const card = c.surface;
  const ink = c.textPrimary;
  return Object.assign(c, {
    // The renames. `FolioColors.background` is the art direction's `backgroundBase`, and
    // `onSurface` is its `textPrimary` — reading either by the art-direction name silently
    // yields `undefined`, which is exactly the bug this mapping exists to prevent.
    background: page,
    onBackground: ink,
    onSurface: ink,
    surfaceVariant: page2,
    onSurfaceVariant: c.textSecondary,
    surfaceContainerHighest: c.surfaceElevated,
    outline: c.border,
    outlineVariant: c.borderHighlight,
    // The four accents, under the names `FolioColors` gives them. `folioAmbientShader` is
    // called with `primary` and `tertiary`, so a missing alias here is not a cosmetic
    // problem — it hands the shader a NaN and the pass draws nothing, silently.
    primary: c.primaryAccent,
    secondary: c.secondaryAccent,
    tertiary: c.tertiaryAccent,
    // `mixA(card, page2, if (light) 0.45f else 0.35f)`
    navigationSurface: mixG(card, page2, isLight ? 0.45 : 0.35),
  });
}

/**
 * A colour the render needs but the mapping did not produce. Throwing is deliberate: the
 * spec's own rule is that a renderer silently ignoring an unsupported input is an API
 * failure, and a `undefined` that reaches a `uniform3f` becomes a NaN that draws nothing.
 */
function requiredColour(colors, role) {
  const c = colors[role];
  if (!c || c.some((v) => !Number.isFinite(v))) {
    throw new Error(`role \`${role}\` is missing or not finite — the colour mapping has a gap`);
  }
  return c;
}

function buildFace(family, light) {
  const key = `${family}:${light}`;
  const face = FACES_BY_KEY.get(key);
  if (!face) throw new Error(`no such face: ${key}`);
  const colors = toFolioColors(face.roles, light);
  // Everything the ground plane and the two shaders read, checked once, here, rather than
  // discovered as a NaN in a uniform.
  ["background", "backgroundSecondary", "onSurface", "primary", "secondary", "tertiary",
   "guillochePrimary", "guillocheSecondary", "guillocheHighlight",
   "glowPrimary", "glowSecondary", "warmAccent"].forEach((r) => requiredColour(colors, r));
  const lightParams = face.lightParams;
  const atmos = atmosphereFor(colors, lightParams);
  const spec = guillocheSpecFor(face.geometryKey, lightParams, face.geometry);
  const tiers = guillocheTiersFor(colors, atmos.isDark);
  return {
    family, light, face, colors, lightParams, atmos, spec, tiers,
    field: fieldColors(atmos),
    materials: materialsFor(colors, atmos, light),
    weight: guillocheWeightFor(atmos.isDark),
    intensity: MODEL.GUILLOCHE_ALPHA_MAX,
  };
}

window.FOLIO_MODEL = {
  MODEL, FACES, buildFace, guillocheSeed, guillocheSpecFor, guillocheTiersFor,
  guillocheWeightFor, atmosphereFor, fieldColors, DAYLIGHT_POOLS,
  hexToRgb, luminanceOf, relativeLuminance, oklabHue, oklabChroma, roomHue,
  signedHueDelta, matchLuma, mixG, tierColour,
  materialsFor, glassTierAlpha, navCapsuleFill, panelFillAlpha, topBarFill, OPACITY,
  oklab, fromOklab, tintGlass, GLASS,
};
