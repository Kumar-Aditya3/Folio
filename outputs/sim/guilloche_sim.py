"""
Faithful-ish port of the Folio guilloche weave motion (GuillocheShader.android.kt)
into numpy, so the *movement* can be watched as a GIF.

It renders two panels side by side over one 53 s lap (phase 0..2PI):
  LEFT  = OLD  (every fibre shares `walk` -> the family folds in unison, "a sheet")
  RIGHT = NEW  (per-fibre `lane` desync -> threads fall out of step into curves)

Only the weave coverage + micro ruling are modelled; colour/accents/fan are
irrelevant to judging motion. Each frame is contrast-stretched so the faint
engraving is actually visible.
"""
import numpy as np
from PIL import Image, ImageDraw

TAU = 6.2831853

# ---- field ----
W, H = 230, 470                 # phone-portrait proportions
ASPECT = W / H
ux = (np.arange(W) + 0.5) / W
uy = (np.arange(H) + 0.5) / H
UX, UY = np.meshgrid(ux, uy)    # (H,W)
PX = (UX - 0.5) * ASPECT
PY = (UY - 0.5) * 1.0

# ---- a representative ORNAMENTAL pack (family=6), values picked mid-range ----
FAMILY = 6
STRUCTURE_SCALE = 0.16
CH_FREQ, CH_BRAID, CH_WEIGHT = 1.35, 1.60, 0.80
character = 0.5
base = -2.0

def mkwave(i, freqRaw, crossRaw, crossFreq, angOff, drift):
    freq = freqRaw * CH_FREQ * STRUCTURE_SCALE
    cross = crossRaw * (1.0 + 0.45 * character) * CH_BRAID
    weight = CH_WEIGHT * 1.0
    share = (1.0, 0.45, 0.25)[i]
    return dict(freq=freq, cross=cross, crossFreq=crossFreq,
                angle=base + angOff, weight=weight, share=share, drift=drift)

WAVES = [
    mkwave(0, 5.5, 2.5, 1.35, -0.60, +1.0),
    mkwave(1, 10.75, 2.5, 1.30,  0.00, -2.0),
    mkwave(2, 19.0, 2.5, 1.20, +0.60, +3.0),
]

def family_warp(m, braid):
    # ORNAMENTAL
    return 1.00 * np.sin(m * 0.85 + braid) + 0.62 * np.sin(m * 2.55 - braid)

def wave_line(freq, cross, crossFreq, angle, w, walk, braid, new):
    dx, dy = np.cos(angle), np.sin(angle)
    v = PX * dx + PY * dy
    m = PX * (-dy) + PY * dx
    warp = family_warp(m, braid) * cross * 2.4
    arg = (v * freq + warp + walk) * TAU
    if new:
        lane = np.sin((v * freq * 0.73) * TAU - walk * 2.0 * TAU)
        s = np.clip(0.5 + 0.5 * np.sin(arg + 0.85 * lane), 0.0, 1.0)
    else:
        s = np.clip(0.5 + 0.5 * np.sin(arg), 0.0, 1.0)
    coreK = np.clip(4.2 * w, 2.0, 10.0)
    core = s ** coreK
    halo = s ** (coreK * 0.42)
    streak = 0.5 + 0.5 * np.sin(m * crossFreq * 3.0 + braid * 2.0)
    return (core * 0.62 + halo * 0.38) * (0.72 + 0.28 * streak * streak)

def plate(turns, breath, new):
    phase = turns * TAU
    acc = np.zeros((H, W))
    bf = [1.0 + 0.06 * np.sin(breath),
          1.0 - 0.06 * np.sin(breath * 2.0 + 1.1),
          1.0 + 0.06 * np.cos(breath * 3.0 + 2.3)]
    for i, wv in enumerate(WAVES):
        walk = wv['drift'] * turns
        braid = np.sign(wv['drift']) * phase
        l = wave_line(wv['freq'], wv['cross'], wv['crossFreq'], wv['angle'],
                      wv['weight'], walk, braid, new)
        acc += l * wv['share'] * bf[i]
    raw = acc * 0.30
    weave = raw / (1.0 + raw)
    # micro ruling, on wave0's perpendicular
    a0 = WAVES[0]['angle']
    microDir = PX * (-np.sin(a0)) + PY * np.cos(a0)
    microPhase = (microDir * WAVES[0]['freq'] * 6.0 + turns * 7.0) * TAU
    micro = np.clip(0.5 + 0.5 * np.sin(microPhase), 0.0, 1.0) ** 5.0
    return weave + micro * 0.15

def stretch(img):
    lo, hi = np.percentile(img, 2), np.percentile(img, 98)
    if hi - lo < 1e-6:
        hi = lo + 1e-6
    return np.clip((img - lo) / (hi - lo), 0, 1)

# teal->green wash so it reads like the app, structure as lightening
def colorize(cov):
    cov = stretch(cov)
    base_c = np.array([14, 60, 72])      # deep teal
    thread = np.array([150, 235, 180])   # mint thread
    rgb = base_c[None, None, :] + (thread - base_c)[None, None, :] * cov[..., None]
    return rgb.astype(np.uint8)

FRAMES = 60
frames = []
for f in range(FRAMES):
    turns = f / FRAMES                         # 0..1 over one 53s lap
    breath = turns * TAU * (53.0 / 79.0)
    old = colorize(plate(turns, breath, new=False))
    new = colorize(plate(turns, breath, new=True))
    gap = np.full((H, 6, 3), 20, np.uint8)
    both = np.concatenate([old, gap, new], axis=1)
    im = Image.fromarray(both)
    d = ImageDraw.Draw(im)
    d.text((6, 6), "OLD (unison)", fill=(255, 255, 255))
    d.text((W + 12, 6), "NEW (independent)", fill=(255, 255, 255))
    frames.append(im)

out = r"D:\projects\Folio\outputs\sim\guilloche_motion.gif"
frames[0].save(out, save_all=True, append_images=frames[1:],
               duration=70, loop=0, optimize=True)
print("wrote", out)
