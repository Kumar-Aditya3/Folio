from PIL import Image
import statistics

im = Image.open('b127_now.png').convert('RGB')
W, H = im.size
px = im.load()

def lum(c):
    r, g, b = [v / 255 for v in c]
    f = lambda v: v / 12.92 if v <= 0.04045 else ((v + 0.055) / 1.055) ** 2.4
    return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b)

def spread(c):
    return (max(c) - min(c)) / 255.0

def cell_median(x0, y0, n=20):
    rs, gs, bs = [], [], []
    for y in range(y0, y0 + n):
        for x in range(x0, x0 + n):
            c = px[x, y]
            rs.append(c[0]); gs.append(c[1]); bs.append(c[2])
    return (int(statistics.median(rs)), int(statistics.median(gs)), int(statistics.median(bs)))

# --- 1. Fine scan ACROSS the right border, 6px steps, at three heights (text-free). ---
print('=== fine scan across the RIGHT edge (x 820..1060, 6px steps) ===')
for y in (500, 700, 950):
    print('y=%d' % y)
    line = []
    for x in range(820, 1061, 6):
        c = px[x, y]
        line.append('%d:%d/%d' % (x, int(lum(c) * 1000), int(spread(c) * 1000)))
    print('   ' + ' '.join(line))

# --- 2. Where is the loudest interior? median cells over the pane, cover excluded. ---
print()
print('=== median-cell maps over the pane (x 500..1040, y 340..1160) ===')
XS = range(500, 1040, 20)
YS = range(340, 1160, 20)
best_l, best_c = None, None
for label, fn, lo, hi in (('LUMA', lum, 0.0, 0.05), ('CHROMA', spread, 0.0, 0.12)):
    print('--- %s (scale %.2f..%.2f) ---' % (label, lo, hi))
    chars = ' .:-=+*#%@'
    print('     ' + ''.join('%-2d' % (x // 100) for x in XS))
    for y in YS:
        row = ''
        for x in XS:
            v = fn(cell_median(x, y))
            t = (v - lo) / (hi - lo)
            row += chars[max(0, min(9, int(t * 10)))]
            if label == 'LUMA' and (best_l is None or v > best_l[0]):
                best_l = (v, x, y, cell_median(x, y))
            if label == 'CHROMA' and (best_c is None or v > best_c[0]):
                best_c = (v, x, y, cell_median(x, y))
        print('%4d %s' % (y, row))
print()
print('peak luma   : %.4f at (x=%d,y=%d) rgb=%s' % best_l)
print('peak chroma : %.4f at (x=%d,y=%d) rgb=%s' % best_c)
