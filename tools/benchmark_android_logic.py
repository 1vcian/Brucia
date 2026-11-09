#!/usr/bin/env python3
"""Measure existing Kotlin processing on the HOST JVM, never as Android/ESP32 speed.

No device, radio, downloads or real captures. Synthetic fixtures are cross-checked
with Python's crypto implementation. Cached compiler dependencies match the host tests.
"""
import argparse
import hashlib
import hmac
import json
import os
from pathlib import Path
import platform
import subprocess
import tempfile
from datetime import datetime, timezone

from test_android_logic import ARTIFACTS, ROOT

SOURCES = ["wifi/WpaCracker.kt", "audio/Fft.kt", "audio/Dtmf.kt",
           "audio/Goertzel.kt", "audio/Sstv.kt"]


def fixture():
    password, ssid = "benchmark-secret", "BruciaBenchmark"
    ap, sta = bytes.fromhex("020000000001"), bytes.fromhex("020000000002")
    anonce, snonce = bytes(range(32)), bytes(range(32, 64))
    eapol = bytearray(99)
    eapol[:9] = bytes.fromhex("0203005f02010a0010")
    eapol[17:49] = snonce
    pmk = hashlib.pbkdf2_hmac("sha1", password.encode(), ssid.encode(), 4096, 32)
    data = ap + sta + anonce + snonce
    kck = hmac.digest(pmk, b"Pairwise key expansion\x00" + data + b"\x00", "sha1")[:16]
    return dict(password=password, ssid=ssid, ap=ap.hex(), sta=sta.hex(),
                anonce=anonce.hex(), snonce=snonce.hex(), eapol=eapol.hex(),
                pmk=pmk.hex(), mic=hmac.digest(kck, eapol, "sha1")[:16].hex(),
                pmkid=hmac.digest(pmk, b"PMK Name" + ap + sta, "sha1")[:16].hex())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    cache = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")) / "caches/modules-2/files-2.1"
    jars = []
    for group, artifact, version in ARTIFACTS:
        matches = sorted((cache / group / artifact / version).glob(f"*/{artifact}-{version}.jar"))
        if not matches:
            raise SystemExit(f"Missing cached dependency {artifact}:{version}")
        jars.append(str(matches[0]))
    classpath = os.pathsep.join(jars)
    source_paths = [ROOT / "android/app/src/main/java/app/brucia" / p for p in SOURCES]
    harness = ROOT / "tools/benchmarks/LogicBenchmark.kt"
    fixture_data = fixture()
    inputs = source_paths + [harness, Path(__file__).resolve()]
    source_hashes = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in inputs}
    with tempfile.TemporaryDirectory(prefix="brucia-benchmark-") as directory:
        temp = Path(directory)
        constants = temp / "Fixture.kt"
        constants.write_text("object Fixture {\n" + "".join(
            f"const val {key} = {json.dumps(value)}\n" for key, value in fixture_data.items()) + "}\n")
        classes = temp / "classes"
        subprocess.run(["java", "-cp", classpath, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                        "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-classpath", classpath,
                        "-d", str(classes), *map(str, source_paths), str(harness), str(constants)], check=True)
        output = subprocess.check_output(["java", "-Xmx512m", "-cp", str(classes) + os.pathsep + classpath,
                                          "LogicBenchmarkKt"], text=True, timeout=180)
    report = json.loads(output)
    if any(hashlib.sha256((ROOT / p).read_bytes()).hexdigest() != digest for p, digest in source_hashes.items()):
        raise SystemExit("Benchmark sources changed during measurement; rerun on stable inputs")
    report.update(scope="host JVM microbenchmark; NOT Android, firmware or end-to-end performance",
                  utc=datetime.now(timezone.utc).isoformat(), host=platform.platform(),
                  revision=subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
                  fixture=fixture_data,
                  source_sha256=source_hashes)
    if platform.system() == "Darwin":
        report["cpu"] = subprocess.check_output(["sysctl", "-n", "machdep.cpu.brand_string"], text=True).strip()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
