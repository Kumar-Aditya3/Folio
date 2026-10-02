import sys
from PIL import Image

def lum(c):
    r, g, b = [v / 255 for v in c]
    f = lambda v: v / 12.92 if v <= 0.04045 else ((v + 0.055) / 1.055) ** 2.4
    return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b)

def spread(c):
    return (max(c) - min(c)) / 255.0

def report(path):
    im = Image.open(path).convert('RGB')
    px = im.load()
    print('=== %s ===' % path)
    # Same coordinates as the build-127 scan: the pane's own baseline well inside the
    # sun-side band, the band itself, the rim stroke, and the room outside.
    for y in (500, 700, 950):
        base = px[820, y]
        band = px[1006, y]
        rim = px[1014, y]
        room = px[1035, y]
        lb, lband = lum(base), lum(band)
        print('y=%-4d pane(820)=%s l=%.4f | band(1006)=%s l=%.4f | lift=%.2fx | '
              'band spread=%.3f rim spread=%.3f | room=%s l=%.4f'
              % (y, base, lb, band, lband, (lband / lb if lb else 0),
                 spread(band), spread(rim), room, lum(room)))
    print('build 127 measured: lift 3.0x at y=700 (band (83,76,76) over pane (48,43,43)),')
    print('band spread 0.027 vs rim 0.114. Prediction for 128: lift ~1.5x, band luma ~0.039.')

for p in sys.argv[1:]:
    report(p)
