# usage: py tools/make_icon.py
# Writes the launcher icon (adaptive icon: background, foreground, monochrome) as vector drawables.
import os
RES = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'android', 'app', 'src', 'main', 'res') + os.sep

HEAD = '''<?xml version="1.0" encoding="utf-8"?>
<!-- FF7Recomp launcher icon (original art): a glowing green orb set on a sword, on the
     launcher's navy. 108dp adaptive-icon canvas; everything sits inside the 66dp safe circle. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
'''
TAIL = '</vector>\n'

def circle(cx, cy, r):
    return f'M{cx - r},{cy} a{r},{r} 0 1,0 {2 * r},0 a{r},{r} 0 1,0 {-2 * r},0 Z'

def rrect(x, y, w, h, r):
    return (f'M{x + r},{y} h{w - 2 * r} a{r},{r} 0 0,1 {r},{r} v{h - 2 * r} a{r},{r} 0 0,1 {-r},{r} '
            f'h{-(w - 2 * r)} a{r},{r} 0 0,1 {-r},{-r} v{-(h - 2 * r)} a{r},{r} 0 0,1 {r},{-r} Z')

def stops(items):
    return ''.join(f'\n                <item android:offset="{o}" android:color="{c}"/>' for o, c in items)

def grad_path(d, grad):
    return f'''    <path android:pathData="{d}">
        <aapt:attr name="android:fillColor">
            {grad}
        </aapt:attr>
    </path>
'''

def radial(cx, cy, r, items):
    return (f'<gradient android:type="radial" android:centerX="{cx}" android:centerY="{cy}" '
            f'android:gradientRadius="{r}">{stops(items)}\n            </gradient>')

def linear(x1, y1, x2, y2, items):
    return (f'<gradient android:type="linear" android:startX="{x1}" android:startY="{y1}" '
            f'android:endX="{x2}" android:endY="{y2}">{stops(items)}\n            </gradient>')

BLADE = 'M54,23 L60,31 L60,58 L48,58 L48,31 Z'
GUARD = rrect(40.5, 72.5, 27, 4.5, 2.25)
GRIP = rrect(52, 77, 4, 6.5, 1)
POMMEL = circle(54, 85, 2.6)
GOLD = linear(0, 72, 0, 77, [(0, '#FFF3D58A'), (1, '#FFA9802E')])

background = HEAD + grad_path('M0,0 h108 v108 h-108 Z', radial(54, 46, 70, [
    (0, '#FF31426E'), (0.55, '#FF18213F'), (1, '#FF0B1020')])) + TAIL

# the art scaled up 10% about its own centre: fills the visible 72dp circle like other icons
G = ('    <group android:pivotX="54" android:pivotY="55.3" android:scaleX="1.1" android:scaleY="1.1"\n'
     '        android:translateY="-1">\n')
GEND = '    </group>\n'

foreground = (HEAD + G
    + grad_path(BLADE, linear(48, 0, 60, 0, [(0, '#FF8C96B4'), (0.5, '#FFF2F4FB'), (1, '#FF5D6787')]))
    + '    <path android:fillColor="#1FFFFFFF" android:pathData="M54,23 L54,58 L48,58 L48,31 Z"/>\n'
    + grad_path(GUARD, GOLD)
    + f'    <path android:fillColor="#FF3A2A1C" android:pathData="{GRIP}"/>\n'
    + grad_path(POMMEL, GOLD)
    + grad_path(circle(54, 59, 27), radial(54, 59, 27, [
        (0, '#805CFF9A'), (0.6, '#242BD872'), (1, '#002BD872')]))
    + grad_path(circle(54, 60, 13), radial(49.5, 53.5, 16, [
        (0, '#FFD8FFE6'), (0.25, '#FF6CF59E'), (0.7, '#FF16A653'), (1, '#FF075A2B')]))
    + '    <path android:pathData="M45.5,55.5 A10,10 0 0,1 53,49.5" android:strokeColor="#D9FFFFFF"\n'
      '        android:strokeWidth="2.2" android:strokeLineCap="round"/>\n'
    + f'    <path android:fillColor="#59FFFFFF" android:pathData="{circle(58.8, 65, 1.6)}"/>\n'
    + GEND + TAIL)

# themed icons (Android 13+): one colour, the launcher tints it; the orb's highlight is cut out
monochrome = (HEAD + G
    + f'    <path android:fillColor="#FFFFFFFF" android:pathData="{BLADE} {GUARD} {GRIP} {POMMEL}"/>\n'
    + f'    <path android:fillColor="#FFFFFFFF" android:fillType="evenOdd" android:pathData="{circle(54, 60, 14.5)} '
      f'M44.4,56 A11,11 0 0,1 53.2,48.6 L53.6,51.4 A8.2,8.2 0 0,0 46.9,57 Z"/>\n'
    + GEND + TAIL)

adaptive = '''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background"/>
    <foreground android:drawable="@drawable/ic_launcher_foreground"/>
    <monochrome android:drawable="@drawable/ic_launcher_monochrome"/>
</adaptive-icon>
'''

for d in ('drawable', 'mipmap-anydpi-v26'):
    os.makedirs(RES + d, exist_ok=True)
for name, text in (('drawable/ic_launcher_background.xml', background),
                   ('drawable/ic_launcher_foreground.xml', foreground),
                   ('drawable/ic_launcher_monochrome.xml', monochrome),
                   ('mipmap-anydpi-v26/ic_launcher.xml', adaptive)):
    with open(RES + name, 'w', encoding='utf-8', newline='\n') as f:
        f.write(text)
print('ok')
