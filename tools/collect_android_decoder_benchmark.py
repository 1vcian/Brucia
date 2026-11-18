#!/usr/bin/env python3
"""Collect the opt-in ART decoder report from a successful Gradle instrumentation log.

Run immediately after the benchmark, before changing sources or rebuilding APKs.
Source and APK hashes describe the local files at collection time.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import subprocess


ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--log", type=Path, required=True, help="Gradle testlog/test-results.log")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    log = args.log.read_text()
    prefix = "INSTRUMENTATION_RESULT: decoder_report="
    reports = [line[len(prefix):] for line in log.splitlines() if line.startswith(prefix)]
    if len(reports) != 1 or "OK (1 test)" not in log or "INSTRUMENTATION_CODE: -1" not in log:
        parser.error("Expected one decoder report from a successful isolated benchmark")
    report = json.loads(reports[0])
    if len(report["rows"]) != 12 or any(len(row["samples"]) != 3 for row in report["rows"]):
        parser.error("Incomplete benchmark matrix")
    paths = [
        "android/app/src/main/java/app/brucia/protocol/CompanionClient.kt",
        "android/app/src/main/java/app/brucia/protocol/FrameBudget.kt",
        "android/app/src/main/java/app/brucia/protocol/Protocol.kt",
        "android/app/src/main/java/app/brucia/protocol/LinkCounters.kt",
        "android/app/src/main/java/app/brucia/transport/Transport.kt",
        "android/app/src/androidTest/java/app/brucia/CompanionDecoderBenchmark.kt",
        "tools/collect_android_decoder_benchmark.py",
    ]
    apks = [
        "android/app/build/outputs/apk/debug/app-debug.apk",
        "android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk",
    ]

    def hashes(names):
        return {name: hashlib.sha256((ROOT / name).read_bytes()).hexdigest() for name in names}

    report.update(
        collected_utc=datetime.now(timezone.utc).isoformat(),
        revision=subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        provenance_scope="Source/APK hashes at collection time, immediately after the run; includes uncommitted benchmark",
        source_sha256=hashes(paths),
        apk_sha256=hashes(apks),
        instrumentation_log_sha256=hashlib.sha256(args.log.read_bytes()).hexdigest(),
    )
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n")
    print(f"Collected {len(report['rows'])} scenarios into {args.output}")


if __name__ == "__main__":
    main()
