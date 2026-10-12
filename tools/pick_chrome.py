import io, re, math, json

P = r"shared/src/composeUi/kotlin/com/folio/reader/ui/theme/FolioArtDirection.kt"
s = io.open(P, encoding="utf-8").read()
blocks = re.findall(r"    FolioArtDirection\(\n(.*?)\n    \),", s, re.S)


def s2l(c):
    c = c / 255.0
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def l2s(c):
    v = 12.92 * c if c <= 0.0031308 else 1.055 * (c ** (1 / 2.4)) - 0.055
    return max(0.0, min(1.0, v))


M1 = [[0.4122214708, 0.5363325363, 0.0514459929],
      [0.2119034982, 0.6806995451, 0.1073969566],
      [0.0883024619, 0.2817188376, 0.6299787005]]
M2 = [[0.2104542553, 0.7936177850, -0.0040720468],
      [1.9779984951, -2.4285922050, 0.4505937099],
      [0.0259040371, 0.7827717662, -0.8086757660]]


def rgb2oklab(r, g, b):
    r, g, b = s2l(r), s2l(g), s2l(b)
    l = M1[0][0] * r + M1[0][1] * g + M1[0][2] * b
    m = M1[1][0] * r + M1[1][1] * g + M1[1][2] * b
    t = M1[2][0] * r + M1[2][1] * g + M1[2][2] * b
    l = math.copysign(abs(l) ** (1 / 3), l)
    m = math.copysign(abs(m) ** (1 / 3), m)
    t = math.copysign(abs(t) ** (1 / 3), t)
    return (M2[0][0] * l + M2[0][1] * m + M2[0][2] * t,
            M2[1][0] * l + M2[1][1] * m + M2[1][2] * t,
            M2[2][0] * l + M2[2][1] * m + M2[2][2] * t)


def oklab2rgb(L, a, b):
    l = L + 0.3963377774 * a + 0.2158037573 * b
    m = L - 0.1055613458 * a - 0.0638541728 * b
    t = L - 0.0894841775 * a - 1.2914855480 * b
    l, m, t = l ** 3, m ** 3, t ** 3
    r = +4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * t
    g = -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * t
    bb = -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * t
    return (l2s(r) * 255, l2s(g) * 255, l2s(bb) * 255)


def hue_of(a, b):
    return (math.degrees(math.atan2(b, a)) + 360) % 360


def chroma_of(a, b):
    return math.hypot(a, b)


SKIP = ("backgroundBase", "backgroundSecondary", "surface", "surfaceElevated")


def parse(body):
    d = {}
    for m in re.finditer(r"(\w+) = 0x([0-9A-Fa-f]{8})", body):
        d[m.group(1)] = int(m.group(2), 16)
    return d


def role_lab(d):
    out = {}
    for k, v in d.items():
        if k in SKIP:
            continue
        out[k] = rgb2oklab((v >> 16) & 255, (v >> 8) & 255, v & 255)
    return out


CHROMATIC_MIN = 0.05
BROWN_LO, BROWN_HI = 20.0, 140.0


def adist(a, b):
    d = abs(a - b) % 360
    return min(d, 360 - d)


def pick(labs, dom, L):
    hues = [hue_of(a, b) for (Lr, a, b) in labs.values() if chroma_of(a, b) >= CHROMATIC_MIN]
    # A candidate is judged by the colour it actually RENDERS as: gamut clipping moves
    # the apparent hue, so a requested 119 can come out olive. Reject on the rendered
    # Oklab hue/chroma, not on the request.
    usable = []
    for h in range(360):
        hf = float(h)
        C = 0.14
        r, g, b = oklab2rgb(L, C * math.cos(math.radians(hf)), C * math.sin(math.radians(hf)))
        Lr, ar, br = rgb2oklab(r, g, b)
        hr, cr = hue_of(ar, br), chroma_of(ar, br)
        if BROWN_LO <= hr <= BROWN_HI:
            continue
        if cr < 0.10:
            continue
        usable.append((hf, hr, cr))
    for need, pair in ((30.0, 45.0), (25.0, 40.0), (22.0, 32.0), (19.0, 26.0), (16.0, 20.0), (13.0, 16.0)):
        cands = []
        for hf, hr, cr in usable:
            dmin = min((adist(hr, x) for x in hues), default=999)
            if dmin < need:
                continue
            cands.append((hf, dmin, adist(hf, dom)))
        cands.sort(key=lambda t: -(t[1] - 0.25 * t[2]))
        chosen = []
        for h, dmin, dd in cands:
            if all(adist(h, c) >= pair for c in chosen):
                chosen.append(h)
            if len(chosen) == 3:
                break
        if len(chosen) == 3:
            return chosen, need
    return chosen, None


faces = []
for b in blocks:
    idm = re.search(r'id = "([^"]+)"', b).group(1)
    light = re.search(r"light = (true|false)", b).group(1) == "true"
    faces.append((idm, light, parse(b)))

out = {}
for idm, light, d in faces:
    labs = role_lab(d)
    Lp, ap, bp = rgb2oklab((d["primaryAccent"] >> 16) & 255, (d["primaryAccent"] >> 8) & 255, d["primaryAccent"] & 255)
    dom = hue_of(ap, bp)
    L = 0.55 if light else 0.75
    chosen, need = pick(labs, dom, L)
    if idm == "gallery":
        chosen, need = [0.0, 0.0, 0.0], 0.0
    if len(chosen) < 3:
        print("FAILED", idm, light, chosen)
        continue
    hexes = []
    if idm == "gallery":
        hexes = [0xFF444444, 0xFF7A7A7A, 0xFF5E5E5E] if light else [0xFFCFCFCF, 0xFF8A8A8A, 0xFFAEAEAE]
    for h in ([] if idm == "gallery" else chosen):
        C = 0.14
        R, G, B = oklab2rgb(L, C * math.cos(math.radians(h)), C * math.sin(math.radians(h)))
        hexes.append(0xFF000000 | (int(round(R)) << 16) | (int(round(G)) << 8) | int(round(B)))
    mins = []
    for v in hexes:
        Ln, an, bn = rgb2oklab((v >> 16) & 255, (v >> 8) & 255, v & 255)
        best = 999.0
        for (Le, ae, be) in labs.values():
            best = min(best, math.sqrt((Ln - Le) ** 2 + (an - ae) ** 2 + (bn - be) ** 2))
        mins.append(best)
    out[f"{idm}:{'L' if light else 'D'}"] = hexes
    print("%-10s %s dom=%5.0f need=%s hues=%s minDE=%s" % (
        idm, "L" if light else "D", dom, need, [round(c) for c in chosen], [round(m, 3) for m in mins]))

json.dump(out, io.open("chrome_pick.json", "w"))
print("faces:", len(out))
