import numpy as np
from PIL import Image, ImageFilter
TAU = 6.2831853
W, H = 480, 320

COOL = np.array([60,140,255], float)
WARM = np.array([255,120,50], float)
MAG  = np.array([230,70,160], float)

def warp_field(t):
    """A deforming parametric lattice. Two families (u,v) mapped through a
    time-varying warp so the whole net twists; line thickness varies with
    local stretch, crossings accumulate -> glow."""
    acc = np.zeros((H, W, 3))
    # parametric grid in [0,1]^2 sampled densely, pushed through warp to screen
    nu, nv = 70, 46            # lattice lines each family
    su, sv = 900, 900          # samples along each line
    u = np.linspace(0, 1, nu)
    v = np.linspace(0, 1, su)
    # warp params (asymmetric, slow drift)
    def warp(uu, vv, t):
        x = uu[:,None] + 0.10*np.sin(TAU*(1.3*vv[None,:]+0.2*t))
        y = vv[None,:] + 0.08*np.sin(TAU*(1.1*uu[:,None]+0.17*t))
        # global twist
        ang = 0.6*np.sin(TAU*0.1*t)
        cx, cy = 0.5, 0.5
        xr = cx + (x-cx)*np.cos(ang) - (y-cy)*np.sin(ang)
        yr = cy + (x-cx)*np.sin(ang) + (y-cy)*np.cos(ang)
        # breathing bulge
        r = np.sqrt((xr-0.4)**2+(yr-0.5)**2)
        bulge = 1 + 0.25*np.sin(TAU*(0.7*r-0.3*t))
        xr = 0.4 + (xr-0.4)*bulge
        yr = 0.5 + (yr-0.5)*bulge
        return xr, yr
    def splat(xr, yr, col, dens):
        px = np.clip((xr*W).astype(int),0,W-1)
        py = np.clip((yr*H).astype(int),0,H-1)
        np.add.at(acc, (py.ravel(), px.ravel()), col*dens)
    # warp family (constant u)
    xr,yr = warp(u, v, t)
    # local stretch -> thickness/brightness
    dx = np.gradient(xr,axis=1); dy = np.gradient(yr,axis=1)
    stretch = np.sqrt(dx**2+dy**2)
    dens = (1.0/ (1e-3+stretch*W))*0.9
    cmix = np.broadcast_to(u[:,None], xr.shape)
    col = COOL[None,None,:]*(1-cmix)[...,None] + WARM[None,None,:]*cmix[...,None]
    splat(xr,yr,col.reshape(-1,3), dens.reshape(-1,1))
    # weft family (constant v)
    u2 = np.linspace(0,1,su); v2 = np.linspace(0,1,nv)
    xr2,yr2 = warp(u2, v2, t+5)
    xr2,yr2 = xr2.T, yr2.T
    dx2 = np.gradient(xr2,axis=1); dy2=np.gradient(yr2,axis=1)
    stretch2=np.sqrt(dx2**2+dy2**2)
    dens2=(1.0/(1e-3+stretch2*W))*0.9
    cmix2=np.broadcast_to(v2[:,None], xr2.shape)
    col2 = WARM[None,None,:]*(1-cmix2)[...,None] + MAG[None,None,:]*cmix2[...,None]
    splat(xr2,yr2,col2.reshape(-1,3),dens2.reshape(-1,1))
    # bloom + background
    im = Image.fromarray(np.clip(acc,0,255).astype(np.uint8))
    bloom = im.filter(ImageFilter.GaussianBlur(3))
    acc = np.asarray(im,float) + 0.6*np.asarray(bloom,float)
    acc += np.array([6,9,22])[None,None,:]
    acc = 255*(1-np.exp(-acc/150))
    return np.clip(acc,0,255).astype(np.uint8)

Image.fromarray(warp_field(0.0)).save(r"D:\projects\Folio\outputs\sim\mesh_still.jpg",quality=82)
fr=[Image.fromarray(warp_field(f/60*4)) for f in range(60)]
fr[0].save(r"D:\projects\Folio\outputs\sim\mesh.gif",save_all=True,append_images=fr[1:],duration=66,loop=0,optimize=True)
print("wrote mesh_still.jpg and mesh.gif")
