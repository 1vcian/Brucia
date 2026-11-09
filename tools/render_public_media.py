#!/usr/bin/env python3
"""Build public illustrated guides, not hardware demos. Needs Pillow and ffmpeg.

Run after verifyPaparazziDebug for FlameUiScreenshotTest. Sources and hashes are
recorded in docs/media/manifest.json. No device, network, AI images or publication.
"""
import argparse
import hashlib
import json
import re
from pathlib import Path
import shutil
import subprocess
import tempfile
import textwrap

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
MEDIA = ROOT / 'docs/media'
IMG = ROOT / 'docs/img'
SNAPS = ROOT / 'android/app/src/test/snapshots/images'
BG, PANEL, FG, MUTED, ORANGE = '#101014', '#1c1c24', '#f5f1ea', '#bcb6ad', '#ff791f'

# These exact captions are also the accessible transcript and VTT tracks.
GUIDES = [
    ('first-connection', 'Install and connect', [
        ('Choose a matching release', 'Download the APK and firmware for your exact board. Read the release notes and back up files you need.'),
        ('Flash from a computer', 'Use the public web flasher in desktop Chrome or Edge with a USB data cable. Confirm board and version before starting.'),
        ('Connect the Android app', 'Install the APK. Connect via USB host / OTG, grant USB access and enter Brucia companion mode on the device.'),
        ('Check the first reply', 'Confirm device and firmware information. List files or request device info. No reply? Check cable, permission, mode and serial-port ownership.')]),
    ('updates', 'Update and recover', [
        ('Prepare the pair', 'Read the release notes. Preserve required files. Stop tools and use stable power. APK installation and firmware flashing are separate.'),
        ('Use Updates', 'Select the correct board and release. Review verification status. Keep USB connected while the app writes and verifies firmware.'),
        ('Reconnect and confirm', 'After completion, reconnect to companion mode. Check the reported firmware version and a benign device-info reply.'),
        ('If flashing fails', 'Keep the error and version details. Follow the board bootloader procedure and recover with the matching image. Never treat an incomplete upload as success.')]),
    ('files-scripts', 'Files and scripts', [
        ('List device storage', 'Connect over USB and open Files. A failed listing is not an empty folder. Confirm the path and selected storage before changing files.'),
        ('Try a harmless file', 'Use a small test file. Transfer it, open the returned copy and compare its content. Keep required device files backed up.'),
        ('Review before running', 'Scripts run on the device. Read the code first, use a harmless local script and stop it when done. Third-party store downloads have separate trust limits.'),
        ('Check recovery', 'Test disconnect and reconnect with disposable files. Inspect the visible result; do not infer a completed transfer from progress alone.')]),
    ('observations', 'Wi-Fi and BLE observations', [
        ('Use a known lab', 'Open Wi-Fi or BLE observations for equipment you own or are permitted to test. Check capabilities and start one observation task.'),
        ('Confirm actual input', 'Look for your known device and changing timestamps. Preview fixtures and a connected badge are not evidence of received radio events.'),
        ('Save a useful result', 'Export a small observation set and inspect the file. Remove personal identifiers before sharing a report or screenshot.'),
        ('Stop and reconnect', 'Stop the task and confirm its result. Check rotation and reconnect separately. BLE advertisements do not guarantee a popup on another operating system.')]),
    ('peripherals', 'Radio, NFC and IR checks', [
        ('Check the actual module', 'Open the feature guide and inspect runtime capabilities. CC1101, PN532 and IR have different limits. Optional radios need compatible hardware.'),
        ('Start with inspection', 'Use a saved sample, a disposable tag or a known lab signal. Read the selected protocol and operation before proceeding. Write and erase change the target.'),
        ('Separate reply from effect', 'An accepted command does not prove a receiver reacted. Physical output requires a controlled test and an independent observation.'),
        ('Record the boundary', 'Record app and firmware versions, peripheral, expected result and observed result. Keep blocked and not-run tests distinct from passes.')]),
    ('audio-remote', 'Audio and Remote', [
        ('Check audio availability', 'Inspect microphone and speaker capabilities. Start with a saved audio sample or a quiet, controlled input; keep output volume low.'),
        ('One owner at a time', 'Stop audio before switching to another audio function. FM needs separate hardware; a DSP unit test is not a physical sound-path test.'),
        ('Enter Remote deliberately', 'Stop other operations before starting Remote. Wait for confirmed mode change. The app must show a failure if entry is not acknowledged.'),
        ('Return to companion', 'Stop Remote, wait for reconnection and request device information again. Test disconnect and rotation separately; record failures with versions.')]),
]

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

def wrapped(d, text, xy, width=52, size=30, color=FG):
    x, y = xy
    for line in textwrap.wrap(text, width):
        d.text((x, y), line, font=font(size), fill=color)
        y += size + 14
    return y

def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()

