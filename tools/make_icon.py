# usage: py tools/make_icon.py
# Writes the launcher icon (adaptive icon: background, foreground, monochrome) as vector drawables,
# and the same picture as docs/images/icon.svg for the README.
#
# The picture (drawn here as vectors, nothing taken from the game): the Buster Sword planted in
# the ground on the left, a green and a blue materia in its slots, under a dark sky burning
# orange at the horizon, with a turquoise meteor falling on the right. The sky and the meteor
# are the background layer, the sword and the ground the foreground (launchers move the two
# apart a little). 108-unit adaptive-icon canvas; what matters sits inside the 66-unit circle.
import math
import os

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
RES = os.path.join(ROOT, 'android', 'app', 'src', 'main', 'res')


# ---- a tiny scene model: groups (Android's transform: scale and rotate about a pivot, then
# translate) holding paths. Fills: ('solid', '#AARRGGBB'), ('stroke', '#AARRGGBB', width),
# ('linear', x1, y1, x2, y2, stops), ('radial', cx, cy, r, stops), in the path's own units.
class G:
    def __init__(self, items, px=0, py=0, rot=0, s=1, tx=0, ty=0):
        self.items, self.px, self.py, self.rot, self.s, self.tx, self.ty = items, px, py, rot, s, tx, ty


def circle(cx, cy, r):
    return f'M{cx - r:g},{cy:g} a{r:g},{r:g} 0 1,0 {2 * r:g},0 a{r:g},{r:g} 0 1,0 {-2 * r:g},0 Z'


def f2(v):
    return f'{v:.2f}'.rstrip('0').rstrip('.')


# ---- the sword, drawn tip up (tip y=15, pommel y=83); the planted group turns it over
STEEL = [(0, '#FF9AA3BD'), (1, '#FF3E4663')]
BLADE = 'M45,62 L45,15 L63,27 L63,62 Z'
SLOTS = circle(51.1, 51, 2.6) + ' ' + circle(51.1, 57, 2.6)
GUARD = 'M43.5,62 h21 v4.5 h-21 Z'
GRIP = 'M51.5,66.5 h5 v12.5 h-5 Z'
POMMEL = 'M50.5,79 h7 v2.5 a1.5,1.5 0 0,1 -1.5,1.5 h-4 a1.5,1.5 0 0,1 -1.5,-1.5 Z'
SWORD = [
    (BLADE, ('linear', 44, 0, 62, 0, [(0, '#FF5D6787'), (0.45, '#FFC9CFDF'), (1, '#FF8C96B4')])),
    ('M58.5,24 L63,27 L63,62 L58.5,62 Z', ('linear', 57, 0, 62, 0, [(0, '#FFC4CBDD'), (1, '#FFEEF1F8')])),   # edge
    ('M45,15 L45,62 L46.8,62 L46.8,16.2 Z', ('solid', '#4D1A2240')),                                          # spine
    (SLOTS, ('solid', '#FF2A0E08')),
    (circle(51, 51, 8), ('radial', 51, 51, 8, [(0, '#805CFF9A'), (1, '#002BD872')])),                          # green glow
    (circle(51, 57, 8), ('radial', 51, 57, 8, [(0, '#805AA8FF'), (1, '#002A70E0')])),                          # blue glow
    (circle(51.1, 51, 2.1), ('radial', 50.3, 50.3, 3, [(0, '#FFD8FFE6'), (0.35, '#FF6CF59E'), (1, '#FF0E8A43')])),
    (circle(51.1, 57, 2.1), ('radial', 50.3, 56.3, 3, [(0, '#FFE0F2FF'), (0.35, '#FF5AA8FF'), (1, '#FF0E3E9A')])),
    (GUARD, ('linear', 0, 62, 0, 66.5, STEEL)),
    (GRIP, ('solid', '#FF2A1E16')),
    ('M51.5,68 L56.5,70 M51.5,71 L56.5,73 M51.5,74 L56.5,76 M51.5,77 L56.5,79', ('stroke', '#FF6A5038', 1)),  # wrap
    (POMMEL, ('linear', 0, 79, 0, 83, STEEL)),
]
# planted: turned over and scaled 0.88 about (54,58), its middle moved to (43,54)
PLANTED = dict(px=54, py=58, rot=180, s=0.88, tx=-11, ty=-4)
GROUND = 'M0,84 Q30,76 54,80 T108,78 V108 H0 Z'


# ---- the meteor at (MX,MY), rock radius R, flames streaming up and to the right
MX, MY, R, DIR = 70, 38, 11, -45


def rnd(i):   # the same wobble every run
    x = math.sin(i * 12.9898 + 78.233) * 43758.5453
    return x - math.floor(x)


def ring(n, r, amp, seed):
    pts = []
    for i in range(n):
        a = i / n * 2 * math.pi
        rr = r * (1 + amp * (rnd(seed + i) * 2 - 1))
        pts.append(f'{f2(math.cos(a) * rr)},{f2(math.sin(a) * rr)}')
    return 'M' + ' L'.join(pts) + ' Z'


