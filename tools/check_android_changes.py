#!/usr/bin/env python3
"""Type-check modified main Kotlin files against an existing debug build/cache.

No Gradle daemon or sockets. Requires a previous Android debug build. This does
not regenerate Room/KSP output, run lint, package resources or build an APK.
"""
import os
import argparse
from pathlib import Path
import subprocess
import tempfile
from test_android_logic import ARTIFACTS, ROOT


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--unit-tests', action='store_true', help='Compile/run all non-Paparazzi unit suites against the cached app build')
    parser.add_argument('--all-main', action='store_true', help='Compile all production Kotlin, including Compose, against cached dependencies/generated Java')
    args = parser.parse_args()
    cache = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")) / "caches"
    modules = cache / "modules-2/files-2.1"
    compiler = [str(next((modules / g / a / v).glob(f"*/{a}-{v}.jar"))) for g, a, v in ARTIFACTS]
    build = ROOT / "android/app/build"
    original = build / "tmp/kotlin-classes/debug"
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        props = ROOT / "android/local.properties"
        if props.exists():
            sdk = next((line.split("=", 1)[1] for line in props.read_text().splitlines() if line.startswith("sdk.dir=")), None)
    sdk = Path(sdk) if sdk else Path.home() / "Library/Android/sdk"
    android_jar = sdk / "platforms/android-34/android.jar"
    if not original.is_dir() or not android_jar.exists():
        raise SystemExit("A previous debug build and Android SDK 34 are required")
    classpath = [original, build / "intermediates/javac/debug/compileDebugJavaWithJavac/classes", android_jar]
    classpath += list(cache.glob("*/transforms/**/classes.jar"))
    classpath += [p for p in modules.rglob("*.jar") if not p.name.endswith(("-sources.jar", "-javadoc.jar"))]
    classpath += list(build.glob("intermediates/compile_and_runtime_not_namespaced_r_class_jar/debug/**/R.jar"))
    changed = subprocess.check_output(["git", "diff", "--name-only", "HEAD"], cwd=ROOT, text=True).splitlines()
    changed += subprocess.check_output(["git", "ls-files", "--others", "--exclude-standard"], cwd=ROOT, text=True).splitlines()
    sources = sorted({str(ROOT / p) for p in changed if p.startswith("android/app/src/main/") and p.endswith(".kt") and (ROOT / p).exists()})
    plugins = []
    if args.all_main:
        sources = sorted(str(p) for p in (ROOT / 'android/app/src/main').rglob('*.kt'))
        plugin = next((modules / 'org.jetbrains.kotlin/kotlin-compose-compiler-plugin-embeddable/2.0.21').glob('*/*.jar'), None)
        if plugin is None:
            raise SystemExit('Cached Kotlin Compose compiler 2.0.21 is required')
        plugins = [f'-Xplugin={plugin}']
    production_count = len(sources)
    test_files = []
    if args.unit_tests:
        test_files = sorted((ROOT / 'android/app/src/test/java/app/brucia').glob('*Test.kt'))
        test_files = [p for p in test_files if p.name != 'FlameUiScreenshotTest.kt']
        sources += [str(p) for p in test_files]
    if not sources:
        print("No modified production Kotlin sources")
        return
    with tempfile.TemporaryDirectory(prefix="brucia-typecheck-") as tmp:
        subprocess.run([
            "java", "-Xmx2g", "-cp", os.pathsep.join(compiler), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
            "-no-stdlib", "-no-reflect", "-jvm-target", "17", f"-Xfriend-paths={original}",
            "-classpath", os.pathsep.join(map(str, classpath)), "-d", tmp, *plugins, *sources,
        ], cwd=ROOT, check=True)
        if test_files:
            # Actual JSON/JUnit/Kotlin jars precede SDK stubs at runtime.
            runtime = [Path(tmp), *map(Path, compiler), *classpath]
            subprocess.run(['java', '-Xmx2g', '-cp', os.pathsep.join(map(str, runtime)),
                            'org.junit.runner.JUnitCore', *(f'app.brucia.{p.stem}' for p in test_files)],
                           cwd=ROOT / 'android/app', check=True)
    scope = 'all' if args.all_main else 'modified'
    print(f"Compilation passed: {production_count} {scope} production Kotlin files and {len(test_files)} unit suites")


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as error:
        raise SystemExit(error.returncode) from None
