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

Release/website checks additionally use Node.js (CI pins 22.20.0). Run
`node --test tools/test_site_release.cjs` for the release catalog and installer logic.
Restricted-environment Kotlin checks and their limits are documented in [TESTS.md](TESTS.md).
Do not substitute cached compilation for a fresh Gradle/lint/APK build before release.

### Firmware (ESP32-S3, LilyGo T-Embed CC1101)
PlatformIO with the pioarduino core (Python 3.11 venv recommended):

```sh
python3 tools/build_firmware.py -e lilygo-t-embed-cc1101  # bootstrap + build
pio run -e lilygo-t-embed-cc1101 -t upload  # flash
```

The build wrapper installs direct dependencies first and fixes malformed Arduino
NimBLE/PN532-UART dependency declarations before recursive resolution. This avoids redundant
registry requests and makes subsequent builds work with an existing dependency
cache. `pio run` remains usable after that bootstrap.

A merged, flashable image is produced at
`.pio/build/lilygo-t-embed-cc1101/firmware.factory.bin` (used by the web flasher).

### Android app (JDK 17+; 21/23 tested)

CI uses Temurin JDK 21 (LTS) with an explicitly installed SDK (platform 34,
build-tools 34.0.0).

```sh
cd android
./gradlew :app:testDebugUnitTest   # unit + Paparazzi screenshot tests
./gradlew :app:assembleDebug       # debug APK
./gradlew :app:recordPaparazziDebug  # re-record UI snapshots after a UI change
```

Check a built APK's applicationId, `VERSION`-derived versionName/versionCode,
min/target SDK and debuggable flag with the SDK's aapt2 (CI runs the same check):

```sh
python3 tools/check_apk_identity.py --build-type release android/app/build/outputs/apk/release/app-release-unsigned.apk
```

WebUI assets are gzip-compressed locally without a network minifier. Validate
resource generation and the firmware's host-testable code with:

```sh
python3 -m unittest discover -s tools -p test_web_resources.py
python3 tools/test_firmware_framing.py
python3 tools/test_firmware_codec.py
```

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

Then `./gradlew :app:assembleRelease`. Without that file, the release build falls
back to unsigned so CI and fresh clones still build.

## Host-side protocol testing

```sh
python3 tools/companion_test.py /dev/tty.usbmodemXXXX            # handshake + PING
python3 tools/companion_test.py /dev/tty.usbmodemXXXX --wardrive # live AP stream
python3 tools/test_protocol_e2e.py                              # mock-device e2e
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
