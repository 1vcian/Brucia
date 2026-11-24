"""Import/export local Brucia v1 capture fixture packs. All paths are caller supplied."""
import argparse
import base64
import binascii
import hashlib
import json
from pathlib import Path
import struct

MAX_CAPTURE = 1024 * 1024
MAX_PACK = 1400000
FORMAT = "org.brucia.fixture.pcap"
FIELDS = {"format", "version", "source", "license", "sha256", "capture"}


def read(path, limit):
    with path.open("rb") as stream:
        data = stream.read(limit + 1)
    if len(data) > limit:
        raise ValueError("file exceeds size limit")
    return data


def validate_capture(data):
    endian = {b"\xd4\xc3\xb2\xa1": "<", b"\xa1\xb2\xc3\xd4": ">"}.get(data[:4])
    if not endian or not 24 <= len(data) <= MAX_CAPTURE:
        raise ValueError("expected a bounded classic PCAP file")
    major, minor, _, _, snap, link = struct.unpack_from(endian + "HHIIII", data, 4)
    if (major, minor, link) != (2, 4, 105) or not snap:
        raise ValueError("expected raw IEEE 802.11 PCAP 2.4")
    offset = 24
    while offset < len(data):
        if len(data) - offset < 16:
            raise ValueError("partial packet header")
        size, original = struct.unpack_from(endian + "II", data, offset + 8)
        offset += 16
        if size > min(original, snap, len(data) - offset):
            raise ValueError("invalid packet length")
        offset += size


def provenance(value):
    if not isinstance(value, str) or not value.isprintable() or not value.strip() or len(value) > 240:
        raise ValueError("expected printable source and license (1 to 240 characters)")
    return value


def export_pack(data, source, license_name):
    validate_capture(data)
    pack = dict(format=FORMAT, version=1, source=provenance(source), license=provenance(license_name),
                sha256=hashlib.sha256(data).hexdigest(), capture=base64.b64encode(data).decode("ascii"))
    encoded = json.dumps(pack, indent=2, ensure_ascii=False).encode() + b"\n"
    if len(encoded) > MAX_PACK:
        raise ValueError("encoded pack exceeds size limit")
    return encoded


def unique_fields(pairs):
    result = dict(pairs)
    if len(result) != len(pairs):
        raise ValueError("duplicate pack field")
    return result


def import_pack(raw):
    if len(raw) > MAX_PACK:
        raise ValueError("pack exceeds size limit")
    pack = json.loads(raw.decode("utf-8"), object_pairs_hook=unique_fields)
    if (not isinstance(pack, dict) or set(pack) != FIELDS or pack["format"] != FORMAT
            or type(pack["version"]) is not int or pack["version"] != 1):
        raise ValueError("unsupported pack format or version")
    provenance(pack["source"])
    provenance(pack["license"])
    if not isinstance(pack["capture"], str):
        raise ValueError("expected base64 capture")
    data = base64.b64decode(pack["capture"], validate=True)
    validate_capture(data)
    if hashlib.sha256(data).hexdigest() != pack["sha256"]:
        raise ValueError("capture digest mismatch")
    return data


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    cmds = parser.add_subparsers(dest="action", required=True)
    export = cmds.add_parser("export")
    export.add_argument("--source", required=True)
    export.add_argument("--license", required=True)
    for sub in (export, cmds.add_parser("import")):
        sub.add_argument("input", type=Path)
        sub.add_argument("output", type=Path)
    args = parser.parse_args()
    try:
        if args.action == "export":
            output = export_pack(read(args.input, MAX_CAPTURE), args.source, args.license)
        else:
            output = import_pack(read(args.input, MAX_PACK))
        with args.output.open("xb") as dest:
            try:
                dest.write(output)
                dest.flush()
            except OSError:
                args.output.unlink()
                raise
    except (ValueError, OSError, RecursionError, binascii.Error) as exc:
        parser.exit(1, f"{exc}\n")


if __name__ == "__main__":
    main()
