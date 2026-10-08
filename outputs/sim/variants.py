import numpy as np
from PIL import Image, ImageDraw
TAU=6.2831853; W,H=230,470
XS=np.linspace(0,1,W); ROWS=np.arange(H)[:,None]

def build(P,seed):
    rng=np.random.default_rng(seed); strands=[]
    for n in range(P['NSTR']):
        base=(n+0.5)/P['NSTR']+rng.uniform(-1,1)*P['JIT']/P['NSTR']
        comps=[(rng.uniform(*P['FREQ']),rng.uniform(*P['AMP']),rng.uniform(0,TAU),
                rng.choice([-1,1])*rng.uniform(*P['DRIFT'])) for _ in range(P['COMPS'])]
        sway=(P['SWAY']*rng.uniform(0.4,1.0),rng.uniform(0.4,1.1)*rng.choice([-1,1]),rng.uniform(0,TAU))
        strands.append((base,comps,sway,rng.uniform(*P['THICK']),rng.uniform(P['DIM'],1.0)))
    strands.sort(key=lambda s:s[4]); return strands

def cov(strands,turns):
    img=np.zeros((H,W))
    for base,comps,sway,thick,depth in strands:
        y=np.full(W,base)
        for f,a,p,dr in comps: y=y+a*np.sin(TAU*f*XS+p+dr*turns*TAU)
        sa,sr,sp=sway; y=y+sa*np.sin(sr*turns*TAU+sp)
        d=ROWS-(y*(H-1))[None,:]
        img=np.maximum(img,np.exp(-(d/thick)**2)*depth)
    return img
def col(c):
    b=np.array([12,52,64]);t=np.array([156,240,188])
    return (b[None,None,:]+(t-b)[None,None,:]*c[...,None]).astype(np.uint8)

VARIANTS={
 "GRACEFUL":dict(NSTR=22,COMPS=2,FREQ=(0.35,0.95),AMP=(0.05,0.12),DRIFT=(0.3,0.9),
                 SWAY=0.022,THICK=(1.3,2.6),JIT=0.3,DIM=0.5),
 "FLOWING": dict(NSTR=30,COMPS=3,FREQ=(0.45,1.3),AMP=(0.035,0.09),DRIFT=(0.35,1.1),
                 SWAY=0.018,THICK=(1.1,2.2),JIT=0.35,DIM=0.47),
 "DENSE":   dict(NSTR=38,COMPS=3,FREQ=(0.5,2.0),AMP=(0.025,0.08),DRIFT=(0.4,1.4),
                 SWAY=0.015,THICK=(1.0,2.0),JIT=0.4,DIM=0.45),
}
rows=[]
for name,P in VARIANTS.items():
    s=build(P,hash(name)&0xffff)
    cols=[]
    for t in [0.0,0.25,0.5,0.75]:
        cols.append(col(cov(s,t))); cols.append(np.full((H,4,3),18,np.uint8))
    strip=np.concatenate(cols,axis=1)
    im=Image.fromarray(strip); ImageDraw.Draw(im).text((6,6),name,fill=(255,255,255))
    rows.append(np.array(im)); rows.append(np.full((6,strip.shape[1],3),18,np.uint8))
m=np.concatenate(rows,axis=0)
Image.fromarray(m).resize((int(m.shape[1]*0.6),int(m.shape[0]*0.6))).convert("RGB").save(
    r"D:\projects\Folio\outputs\sim\variants.jpg",quality=74)
# also a gif of GRACEFUL to feel the motion
s=build(VARIANTS["GRACEFUL"],hash("GRACEFUL")&0xffff)
fr=[Image.fromarray(col(cov(s,f/72))) for f in range(72)]
fr[0].save(r"D:\projects\Folio\outputs\sim\concept_graceful.gif",save_all=True,
           append_images=fr[1:],duration=66,loop=0,optimize=True)
print("wrote variants.jpg and concept_graceful.gif")
