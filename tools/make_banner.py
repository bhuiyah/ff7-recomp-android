# usage: py tools/make_banner.py
# Writes docs/images/banner.svg and banner.png (1280x640, GitHub's social-preview size): the app
# icon (docs/images/icon.svg, from tools/make_icon.py) on the left, the title and a few lines on
# the right. The PNG is rendered with headless Microsoft Edge (Windows: the Bahnschrift font).
import os
import re
import subprocess

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..'))
IMG = os.path.join(ROOT, 'docs', 'images')
EDGE = r'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe'

TURQ, TURQ_DEEP, WHITE = '#5CE6E0', '#0A9AA8', '#F2F5F8'
FONT = "Bahnschrift, 'Segoe UI', Arial, sans-serif"

# the icon, nested: its gradient and clip ids get a prefix so they cannot clash
icon = open(os.path.join(IMG, 'icon.svg'), encoding='utf-8').read()
inner = icon[icon.index('>', icon.index('<svg')) + 1:icon.rindex('</svg>')]
inner = re.sub(r'id="([^"]+)"', r'id="i-\1"', inner)
inner = re.sub(r'url\(#([^)]+)\)', r'url(#i-\1)', inner)
IX, IY, IS = 96, 166, 308   # icon box

svg = f'''<svg xmlns="http://www.w3.org/2000/svg" width="1280" height="640" viewBox="0 0 1280 640">
<defs>
 <linearGradient id="bg" x1="0" y1="0" x2="0" y2="640" gradientUnits="userSpaceOnUse">
  <stop offset="0" stop-color="#0B0A0E"/><stop offset="0.62" stop-color="#140B0A"/><stop offset="1" stop-color="#3A1508"/>
 </linearGradient>
 <radialGradient id="ember" cx="250" cy="640" r="560" gradientUnits="userSpaceOnUse">
  <stop offset="0" stop-color="#F07A24" stop-opacity="0.28"/><stop offset="1" stop-color="#F07A24" stop-opacity="0"/>
 </radialGradient>
 <filter id="glow" x="-30%" y="-30%" width="160%" height="160%"><feGaussianBlur stdDeviation="18"/></filter>
 <filter id="tglow" x="-20%" y="-40%" width="140%" height="180%"><feGaussianBlur stdDeviation="9"/></filter>
</defs>
<rect width="1280" height="640" fill="url(#bg)"/>
<rect width="1280" height="640" fill="url(#ember)"/>
<rect x="{IX}" y="{IY}" width="{IS}" height="{IS}" rx="{IS * 0.22:g}" fill="{TURQ_DEEP}" opacity="0.55" filter="url(#glow)"/>
<svg x="{IX}" y="{IY}" width="{IS}" height="{IS}" viewBox="18 18 72 72">{inner}</svg>
<rect x="{IX}" y="{IY}" width="{IS}" height="{IS}" rx="{IS * 0.22:g}" fill="none" stroke="{TURQ}" stroke-opacity="0.35" stroke-width="2"/>
<text x="470" y="262" font-family="{FONT}" font-weight="700" font-size="98" fill="{WHITE}" font-stretch="semi-condensed">FINAL FANTASY</text>
<text x="1078" y="262" font-family="{FONT}" font-weight="700" font-size="98" fill="{TURQ}" font-stretch="semi-condensed" opacity="0.8" filter="url(#tglow)">VII</text>
<text x="1078" y="262" font-family="{FONT}" font-weight="700" font-size="98" fill="{TURQ}" font-stretch="semi-condensed">VII</text>
<text x="473" y="334" font-family="{FONT}" font-weight="600" font-size="36" letter-spacing="12" fill="{TURQ}">NATIVE ANDROID RECOMP</text>
<text x="472" y="398" font-family="{FONT}" font-size="27" fill="{WHITE}" opacity="0.85">The Steam PC game, recompiled to native ARM64. No emulator.</text>
<text x="472" y="444" font-family="{FONT}" font-size="24" fill="{TURQ}">AYN Thor dual screen · 120 FPS · FFNx mods · bring your own game</text>
</svg>
'''
svg_path = os.path.join(IMG, 'banner.svg')
with open(svg_path, 'w', encoding='utf-8', newline='\n') as f:
    f.write(svg)

# render: an HTML page holding the SVG inline, screenshotted at exactly 1280x640
page = os.path.join(IMG, '_banner_render.html')
with open(page, 'w', encoding='utf-8') as f:
    f.write('<!doctype html><html><body style="margin:0;overflow:hidden">' + svg + '</body></html>')
png = os.path.join(IMG, 'banner.png')
subprocess.run([EDGE, '--headless', '--disable-gpu', '--hide-scrollbars', f'--screenshot={png}',
                '--window-size=1280,640', 'file:///' + page.replace(os.sep, '/')], check=True,
               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
os.remove(page)
print('banner written:', svg_path, png)
