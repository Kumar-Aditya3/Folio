"""
folio_palette_apply.py — iteratively apply the bounded-OKLCH suggestions from
folio_palette.py back into FolioArtDirection.kt until the audit converges.

The auditor only reports the single worst offender per rule per face, so a one-shot
apply plays whack-a-mole (fixing the worst pair exposes the next-worst). This driver
loops score -> suggest -> write -> re-parse until no face yields a new suggestion or a
small iteration cap is hit (intrinsic cases like the achromatic 'system' theme or the
deliberately-close-hued 'solar' concept may never fully clear and are left as-is after
the cap, since forcing them further would break the concept).

Alpha (AA) bytes are preserved; only the RRGGBB of the targeted token is rewritten,
scoped to that face's own block span so identical hexes in other faces are untouched.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

import folio_palette as fp

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

MAX_ITERS = 10
BLOCK_RE = re.compile(
    r"FolioArtDirection\((.*?)\)\s*,\s*(?=//|\n\s*//|FolioArtDirection|\))", re.S
)


def face_blocks(src: str):
    """Yield (fid, light, block_text) in source order, matching parse_faces()."""
    out = []
    for m in BLOCK_RE.finditer(src):
        block = m.group(1)
        if "id =" not in block:
            continue
        fid = re.search(r'id\s*=\s*"([^"]+)"', block).group(1)
        light = re.search(r"light\s*=\s*(true|false)", block).group(1) == "true"
        out.append((fid, light, block))
    return out


def apply_fix_to_block(block: str, token: str, new_rrggbb: str) -> str | None:
    """Replace token's RRGGBB inside this block, preserving the AA byte. Returns new
    block text, or None if nothing changed / token not found."""
    new_rrggbb = new_rrggbb.lstrip("#").upper()
    pat = re.compile(rf"(\b{re.escape(token)}\s*=\s*0x)([0-9A-Fa-f]{{2}})([0-9A-Fa-f]{{6}})")

    def repl(mm):
        return f"{mm.group(1)}{mm.group(2)}{new_rrggbb}"

    new_block, n = pat.subn(repl, block, count=1)
    if n == 0:
        return None
    if new_block == block:
        return None
    return new_block


def main():
    src = fp.ART.read_text(encoding="utf-8")
    total_applied = 0
    for it in range(1, MAX_ITERS + 1):
        # score/suggest against the CURRENT file contents
        fp.ART.write_text(src, encoding="utf-8")  # ensure parse sees current src
        faces = fp.parse_faces()
        by_key = {(f["id"], f["light"]): f for f in faces}
        suggestions = {}
        for f in faces:
            rep = fp.score_face(f)
            fixes = [s for s in fp.suggest_all(f, rep) if "error" not in s]
            if fixes:
                suggestions[(f["id"], f["light"])] = fixes

        if not suggestions:
            print(f"iter {it}: converged, no suggestions.")
            break

        blocks = face_blocks(src)
        applied_this_iter = 0
        for fid, light, block in blocks:
            key = (fid, light)
            if key not in suggestions:
                continue
            new_block = block
            for sg in suggestions[key]:
                res = apply_fix_to_block(new_block, sg["token"], sg["suggested"])
                if res is not None:
                    new_block = res
                    applied_this_iter += 1
                    print(f"iter {it}: {fid} {'light' if light else 'dark':5} "
                          f"[{sg['kind']}] {sg['token']} {sg['was']} -> {sg['suggested']} "
                          f"({sg['metric']})")
            if new_block != block:
                src = src.replace(block, new_block, 1)

        total_applied += applied_this_iter
        if applied_this_iter == 0:
            print(f"iter {it}: suggestions existed but none applied (bounded out); stopping.")
            break
    else:
        print(f"hit iteration cap ({MAX_ITERS}); remaining issues are likely intrinsic.")

    fp.ART.write_text(src, encoding="utf-8")
    print(f"total token nudges applied: {total_applied}")
    print(f"wrote {fp.ART}")


if __name__ == "__main__":
    main()
