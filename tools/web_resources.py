"""Offline, deterministic generation of the firmware's gzip WebUI resources."""
from __future__ import annotations

import gzip
import hashlib
import os
from pathlib import Path
import tempfile

GENERATOR_VERSION = b"brucia-web-resources-v1\0"


def _atomic_write(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp_name = None
    try:
        with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as tmp:
            tmp_name = tmp.name
            tmp.write(data)
        os.replace(tmp_name, path)
    finally:
        if tmp_name is not None and os.path.exists(tmp_name):
            os.unlink(tmp_name)


def embed_web(source_dir: Path, header_file: Path, checksum_file: Path) -> bool:
    """Embed exact source bytes, returning True if the generated header changed.

    No source is uploaded or minified: altering HTML/JS whitespace without a parser
    can change application behavior. Gzip provides compression without that risk.
    """
    if not source_dir.is_dir():
        raise FileNotFoundError(f"WebUI source directory not found: {source_dir}")
    files = sorted(p for p in source_dir.iterdir() if p.suffix in {".html", ".css", ".js"})
    if not files:
        raise ValueError("No WebUI resources found")
    contents = [(p.name, p.read_bytes()) for p in files]
    digest = hashlib.sha256(GENERATOR_VERSION)
    for name, raw in contents:
        digest.update(name.encode("utf-8") + b"\0" + raw + b"\0")
    checksum = digest.hexdigest()
    if header_file.is_file() and checksum_file.is_file() and checksum_file.read_text().strip() == checksum:
        return False

    lines = ["#ifndef WEB_FILES_H", "#define WEB_FILES_H", "", "#include <Arduino.h>", "",
             "// Generated locally from embedded_resources/web_interface. Do not edit.", ""]
    for name, raw in contents:
        compressed = bytearray(gzip.compress(raw, compresslevel=9, mtime=0))
        compressed[9] = 255  # unspecified OS, consistent across build hosts
        symbol = name.replace(".", "_")
        lines.append(f"const uint8_t {symbol}[] PROGMEM = {{")
        for offset in range(0, len(compressed), 15):
            lines.append("  " + ", ".join(f"0x{byte:02X}" for byte in compressed[offset:offset + 15]) + ",")
        lines.extend(["};", f"const uint32_t {symbol}_size = {len(compressed)};", ""])
    lines.extend(["#endif // WEB_FILES_H", ""])
    _atomic_write(header_file, "\n".join(lines).encode("utf-8"))
    _atomic_write(checksum_file, (checksum + "\n").encode("ascii"))
    return True
