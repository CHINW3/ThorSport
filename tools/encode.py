import sys, os, io, json, base64, colorsys
from PIL import Image, features
d, out = sys.argv[1], sys.argv[2]
assert features.check('webp'), 'no webp'
P = {
 # key: (filename fragment, max px)
 'l-nhl':('Property 1=NHL Logo',96),'l-nba':('Property 1=NBA Logo',96),'l-nfl':('Property 1=NFL Logo',96),
 'l-mlb':('Property 1=MLB Logo',96),'l-mls':('Property 1=MLS',96),
 'tor':('Team=Toronto Maple Leafs',96),'mtl':('Team=Montreal Canadiens',96),'bos':('Team=Boston Bruins',96),
 'edm':('Team=Edmonton Oilers',96),'vgk':('Team=Vegas Golden Knights',96),'nyr':('Team=New York Rangers',96),
 'sea':('Team=Seattle Kraken',96),'dal':('Team=Dallas Stars',96),'fla':('Team=Florida Panthers',96),
 'ott':('Team=Ottawa Senators',96),
 'nyk':('Team=New York Knicks, Alt=Default',96),'lal':('Team=Los Angles Lakers, Alt=Default',96),
 'bosc':('Team=Boston Celtics, Alt=Default',96),'gsw':('Team=Golden State Warriors, Alt=Default',96),
 'rap':('Team=Toronto Raptors, Alt=Default',96),'mia':('Team=Miami Heat, Alt=Default',96),
 'buf':('Team=Buffalo Bills',96),'kc':('Team=Kansas City Chiefs',96),'phi':('Team=Philadelphia Eagles',96),
 'sf':('Team=San Francisco 49ers',96),'gb':('Team=Green Bay Packers',96),
 'nyy':('Team=New York Yankees, Type=Default',96),'tbj':('Team=Toronto Blue Jays, Type=Default',96),
 'chc':('Team=Chicago Cubs, Type=Default',96),'lad':('Team=Los Angeles Dodgers, Type=Default',96),
 'mia-fc':('Team=Inter Miami CF',96),'lafc':('Team=LA FC',96),'tfc':('Team=Toronto FC',96),'sdr':('Team=Seattle Sounders FC',96),
}
files = os.listdir(d)
res = {}; total = 0
for k,(frag,mx) in P.items():
    m = [f for f in files if frag in f]
    assert len(m)==1, (k, m)
    im = Image.open(os.path.join(d,m[0])).convert('RGBA')
    bb = im.getbbox(); im = im.crop(bb)
    im.thumbnail((mx,mx), Image.LANCZOS)
    # glow color: weighted hue of saturated, opaque pixels
    sm = im.copy(); sm.thumbnail((48,48))
    buckets = {}
    for r,g,b,a in sm.getdata():
        if a < 200: continue
        h,l,s = colorsys.rgb_to_hls(r/255,g/255,b/255)
        if s < .35 or l < .12 or l > .9: continue
        key = round(h*24)%24
        w = s * (1 - abs(l-.5))
        bk = buckets.setdefault(key,[0,0,0,0]); bk[0]+=w; bk[1]+=r*w; bk[2]+=g*w; bk[3]+=b*w
    if buckets:
        bk = max(buckets.values(), key=lambda x:x[0])
        r,g,b = bk[1]/bk[0], bk[2]/bk[0], bk[3]/bk[0]
        h,l,s = colorsys.rgb_to_hls(r/255,g/255,b/255)
        l = min(max(l,.55),.65); s = max(s,.7)
        r,g,b = colorsys.hls_to_rgb(h,l,s)
        col = '#%02x%02x%02x' % (round(r*255),round(g*255),round(b*255))
    else: col = '#9fb4d9'
    buf = io.BytesIO(); im.save(buf,'WEBP',quality=84,method=6)
    b64 = base64.b64encode(buf.getvalue()).decode()
    total += len(b64)
    res[k] = {'uri':'data:image/webp;base64,'+b64,'color':col,'w':im.width,'h':im.height}
json.dump(res, open(out,'w'))
print('total base64 KB', round(total/1024,1))
for k,v in res.items(): print(k, v['color'], v['w'], v['h'], round(len(v['uri'])/1024,1))
