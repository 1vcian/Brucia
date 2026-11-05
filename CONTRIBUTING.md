# Contributing to Brucia

Thanks for helping build Brucia. This guide covers the build, the project rules, and
how to add a feature cleanly.

Brucia is **for authorized security research and education only**. Contributions must
keep that framing; do not add anything whose only purpose is to attack systems the
user does not own or is not permitted to test.

## Project layout

| Path | What |
|------|------|
| *(root)* | Fork of the Bruce firmware (AGPL): `src/`, `platformio.ini`, boards. Companion code in `src/companion/`. |
| `android/` | Native Android app (Kotlin + Jetpack Compose), package `app.brucia`. |
| `proto/` | The companion wire protocol - the single source of truth for both sides. |
| `docs/` | Usage, comparison, hardware proof, and the browser flasher (GitHub Pages). |
| `tools/` | Host-side protocol clients (`companion_test.py`, mock device, e2e). |

See [ARCHITECTURE.md](ARCHITECTURE.md) for the design and [firmware module map](src/companion/README.md)
for where Brucia hooks into Bruce.

## Build & test

All commands run from the repository root unless they start with `cd`. They are
written for bash/zsh on macOS and Linux.

### Prerequisites

| Tool | Version | Needed for |
|------|---------|------------|
| Git | any | clone; PlatformIO fetches Git-pinned libraries |
| JDK | 21 to 25 (Paparazzi 2.0 requires 21+; Gradle 9.7.1/AGP 9.3.3 require 17+); verified with 23 locally and 21 (Temurin) in CI | Android |
| Android SDK | `platform-tools`, `platforms;android-37.0`, `build-tools;36.0.0` | Android |
| Python | 3.10 to 3.14 (CI: 3.11). The pinned pioarduino platform rejects 3.15+ | firmware, host tests |
| PlatformIO Core | 6.2.0 (CI pin), installed with pip | firmware |
| pyserial | any (`pip install pyserial`) | `test_protocol_e2e.py`, `companion_test.py` |
| `c++` and `cc` | Clang or GCC with UBSan (`xcode-select --install`, or `build-essential`) | host firmware tests |
| Node.js | CI: 22.20.0 | website release tests |

There are no Git submodules. `gh` is only used by the maintainer's `release.sh`.

Python environment (once). Keep it outside the repository; any 3.10-3.14
interpreter works in place of `python3.11` (CI uses 3.11; Debian/Ubuntu also need
the `python3-venv` package):

```sh
python3.11 -m venv ~/.venvs/brucia
. ~/.venvs/brucia/bin/activate          # in every new shell
pip install platformio==6.2.0 pyserial
pio system info | grep -i python        # must report 3.10-3.14
```

Use the `pio` from this venv. A system `pio` running on Python 3.15+ (for example
a current Homebrew `platformio`) fails with `ERROR: Python version must be 3.10, 3.11, 3.12, 3.13, 3.14.`

### Android app

Point Gradle at a JDK 21-25 and the SDK. Use the lines for your OS; the SDK paths
are the Android Studio defaults:

```sh
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"   # macOS
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64  # Linux, adjust to your JDK
export ANDROID_HOME="$HOME/Library/Android/sdk"      # macOS
export ANDROID_HOME="$HOME/Android/Sdk"              # Linux
```

Without `ANDROID_HOME`, Gradle fails with "SDK location not found"; writing
`sdk.dir=/path/to/sdk` to `android/local.properties` (git-ignored) also works.
Without Android Studio, install the SDK packages with the command-line tools:
`sdkmanager --licenses` then
`sdkmanager "platform-tools" "platforms;android-37.0" "build-tools;36.0.0"`.

```sh
cd android
./gradlew :app:testDebugUnitTest     # unit + Paparazzi screenshot tests
./gradlew :app:assembleDebug         # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:lintDebug             # CI also runs lint
./gradlew :app:recordPaparazziDebug  # re-record UI snapshots after a UI change
cd ..
```

The first run downloads Gradle 9.7.1 and all dependencies (about 2.5 GB in
`~/.gradle`, a few minutes). Check a built APK's applicationId,
`VERSION`-derived versionName/versionCode, min/target SDK and debuggable flag
(uses `aapt2` from `ANDROID_HOME`; CI runs the same check):

```sh
python3 tools/check_apk_identity.py --build-type debug android/app/build/outputs/apk/debug/app-debug.apk
```

Restricted-environment Kotlin checks and their limits are documented in
[TESTS.md](TESTS.md). Do not substitute cached compilation for a fresh
Gradle/lint/APK build before release.

### Host tests (no hardware)

With the venv active (pyserial is required by the end-to-end test):

