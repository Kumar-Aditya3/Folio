import sys
from PIL import Image

DARK = r"C:\Users\kumar\.workbuddy-ai\clipboard-images\clipboard-2026-10-12T16-48-46-721Z-7a37a9f3.png"
LIGHT = r"C:\Users\kumar\.workbuddy-ai\clipboard-images\clipboard-2026-10-12T16-48-46-725Z-00afb855.png"


def bands(img, x, y0, y1, tol=6):
    px = img.load()
    out = []
    prev = px[x, y0]
    start = y0
    for y in range(y0 + 1, y1):
        c = px[x, y]
        if max(abs(c[i] - prev[i]) for i in range(3)) > tol:
            out.append((start, y - 1, prev))
            prev = c
            start = y
    out.append((start, y1 - 1, prev))
    return out


def show(tag, path, x_frac, y0, y1):
    img = Image.open(path).convert("RGB")
    w, h = img.size
    x = int(w * x_frac)
    print("== %s  size=%dx%d  x=%d  y=%d..%d" % (tag, w, h, x, y0, y1))
    for a, b, c in bands(img, x, y0, y1):
        if b - a >= 1:
            print("   y %4d-%4d (%2dpx)  #%02X%02X%02X" % (a, b, b - a + 1, c[0], c[1], c[2]))
    return img


if __name__ == "__main__":
    show("LIGHT", LIGHT, 0.5, 130, 430)
    print()
    show("DARK", DARK, 0.5, 130, 430)
