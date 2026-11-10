#!/usr/bin/env python3
"""Package actual Android screenrecord captures; never synthesize app interactions.

Usage: python3 tools/package_app_recordings.py CAPTURE_DIRECTORY
Requires ffmpeg/ffprobe and Pillow. Original captures remain in the supplied directory.
The release APK identity and recording context below describe this capture session.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
MEDIA = ROOT / "docs/media"
CLIPS = {
    "app-tour": ("Explore the app", "Android emulator / Brucia 0.1.12", [
        (0, "Browse the tools in the released Android app."),
        (8, "Open NFC. Tag operations require a connected device and PN532."),
    ]),
    "dictionary-demo": ("Run the built-in demo", "Android emulator / synthetic handshake", [
        (0, "Tap Demo to run the built-in synthetic WPA fixture."),
        (5, "The app checks candidates against the synthetic handshake."),
        (8, "401 candidates checked; the known demo passphrase is found."),
        (12, "No network capture. The displayed speed is from this emulator."),
    ]),
    "nfc-dictionaries": ("Import NFC dictionaries", "Android emulator / downloads, no tag operation", [
        (0, "Add the public Flipper MIFARE Classic dictionary."),
        (5, "Flipper loads 2,038 Classic keys."),
        (9, "Add Proxmark3. Existing keys are deduplicated."),
        (10.5, "473 new keys; 2,511 total. Keys remain in this app session."),
    ]),
}


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def stamp(seconds):
    ms = round(seconds * 1000)
    return f"{ms // 3600000:02}:{ms // 60000 % 60:02}:{ms // 1000 % 60:02}.{ms % 1000:03}"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("captures", type=Path)
    args = parser.parse_args()
    # Fail before touching published files if a source is unavailable.
    for slug in CLIPS:
        if not (args.captures / f"{slug}.mp4").is_file():
            parser.error(f"Missing real screen recording: {slug}.mp4")
    manifest_path = MEDIA / "manifest.json"
    old = json.loads(manifest_path.read_text())
    manifest = {
        "kind": "Android app screen recordings plus separately labelled Compose preview images",
        "version_context": "0.1.12",
        "recordings": {
            "date": "2026-11-10", "apk_commit": "21b8f80d7c95422689e6a5089a1a52454a8c3eb8",
            "apk_sha256": "5f59691ddf5fcde46072e99c167c8e547ac6d272a0c294a129a0c4d8f900d083",
            "device": "Android emulator, API 36.1, arm64, 1080x2400; screenrecord 720x1600",
            "hardware_connected": False,
            "editing": "Full app viewport retained. Provenance banner added above it; H.264 encoding and 4-second final-frame hold. No speed-up or fabricated UI.",
            "clips": {},
        },
        "preview_images": {"kind": "Compose fixtures and architecture illustration, not device captures",
                           "sources": {k: v for k, v in old.get("sources", {}).items() if "snapshots/" in k}},
        "sources": {},
        "assets": {k: v for k, v in old.get("assets", {}).items() if k.startswith("docs/img/")},
    }
    font_paths = [Path("/System/Library/Fonts/Supplemental/Arial.ttf"),
                  Path("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf")]
    font = ImageFont.truetype(str(next(p for p in font_paths if p.exists())), 23)
    with tempfile.TemporaryDirectory() as directory:
        banner = Path(directory) / "banner.png"
        for slug, (title, label, captions) in CLIPS.items():
            source = args.captures / f"{slug}.mp4"
            info = json.loads(subprocess.check_output([
                "ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "json", str(source)]))
            duration = float(info["format"]["duration"]) + 4
            header = Image.new("RGB", (720, 64), "#101014")
            ImageDraw.Draw(header).text((18, 19), label, fill="#ff791f", font=font)
            header.save(banner)
            output = MEDIA / f"{slug}.mp4"
            subprocess.run([
                "ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", str(source), "-i", str(banner),
                "-filter_complex", "[0:v]pad=720:1664:0:64:color=0x101014[v];[v][1:v]overlay=0:0,tpad=stop_mode=clone:stop_duration=4,fps=30[out]",
                "-map", "[out]", "-an", "-t", str(duration), "-c:v", "libx264", "-preset", "fast", "-crf", "20",
                "-pix_fmt", "yuv420p", "-movflags", "+faststart", str(output)], check=True)
            subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-ss",
                            str(2 if slug == "app-tour" else max(0, duration - 1)), "-i", str(output), "-frames:v", "1",
                            str(MEDIA / f"{slug}.png")], check=True)
            cues = ["WEBVTT", ""]
            for index, (start, text) in enumerate(captions):
                end = captions[index + 1][0] if index + 1 < len(captions) else duration
                cues.extend([f"{stamp(start)} --> {stamp(end)}", text, ""])
            (MEDIA / f"{slug}.vtt").write_text("\n".join(cues))
            manifest["recordings"]["clips"][slug] = {
                "title": title, "raw_sha256": sha(source), "duration_seconds": duration,
                "captions": [text for _, text in captions],
            }
            for ext in ("mp4", "png", "vtt"):
                path = MEDIA / f"{slug}.{ext}"
                manifest["assets"][str(path.relative_to(ROOT))] = sha(path)
    manifest["sources"]["tools/package_app_recordings.py"] = sha(Path(__file__))
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")
    print("Packaged three actual app recordings; no physical device acceptance claimed.")


if __name__ == "__main__":
    main()
