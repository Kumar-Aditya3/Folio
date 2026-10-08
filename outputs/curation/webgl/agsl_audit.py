"""
Audit the AGSL OUTPUT, not the source hexes.

The identity metrics were being run on the authored palette, which answers "is the palette
well designed?" and not "did the shader preserve it?". Those are different questions, and the
spec's own warning is the second one: a source palette can pass while a shader transform
produces a visibly different result.

Three measurements, in the order the pipeline applies them, so a failure can be attributed:

    source ──compositing──▶ A ──the app's alpha──▶ plain ──F_AGSL──▶ full

  * `dE(source, A)`      the browser composites the role faithfully. Should be ~0.
  * `dE(A, plain)`       the app's deliberate alpha (a pane is translucent on purpose).
                         Large by design, and not the shader's doing.
  * `dE(plain, full)`    **F_AGSL.** The invariant. The substrate must keep its identity;
                         the accents may be reinterpreted, but only within a bound.

The bound is role-specific, and that is the point the spec's review makes: AGSL is allowed to
reinterpret accents more than it is allowed to reinterpret the substrate.

    dE(substrate) <= 3.0      and |dH| <= 3 deg, |dC| <= 0.02
    dE(accent)    <= 8.0      and |dH| <= 12 deg   (MAX_GUILLOCHE_HUE_DEV)

Theme and content are kept apart throughout: a book thumbnail is content, it is not measured
here, and no content colour can fail a theme gate.
"""
import json
import math
import pathlib
import sys

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from audit import GATES, REGRESSION_SET, TOP, lch  # noqa: E402

# The six authored keys, and which of them the probe measures.
KEYS = ["backgroundBase", "surface", "primaryAccent", "secondaryAccent",
        "tertiaryAccent", "warmAccent"]

# Role-class bounds on F_AGSL. `dE` is Oklab Euclidean x 100.
BOUNDS = {
    "substrate": {"dE": 3.0, "dH": 3.0, "dC": 0.020},
    "accent": {"dE": 8.0, "dH": 12.0, "dC": 0.060},
}
# The compositing chain should be near-exact; anything above this is a browser/render fault.
COMPOSITE_TOL = 1.5

CROWN_CONTRAST_FLOOR = 4.2      # the figure the curation pass recorded

# Below this Oklab chroma a colour has no hue worth bounding -- the same sentinel
# `roomHue` uses. Hue is gated only where there is a hue to gate; `dE` carries the rest.
CHROMA_FOR_HUE = 0.02


def _rgb(t):
    return tuple(v / 255 for v in t)


def _oklab(rgb):
    def lin(u):
        return u / 12.92 if u <= 0.04045 else ((u + 0.055) / 1.055) ** 2.4
    r, g, b = (lin(v) for v in rgb)
    l = 0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b
    m = 0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b
    s = 0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b
    lc, mc, sc = l ** (1 / 3), m ** (1 / 3), s ** (1 / 3)
    return (0.2104542553 * lc + 0.7936177850 * mc - 0.0040720468 * sc,
            1.9779984951 * lc - 2.4285922050 * mc + 0.4505937099 * sc,
            0.0259040371 * lc + 0.7827717662 * mc - 0.8086757660 * sc)


def dE(a, b):
    """Oklab Euclidean distance, x100 so the numbers read as familiar delta-E units."""
    la, aa, ba = _oklab(_rgb(a))
    lb, ab, bb = _oklab(_rgb(b))
    return 100 * math.sqrt((la - lb) ** 2 + (aa - ab) ** 2 + (ba - bb) ** 2)


def _lch_of(rgb):
    return lch(tuple(v / 255 for v in rgb))


def dLH(a, b):
    """(dL, dC, dH degrees). Hue is the identity measure; lightness is the design's to move."""
    la, ca, ha = _lch_of(a)
    lb, cb, hb = _lch_of(b)
    return lb - la, cb - ca, TOP.dh(ha, hb)


def wcag(hexstr):
    v = int(hexstr, 16)
    def lin(u):
        u /= 255.0
        return u / 12.92 if u <= 0.04045 else ((u + 0.055) / 1.055) ** 2.4
    r, g, b = (v >> 16) & 255, (v >> 8) & 255, v & 255
    return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)


