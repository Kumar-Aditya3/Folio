"""
CONCEPT tuning harness - independent flowing strands.
Dial the PARAMS block, re-run, watch outputs\sim\concept.gif.
Each strand is its own smooth curve with its own drift + vertical sway,
rendered as an anti-aliased distance field (no grid, no crosshatch).
"""
import numpy as np
from PIL import Image, ImageDraw

TAU = 6.2831853
W, H = 230, 470

# ------------------- PARAMS (tune these) -------------------
NSTR        = 34          # number of strands
COMPS       = 3           # sine components per strand (curve richness)
FREQ_LO,FREQ_HI   = 0.5, 2.0     # cycles across the width
AMP_LO, AMP_HI    = 0.025, 0.085 # as fraction of height, per component
DRIFT_LO,DRIFT_HI = 0.35, 1.35   # horizontal phase drift rate (laps-ish)
SWAY_AMP    = 0.018       # vertical self-sway amplitude (frac of H)
SWAY_LO,SWAY_HI   = 0.4, 1.1      # vertical sway rate
THICK_LO,THICK_HI = 1.0, 2.3      # stroke half-width (px)
SPACE_JITTER= 0.35        # how much strand base-rows wander off the even grid
DEPTH_DIM   = 0.45        # dimmest back strand (1=all equal)
SEED        = 11
# -----------------------------------------------------------

rng = np.random.default_rng(SEED)
XS  = np.linspace(0, 1, W)
ROWS = np.arange(H)[:, None]            # (H,1)

strands = []
for n in range(NSTR):
    base = (n + 0.5) / NSTR + rng.uniform(-1, 1) * SPACE_JITTER / NSTR
    comps = [(rng.uniform(FREQ_LO, FREQ_HI),
              rng.uniform(AMP_LO, AMP_HI),
              rng.uniform(0, TAU),
              rng.choice([-1, 1]) * rng.uniform(DRIFT_LO, DRIFT_HI)) for _ in range(COMPS)]
    sway = (SWAY_AMP * rng.uniform(0.4, 1.0),
            rng.uniform(SWAY_LO, SWAY_HI) * rng.choice([-1, 1]),
            rng.uniform(0, TAU))
    thick = rng.uniform(THICK_LO, THICK_HI)
    depth = rng.uniform(DEPTH_DIM, 1.0)
    strands.append((base, comps, sway, thick, depth))
strands.sort(key=lambda s: s[4])        # draw back (dim) first

def frame_cov(turns):
    img = np.zeros((H, W))
    for base, comps, sway, thick, depth in strands:
        y = np.full(W, base)
        for f, a, p, dr in comps:
            y = y + a * np.sin(TAU * f * XS + p + dr * turns * TAU)
        sa, sr, sp = sway
        y = y + sa * np.sin(sr * turns * TAU + sp)
        ypx = y * (H - 1)
        d = ROWS - ypx[None, :]                 # (H,W)
        cov = np.exp(-(d / thick) ** 2) * depth
        img = np.maximum(img, cov)
    return img

def colorize(cov):
    b = np.array([12, 52, 64]); t = np.array([156, 240, 188])
    return (b[None, None, :] + (t - b)[None, None, :] * cov[..., None]).astype(np.uint8)

FR = 72
frames = []
for f in range(FR):
    turns = f / FR
    frames.append(Image.fromarray(colorize(frame_cov(turns))))
out = r"D:\projects\Folio\outputs\sim\concept.gif"
frames[0].save(out, save_all=True, append_images=frames[1:], duration=66, loop=0, optimize=True)

# montage for still inspection
steps = [0.0, 0.2, 0.4, 0.6, 0.8]
cols = []
for t in steps:
    cols.append(colorize(frame_cov(t))); cols.append(np.full((H, 4, 3), 18, np.uint8))
m = np.concatenate(cols, axis=1)
Image.fromarray(m).resize((int(m.shape[1]*0.62), int(m.shape[0]*0.62))).convert("RGB").save(
    r"D:\projects\Folio\outputs\sim\concept_montage.jpg", quality=74)
print("wrote", out, "and concept_montage.jpg")
