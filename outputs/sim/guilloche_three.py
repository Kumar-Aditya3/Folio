"""
Three motion models, side by side, over one lap:
  OLD      - current shader: 3 wave families summed, unison walk (a folding sheet)
  NEW      - current shader + per-fibre lane desync (my edit): wavier, less rigid
  CONCEPT  - true independent strands: N discrete threads, each its own smooth
             curve + its own drift, so each thread moves on its own into a curve
"""
import numpy as np
from PIL import Image, ImageDraw

TAU = 6.2831853
W, H = 230, 470
ASPECT = W / H
ux = (np.arange(W) + 0.5) / W
uy = (np.arange(H) + 0.5) / H
UX, UY = np.meshgrid(ux, uy)
PX = (UX - 0.5) * ASPECT
PY = (UY - 0.5)

FAMILY = 6
SS = 0.16
CH_FREQ, CH_BRAID, CH_WEIGHT = 1.35, 1.60, 0.80
character = 0.5
base = -2.0

def mkwave(i, fr, cr, cf, ao, dr):
    return dict(freq=fr*CH_FREQ*SS, cross=cr*(1+0.45*character)*CH_BRAID,
                crossFreq=cf, angle=base+ao, weight=CH_WEIGHT,
                share=(1.0,0.45,0.25)[i], drift=dr)
WAVES=[mkwave(0,5.5,2.5,1.35,-0.6,1.0),mkwave(1,10.75,2.5,1.3,0.0,-2.0),
       mkwave(2,19.0,2.5,1.2,0.6,3.0)]

def fwarp(m,b): return 1.0*np.sin(m*0.85+b)+0.62*np.sin(m*2.55-b)
def wline(freq,cross,cf,ang,w,walk,braid,new):
    dx,dy=np.cos(ang),np.sin(ang)
    v=PX*dx+PY*dy; m=PX*(-dy)+PY*dx
    warp=fwarp(m,braid)*cross*2.4
    arg=(v*freq+warp+walk)*TAU
    if new:
        lane=np.sin((v*freq*0.73)*TAU-walk*2.0*TAU)
        s=np.clip(0.5+0.5*np.sin(arg+0.85*lane),0,1)
    else:
        s=np.clip(0.5+0.5*np.sin(arg),0,1)
    k=np.clip(4.2*w,2,10); core=s**k; halo=s**(k*0.42)
    streak=0.5+0.5*np.sin(m*cf*3+braid*2)
    return (core*0.62+halo*0.38)*(0.72+0.28*streak*streak)
def plate(turns,breath,new):
    ph=turns*TAU; acc=np.zeros((H,W))
    bf=[1+0.06*np.sin(breath),1-0.06*np.sin(breath*2+1.1),1+0.06*np.cos(breath*3+2.3)]
    for i,wv in enumerate(WAVES):
        acc+=wline(wv['freq'],wv['cross'],wv['crossFreq'],wv['angle'],wv['weight'],
                   wv['drift']*turns,np.sign(wv['drift'])*ph,new)*wv['share']*bf[i]
    raw=acc*0.30; weave=raw/(1+raw)
    a0=WAVES[0]['angle']
    md=PX*(-np.sin(a0))+PY*np.cos(a0)
    micro=np.clip(0.5+0.5*np.sin((md*WAVES[0]['freq']*6+turns*7)*TAU),0,1)**5
    return weave+micro*0.15

# ---- CONCEPT: independent strands ----
rng=np.random.default_rng(7)
NSTR=46
strands=[]
for n in range(NSTR):
    y0=(n+0.5)/NSTR
    comps=[(rng.uniform(0.6,2.4), rng.uniform(0.02,0.09), rng.uniform(0,TAU),
            rng.uniform(-1,1)) for _ in range(3)]  # freq, amp, phase, driftSign
    drift=rng.uniform(0.5,1.6)*(1 if rng.random()<0.5 else -1)
    thick=rng.uniform(0.9,1.8)
    strands.append((y0,comps,drift,thick))
XS=np.linspace(0,1,260)
def concept(turns):
    img=np.zeros((H,W))
    for y0,comps,drift,thick in strands:
        y=np.full_like(XS,y0)
        for f,a,p,ds in comps:
            y=y+a*np.sin(TAU*f*XS+p+ds*drift*turns*TAU)
        px=(XS*(W-1)).astype(int)
        py=(y*(H-1))
        for xi,yf in zip(px,py):
            yc=int(round(yf))
            for dy in range(-2,3):
                yy=yc+dy
                if 0<=yy<H:
                    img[yy,xi]=max(img[yy,xi],np.exp(-(dy/thick)**2))
    return img

def stretch(a):
    lo,hi=np.percentile(a,2),np.percentile(a,98)
    if hi-lo<1e-6: hi=lo+1e-6
    return np.clip((a-lo)/(hi-lo),0,1)
def colorize(cov,do_stretch=True):
    if do_stretch: cov=stretch(cov)
    b=np.array([14,60,72]); t=np.array([150,235,180])
    return (b[None,None,:]+(t-b)[None,None,:]*cov[...,None]).astype(np.uint8)

FR=60; frames=[]
for f in range(FR):
    turns=f/FR; breath=turns*TAU*(53/79)
    old=colorize(plate(turns,breath,False))
    new=colorize(plate(turns,breath,True))
    con=colorize(concept(turns),do_stretch=False)
    gap=np.full((H,6,3),20,np.uint8)
    im=Image.fromarray(np.concatenate([old,gap,new,gap,con],axis=1))
    d=ImageDraw.Draw(im)
    d.text((6,6),"OLD",fill=(255,255,255))
    d.text((W+12,6),"NEW",fill=(255,255,255))
    d.text((2*W+18,6),"CONCEPT",fill=(255,255,255))
    frames.append(im)
out=r"D:\projects\Folio\outputs\sim\guilloche_three.gif"
frames[0].save(out,save_all=True,append_images=frames[1:],duration=70,loop=0,optimize=True)
print("wrote",out)
# small inspect montage
steps=[0.0,0.25,0.5,0.75]
rows=[]
for lbl,fn in [("OLD",lambda t:colorize(plate(t,t*TAU*53/79,False))),
               ("NEW",lambda t:colorize(plate(t,t*TAU*53/79,True))),
               ("CONCEPT",lambda t:colorize(concept(t),False))]:
    cols=[]
    for t in steps:
        cols.append(fn(t)); cols.append(np.full((H,4,3),20,np.uint8))
    rows.append(np.concatenate(cols,axis=1)); rows.append(np.full((5,rows[-1].shape[1],3),20,np.uint8))
m=np.concatenate(rows,axis=0)
Image.fromarray(m).resize((int(m.shape[1]*0.62),int(m.shape[0]*0.62))).convert('RGB').save(
    r"D:\projects\Folio\outputs\sim\three_montage.jpg",quality=72)
print("wrote montage")
