#!/usr/bin/env python3
"""Host JVM throughput/allocation probe of the actual framed decoder, with synthetic bytes only."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import subprocess
import tempfile
from test_android_logic import ARTIFACTS, ROOT


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    cache = Path(os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle')) / 'caches/modules-2/files-2.1'
    cp = os.pathsep.join(str(next((cache/g/a/v).glob(f'*/{a}-{v}.jar'))) for g,a,v in ARTIFACTS)
    base = ROOT / 'android/app/src/main/java/app/brucia'
    sources = [base/p for p in ['protocol/CompanionClient.kt', 'protocol/FrameBudget.kt',
               'protocol/Protocol.kt', 'protocol/LinkCounters.kt', 'transport/Transport.kt']]
    sources.append(ROOT/'tools/benchmarks/CompanionDecoderBenchmark.kt')
    with tempfile.TemporaryDirectory(prefix='brucia-decoder-') as temp:
        temp = Path(temp)
        config = temp/'BuildConfig.kt'
        config.write_text('package app.brucia\nobject BuildConfig { const val VERSION_NAME = "benchmark" }\n')
        subprocess.run(['java', '-cp', cp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib',
                        '-no-reflect', '-jvm-target', '17', '-classpath', cp, '-d', str(temp/'classes'),
                        *map(str,sources), str(config)], check=True)
        report = json.loads(subprocess.check_output(['java','-Xmx256m','-cp',str(temp/'classes')+os.pathsep+cp,
                           'CompanionDecoderBenchmarkKt'], text=True, timeout=120))
    report.update(scope='Host JVM synthetic decoder benchmark; NOT Android or USB end-to-end throughput',
                  host=platform.platform(), revision=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(),
                  source_sha256={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sources})
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2)+'\n')
    print(json.dumps(report, indent=2))

if __name__ == '__main__':
    main()
