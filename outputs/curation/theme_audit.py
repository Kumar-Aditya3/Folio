"""Audit the eighteen faces against the colour-system brief's numerical bars.

The brief says to ignore decorative geometry and judge the *colour relationships* only. Every rule
in it is a number, so this measures rather than eyeballs:

    theme thesis      P + Q must identify the theme; 90 <= d(hP,hQ) <= 210 (prefer 120-180)
    bridge            R: d(hR,hP) >= 40, d(hR,hQ) >= 40, C(R) <= C(P)
    spark             X: C(X) >= C(P), tiny area
    frequency         P > Q > R > X
    light surfaces    0.94 <= L(B0) <= 0.985, 0.008 <= C(B0) <= 0.030, dL(B0,S0) >= 0.02
    dark surfaces     0.055 <= L(B0) <= 0.10, 0.09 <= L(S0) <= 0.17,
                      L(B0) < L(B1) < L(S0) < L(S1), C(B0) <= 0.025, C(S0) <= 0.040
    chart             C_max / C_min ~ 1.5-2.5, most adjacent d(h) <= 90

OKLCH via `design_chroma_tiers.lch`, which returns chroma x100 (the harness's own convention).

    python theme_audit.py
"""
import contextlib
import io
import os
import sys

sys.path.insert(0, r"D:\projects\Folio\.workbuddy-ai\tmp\curate")
sys.path.insert(0, r"D:\projects\Folio\.workbuddy-ai\tmp")
os.chdir(r"D:\projects\Folio\.workbuddy-ai\tmp")

with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
    import assign as AS
    import design_chroma_tiers as D
    import sheet as S


def lch(c):
    L, C, h = D.lch(tuple(v / 255.0 for v in c))
    return L, C / 100.0, h


def dh(a, b):
    x = abs(a - b) % 360.0
    return min(x, 360.0 - x)


def ok(cond):
    return "ok " if cond else "FAIL"


def main():
    fails = []
    print("hue relationships:  d(hP,hQ) 90-210 | d(hP,hR)>=40 | d(hR,hQ)>=40 | C(R)<=C(P) | C(X)>=C(P)")
    print("%-11s %-5s  %-7s %-7s %-7s | %-6s %-6s | %s" % (
        "family", "face", "dPQ", "dPR", "dQR", "C(R)/C(P)", "C(X)/C(P)", "verdict"))
    for fam in S.SHEET:
        for light in (True, False):
            r = AS.face(fam, light)
            _, cP, hP = lch(r["primaryAccent"])
            _, cQ, hQ = lch(r["secondaryAccent"])
            _, cR, hR = lch(r["tertiaryAccent"])
            _, cX, _ = lch(r["warmAccent"])
            dPQ, dPR, dQR = dh(hP, hQ), dh(hP, hR), dh(hR, hQ)
            bad = []
            if not (90 <= dPQ <= 210):
                bad.append("dPQ")
            if dPR < 40 or dQR < 40:
                bad.append("bridge")
            if cR > cP:
                bad.append("C(R)>C(P)")
            if cX < cP:
                bad.append("C(X)<C(P)")
            tag = "ok" if not bad else " ".join(bad)
            if bad:
                fails.append((fam, light, tag))
            print("%-11s %-5s  %-7.0f %-7.0f %-7.0f | %-6.2f %-6.2f | %s" % (
                fam, "light" if light else "dark", dPQ, dPR, dQR, cR / max(cP, 1e-6),
                cX / max(cP, 1e-6), tag))

    print("\nsurface bars")
    print("%-11s %-5s  %-13s %-9s %-9s | %-11s %s" % (
        "family", "face", "L(B0)", "C(B0)", "dL(B0,S0)", "L ladder", "verdict"))
    for fam in S.SHEET:
        for light in (True, False):
            r = AS.face(fam, light)
            L0, C0, _ = lch(r["backgroundBase"])
            L1, _, _ = lch(r["backgroundSecondary"])
            LS, CS, _ = lch(r["surface"])
            LE, _, _ = lch(r["surfaceElevated"])
            bad = []
            if light:
                if not (0.94 <= L0 <= 0.985):
                    bad.append("L(B0)")
                if not (0.008 <= C0 <= 0.030):
                    bad.append("C(B0)")
                if abs(L0 - LS) < 0.02:
                    bad.append("dL(B0,S0)")
            else:
                if not (0.055 <= L0 <= 0.10):
                    bad.append("L(B0)")
                if not (0.09 <= LS <= 0.17):
                    bad.append("L(S0)")
                if not (L0 < L1 < LS < LE):
                    bad.append("ladder")
                if C0 > 0.025:
                    bad.append("C(B0)")
                if CS > 0.040:
                    bad.append("C(S0)")
            tag = "ok" if not bad else " ".join(bad)
            if bad:
                fails.append((fam, light, "surface: " + tag))
            print("%-11s %-5s  %-13.3f %-9.4f %-9.3f | %-11s %s" % (
                fam, "light" if light else "dark", L0, C0, abs(L0 - LS),
                "%.3f<%.3f<%.3f" % (L0, LS, LE), tag))

    print("\ntotal failing checks: %d across 18 faces" % len(fails))
    for fam, light, tag in fails:
        print("   %-11s %-5s  %s" % (fam, "light" if light else "dark", tag))


if __name__ == "__main__":
    main()
