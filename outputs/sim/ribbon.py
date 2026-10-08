import numpy as np
from PIL import Image, ImageFilter
TAU = 6.2831853
W, H = 480, 320
XS = np.linspace(0, 1, W)
ROWS = np.arange(H)[:, None].astype(np.float32)

# ---- colour poles (additive) ----
COOL = [np.array(c, float) for c in ([30,90,220],[90,60,210],[60,180,255])]  # blue/indigo/cyan
WARM = [np.array(c, float) for c in ([255,150,40],[255,80,70],[230,60,160])]  # amber/orange/magenta

def ribbon(seed, warm, depth, t):
    """One twisting ribbon = M parallel lines sharing a harmonic envelope.
    Brightness emerges from local line density (fan vs pinch)."""
    rng = np.random.default_rng(seed)
    # asymmetric envelope: 3 non-integer freqs, independent slow phase drift
    comps = [(rng.uniform(0.6,1.0), rng.uniform(0.10,0.16), rng.uniform(0,TAU), rng.uniform(0.2,0.5)),
             (rng.uniform(1.3,2.1), rng.uniform(0.05,0.09), rng.uniform(0,TAU), rng.uniform(0.3,0.7)),
             (rng.uniform(2.4,3.6), rng.uniform(0.02,0.05), rng.uniform(0,TAU), rng.uniform(0.4,0.9))]
    base = rng.uniform(0.32, 0.68)
    yc = np.full(W, base)
    slope = np.zeros(W)
    for f,a,p,dr in comps:
        yc = yc + a*np.sin(TAU*f*XS + p + dr*t*TAU)
        slope = slope + a*TAU*f*np.cos(TAU*f*XS + p + dr*t*TAU)
    # ribbon half-width fans and pinches along x (guilloche waist nodes)
    wf, wp = rng.uniform(1.6,2.4), rng.uniform(0,TAU)
    halfw = (0.5 + 0.5*np.abs(np.sin(TAU*wf*XS*0.5 + wp + 0.3*t*TAU))) * rng.uniform(0.05,0.10)
    halfw = np.clip(halfw, 0.012, None)
    # density ~ inverse of apparent thickness when ribbon turns edge-on (slope high => compressed)
    edge = 1.0/np.sqrt(1.0 + (slope*0.25)**2)      # 1 at face-on, ->0 edge-on
    local_hw = halfw*(0.25 + 0.75*edge)*(H)         # px half width
    local_hw = np.clip(local_hw, 1.2, None)
    yc_px = yc*(H-1)
    d = ROWS - yc_px[None,:]
    inside = np.exp(-(d/local_hw[None,:])**2)        # ribbon envelope
    # fine parallel line striations across the ribbon normal -> the "many strands" look
    stripes = 0.5 + 0.5*np.cos(TAU*18*(d/(local_hw[None,:]*2.0)))
    cov = inside*(0.35 + 0.65*stripes)
    # density hotspot where compressed (edge-on) -> boost
    cov = cov*(0.6 + 1.6*(1-edge))[None,:]
    cov *= depth
    # colour along length: warm shift toward compressions
    pal = WARM if warm else COOL
    mix = (1-edge)                                   # 0 face,1 edge
    c0,c1,c2 = pal
    base_col = c0[None,:]*(1-np.clip(mix*1.3,0,1))[:,None] + c2[None,:]*np.clip(mix*1.3,0,1)[:,None]
    col = base_col[None,:,:]*cov[...,None]
    return col

def frame(t):
    acc = np.zeros((H,W,3))
    specs = [(11,False,0.45),(23,True,0.5),(37,False,0.7),(51,True,0.85),(67,False,1.0),(83,True,0.95)]
    blurs = {11:2.2,23:1.8,37:1.0,51:0.0,67:0.0,83:0.6}
    for seed,warm,depth in specs:
        c = ribbon(seed,warm,depth,t)
        b = blurs[seed]
        if b>0:
            im = Image.fromarray(np.clip(c,0,255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(b))
            c = np.asarray(im,float)
        acc += c
    # radial background brighten center-left
    yy,xx = np.mgrid[0:H,0:W]
    r = np.sqrt(((xx-W*0.33)/W)**2 + ((yy-H*0.5)/H)**2)
    acc += np.array([14,20,42])[None,None,:]*np.exp(-(r*2.1)**2)[...,None]
    acc += np.array([6,8,20])[None,None,:]
    # tonemap -> hot cores blow to white
    acc = 255*(1-np.exp(-acc/150))
    return np.clip(acc,0,255).astype(np.uint8)

Image.fromarray(frame(0.0)).save(r"D:\projects\Folio\outputs\sim\ribbon_still.jpg",quality=82)
fr=[Image.fromarray(frame(f/90)) for f in range(90)]
fr[0].save(r"D:\projects\Folio\outputs\sim\ribbon.gif",save_all=True,append_images=fr[1:],
           duration=66,loop=0,optimize=True)
print("wrote ribbon_still.jpg and ribbon.gif")
