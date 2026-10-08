import numpy as np
from PIL import Image, ImageDraw
import importlib.util, sys
spec = importlib.util.spec_from_file_location("gs", r"D:\projects\Folio\outputs\sim\guilloche_sim.py")
# avoid re-running the gif save: load functions only by exec with a guard
src = open(r"D:\projects\Folio\outputs\sim\guilloche_sim.py").read()
src = src.split("FRAMES = 60")[0]
g = {}
exec(src, g)
plate, colorize, H, W = g['plate'], g['colorize'], g['H'], g['W']
TAU = g['TAU']

steps = [0.0, 0.16, 0.33, 0.5, 0.66, 0.83]
rows = []
for new in (False, True):
    cols = []
    for t in steps:
        breath = t * TAU * (53.0/79.0)
        img = colorize(plate(t, breath, new=new))
        cols.append(img)
        cols.append(np.full((H,5,3),20,np.uint8))
    rows.append(np.concatenate(cols,axis=1))
    rows.append(np.full((6, rows[-1].shape[1],3),20,np.uint8))
mont = np.concatenate(rows,axis=0)
im = Image.fromarray(mont)
d = ImageDraw.Draw(im)
d.text((4,4),"OLD unison  (t=0 .16 .33 .5 .66 .83)",fill=(255,255,255))
d.text((4,H+10),"NEW independent",fill=(255,255,255))
im.save(r"D:\projects\Folio\outputs\sim\montage.png")
print("wrote montage", mont.shape)
