from pathlib import Path
import re,json
s=(Path(__file__).resolve().parents[1] / 'app/src/main/java/com/vlesscardvpn/ui/theme/Theme.kt').read_text()
def lum(h):
 c=[int(h[i:i+2],16)/255 for i in (0,2,4)]
 c=[x/12.92 if x<=.04045 else ((x+.055)/1.055)**2.4 for x in c]
 return sum(x*y for x,y in zip(c,(.2126,.7152,.0722)))
def ratio(a,b):
 a,b=sorted((lum(a),lum(b)));return (b+.05)/(a+.05)
results=[]
for name,body in re.findall(r'private val (LightColorScheme|DarkColorScheme) = (?:light|dark)ColorScheme\((.*?)\n\)',s,re.S):
 vals={k:hexv[2:] for k,hexv in re.findall(r'(\w+) = Color\(0x([A-F0-9]{8})\)',body)};vals.update({k:'FFFFFF' for k in re.findall(r'(\w+) = Color.White',body)})
 for fg,bg in [('onBackground','background'),('onSurface','surface'),('onSurfaceVariant','surface'),('primary','surface'),('onPrimary','primary'),('tertiary','surface'),('error','surface'),('onPrimaryContainer','primaryContainer'),('secondary','secondaryContainer')]:
  value=ratio(vals[fg],vals[bg]);assert value>=4.5,(name,fg,bg,value)
  results.append({'theme':name,'pair':fg+'/'+bg,'ratio':round(value,2)})
print('Contrast:',len(results),'normal-text pairs pass WCAG AA')
