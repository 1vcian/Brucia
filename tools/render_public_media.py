#!/usr/bin/env python3
"""Build public still images from illustrations and Compose fixtures. Needs Pillow.

Run after verifyPaparazziDebug for FlameUiScreenshotTest. Sources and hashes are
recorded in docs/media/manifest.json. No device, network, AI images or publication.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
MEDIA = ROOT / 'docs/media'
IMG = ROOT / 'docs/img'
SNAPS = ROOT / 'android/app/src/test/snapshots/images'
BG, PANEL, FG, MUTED, ORANGE = '#101014', '#1c1c24', '#f5f1ea', '#bcb6ad', '#ff791f'

def font(size, bold=False):
    names = (["/System/Library/Fonts/Supplemental/Arial Bold.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"] if bold else
             ["/System/Library/Fonts/Supplemental/Arial.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"])
    path = next((p for p in names if Path(p).exists()), None)
    if not path:
        raise SystemExit('Install Arial or DejaVu Sans to render media.')
    return ImageFont.truetype(path, size)

def base(kicker):
    im = Image.new('RGB', (1280, 720), BG)
    d = ImageDraw.Draw(im)
    d.rectangle((0, 0, 12, 720), fill=ORANGE)
    d.text((64, 43), 'Brucia', font=font(38, True), fill=ORANGE)
    d.text((250, 55), kicker.upper(), font=font(20), fill=MUTED)
    d.line((64, 110, 1216, 110), fill='#373139', width=2)
    return im, d

def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()

def build():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--images-only', action='store_true')
    parser.parse_args()  # --images-only retained for older invocations.
    MEDIA.mkdir(exist_ok=True)
    brand = json.loads((ROOT/'brand.json').read_text())
    version = (ROOT/'VERSION').read_text().strip()
    commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    manifest_path = MEDIA/'manifest.json'
    manifest = json.loads(manifest_path.read_text()) if manifest_path.exists() else {'assets': {}}
    previews = {'kind': 'Compose fixtures and architecture illustration, not device captures',
                'version_context': version, 'source_commit': commit, 'sources': {}}
    im, d = base('Public project / Android + ESP32')
    d.text((64, 142), 'The phone is the brain.', font=font(54, True), fill=FG)
    d.text((64, 211), 'The ESP32 is the antenna.', font=font(54, True), fill=ORANGE)
    for x, title, lines in [(64, 'ESP32 / peripherals', ['Radio and sensor interfaces', 'Physical timing', 'Standalone device menu']),
                             (720, 'Android / workspace', ['Analysis, maps and storage', 'Files and device controls', 'USB companion interface'])]:
        d.rounded_rectangle((x, 333, x+496, 577), radius=20, fill=PANEL)
        d.text((x+28, 363), title, font=font(30, True), fill=FG)
        for j,line in enumerate(lines): d.text((x+28, 424+j*41), line, font=font(25), fill=MUTED)
    d.line((572, 450, 706, 450), fill=ORANGE, width=5)
    d.text((601, 412), 'USB', font=font(26, True), fill=ORANGE)
    d.text((64, 627), 'Based on Bruce / AGPL-3.0 / Verify each physical workflow', font=font(24), fill=MUTED)
    im.save(IMG/'public-overview.png', optimize=True)
    # Use verified existing previews, retaining their fixture provenance.
    im, d = base('Compose preview renders / fixture data')
    for x, method, title in [(80,'homeDashboardConnectedScreen','Dashboard'), (495,'reportScreen','Report preview'), (910,'irBrowserScreen','IR catalog preview')]:
        src = SNAPS/f'app.brucia_FlameUiScreenshotTest_{method}.png'
        previews['sources'][str(src.relative_to(ROOT))] = sha(src)
        snap = Image.open(src).convert('RGB')
        snap.thumbnail((290, 485))
        im.paste(snap, (x, 142))
        d.text((x, 640), title, font=font(24,True), fill=FG)
    im.save(IMG/'public-ui.png', optimize=True)
    for name in ('public-overview.png','public-ui.png'):
        manifest['assets']['docs/img/'+name] = sha(IMG/name)

    previews['sources']['tools/render_public_media.py'] = sha(Path(__file__))
    manifest['preview_images'] = previews
    manifest_path.write_text(json.dumps(manifest, indent=2)+'\n')
    print('Generated two still images. App recordings and tutorials were preserved.')

if __name__=='__main__': build()