```sh
python3 -m unittest discover -s tools -p 'test_*.py'
for t in tools/test_firmware_*.py tools/test_protocol_e2e.py; do
  python3 "$t" || echo "FAILED: $t"
done
node --test tools/test_site_release.cjs
```

The `test_firmware_*.py` scripts compile firmware sources natively with `c++`
(override with `CXX=...`). WebUI assets are gzip-compressed locally without a
network minifier; `test_web_resources.py` covers that.

### Firmware (ESP32-S3, LilyGo T-Embed CC1101)

With the venv active:

```sh
python3 tools/build_firmware.py -e lilygo-t-embed-cc1101   # bootstrap + build
python3 tools/test_audio_noise_bounds.py -e lilygo-t-embed-cc1101  # CI check on the built env
pio run -e lilygo-t-embed-cc1101 -t upload                 # flash (device attached)
```

The first build downloads the pioarduino platform and the ESP32 toolchains and
framework into `~/.platformio` (several GB; set `PLATFORMIO_CORE_DIR` to put them
elsewhere) and the libraries into `.pio/libdeps`. The build wrapper installs direct
dependencies first and fixes malformed Arduino NimBLE/PN532-UART dependency
declarations before recursive resolution, then lets PlatformIO install the
owner-less registry libraries (for example `ESP8266Audio`) before the build, so
`patch_library_conflicts.py` patches them on the first build. Use the wrapper for
a new checkout or after deleting `.pio`; plain `pio run` is fine afterwards.

The flashable images are `.pio/build/lilygo-t-embed-cc1101/firmware.factory.bin`
(merged, used by the web flasher) and `Bruce-lilygo-t-embed-cc1101.bin` in the
repository root.

Companion contributions should follow the [service ownership map](src/companion/README.md).

### Release signing
Release builds are signed from a keystore that is **never committed**. Create
`android/keystore.properties` (git-ignored) with:

```
storeFile=/absolute/path/to/brucia.keystore
storePassword=...
keyAlias=brucia
keyPassword=...
```

Then `cd android && ./gradlew :app:assembleRelease`. Without that file, the release
build falls back to unsigned (`app/build/outputs/apk/release/app-release-unsigned.apk`)
so CI and fresh clones still build. Check it with
`python3 tools/check_apk_identity.py --build-type release <apk>`.

## Host-side protocol testing

These need the Python venv with pyserial (see Build & test).

```sh
python3 tools/companion_test.py /dev/tty.usbmodemXXXX            # handshake + PING
python3 tools/companion_test.py /dev/tty.usbmodemXXXX --wardrive # live AP stream
python3 tools/test_protocol_e2e.py                              # mock-device e2e
python3 tools/hw_acceptance.py --port /dev/tty.usbmodemXXXX   # passive acceptance report
python3 tools/test_hw_acceptance.py                            # runner vs mock device
```
On the device, tap **Brucia** on the home screen to enter companion mode.

## The one hard rule: the protocol is the source of truth

The wire protocol in [proto/PROTOCOL.md](proto/PROTOCOL.md) is mirrored by the
firmware (C++, `src/companion/`) and the app (Kotlin, `app.brucia`). **Never add a
message to one side only.** Bump `PROTOCOL_VERSION` on any schema change and keep
both sides aware of it.

## Adding a companion module (end to end)

1. **Protocol** - define the message/stream in `proto/PROTOCOL.md` first.
2. **Firmware** - in `src/companion/companion.cpp`, handle the `CMD`/`SUB`, and
   emit `EVENT`/`DATA`. Keep peripheral work on the ESP; do not compute there what
   the phone can do.
3. **App** - add the Kotlin side (ViewModel + a Compose screen under `app.brucia`),
   parsing the same messages.
4. **Tests** - unit-test the phone-side logic; add a Paparazzi snapshot if it has UI;
   extend `tools/` if it helps.
5. **Keep standalone intact** - the device must remain a full Bruce with no phone.

## Style & conventions

- **English everywhere**: code, comments, docs, UI strings, commit messages.
- **No emoji** anywhere (code, UI, docs, commits).
- Match the surrounding code's idiom and comment density.
- Flame theme: ember `#FF6D1A`, amber `#FFB300`, deep `#E23E00`, bg `#0E0E10`.

## Commits & PRs

- Gate commits on a green build/tests.
- Keep changes focused; describe the what and why.
- By contributing you agree your work is licensed under **AGPL-3.0**, and that
  Brucia credits upstream Bruce (keep `LICENSE`, `NOTICE`, `THIRD_PARTY.md` intact).

## License

[AGPL-3.0](LICENSE). Brucia is a derivative of
[Bruce](https://github.com/BruceDevices/firmware) and complementary to its official app.
