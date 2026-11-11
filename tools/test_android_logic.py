#!/usr/bin/env python3
"""Run selected real Kotlin/JUnit logic tests without Gradle, SDK or sockets.

Reads already installed Maven artifacts from GRADLE_USER_HOME (default ~/.gradle).
No downloads. This is a subset of Android tests, not an APK/lint/UI verification.
Only BuildConfig.VERSION_NAME and the brand.json fields (BRAND_*, as Gradle generates them)
are supplied as generated host constants.
"""
import json
import argparse
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
ARTIFACTS = [
    # Keep in step with the Kotlin Gradle plugin in android/build.gradle.kts (AGP built-in
    # Kotlin: the org.jetbrains.kotlin.plugin.compose version selects it); the compiler's
    # own runtime dependencies (kotlin-reflect, coroutines) follow its published POM.
    ("org.jetbrains.kotlin", "kotlin-compiler-embeddable", "2.4.21"),
    ("org.jetbrains.kotlin", "kotlin-stdlib", "2.4.21"),
    ("org.jetbrains.kotlin", "kotlin-script-runtime", "2.4.21"),
    ("org.jetbrains.kotlin", "kotlin-reflect", "1.6.10"),
    ("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.8.0"),
    ("org.jetbrains", "annotations", "13.0"),
    ("junit", "junit", "4.13.2"),
    ("org.hamcrest", "hamcrest-core", "1.3"),
    ("org.json", "json", "20260814"),
]
TESTS = [
    "ProtocolTest", "DeviceFilesTest", "FileListTest", "CapsTest", "CapsEvidenceTest", "AudioDspTest",
    "TftProtocolTest", "SubGhzDecoderTest", "SubGhzEncoderTest", "NdefBuilderTest", "DeviceFileReaderTest", "DownloadIOTest", "ReleaseCatalogTest", "AwaitReplyTest", "FirmwareImageTest", "UpdateHttpTest", "HttpWireTest", "RecordingBufferTest",
    "DiagnosticsTest", "CaptureWorkflowTest", "CaptureDescriptorTest",
    "StreamRotationTest", "ParkedLeavesTest", "DeviceOutputsTest",
    "FrameBudgetTest", "HandshakeParserTest", "PcapReaderTest", "SubFileTest", "DeBruijnTxTest",
    "SlipReceiverTest", "CliReplyLimitTest",
]
# Test-only files without a test class (compiled, not run).
TEST_HELPERS = ["DeviceJson", "VirtualTime"]
SOURCES = [
    "protocol/Protocol.kt", "protocol/CompanionClient.kt", "protocol/CommandReply.kt", "protocol/LinkCounters.kt",
    "protocol/FrameBudget.kt",
    "session/CommandSession.kt", "wardrive/DeviceDiagnostics.kt",
    "transport/Transport.kt", "wardrive/DeviceInfo.kt", "wardrive/CapabilityEvidence.kt",
    "files/DeviceFilePush.kt", "files/FileKinds.kt", "files/FileTemplates.kt",
    "files/DeviceFileReader.kt",
    "update/DownloadIO.kt", "update/ReleaseCatalog.kt", "update/AssetIntegrity.kt", "update/FirmwareImage.kt", "protocol/StreamPolicy.kt", "protocol/AwaitReply.kt",
    "update/UpdateHttp.kt",
    "mitm/HttpWire.kt",
    "audio/RecordingBuffer.kt",
    "files/FileList.kt", "remote/TftProtocol.kt", "nfc/NdefBuilder.kt",
    "subghz/OokProtocols.kt", "subghz/DecodedSignal.kt", "subghz/SubGhzDecoder.kt", "subghz/SubGhzEncoder.kt",
    "subghz/DeBruijn.kt", "subghz/DeBruijnTx.kt",
    "audio/Dtmf.kt", "audio/Fft.kt", "audio/Goertzel.kt", "audio/Oscilloscope.kt", "audio/Sstv.kt",
    "session/CommandRunner.kt", "session/StreamSubscriptions.kt", "ui/ParkedLeaves.kt", "device/DeviceOutputs.kt",
    "wifi/CaptureWorkflow.kt", "wifi/CaptureDescriptor.kt", "wardrive/WifiTargetStore.kt", "wifi/Hc22000.kt",
    "wifi/HandshakeParser.kt", "wifi/WpaCracker.kt", "wifi/Pmkid.kt",
    "wifi/PcapReader.kt", "wifi/PcapWriter.kt", "wifi/Dot11Frame.kt", "subghz/SubFile.kt",
    "update/EspProtocol.kt", "BrandLinks.kt",
]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--firmware', type=Path, help='Also validate a real merged T-Embed image with the production Kotlin validator')
    args = parser.parse_args()
    cache = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")) / "caches/modules-2/files-2.1"
    jars = []
    for group, artifact, version in ARTIFACTS:
        matches = sorted((cache / group / artifact / version).glob(f"*/{artifact}-{version}.jar"))
        if not matches:
            raise SystemExit(f"Missing cached {group}:{artifact}:{version}; run Gradle dependency setup first")
        jars.append(str(matches[0]))
    classpath = os.pathsep.join(jars)
    with tempfile.TemporaryDirectory(prefix="brucia-kotlin-") as tmp:
        tmp = Path(tmp)
        config = tmp / "BuildConfig.kt"
        version = (ROOT / "VERSION").read_text().strip()
        brand = json.loads((ROOT / "brand.json").read_text())
        fields = {"VERSION_NAME": version, "BRAND_NAME": brand["name"], "BRAND_GITHUB_OWNER": brand["github_owner"],
                  "BRAND_GITHUB_REPO": brand["github_repo"], "BRAND_SITE_URL": brand["site_url"],
                  "BRAND_ACCEPTED_RELEASE_REPOS": ",".join(brand["accepted_release_repos"])}
        config.write_text("package app.brucia\nobject BuildConfig {\n" + "".join(
            f"    const val {key} = {json.dumps(value)}\n" for key, value in fields.items()) + "}\n")
        production = ROOT / "android/app/src/main/java/app/brucia"
        tests = ROOT / "android/app/src/test/java/app/brucia"
        sources = [str(production / name) for name in SOURCES]
        sources += [str(tests / f"{name}.kt") for name in TESTS]
        sources += [str(tests / f"{name}.kt") for name in TEST_HELPERS]
        if args.firmware:
            main_file = tmp / 'FirmwareCheck.kt'
            main_file.write_text('''import app.brucia.update.FirmwareImage
fun main(args: Array<String>) {
    FirmwareImage.validateTEmbed(java.io.File(args[0]).readBytes())
    println("Real merged image passed production Kotlin preflight")
}
''')
            sources.append(str(main_file))
        output = tmp / "classes"
        subprocess.run([
            "java", "-cp", classpath, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
            "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-classpath", classpath,
            "-d", str(output), str(config), *sources,
        ], check=True)
        if args.firmware:
            subprocess.run(['java', '-cp', str(output) + os.pathsep + classpath,
                            'FirmwareCheckKt', str(args.firmware.resolve())], check=True)
        subprocess.run([
            "java", "-cp", str(output) + os.pathsep + classpath,
            "org.junit.runner.JUnitCore", *(f"app.brucia.{name}" for name in TESTS),
        ], check=True)


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as error:
        raise SystemExit(error.returncode) from None
