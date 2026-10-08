import json, colorsys
d = json.load(open('faces.json'))
def hue(hx):
    hx = hx.lstrip('#'); r,g,b = [int(hx[i:i+2],16)/255 for i in (0,2,4)]
    h,l,s = colorsys.rgb_to_hls(r,g,b); return (round(h*360), round(s*100), round(l*100))
for f in d:
    if not f['light']:
        r = f['roles']
        print("%-11s bg=%s surfElev=%s prim=%s%s sec=%s%s" % (
            f['pack'], r['backgroundBase'], r['surfaceElevated'],
            r['primaryAccent'], hue(r['primaryAccent']),
            r['secondaryAccent'], hue(r['secondaryAccent'])))
