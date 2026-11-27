from PIL import Image, ImageDraw, ImageChops
from pathlib import Path
from math import sqrt, sin, exp, pi, cos
import subprocess

out=Path('build/flyme-orbita-hd')
out.mkdir(parents=True,exist_ok=True)
colors={
 'violeta':((108,65,255),(23,29,104),'Violeta'),
 'escarlate':((255,65,96),(106,16,53),'Coral'),
 'jade':((28,249,178),(8,85,89),'Jade'),
 'prata':((65,157,255),(23,48,123),'Azul aurora'),
 'lunar':((163,192,255),(13,20,49),'Lunar · radial'),
 'menta':((122,255,207),(7,35,42),'Menta · radial'),
}
backgrounds={key:(2,2,7) for key in colors}
W,H=1080,2340
SUPERSAMPLE=2
def planet(rim, depth, layer, radial):
 s=768
 pixels=bytearray()
 for y in range(s):
  for x in range(s):
   u,v=(2*x-s+1)/640,(2*y-s+1)/640
   d=sqrt(u*u+v*v)
   z=sqrt(max(.015,1-(d*.95)**2))
   if radial:
    strength=.82*exp(-z*5.4)+.28*exp(-z*1.9)
    strength=min(1,strength*(.82+.28*max(0,-.52*u-.85*v)))
    color=[round(base+(edge-base)*strength) for base,edge in zip(depth,rim)]
   else:
    vertical=max(0,min(1,(v+.64)/1.28))
    top=tuple(round(h*.91+b*.09) for h,b in zip(rim,depth))
    rotated=(rim[2],rim[0],rim[1])
    bottom=tuple(round(b*.84+h*.16) for b,h in zip(depth,rotated))
    color=[round(a*(1-vertical)+b*vertical) for a,b in zip(top,bottom)]
    if layer:
     color=[round(value*.51+base*.43) for value,base in zip(color,depth)]
    shade=.52+.48*min(1,d)**1.25
    edge=.44*min(1,d)**5
    glow=[round(c+(255-c)*.52) for c in rim]
    color=[round(value*shade*(1-edge)+highlight*edge) for value,highlight in zip(color,glow)]
   pixels.extend(color)
   pixels.append(round(255*((.60 if radial else .33 if layer else .57)+.21*min(1,d)**1.65)))
 return Image.frombytes('RGBA',(s,s),bytes(pixels))

for key,(rim,depth,label) in colors.items():
 radial=key in {"lunar","menta"}
 layer_count=1 if radial else 2
 textures=[]
 for layer in range(layer_count):
  mix=layer*.035
  hue=tuple(round(a*(1-mix)+b*mix) for a,b in zip(rim,depth))
  secondary=tuple(round(a*(1-mix)+b*mix) for a,b in zip(depth,rim))
  textures.append(planet(hue,secondary,layer,radial))
 for wake,suffix,count in [(True,'-ligar',36),(False,'-desbloquear',61)]:
  proc=subprocess.Popen(['ffmpeg','-loglevel','error','-y','-threads','1','-f','rawvideo','-pix_fmt','rgb24','-s',f'{W}x{H}','-r','30','-i','-','-c:v','libx264','-threads','1','-crf','13','-filter_threads','1','-vf','format=yuv420p,gradfun=strength=0.9:radius=20','-pix_fmt','yuv420p','-movflags','+faststart',str(out/(key+suffix+'.mp4'))],stdin=subprocess.PIPE)
  for i in range(count):
   t=0 if wake else min(1,i/52)
   t=t*t*(3-2*t)
   w=min(1,i/35) if wake else 1
   w=w*w*(3-2*w)
   frame=Image.new('RGB',(W,H),backgrounds[key])
   for layer in range(layer_count):
    cx=180+200*w+60*t+layer*(2+9*w)
    cy=156+257*w-48*t+layer*(1+5*w)
    radius=41+292*w+294*t
    amp=(3.4+15*w)*(0.6+0.4*sin(pi*t))
    phase=((i-35 if wake else i)/30)*(4.1+0.31*layer)+layer*2.35
    rings=[]
    for step in range(540):
     angle=2*pi*step/540
     length=radius+amp*(sin(7*angle+phase)+.40*sin(11*angle-phase*1.24)+.18*sin(16*angle+phase*1.53))
     rings.append(((cx+length*cos(angle))*3*SUPERSAMPLE,(cy+length*sin(angle))*3*SUPERSAMPLE))
    mask=Image.new('L',(W*SUPERSAMPLE,H*SUPERSAMPLE))
    ImageDraw.Draw(mask).polygon(rings,fill=255)
    mask=mask.resize((W,H),Image.Resampling.BOX)
    scale=320/(radius+amp*.40)
    texture=textures[layer].transform((W,H),Image.Transform.AFFINE,(scale/3,0,384-cx*scale,0,scale/3,384-cy*scale),Image.Resampling.BILINEAR)
    frame.paste(texture,(0,0),ImageChops.multiply(mask,texture.getchannel("A")))
   if i==0: frame.save(out/(key+suffix+'.webp'),lossless=True)
   proc.stdin.write(frame.tobytes())
  proc.stdin.close()
  assert proc.wait()==0

buttons=''.join(f'<button data-color="{k}">{v[2]}</button>' for k,v in colors.items())
(out/'previa.html').write_text(f"""<!doctype html><html lang="pt-BR"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Órbita</title><style>body{{margin:0;background:#101119;color:#f0eef9;font:16px system-ui;display:grid;justify-items:center;gap:14px;padding:20px}}video{{height:min(74vh,660px);max-width:100%;border-radius:24px;background:#05060c}}button{{background:#27283b;color:white;border:1px solid #717178;padding:9px 12px;border-radius:8px;margin:3px;cursor:pointer}}button[aria-pressed=true]{{background:#5d418e}}h1{{margin:0}}</style><h1>Órbita</h1><video controls muted playsinline preload="metadata" poster="violeta-ligar.webp" src="violeta-ligar.mp4"></video><div>{buttons}<button id="wake">Simular ligar tela</button><button id="play">Simular desbloqueio</button></div><script>const v=document.querySelector('video'), b=[...document.querySelectorAll('[data-color]')]; let color='violeta';function play(stage,auto=true){{v.poster=color+'-'+stage+'.webp';v.src=color+'-'+stage+'.mp4';if(auto)v.play().catch(()=>{{}})}}b[0].setAttribute('aria-pressed',true);b.forEach(btn=>btn.onclick=()=>{{color=btn.dataset.color;b.forEach(x=>x.setAttribute('aria-pressed',x===btn));play('ligar',false)}});document.querySelector('#wake').onclick=()=>play('ligar');document.querySelector('#play').onclick=()=>play('desbloquear');</script></html>""", encoding='utf-8')
