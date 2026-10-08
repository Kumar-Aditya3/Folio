import numpy as np
from PIL import Image, ImageFilter
TAU=6.2831853
W,H=360,760

# ---- palette: color travels along each ribbon ----
COOL=np.array([ 70,150,255],float)
VIOL=np.array([150, 90,235],float)
PINK=np.array([235,100,180],float)
WARM=np.array([255,140, 60],float)
STOPS=np.stack([COOL,VIOL,PINK,WARM,COOL])
NS=len(STOPS)-1
def pal(ph):
    ph=ph-np.floor(ph); s=ph*NS
    i=np.floor(s).astype(int); f=(s-i)[:,None]
    return STOPS[i]*(1-f)+STOPS[i+1]*f

# ---- ribbon definitions: asymmetric angles spanning the frame ----
# each: start (fractional canvas), flow angle deg, meander amps/freqs/phase,
#       twist freq/phase, hue offset, width
# grand sweeping ribbons: long low-freq meander, slow single-ish twist (few/no waists)
RIB=[
  dict(sx=-0.15,sy=0.22, ang=-14, a1=0.26,f1=0.42,p1=0.0, a2=0.08,f2=1.0,p2=1.3, tf=0.60,tp=0.2, hue=0.00, wmax=0.15),
  dict(sx=-0.10,sy=0.62, ang=  8, a1=0.30,f1=0.36,p1=1.7, a2=0.07,f2=0.9,p2=0.4, tf=0.50,tp=1.1, hue=0.24, wmax=0.17),
  dict(sx=-0.05,sy=1.02, ang= 30, a1=0.22,f1=0.48,p1=3.0, a2=0.07,f2=1.1,p2=2.1, tf=0.70,tp=2.0, hue=0.52, wmax=0.13),
  dict(sx= 1.10,sy=0.18, ang=196, a1=0.28,f1=0.40,p1=0.8, a2=0.08,f2=1.0,p2=3.4, tf=0.55,tp=0.7, hue=0.70, wmax=0.16),
  dict(sx= 1.05,sy=0.58, ang=172, a1=0.24,f1=0.52,p1=2.4, a2=0.07,f2=1.1,p2=1.0, tf=0.65,tp=1.6, hue=0.88, wmax=0.13),
  dict(sx= 0.25,sy=-0.12,ang= 60, a1=0.26,f1=0.44,p1=1.2, a2=0.07,f2=0.9,p2=2.7, tf=0.58,tp=0.4, hue=0.40, wmax=0.13),
]

K=64          # cross-section samples -> continuous density across the ribbon normal
U=900         # samples along each ribbon
SIG=3.0; RAD=4; _NORM=1.0/(2*np.pi*SIG*SIG)   # fat splat -> no hard thread edges, smooth gas
TWFLOOR=0.60  # keep ribbons broad; never collapse to a pinch node
XSIG=0.52     # std-dev of the gaussian cross-section probability distribution (in q units)
EDGEK=0.42    # how sharply steep (edge-on) sections pinch + brighten -> breathing nodes
DIAG=np.hypot(W,H)
# precompute cross-section probability distribution: a gaussian cloud across the normal
QS=np.linspace(-1,1,K)
QPROF=np.exp(-(QS*QS)/(2*XSIG*XSIG))          # soft-tailed density, no edge

