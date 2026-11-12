# Contributing to Brucia

Code, documentation, accessibility fixes and reproducible device reports are all
welcome. This guide covers the toolchain and project conventions; the
[wiki contribution guide](wiki/Contributing.md) offers a shorter starting point.

## Find your first contribution

- **Using Brucia?** Follow a guide and tell us where you got stuck. Include versions
  and the board in a bug report; the issue template will walk you through it.
- **Editing documentation?** Fix the source under `wiki/` or the relevant Markdown
  file. Check links and commands; you do not need hardware for a wording fix.
- **Testing hardware?** Use the [functional checklist](docs/APP_CONTROLS_CHECKLIST.md)
  and report the setup, result and any limitations.
- **Writing code?** Start with a focused issue or a small fix in a familiar module.
  Read the [roadmap](ROADMAP.md) before proposing a larger feature.

Fork the repository, create a branch and open a pull request describing the problem,
the change and the checks you ran. For a larger feature, discuss the scope in an
issue first. Keep reports free of personal captures, passwords and keys.

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
`patch_library_conflicts.py` patches them on the first build. Before `pio run` it
also moves every library in `.pio/libdeps/<env>` that the env's `lib_deps` (or their
own declared dependencies) do not account for to `.pio/libdeps-orphans/<env>/` and
prints each one: an undeclared library left over from an older config or a manual
`pio pkg install` would otherwise be linked by the Library Dependency Finder through
a `__has_include` and change the firmware (it did for the Cardputer and M5Unified).
Use the wrapper for a new checkout, after deleting `.pio`, or whenever libraries
were installed by hand; plain `pio run` does not clean `.pio/libdeps`.

The pioarduino platform edits the shared
`~/.platformio/packages/framework-arduinoespressif32-libs/<chip>/pioarduino-build.py`
for `lib_ignore` (for example `SD` strips the `fatfs` include paths) and restores it
only when a build reaches the size check. A failed or interrupted build, or a target
such as `envdump`/`compiledb`, used to leave it edited, and every later env of that
chip then failed with `esp_vfs_fat.h: No such file or directory`.
`pre_restore_pioarduino_build.py` puts the original back before and after every
build. Keep it the first `pre:` entry, and if a board overrides `extra_scripts`,
include it there. It does not protect parallel `pio run` processes sharing one core
directory: give each parallel worker its own `PLATFORMIO_CORE_DIR`.

#### Pinned libraries and how to bump one

Every library in `lib_deps` (platformio.ini `[env]`/`[env_light]` and the `boards/**/*.ini`
overrides) names one release: a registry library as `owner/name @ x.y.z`, a Git library at
a full 40-character commit (`https://github.com/owner/repo#<sha>`), a download as a commit
archive URL. No `^`, `~`, `>=`, bare names or branches: with a range, a fresh install picks
the newest matching release while an existing `.pio/libdeps` keeps the one it has, so the
same commit built different firmware (m5stack-core4mb differed by 608 B: M5Unified
0.2.28/0.2.29 and M5GFX 0.2.35/0.2.36). The one exception is `FFat`, an Arduino-ESP32
built-in whose version is the platform's. A compiled registry library that another library
pulls in through a range in its own manifest is pinned directly next to it (`m5stack/M5GFX`
next to `m5stack/M5Unified`, `ESP32Async/AsyncTCP` next to `ESP32Async/ESPAsyncWebServer`).
Manifest dependencies that are installed but never compiled stay out of `lib_deps`, because
listing a library there forces it into the build (Adafruit SH110X and Adafruit GFX Library,
pulled in by Adafruit TCA8418: pinning them grew the Cardputer firmware by 32 B).
`tools/test_dependency_pins.py` enforces all of this for every env.

To bump a library:

1. Pick the release: `pio pkg show owner/name` (registry) or `git ls-remote <url> <ref>`
   (Git; use the full commit, not a tag or branch).
2. Change its line in every ini that names it (`grep -rn 'name' platformio.ini boards`).
   For a parent listed above, read the new release's `library.json` `dependencies` (or
   `library.properties` `depends=`) and move the directly pinned child to a release that
   satisfies it.
3. If `patch_library_conflicts.py` patches the library, check the patterns still apply
   (the build fails when one no longer matches).
4. Build each CI env with the wrapper and run the host tests:
   `python3 tools/build_firmware.py -e <env>`, `python3 tools/test_audio_noise_bounds.py -e <env>`,
   `python3 -m unittest discover -s tools -p 'test_*.py'`. An existing `.pio/libdeps` needs no
   cleaning: `pio pkg install` removes what was installed for a spec that left `lib_deps`
   (it compares against `.pio/libdeps/<env>/integrity.dat`) and installs the pinned release
   when the installed one differs (moving the old one aside as `name@version`); the wrapper
   then quarantines whatever the pins no longer reach, including another installed release
   of a pinned library that only a dependency's range would match. It prints
   `Warning: <env> resolves <library> ... through a range` for each library still reached
   only through a range: pin it if the env compiles it (the build log's "Dependency Graph").
5. Note the old and new versions and the firmware size change in CHANGELOG.md.

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
2. **Firmware** - in the appropriate `src/companion/` module, handle the `CMD`/`SUB`, and
   emit `EVENT`/`DATA`. Keep peripheral work on the ESP; do not compute there what
   the phone can do.
3. **App** - add the Kotlin side (ViewModel + a Compose screen under `app.brucia`),
   parsing the same messages.
4. **Tests** - unit-test the phone-side logic; add a Paparazzi snapshot if it has UI;
   extend `tools/` if it helps.
5. **Keep standalone intact** - preserve and check the standalone paths affected by the change.

## Style & conventions

- **English everywhere**: code, comments, docs, UI strings, commit messages.
- **No emoji** anywhere (code, UI, docs, commits).
- Match the surrounding code's idiom and comment density.
- Flame theme: ember `#FF6D1A`, amber `#FFB300`, deep `#E23E00`, bg `#0E0E10`.
- **Project links** (repository, site, wiki, releases, Bruce credit) come from `brand.json`: use
  `BRUCIA_*` macros from `brucia_identity.h` in firmware, `BrandLinks` in the app and `BruciaBrand`
  (`docs/brand.js`) on the site, then run `python3 tools/brand.py write`. `tools/test_brand.py`
  rejects new hardcoded links; see `docs/LAUNCH.md`.

## Commits & PRs

For public documentation and media, use `brand.json` for project links and
`docs/LAUNCH.md` for regeneration. Keep screenshots labelled as previews or physical
captures, and attach revision-specific evidence to hardware claims. Illustrated
guides are generated by `tools/render_public_media.py`; verify the Paparazzi inputs
first. Updating source files does not publish the website or GitHub wiki.

- Run checks relevant to the change and describe their results; distinguish builds,
  automated tests and physical checks. Note anything you could not run.
- Keep changes focused; describe the what and why.
- By contributing you agree your work is licensed under **AGPL-3.0**, and that
  Brucia credits upstream Bruce (keep `LICENSE`, `NOTICE`, `THIRD_PARTY.md` intact).

## License

[AGPL-3.0](LICENSE). Brucia is a derivative of
[Bruce](https://github.com/BruceDevices/firmware) and complementary to its official app.
