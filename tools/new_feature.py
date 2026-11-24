"""Generate a phone-only Kotlin source contribution from the version 1 template."""
import argparse
from pathlib import Path
import re

TEMPLATE = Path(__file__).resolve().parents[1] / "examples/feature-v1/FixtureFeature.kt.in"


def generate(feature_id, tab):
    if not re.fullmatch(r"[a-z][a-z0-9]*(?:\.[a-z][a-z0-9]*){2,}", feature_id):
        raise ValueError("expected a namespaced feature ID such as org.example.capture")
    if not 0 < tab <= 2147483647:
        raise ValueError("tab must be a positive Kotlin Int")
    return TEMPLATE.read_text().replace("@ID@", feature_id).replace("@TAB@", str(tab))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("feature_id")
    parser.add_argument("tab", type=int)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    try:
        source = generate(args.feature_id, args.tab)
        with args.output.open("x") as out:
            out.write(source)
    except (ValueError, OSError) as error:
        parser.exit(1, f"{error}\n")