def ribbon_points(r, t):
    """returns px,py,col,amp for a smooth gaussian gas cloud of one ribbon at time t."""
    s=np.linspace(0,1,U)
    th=np.deg2rad(r['ang'])
    d=np.array([np.cos(th),np.sin(th)])
    n=np.array([-np.sin(th),np.cos(th)])
    start=np.array([r['sx']*W, r['sy']*H])
    base=start[None,:]+ d[None,:]*(s[:,None]*DIAG*1.25)
    # grand low-freq meander along the normal
    arg1=TAU*r['f1']*s+r['p1']+t*0.9
    arg2=TAU*r['f2']*s+r['p2']-t*0.6
    mnd=( r['a1']*np.sin(arg1) + r['a2']*np.sin(arg2) )*DIAG
    center=base + n[None,:]*mnd[:,None]
    # local tangent / normal (finite diff) for proper ribbon orientation
    dc=np.gradient(center,axis=0)
    tl=np.hypot(dc[:,0],dc[:,1])+1e-6
    T=dc/tl[:,None]
    N=np.stack([-T[:,1],T[:,0]],axis=1)
    # ---- ribbon.py-style breathing: edge-on compression drives density ----
    # slope of the meander along length; steep => sheet turns edge-on
    slope=( r['a1']*TAU*r['f1']*np.cos(arg1)
          - r['a2']*TAU*r['f2']*np.cos(arg2) )        # d(mnd/DIAG)/ds
    edge=1.0/np.sqrt(1.0+(slope*EDGEK)**2)            # 1 face-on, ->0 edge-on
    # width envelope: gentle taper * slow traveling twist, pinched where edge-on
    taper=np.sin(np.pi*s)**0.3
    twist=np.abs(np.cos(TAU*r['tf']*s+r['tp']+t*1.1))*(1-TWFLOOR)+TWFLOOR
    w=r['wmax']*DIAG*taper*twist*(0.30+0.70*edge)     # compress at edge-on nodes
    dens=0.55+1.55*(1-edge)                           # brighten where compressed -> breathing
    # color travels along length + drifts in time
    col=pal(s*0.6 + r['hue'] + t*0.05)                # (U,3)
    PX=[];PY=[];CL=[];AM=[]
    for j in range(K):
        q=QS[j]
        off=q*w                                       # smooth gaussian cross-section, no jitter edges
        p=center + N*off[:,None]
        amp=QPROF[j]*dens                             # probability density * breathing brightness
        PX.append(p[:,0]);PY.append(p[:,1]);CL.append(col);AM.append(amp)
    return (np.concatenate(PX),np.concatenate(PY),
            np.concatenate(CL,axis=0),np.concatenate(AM))

def render(t, ampk=0.52, expo=235):
    acc=np.zeros((H,W,3),float)
    for r in RIB:
        px,py,col,am=ribbon_points(r,t)
        x0=np.round(px).astype(int); y0=np.round(py).astype(int)
        for dx in range(-RAD,RAD+1):
            for dy in range(-RAD,RAD+1):
                xi=x0+dx; yi=y0+dy
                ddx=xi-px; ddy=yi-py
                wgt=np.exp(-(ddx*ddx+ddy*ddy)/(2*SIG*SIG))*(_NORM*ampk)*am
                m=(xi>=0)&(xi<W)&(yi>=0)&(yi<H)
                if not m.any():continue
                np.add.at(acc,(yi[m],xi[m]),(wgt[m])[:,None]*col[m])
    out=acc+np.array([6,8,20])[None,None,:]
    out=255*(1-np.exp(-out/expo))
    base=np.clip(out,0,255).astype(np.uint8)
    # heavy soft bloom -> dissolve any residual structure into smooth nebular gas
    im=Image.fromarray(base)
    glow=im.filter(ImageFilter.GaussianBlur(9))
    comp=np.clip(0.72*base.astype(float)+0.55*np.asarray(glow,float),0,255).astype(np.uint8)
    return comp

if __name__=="__main__":
    import sys
    if "--gif" in sys.argv:
        N=48; frames=[]
        for k in range(N):
            t=TAU*k/N
            frames.append(Image.fromarray(render(t)))
            print(f"frame {k+1}/{N}")
        frames[0].save(r"D:\projects\Folio\outputs\sim\ribbons.gif",
                       save_all=True,append_images=frames[1:],
                       duration=70,loop=0,optimize=True)
        print("wrote ribbons.gif")
    else:
        img=render(0.0)
        Image.fromarray(img).save(r"D:\projects\Folio\outputs\sim\ribbons_still.jpg",quality=90)
        print("wrote ribbons_still.jpg")