def spikes(n, r1, r2, seed):
    pts = []
    for i in range(n * 2):
        a = i / (n * 2) * 2 * math.pi
        rr = r1 if i % 2 else r2 * (0.8 + 0.4 * rnd(seed + i))
        pts.append(f'{f2(math.cos(a) * rr)},{f2(math.sin(a) * rr)}')
    return 'M' + ' L'.join(pts) + ' Z'


r = R
FLAME = (f'M0,{f2(-r * 1.15)} L{f2(r * 2.6)},{f2(-r * 0.9)} L{f2(r * 1.9)},{f2(-r * 0.45)} L{f2(r * 4.6)},{f2(-r * 0.3)} '
         f'L{f2(r * 2.4)},0 L{f2(r * 5.2)},{f2(r * 0.2)} L{f2(r * 2)},{f2(r * 0.5)} L{f2(r * 3.2)},{f2(r * 0.95)} L0,{f2(r * 1.15)} Z')
# lava cracks over the rock, in rock radii: the main seams, then finer ones branching off
def polylines(lines):
    return ' '.join('M' + ' L'.join(f'{f2(x * r)},{f2(y * r)}' for x, y in pts) for pts in lines)


CRACKS = polylines([
    [(-0.75, -0.25), (-0.4, -0.15), (-0.15, 0.05), (0.1, -0.35), (0.45, -0.1), (0.78, -0.22)],
    [(-0.15, 0.05), (-0.05, 0.5), (0.2, 0.62), (0.32, 0.78)],
    [(0.1, -0.35), (0.05, -0.7), (-0.15, -0.85)],
    [(0.45, -0.1), (0.55, 0.3), (0.78, 0.36)],
    [(-0.05, 0.5), (-0.45, 0.55), (-0.55, 0.65)],
])
FINE_CRACKS = polylines([
    [(-0.4, -0.15), (-0.5, -0.5), (-0.35, -0.72)],
    [(0.55, 0.3), (0.35, 0.42)],
    [(-0.45, 0.55), (-0.7, 0.3)],
    [(0.05, -0.7), (0.38, -0.62)],
    [(0.2, 0.62), (0.0, 0.8)],
    [(0.45, -0.1), (0.5, -0.45)],
    [(-0.15, 0.05), (-0.42, 0.22)],
])
ROCK = ring(18, r, 0.12, 11)
METEOR = G([
    (circle(0, 0, r * 3.2), ('radial', 0, 0, r * 3.2, [(0, '#4D20E0E0'), (0.5, '#1F0A9AA8'), (1, '#000A9AA8')])),   # sky glow
    G([(FLAME, ('linear', 0, 0, r * 5.2, 0, [(0, '#F2C8FFFF'), (0.35, '#992EE6E0'), (1, '#0003505E')]))], rot=DIR),
    (spikes(22, r * 1.1, r * 1.55, 3), ('radial', 0, 0, r * 1.75,
        [(0.45, '#FFC8FFFF'), (0.62, '#D92EE6E0'), (0.8, '#730A8A9A'), (1, '#0003404E')])),                         # fire
    G([(ROCK, ('radial', r * 0.24, r * 0.32, r * 1.6, [(0, '#FF0E6A72'), (0.45, '#FF06323C'), (1, '#FF021218')])),
       (CRACKS, ('stroke', '#E69FFFF8', f2(r * 0.09))),
       (FINE_CRACKS, ('stroke', '#B39FFFF8', f2(r * 0.05)))], rot=DIR + 180),
], tx=MX, ty=MY)

SKY = ('M0,0 h108 v108 h-108 Z', ('linear', 0, 0, 0, 108,
       [(0, '#FF0E0605'), (0.45, '#FF3E1408'), (0.72, '#FFA63A10'), (0.9, '#FFF07A24')]))

BACKGROUND = [SKY, METEOR]
FOREGROUND = [G(SWORD, **PLANTED), (GROUND, ('solid', '#FF0A0403'))]
MONO = [G([(f'{BLADE} {SLOTS}', ('solid_eo', '#FFFFFFFF')), (f'{GUARD} {GRIP} {POMMEL}', ('solid', '#FFFFFFFF'))], **PLANTED),
        G([G([(ROCK, ('solid', '#FFFFFFFF'))], rot=DIR + 180)], tx=MX, ty=MY),
        (GROUND, ('solid', '#FFFFFFFF'))]


# ---- Android vector drawables
HEAD = '''<?xml version="1.0" encoding="utf-8"?>
<!-- FF7Recomp launcher icon ({part}): the Buster Sword planted under a burning sky, a turquoise
     meteor falling (drawn as vectors, not taken from the game). Written by tools/make_icon.py;
     edit that, not this. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
'''


def a_stops(items):
    return ''.join(f'\n                <item android:offset="{o:g}" android:color="{c}"/>' for o, c in items)


