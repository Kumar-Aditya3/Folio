import numpy as np
from PIL import Image, ImageFilter
TAU=6.2831853
W,H=360,760

# ---- theme palette: 3 interweaving stops; in the app these come from the theme ----
COOL=np.array([ 70,150,255],float)   # blue
VIOL=np.array([150, 90,235],float)   # violet
WARM=np.array([255,140, 60],float)   # amber
STOPS=np.stack([COOL,VIOL,WARM,COOL])        # cyclic

def pal(ph):
    # ph: array in R -> cyclic blend across the 3 interweaving hues
    ph=ph-np.floor(ph)
    s=ph*3.0
    i=np.floor(s).astype(int); f=(s-i)[:,None]
    a=STOPS[i]; b=STOPS[i+1]
    return a*(1-f)+b*f

# continuous thread: deposit each sample as a SOFT gaussian cross-section
# spread over a (2r+1)^2 neighbourhood. A 1px bilinear trail reads hard/grainy;
# a gaussian filament (sigma ~ 1px) gives a feathered, glowing line whose edges
# fall off smoothly -> the "soft line" look of the concept art.
SIG=1.05                     # filament half-width (px): softness of each thread
RAD=3                        # neighbourhood radius for the gaussian footprint
_NORM=1.0/(2*np.pi*SIG*SIG)  # keep per-sample energy constant vs footprint size
def splat(acc, px, py, col, amp):
    x0=np.round(px).astype(int); y0=np.round(py).astype(int)
    for dx in range(-RAD,RAD+1):
        for dy in range(-RAD,RAD+1):
            xi=x0+dx; yi=y0+dy
            ddx=xi-px; ddy=yi-py                     # true sub-pixel offset
            w=np.exp(-(ddx*ddx+ddy*ddy)/(2*SIG*SIG))*(_NORM*amp)
            m=(xi>=0)&(xi<W)&(yi>=0)&(yi<H)
            if not m.any(): continue
            np.add.at(acc, (yi[m],xi[m]), (w[m])[:,None]*col[m])

# ---- bundles: grand guilloche sheets scattered across the frame at varied angles ----
RNG=np.random.default_rng(7)
NB=7                      # ribbons spread over the screen, fewer gaps
M=56                      # threads per ribbon (translucent sheet)
U=2400                    # dense sub-pixel sampling -> continuous smooth thread
u=np.linspace(0,1,U)
S=float(H)                # isotropic unit (pixels) for rotation

# wide orientation spread across the full 180 deg so sheets face every way
ANGLES=[90.0, 20.0, 0.0, 160.0, 55.0, 115.0, 140.0]
RNG.shuffle(ANGLES)

BUND=[]
for b in range(NB):
    ang = np.radians(ANGLES[b] + RNG.uniform(-22,22))
    cx  = RNG.uniform(0.12,0.88)*W           # anchors reach near the edges
    cy  = RNG.uniform(0.12,0.88)*H
    length=RNG.uniform(1.2,1.6)              # longer sweeps fill the frame
    amp = RNG.uniform(0.12,0.20)             # grand curve reach
    f1  = RNG.uniform(0.45,0.7)              # one long gentle S along the ribbon
    nf  = RNG.uniform(0.4,0.65)              # fewer, gentler nodes -> softer lenses
    fan = RNG.uniform(0.16,0.24)             # sheet opening width
    cross=RNG.uniform(0.10,0.22)             # light shear -> soft crossings, no lattice
    tilt= RNG.uniform(-0.15,0.15)
    spd = RNG.uniform(0.5,0.9)*(1 if b%2 else -1)
    hue0= RNG.uniform(0,1)                   # per-bundle hue offset
    BUND.append(dict(ang=ang,cx=cx,cy=cy,length=length,amp=amp,f1=f1,nf=nf,
                     fan=fan,cross=cross,tilt=tilt,spd=spd,hue0=hue0))

m=np.linspace(-1,1,M)                        # thread index within bundle

def frame(t):
    acc=np.zeros((H,W,3))
    for B in BUND:
        p=t*B['spd']
        ca=np.cos(B['ang']); sa=np.sin(B['ang'])
        for k in range(M):
            mk=m[k]
            # local ribbon frame: lx along the sweep, ly transverse
            lx=(B['length']*(u-0.5) + B['tilt']*np.sin(TAU*0.5*u)
                + mk*B['cross']*0.04*np.cos(TAU*B['nf']*u + p))
            width=B['fan']*np.sin(TAU*B['nf']*u + p*0.6)     # signed -> flips across node
            ly=B['amp']*np.sin(TAU*B['f1']*u + p) + mk*width
            # rotate the whole ribbon to its orientation, place at its anchor
            X=B['cx'] + (lx*ca - ly*sa)*S
            Y=B['cy'] + (lx*sa + ly*ca)*S
            # crest lighting from local tangent
            dX=np.gradient(X); dY=np.gradient(Y)
            slope=np.abs(dY)/(np.hypot(dX,dY)+1e-6)
            crest=1.0-0.35*slope
            dens=0.6+0.4*(1-np.abs(np.sin(TAU*B['nf']*u + p*0.6)))
            # interweaving colour: varies along arc AND across threads AND per bundle
            ph=0.5+0.5*np.sin(TAU*u*0.5+p*0.2) + 0.33*mk + B['hue0']
            col=pal(ph)
            edge=np.exp(-(mk*mk)*2.2)        # gaussian feather -> soft sheet edges
            amp=(9.0*crest*(0.6+0.4*dens)*edge)
            splat(acc,X,Y,np.clip(col,0,255),amp)
    im=Image.fromarray(np.clip(acc,0,255).astype(np.uint8))
    bloom=im.filter(ImageFilter.GaussianBlur(2.2))
    out=np.asarray(im,float)+0.35*np.asarray(bloom,float)
    out+=np.array([6,8,20])[None,None,:]           # navy base
    out=255*(1-np.exp(-out/240))                    # higher threshold -> more air
    return np.clip(out,0,255).astype(np.uint8)

Image.fromarray(frame(0.0)).save(r"D:\projects\Folio\outputs\sim\silk_still.jpg",quality=85)
fr=[Image.fromarray(frame(f/60*TAU)) for f in range(60)]
fr[0].save(r"D:\projects\Folio\outputs\sim\silk.gif",save_all=True,append_images=fr[1:],duration=66,loop=0,optimize=True)
print("wrote silk_still.jpg and silk.gif")
