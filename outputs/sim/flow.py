import numpy as np
from PIL import Image, ImageFilter
TAU=6.2831853
W,H=480,320
COOL=np.array([60,140,255],float)
WARM=np.array([255,120,50],float)
MAG =np.array([230,70,160],float)

def curl(x,y,t):
    # smooth pseudo-curl noise from summed sines (divergence-free-ish)
    a = np.sin(1.7*x+0.6*t)+np.cos(2.1*y-0.4*t)+0.5*np.sin(3.3*(x+y)+0.2*t)
    dyda = 1.7*np.cos(1.7*x+0.6*t)+1.65*np.cos(3.3*(x+y)+0.2*t)
    dxda = -2.1*np.sin(2.1*y-0.4*t)+1.65*np.cos(3.3*(x+y)+0.2*t)
    return dxda, -dyda

def frame(t):
    acc=np.zeros((H,W,3))
    N=90          # independent threads
    L=160         # steps per thread
    rng=np.random.default_rng(7)
    # seed points spread over a tall band on the left, drifting
    sx=rng.uniform(-0.2,1.2,N)
    sy=rng.uniform(0.05,0.95,N)
    phase=rng.uniform(0,TAU,N)
    px=sx.copy(); py=sy.copy()
    cmix=rng.uniform(0,1,N)
    col=COOL[None,:]*(1-cmix)[:,None]+WARM[None,:]*cmix[:,None]
    col=col*0.5+MAG[None,:]*0.5*np.sin(phase)[:,None]**2
    step=0.012
    for s in range(L):
        gx=px*5; gy=py*5
        dx,dy=curl(gx,gy,t+0.3)
        n=np.hypot(dx,dy)+1e-6
        px=px+step*dx/n
        py=py+step*dy/n
        # taper brightness along thread + head glow
        a=(np.sin(np.pi*s/L))**1.3
        ix=np.clip((px*W).astype(int),0,W-1)
        iy=np.clip((py*H).astype(int),0,H-1)
        np.add.at(acc,(iy,ix),col*a*1.4)
    im=Image.fromarray(np.clip(acc,0,255).astype(np.uint8))
    bloom=im.filter(ImageFilter.GaussianBlur(2.5))
    acc=np.asarray(im,float)+0.7*np.asarray(bloom,float)
    acc+=np.array([6,9,22])[None,None,:]
    acc=255*(1-np.exp(-acc/150))
    return np.clip(acc,0,255).astype(np.uint8)

Image.fromarray(frame(0.0)).save(r"D:\projects\Folio\outputs\sim\flow_still.jpg",quality=82)
fr=[Image.fromarray(frame(f/60*4)) for f in range(60)]
fr[0].save(r"D:\projects\Folio\outputs\sim\flow.gif",save_all=True,append_images=fr[1:],duration=66,loop=0,optimize=True)
print("wrote flow_still.jpg and flow.gif")
