import numpy as np
from PIL import Image, ImageFilter
TAU=6.2831853
W,H=360,760

COOL=np.array([ 70,150,255],float)
VIOL=np.array([150, 90,235],float)
WARM=np.array([255,140, 60],float)
STOPS=np.stack([COOL,VIOL,WARM,COOL])

def pal(ph):
    ph=ph-np.floor(ph); s=ph*3.0
    i=np.floor(s).astype(int); f=(s-i)[:,None]
    a=STOPS[i]; b=STOPS[i+1]
    return a*(1-f)+b*f

def make(M, NB, fan_lo, fan_hi, SIG, ampk, bloomk, expo, seed=7):
    RAD=3; _NORM=1.0/(2*np.pi*SIG*SIG)
    def splat(acc,px,py,col,amp):
        x0=np.round(px).astype(int); y0=np.round(py).astype(int)
        for dx in range(-RAD,RAD+1):
            for dy in range(-RAD,RAD+1):
                xi=x0+dx; yi=y0+dy
                ddx=xi-px; ddy=yi-py
                w=np.exp(-(ddx*ddx+ddy*ddy)/(2*SIG*SIG))*(_NORM*amp)
                m=(xi>=0)&(xi<W)&(yi>=0)&(yi<H)
                if not m.any(): continue
                np.add.at(acc,(yi[m],xi[m]),(w[m])[:,None]*col[m])
    RNG=np.random.default_rng(seed)
    U=2200; u=np.linspace(0,1,U); S=float(H)
    ANGLES=[90.0,20.0,0.0,160.0,55.0,115.0,140.0][:NB]; RNG.shuffle(ANGLES)
    BUND=[]
    for b in range(NB):
        ang=np.radians(ANGLES[b]+RNG.uniform(-22,22))
        cx=RNG.uniform(0.1,0.9)*W; cy=RNG.uniform(0.1,0.9)*H
        length=RNG.uniform(1.2,1.6); amp=RNG.uniform(0.12,0.20)
        f1=RNG.uniform(0.45,0.7); nf=RNG.uniform(0.4,0.65)
        fan=RNG.uniform(fan_lo,fan_hi); cross=RNG.uniform(0.10,0.22)
        tilt=RNG.uniform(-0.15,0.15); spd=RNG.uniform(0.5,0.9)*(1 if b%2 else -1)
        hue0=RNG.uniform(0,1)
        BUND.append(dict(ang=ang,cx=cx,cy=cy,length=length,amp=amp,f1=f1,nf=nf,fan=fan,cross=cross,tilt=tilt,spd=spd,hue0=hue0))
    m=np.linspace(-1,1,M)
    def frame(t):
        acc=np.zeros((H,W,3))
        for B in BUND:
            p=t*B['spd']; ca=np.cos(B['ang']); sa=np.sin(B['ang'])
            for k in range(M):
                mk=m[k]
                lx=(B['length']*(u-0.5)+B['tilt']*np.sin(TAU*0.5*u)+mk*B['cross']*0.04*np.cos(TAU*B['nf']*u+p))
                width=B['fan']*(0.45+0.55*np.sin(TAU*B['nf']*u+p*0.6))
                ly=B['amp']*np.sin(TAU*B['f1']*u+p)+mk*width
                X=B['cx']+(lx*ca-ly*sa)*S; Y=B['cy']+(lx*sa+ly*ca)*S
                dX=np.gradient(X); dY=np.gradient(Y)
                slope=np.abs(dY)/(np.hypot(dX,dY)+1e-6); crest=1.0-0.35*slope
                dens=0.6+0.4*(1-np.abs(np.sin(TAU*B['nf']*u+p*0.6)))
                ph=0.5+0.5*np.sin(TAU*u*0.5+p*0.2)+0.33*mk+B['hue0']
                col=pal(ph); edge=np.exp(-(mk*mk)*2.2)
                amp=(ampk*crest*(0.6+0.4*dens)*edge)
                splat(acc,X,Y,np.clip(col,0,255),amp)
        im=Image.fromarray(np.clip(acc,0,255).astype(np.uint8))
        bloom=im.filter(ImageFilter.GaussianBlur(2.2))
        out=np.asarray(im,float)+bloomk*np.asarray(bloom,float)
        out+=np.array([6,8,20])[None,None,:]
        out=255*(1-np.exp(-out/expo))
        return np.clip(out,0,255).astype(np.uint8)
    return frame(0.0)

# three candidates: progressively more air + more separated threads
# variants around B with fan-width floor (no collapse flares)
a=make(M=18,NB=5,fan_lo=0.28,fan_hi=0.40,SIG=0.85,ampk=5.0,bloomk=0.25,expo=185)
b=make(M=16,NB=6,fan_lo=0.30,fan_hi=0.42,SIG=0.85,ampk=4.6,bloomk=0.22,expo=175)
c=make(M=20,NB=5,fan_lo=0.26,fan_hi=0.38,SIG=0.9,ampk=5.2,bloomk=0.25,expo=190)
mont=np.concatenate([a,b,c],axis=1)
Image.fromarray(mont).save(r"D:\projects\Folio\outputs\sim\tune_montage.jpg",quality=88)
print("wrote tune_montage.jpg (A|B|C)")
