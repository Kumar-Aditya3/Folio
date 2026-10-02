import re, sys

SRC = open(r'shared/src/composeUi/kotlin/com/folio/reader/ui/theme/Theme.kt', encoding='utf-8').read()

# ── parse `val Name = FolioColors(...)` and `val Name = Base.copy(...)` ──
defs = {}
for m in re.finditer(r'(?:private\s+)?val\s+(\w+)\s*=\s*FolioColors\((.*?)\n\)', SRC, re.S):
    defs[m.group(1)] = (None, dict(re.findall(r'(\w+)\s*=\s*Color\(0x([0-9A-Fa-f]{8})\)', m.group(2))))
for m in re.finditer(r'(?:private\s+)?val\s+(\w+)\s*=\s*(\w+)\.copy\((.*?)\n\)', SRC, re.S):
    body = m.group(3)
    ov = {}
    for k, hexv in re.findall(r'(\w+)\s*=\s*Color\(0x([0-9A-Fa-f]{8})\)', body):
        ov[k] = hexv
    defs[m.group(1)] = (m.group(2), ov)

def resolve(name, seen=()):
    base, ov = defs[name]
    if base is None:
        d = dict(ov)
    else:
        if name in seen: raise RuntimeError(name)
        d = dict(resolve(base, seen + (name,)))
    d.update(ov)
    return d

# AppPalette entries: ID("id","Label",isDark,ColorsVar)
entries = re.findall(r'\w+\("([\w-]+)",\s*"[^"]*",\s*(true|false),\s*(\w+)\)', SRC)

FIELDS = ('background', 'surface', 'surfaceVariant', 'primary', 'tertiary')

def rgb(h):
    v = int(h, 16)
    return (((v >> 16) & 255) / 255.0, ((v >> 8) & 255) / 255.0, (v & 255) / 255.0)

def lum_of(c):  # sRGB-weighted, gamma values (matches luminanceOf)
    return c[0] * 0.2126 + c[1] * 0.7152 + c[2] * 0.0722

def rel_lum(c):  # WCAG linear (matches the test's luminance)
    def ch(v):
        return v / 12.92 if v <= 0.04045 else ((v + 0.055) / 1.055) ** 2.4
    return 0.2126 * ch(c[0]) + 0.7152 * ch(c[1]) + 0.0722 * ch(c[2])

def mix(a, b, t):
    return tuple(a[i] + (b[i] - a[i]) * t for i in range(3))

def lerp(a, b, t):
    return mix(a, b, t)

def match_luma(c, target):
    l = lum_of(c)
    if l <= 0.0001: return c
    k = target / l
    return tuple(min(max(c[i] * k, 0.0), 1.0) for i in range(3))

def tint_fill(base, toward, amt):
    return match_luma(mix(base, toward, amt), lum_of(base))

def lift(c, a): return mix(c, (1, 1, 1), a)
def deepen(c, a): return mix(c, (0, 0, 0), a)
def desat(c, a):
    l = lum_of(c)
    return mix(c, (l, l, l), a)

SUNKEN_A = 0.34
PANE_A = 0.94

rows = []
for pid, isdark, var in entries:
    if var not in defs: continue
    d = resolve(var)
    try:
        bg, surf, sv, prim, tert = (rgb(d[f]) for f in FIELDS)
    except KeyError:
        continue
    dark = lum_of(bg) < 0.45
    if dark:
        field_top = desat(lerp(bg, surf, 0.55), 0.35)
        raised = desat(tint_fill(mix(surf, sv, 0.18), prim, 0.02), 0.45)
    else:
        field_top = desat(lift(bg, 0.035), 0.10)
        raised = tint_fill(lift(surf, 0.55), prim, 0.12)
    sunken_rgb = mix((0, 0, 0), tert, 0.10) if dark else tint_fill(mix(sv, bg, 0.25), tert, 0.14)
    sa = SUNKEN_A if dark else 1.0

    raw_r, raw_s = rel_lum(raised), rel_lum(sunken_rgb)
    comp_sunken = mix(field_top, sunken_rgb, sa)
    comp_raised = mix(field_top, raised, raised[3] if len(raised) > 3 else 1.0)
    cr, cs = rel_lum(comp_raised), rel_lum(comp_sunken)
    rows.append((pid, dark, raw_r, raw_s, rel_lum(field_top), cr, cs))

print(f"{'palette':14} {'pol':5} {'rawRaise':>9} {'rawSunk':>9} {'field':>8} {'cmpRaise':>9} {'cmpSunk':>9}  raw?  comp?")
bad_raw = bad_comp = 0
for pid, dark, rr, rs, fl, cr, cs in rows:
    ok_raw = rr > rs
    ok_comp = cr > cs and (abs(cr - fl) > 0.002 or abs(cs - fl) > 0.002)
    if not ok_raw: bad_raw += 1
    if not ok_comp: bad_comp += 1
    flag = '' if ok_comp else '   <-- COMP FAILS'
    print(f"{pid:14} {'dark' if dark else 'light':5} {rr:9.4f} {rs:9.4f} {fl:8.4f} {cr:9.4f} {cs:9.4f}  {'ok' if ok_raw else 'RED':4} {'ok' if ok_comp else 'RED':4}{flag}")
print(f"\n{len(rows)} palettes. raw-RGB test fails: {bad_raw}. composited test fails: {bad_comp}")

# Chroma, the thing actually being complained about: channel spread (max-min),
# gamma-space, for the dark panes with and without the neutrality pull.
def spread(c):
    return max(c) - min(c)

print("\ndark panes — chroma vs their own field")
print(f"{'palette':14} {'field':>7} {'pane old':>9} {'pane new':>9} {'gain':>7}")
tot_o = tot_n = tot_f = 0.0
n = 0
for pid, isdark, var in entries:
    if var not in defs: continue
    d = resolve(var)
    try:
        bg, surf, sv, prim, tert = (rgb(d[f]) for f in FIELDS)
    except KeyError:
        continue
    if lum_of(bg) >= 0.45: continue
    fld = desat(lerp(bg, surf, 0.55), 0.35)
    old = tint_fill(mix(surf, sv, 0.18), prim, 0.02)
    new = desat(old, 0.45)
    tot_f += spread(fld); tot_o += spread(old); tot_n += spread(new); n += 1
    print(f"{pid:14} {spread(fld)*255:7.1f} {spread(old)*255:9.1f} {spread(new)*255:9.1f} {100*(1-spread(new)/spread(old)):6.0f}%")
if n:
    print(f"{'MEAN':14} {tot_f/n*255:7.1f} {tot_o/n*255:9.1f} {tot_n/n*255:9.1f}   (0-255 scale)")
