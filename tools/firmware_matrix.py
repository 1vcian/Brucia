#!/usr/bin/env python3
"""List configured board environments for CI, excluding only the contribution template.

Run with the Python interpreter in the PlatformIO environment. New real boards are
included automatically; this is a build matrix, not a hardware certification list.
"""
import json
from pathlib import Path
import re
import sys


TEMPLATES = {"NewDeviceModel": "Contributor template with placeholder hardware, not a supported board."}


def supported_environments(environments):
    boards = sorted(set(environments) - TEMPLATES.keys())
    if not boards:
        raise ValueError("No supported board environments found")
    for board in boards:
        if not re.fullmatch(r"[A-Za-z0-9_-]+", board):
            raise ValueError(f"Unexpected board environment name: {board!r}")
    return boards


def main():
    from platformio.project.config import ProjectConfig

    config = ProjectConfig(str(Path(__file__).resolve().parents[1] / "platformio.ini"))
    environments = list(config.envs())
    boards = supported_environments(environments)
    for name, reason in TEMPLATES.items():
        if name in environments:
            print(f"Excluded {name}: {reason}", file=sys.stderr)
    print(json.dumps(boards))


if __name__ == "__main__":
    main()
