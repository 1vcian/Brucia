"""Run a portable v1 report recipe against one local Brucia PCAP fixture pack."""
import argparse
import hashlib
import json
from pathlib import Path
import struct

from pcap_pack import MAX_PACK, import_pack, read, unique_fields

OPERATIONS = ("packet-count", "capture-bytes", "capture-sha256")


def run(recipe_bytes, pack_bytes):
    if len(recipe_bytes) > 4096:
        raise ValueError("recipe exceeds 4096 bytes")
    recipe = json.loads(recipe_bytes.decode("utf-8"), object_pairs_hook=unique_fields)
    if (not isinstance(recipe, dict) or set(recipe) != {"format", "version", "input", "steps"}
            or recipe["format"] != "org.brucia.fixture.recipe"
            or type(recipe["version"]) is not int or recipe["version"] != 1
            or recipe["input"] != "org.brucia.fixture.pcap/1"):
        raise ValueError("unsupported recipe or input version")
    steps = recipe["steps"]
    if (not isinstance(steps, list) or not 1 <= len(steps) <= len(OPERATIONS)
            or any(op not in OPERATIONS for op in steps) or len(set(steps)) != len(steps)):
        raise ValueError("expected distinct supported report steps")
    capture = import_pack(pack_bytes)
    result = {}
    for op in steps:
        if op == "capture-sha256":
            result[op] = hashlib.sha256(capture).hexdigest()
        elif op == "capture-bytes":
            result[op] = len(capture)
        else:
            # import_pack has validated the complete record chain and endian marker.
            size = struct.Struct("<I" if capture[0] == 0xd4 else ">I")
            offset, count = 24, 0
            while offset < len(capture):
                offset += 16 + size.unpack_from(capture, offset + 8)[0]
                count += 1
            result[op] = count
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("recipe", type=Path)
    parser.add_argument("pack", type=Path)
    args = parser.parse_args()
    try:
        print(json.dumps(run(read(args.recipe, 4096), read(args.pack, MAX_PACK)), indent=2))
    except (ValueError, OSError, RecursionError) as exc:
        parser.exit(1, f"{exc}\n")