def a_path(d, fill, ind):
    if fill[0] in ('solid', 'solid_eo'):
        eo = ' android:fillType="evenOdd"' if fill[0] == 'solid_eo' else ''
        return f'{ind}<path android:fillColor="{fill[1]}"{eo} android:pathData="{d}"/>\n'
    if fill[0] == 'stroke':
        return (f'{ind}<path android:strokeColor="{fill[1]}" android:strokeWidth="{fill[2]}" android:strokeLineCap="round" '
                f'android:strokeLineJoin="round" android:pathData="{d}"/>\n')
    if fill[0] == 'linear':
        _, x1, y1, x2, y2, st = fill
        g = (f'<gradient android:type="linear" android:startX="{f2(x1)}" android:startY="{f2(y1)}" '
             f'android:endX="{f2(x2)}" android:endY="{f2(y2)}">')
    else:
        _, cx, cy, rr, st = fill
        g = f'<gradient android:type="radial" android:centerX="{f2(cx)}" android:centerY="{f2(cy)}" android:gradientRadius="{f2(rr)}">'
    return f'''{ind}<path android:pathData="{d}">
{ind}    <aapt:attr name="android:fillColor">
{ind}        {g}{a_stops(st)}
{ind}        </gradient>
{ind}    </aapt:attr>
{ind}</path>
'''


def a_items(items, ind='    '):
    out = ''
    for it in items:
        if isinstance(it, G):
            attrs = ''
            for name, v, dflt in (('pivotX', it.px, 0), ('pivotY', it.py, 0), ('rotation', it.rot, 0), ('scaleX', it.s, 1),
                                  ('scaleY', it.s, 1), ('translateX', it.tx, 0), ('translateY', it.ty, 0)):
                if v != dflt:
                    attrs += f' android:{name}="{v:g}"'
            out += f'{ind}<group{attrs}>\n' + a_items(it.items, ind + '    ') + f'{ind}</group>\n'
        else:
            out += a_path(it[0], it[1], ind)
    return out


def write(path, text):
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        f.write(text)


for name, part, items in (('background', 'background: sky and meteor', BACKGROUND),
                          ('foreground', 'foreground: sword and ground', FOREGROUND),
                          ('monochrome', 'themed-icon silhouette', MONO)):
    write(os.path.join(RES, 'drawable', f'ic_launcher_{name}.xml'), HEAD.format(part=part) + a_items(items) + '</vector>\n')


# ---- docs/images/icon.svg: both layers in a rounded square
defs = []


def s_color(c):
    return '#' + c[3:], int(c[1:3], 16) / 255


def s_stops(items):
    out = ''
    for o, c in items:
        rgb, a = s_color(c)
        out += f'<stop offset="{o:g}" stop-color="{rgb}" stop-opacity="{a:.3g}"/>'
    return out


def s_path(d, fill):
    if fill[0] in ('solid', 'solid_eo'):
        rgb, a = s_color(fill[1])
        return f'<path fill="{rgb}" fill-opacity="{a:.3g}" d="{d}"/>'
    if fill[0] == 'stroke':
        rgb, a = s_color(fill[1])
        return (f'<path fill="none" stroke="{rgb}" stroke-opacity="{a:.3g}" stroke-width="{fill[2]}" '
                f'stroke-linecap="round" stroke-linejoin="round" d="{d}"/>')
    n = len(defs)
    if fill[0] == 'linear':
        _, x1, y1, x2, y2, st = fill
        defs.append(f'<linearGradient id="g{n}" gradientUnits="userSpaceOnUse" x1="{f2(x1)}" y1="{f2(y1)}" '
                    f'x2="{f2(x2)}" y2="{f2(y2)}">{s_stops(st)}</linearGradient>')
    else:
        _, cx, cy, rr, st = fill
        defs.append(f'<radialGradient id="g{n}" gradientUnits="userSpaceOnUse" cx="{f2(cx)}" cy="{f2(cy)}" '
                    f'r="{f2(rr)}">{s_stops(st)}</radialGradient>')
    return f'<path fill="url(#g{n})" d="{d}"/>'


def s_items(items):
    out = []
    for it in items:
        if isinstance(it, G):
            t = f'translate({it.tx + it.px:g} {it.ty + it.py:g}) rotate({it.rot:g}) scale({it.s:g}) translate({-it.px:g} {-it.py:g})'
            out.append(f'<g transform="{t}">' + ''.join(s_items(it.items)) + '</g>')
        else:
            out.append(s_path(it[0], it[1]))
    return out


body = s_items(BACKGROUND + FOREGROUND)
svg = ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="18 18 72 72" width="216" height="216">\n'
       '<clipPath id="c"><rect x="18" y="18" width="72" height="72" rx="16"/></clipPath>\n'
       f'<defs>{"".join(defs)}</defs>\n<g clip-path="url(#c)">\n' + '\n'.join(body) + '\n</g>\n</svg>\n')
write(os.path.join(ROOT, 'docs', 'images', 'icon.svg'), svg)
print('icon written')