def contrast(a_hex, b_hex):
    la, lb = wcag(a_hex), wcag(b_hex)
    hi, lo = max(la, lb), min(la, lb)
    return (hi + 0.05) / (lo + 0.05)


def main():
    samples = json.loads((HERE / "probe_samples.json").read_text(encoding="utf-8"))
    faces = {f"{f['family']}-{'light' if f['light'] else 'dark'}": f
             for f in json.loads((HERE / "faces.json").read_text(encoding="utf-8"))["faces"]}

    # ── F_AGSL, per role, with the role-class bounds ──────────────────────────
    print("=" * 104)
    print("AGSL OUTPUT AUDIT -- F_AGSL measured from pixels, not assumed from the source")
    print("=" * 104)
    print()
    print("bounds: substrate dE<=3.0 |dH|<=3.0 |dC|<=0.020   "
          "accent dE<=8.0 |dH|<=12.0 |dC|<=0.060   (dE = Oklab x100)")
    print()
    print(f"{'face':<20} {'role':<22} {'cls':<10} {'dE(src,A)':>9} {'dE(A,plain)':>11} "
          f"{'dE(plain,full)':>14} {'dH':>6} {'dC':>7}  verdict")

    violations = []
    per_role = {}
    for name, row in samples.items():
        for role, v in row.items():
            if role not in KEYS and v["class"] != "accent":
                continue
            src = faces[name]["roles"][role]
            src_rgb = tuple((int(src, 16) >> s) & 255 for s in (16, 8, 0))
            de_comp = dE(src_rgb, v["A"])
            de_alpha = dE(v["A"], v["plain"])
            de_shader = dE(v["plain"], v["full"])
            dl, dc, dh = dLH(v["plain"], v["full"])
            b = BOUNDS[v["class"]]
            c_plain, c_full = _lch_of(v["plain"])[1], _lch_of(v["full"])[1]
            hue_meaningful = min(c_plain, c_full) > CHROMA_FOR_HUE
            ok = (de_shader <= b["dE"] and (not hue_meaningful or dh <= b["dH"])
                  and abs(dc) <= b["dC"] and de_comp <= COMPOSITE_TOL)
            per_role.setdefault(role, []).append(de_shader)
            if not ok:
                violations.append((name, role, de_shader, dh, dc, de_comp))

    # Print one face in full as the worked example, then the aggregate.
    exemplar = "ocean-light"
    for role, v in samples[exemplar].items():
        if role not in KEYS and v["class"] != "accent":
            continue
        src = faces[exemplar]["roles"][role]
        src_rgb = tuple((int(src, 16) >> s) & 255 for s in (16, 8, 0))
        dl, dc, dh = dLH(v["plain"], v["full"])
        b = BOUNDS[v["class"]]
        de = dE(v["plain"], v["full"])
        c_plain, c_full = _lch_of(v["plain"])[1], _lch_of(v["full"])[1]
        hue_meaningful = min(c_plain, c_full) > CHROMA_FOR_HUE
        ok = de <= b["dE"] and (not hue_meaningful or dh <= b["dH"]) and abs(dc) <= b["dC"]
        print(f"{exemplar:<20} {role:<22} {v['class']:<10} {dE(src_rgb, v['A']):>9.2f} "
              f"{dE(v['A'], v['plain']):>11.2f} {de:>14.2f} {dh:>6.2f} {dc:>7.4f}  "
              f"{'ok' if ok else 'OUT'}"
              f"{'' if hue_meaningful else '  (hue n/a: near-neutral)'}")
    print(f"{'':<20} (rows above are one face, in pipeline order)")
    print()
    print("  F_AGSL across all 20 faces, by role:")
    print(f"  {'role':<22} {'class':<10} {'dE mean':>8} {'dE max':>8}  bound")
    for role, vals in per_role.items():
        cls = samples[exemplar][role]["class"]
        b = BOUNDS[cls]
        print(f"  {role:<22} {cls:<10} {sum(vals) / len(vals):>8.2f} {max(vals):>8.2f}  "
              f"{b['dE']:>5.1f}  {'ok' if max(vals) <= b['dE'] else 'OUT'}")

    print()
    if violations:
        print(f"  {len(violations)} role/face pairs outside their bound:")
        for name, role, de, dh, dc, de_comp in violations[:20]:
            why = []
            if de > BOUNDS[samples[name][role]["class"]]["dE"]:
                why.append(f"dE {de:.2f}")
            c_p, c_f = _lch_of(samples[name][role]["plain"])[1], _lch_of(samples[name][role]["full"])[1]
            if min(c_p, c_f) > CHROMA_FOR_HUE and dh > BOUNDS[samples[name][role]["class"]]["dH"]:
                why.append(f"dH {dh:.2f}")
            if abs(dc) > BOUNDS[samples[name][role]["class"]]["dC"]:
                why.append(f"dC {dc:.4f}")
            if de_comp > COMPOSITE_TOL:
                why.append(f"compositing {de_comp:.2f}")
            print(f"    {name:<20} {role:<22} {', '.join(why)}")
    else:
        print("  every role inside its bound: the substrate keeps its identity and the")
        print("  accents move only within MAX_GUILLOCHE_HUE_DEV")

    # ── the metrics, on the AGSL output ───────────────────────────────────────
    print()
    print("=" * 104)
    print("SOURCE METRICS vs AGSL METRICS  (the six authored keys, measured at `full`)")
    print("=" * 104)
    print()
    print(f"{'family':<11} {'T6 src':>7} {'T6 agsl':>8} {'W6 src':>7} {'W6 agsl':>8} "
          f"{'C src':>6} {'C agsl':>7}  gate(src)  gate(agsl)")

    out_rows = {}
    for fam in ("ocean", "nebula", "forest", "terracotta", "arctic", "solar", "jade",
                "mintcoral", "moonstone"):
        L, D = f"{fam}-light", f"{fam}-dark"
        if L not in samples or D not in samples:
            continue

        def keys(row):
            return [row[k]["full"] for k in KEYS]

        src_l = [faces[L]["roles"][k] for k in KEYS]
        src_d = [faces[D]["roles"][k] for k in KEYS]
        ag_l, ag_d = keys(samples[L]), keys(samples[D])

        t_src = TOP.T6([_lch_hex(h)[2] for h in src_l], [_lch_hex(h)[2] for h in src_d])
        t_ag = TOP.T6([_lch_of(c)[2] for c in ag_l], [_lch_of(c)[2] for c in ag_d])
        w_src = TOP.W6([_lch_hex(h)[2] for h in src_l], [_lch_hex(h)[2] for h in src_d],
                       [_lch_hex(h)[1] for h in src_l], [_lch_hex(h)[1] for h in src_d])
        w_ag = TOP.W6([_lch_of(c)[2] for c in ag_l], [_lch_of(c)[2] for c in ag_d],
                      [_lch_of(c)[1] for c in ag_l], [_lch_of(c)[1] for c in ag_d])

        def shape(chromas):
            cs = sorted(chromas, reverse=True)
            m = cs[0] or 1.0
            return [v / m for v in cs]

        # `_lch_hex` for the source (hex -> Oklab) and `_lch_of` for the samples (0..255 ->
        # Oklab). Handing `_rgb_hex`'s 0..1 output to `_lch_of`, which divides by 255 again,
        # collapses every chroma to ~0 and the rank-normalised shape with it.
        c_src = max(0.0, 100.0 - sum(abs(a - b) for a, b in
                                     zip(shape([_lch_hex(h)[1] for h in src_l]),
                                         shape([_lch_hex(h)[1] for h in src_d]))) * 90.0)
        c_ag = max(0.0, 100.0 - sum(abs(a - b) for a, b in
                                    zip(shape([_lch_of(c)[1] for c in ag_l]),
                                        shape([_lch_of(c)[1] for c in ag_d]))) * 90.0)

        def gate(t, w, c):
            # Class X is not gated on T/W/C: section 1 says they are diagnostic for it.
            if fam == "nebula":
                return "n/a (X)"
            return "PASS" if t >= GATES["T6"]["threshold"] and w >= GATES["W6"]["threshold"] \
                and c >= GATES["C"]["threshold"] else "FAIL"

        gs, ga = gate(t_src, w_src, c_src), gate(t_ag, w_ag, c_ag)
        out_rows[fam] = {"T6_src": t_src, "T6_agsl": t_ag, "W6_src": w_src, "W6_agsl": w_ag,
                         "C_src": c_src, "C_agsl": c_ag, "gate_src": gs, "gate_agsl": ga}
        print(f"{fam:<11} {t_src:>7.0f} {t_ag:>8.0f} {w_src:>7.0f} {w_ag:>8.0f} "
              f"{c_src:>6.0f} {c_ag:>7.0f}  {gs:<9}  {ga}")

    reg_src = all(out_rows[f]["gate_src"] in ("PASS", "n/a (X)") for f in REGRESSION_SET if f in out_rows)
    reg_ag = all(out_rows[f]["gate_agsl"] in ("PASS", "n/a (X)") for f in REGRESSION_SET if f in out_rows)
    print()
    print(f"  regression set, source: {'PASS' if reg_src else 'FAIL'}   "
          f"AGSL: {'PASS' if reg_ag else 'FAIL'}")
    print("  -> the invariant is `forall t in R: G_t^AGSL = PASS`")

    # ── the crown, as two separate semantic outputs ───────────────────────────
    print()
    print("=" * 104)
    print("CROWN AND CROWN-TEXT AS SEPARATE OUTPUTS")
    print("=" * 104)
    print()
    print("  The crown is a signature surface, so its ground and its wordmark are measured")
    print("  separately. Two candidate wordmarks are compared against the rendered crown.")
    print()
    print(f"  {'face':<20} {'crown (rendered)':<18} {'ink':>7} {'accentProgress':>15}  verdict")
    from PIL import Image
    crown_rows = {}
    for name, face in faces.items():
        p = HERE / "screens" / f"home-{name}.png"
        if not p.exists():
            continue
        im = Image.open(p).convert("RGB")
        W, H = im.size
        # The bar row, clear of the mark and the title: x 0.62, y 0.085.
        box = im.crop((int(0.60 * W), int(0.075 * H), int(0.70 * W), int(0.095 * H)))
        px = list(box.getdata())
        crown = tuple(sum(c[i] for c in px) // len(px) for i in range(3))
        crown_hex = "0x%02X%02X%02X" % crown
        c_ink = contrast(crown_hex, face["roles"]["textPrimary"])
        c_acc = contrast(crown_hex, face["roles"]["accentProgress"])
        best = "ink" if c_ink >= c_acc else "accentProgress"
        crown_rows[name] = {"crown": crown_hex, "ink": c_ink, "accentProgress": c_acc,
                            "best": best,
                            "accent_clears": c_acc >= CROWN_CONTRAST_FLOOR}
        if name in ("ocean-light", "ocean-dark", "nebula-dark", "solar-light", "jade-light"):
            print(f"  {name:<20} {crown_hex:<18} {c_ink:>7.2f} {c_acc:>15.2f}  best={best}")

    clears_ink = sum(1 for v in crown_rows.values() if v["ink"] >= CROWN_CONTRAST_FLOOR)
    clears_acc = sum(1 for v in crown_rows.values() if v["accent_clears"])
    n = len(crown_rows)
    print()
    print(f"  wordmark contrast >= {CROWN_CONTRAST_FLOOR} across {n} faces:")
    print(f"    textPrimary      clears on {clears_ink} / {n}")
    print(f"    accentProgress   clears on {clears_acc} / {n}")
    worst = sorted(crown_rows.items(), key=lambda kv: kv[1]["accentProgress"])[:4]
    print(f"    worst accentProgress readings: "
          + ", ".join(f"{k} {v['accentProgress']:.2f}" for k, v in worst))

    result = {
        "bounds": BOUNDS,
        "violations": [list(v) for v in violations],
        "metrics": out_rows,
        "regression_source_ok": reg_src,
        "regression_agsl_ok": reg_ag,
        "crown": crown_rows,
        "crown_floor": CROWN_CONTRAST_FLOOR,
        "crown_ink_clears": clears_ink,
        "crown_accent_clears": clears_acc,
    }
    (HERE / "agsl_audit.json").write_text(json.dumps(result, indent=1, default=str),
                                          encoding="utf-8")
    print("\n-> agsl_audit.json")
    return 0


def _rgb_hex(h):
    """A role's `0xAARRGGBB` as 0..1 RGB -- the form `_oklab` wants."""
    return tuple(v / 255 for v in ((int(h, 16) >> s) & 255 for s in (16, 8, 0)))


def _lch_hex(h):
    """A role's Oklab (L, C, h). `lch` takes 0..255, so it must not be handed `_rgb_hex`."""
    return lch(_rgb_hex(h))


if __name__ == "__main__":
    raise SystemExit(main())