def build():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--images-only', action='store_true')
    args = parser.parse_args()
    if not args.images_only and not shutil.which('ffmpeg'):
        raise SystemExit('ffmpeg is required for videos.')
    MEDIA.mkdir(exist_ok=True)
    brand = json.loads((ROOT/'brand.json').read_text())
    site = brand['site_url']
    version = (ROOT/'VERSION').read_text().strip()
    commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    manifest = {'kind': 'Illustrated instructions and Compose fixture previews; not hardware recordings',
                'version_context': version, 'source_commit': commit,
                'generator': 'tools/render_public_media.py', 'sources': {}, 'assets': {}}
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
        manifest['sources'][str(src.relative_to(ROOT))] = sha(src)
        snap = Image.open(src).convert('RGB')
        snap.thumbnail((290, 485))
        im.paste(snap, (x, 142))
        d.text((x, 640), title, font=font(24,True), fill=FG)
    im.save(IMG/'public-ui.png', optimize=True)
    for name in ('public-overview.png','public-ui.png'):
        manifest['assets']['docs/img/'+name] = sha(IMG/name)

    tutorial = ['# Tutorials', '', 'Illustrated instructions for the public project. These videos contain diagrams and text,',
                '**not recordings of a phone or device**. They do not establish hardware acceptance.',
                'Each silent video has a caption track and the complete transcript below.', '',
                'Start with [[Getting Started]], then [[Features]]. Use equipment you own or are authorized',
                'to test. The expected outcomes below still need checking on your app/firmware pair.', '']
    with tempfile.TemporaryDirectory() as temporary:
        tmp = Path(temporary)
        for slug,title,slides in GUIDES:
            tutorial += [f'## {title}', '', f'[Watch the illustrated video]({site}media/{slug}.mp4) ·',
                         f'[Captions]({site}media/{slug}.vtt)', '']
            captions = ['WEBVTT', '']
            frames = []
            for i,(heading,body) in enumerate(slides):
                im,d = base('Illustrated guide / not a device recording')
                d.text((64, 145), title, font=font(43,True), fill=FG)
                d.text((64, 228), f'{i+1:02d}', font=font(64,True), fill=ORANGE)
                d.text((175, 248), heading, font=font(33,True), fill=FG)
                wrapped(d,body,(175,328),width=53,size=30)
                d.text((64, 635), 'Expected steps, not proof of a successful hardware run.', font=font(22), fill=MUTED)
                for j in range(len(slides)):
                    d.rounded_rectangle((64+j*292,685,334+j*292,691),radius=3,fill=ORANGE if j<=i else PANEL)
                path=tmp/f'{slug}-{i}.png'; im.save(path)
                frames.append(path)
                if i==0: im.save(MEDIA/f'{slug}.png', optimize=True)
                def stamp(sec): return f'00:{sec//60:02d}:{sec%60:02d}.000'
                captions += [f'{stamp(i*10)} --> {stamp((i+1)*10)}', heading, body, '']
                tutorial += [f'{i+1}. **{heading}.** {body}', '']
            (MEDIA/f'{slug}.vtt').write_text('\n'.join(captions))
            if not args.images_only:
                concat=tmp/f'{slug}.txt'
                concat.write_text(''.join(f"file '{p.name}'\nduration 10\n" for p in frames)+f"file '{frames[-1].name}'\n")
                subprocess.run(['ffmpeg','-hide_banner','-loglevel','error','-y','-f','concat','-safe','1','-i',str(concat),
                                '-t','40','-vf','fps=24','-c:v','libx264','-preset','fast','-crf','24','-pix_fmt','yuv420p',
                                '-movflags','+faststart',str(MEDIA/f'{slug}.mp4')],check=True)
            for ext in ('png','vtt','mp4'):
                path=MEDIA/f'{slug}.{ext}'
                if path.exists(): manifest['assets'][str(path.relative_to(ROOT))]=sha(path)
    tutorial += ['## Evidence and further checks', '',
                 'Use the release notes and TESTS.md in the repository for exact revisions and results.',
                 'Optional Ethernet, FM, LoRa and NRF24 workflows need compatible peripherals.',
                 'BLE peer popups, RF receiver responses, NFC writes and physical audio are separate',
                 'acceptance checks. A simulator, UI preview or completed command does not prove them.', '',
                 f'[Media provenance]({site}media/manifest.json)', '']
    # Standard Markdown works both in repository file views and the site router.
    text = re.sub(r'\[\[([^\]]+)\]\]', lambda m: '['+m[1]+']('+m[1].replace(' ', '-')+'.md)', '\n'.join(tutorial))
    (ROOT/'wiki/Tutorials.md').write_text(text)
    manifest['sources']['tools/render_public_media.py']=sha(Path(__file__))
    (MEDIA/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
    print(f'Generated images and {len(GUIDES)} illustrated guides; version context {version}.')

if __name__=='__main__': build()
