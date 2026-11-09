# Building

How to build the firmware and the Android app, sign release builds, and run the
host-side protocol tools. See [[Contributing]] for project rules and
[[Architecture]] for where things live.

## Repository layout

| Path | What |
|------|------|
| *(root)* | Fork of the Bruce firmware (AGPL): `src/`, `platformio.ini`, boards. Companion code in `src/companion/`. |
| `android/` | Native Android app (Kotlin + Jetpack Compose), package `app.brucia`. |
| `proto/` | The companion wire protocol - the single source of truth for both sides. |
| `docs/` | Usage, comparison, hardware proof, and the browser flasher (GitHub Pages). |
| `tools/` | Host-side protocol clients (`companion_test.py`, mock device, e2e). |

## Firmware (ESP32-S3, LilyGo T-Embed CC1101)

PlatformIO Core 6.2.0 with the pioarduino platform, installed with pip in a
Python 3.10-3.14 venv (3.15+ is rejected by the platform). The target environment
is `lilygo-t-embed-cc1101`.

```sh
python3.11 -m venv ~/.venvs/brucia && . ~/.venvs/brucia/bin/activate
pip install platformio==6.2.0 pyserial
python3 tools/build_firmware.py -e lilygo-t-embed-cc1101   # build (use this on a new checkout)
pio run -e lilygo-t-embed-cc1101 -t upload                 # flash over USB
```

The wrapper makes sure every library is installed before the build so the
pre-build library patches apply on the first build.

A merged, flashable image is produced at
`.pio/build/lilygo-t-embed-cc1101/firmware.factory.bin` - this is what the browser
flasher at https://1vcian.me/Brucia/ serves.

Notes:
- The board is an ESP32-S3 with native USB CDC; no external USB-serial adapter is
  needed.
- Partition layout comes from the `custom_*.csv` files.
- Firmware additions such as deauth, battery reporting and lite mode require flashing
  the latest build.

## Android app (JDK 21-25)

JDK 21 to 25 is required (Paparazzi 2.0 needs 21+; Gradle 9.7.1/AGP 9.3.3 need 17+; verified with 23
locally and 21 in CI), plus the Android SDK with `platforms;android-37.0` and `build-tools;36.0.0`. Set `ANDROID_HOME`
(or `sdk.dir` in `android/local.properties`), otherwise Gradle stops with
"SDK location not found".

```sh
cd android
./gradlew :app:testDebugUnitTest      # unit + Paparazzi screenshot tests
./gradlew :app:assembleDebug          # debug APK
./gradlew :app:recordPaparazziDebug   # re-record UI snapshots after a UI change
```

The debug APK is produced under `android/app/build/outputs/apk/`.

## Release signing

Release builds are signed from a keystore that is **never committed**. Create
`android/keystore.properties` (git-ignored) with:

```
storeFile=/absolute/path/to/brucia.keystore
storePassword=...
keyAlias=brucia
keyPassword=...
```

Then build the signed release:

```sh
cd android
./gradlew :app:assembleRelease
```

Without that file, the release build falls back to **unsigned**, so CI and fresh
clones still build.

## Host-side protocol tools (`tools/`)

Useful for testing the companion protocol without the app. On the device, tap
**Brucia** on the home screen to enter companion mode first (for the live tests).

```sh
# Handshake + PING against a real device
python3 tools/companion_test.py /dev/tty.usbmodemXXXX

# Live Wi-Fi AP stream from a real device
python3 tools/companion_test.py /dev/tty.usbmodemXXXX --wardrive

# Mock-device end-to-end protocol test (no hardware; needs pyserial)
python3 tools/test_protocol_e2e.py
```

`tools/` also includes `mock_device.py` (a software device that speaks the protocol)
and `gen_mfcodes.py`.

## Continuous integration

CI runs the firmware build, the Android unit and Paparazzi screenshot tests, and the
protocol end-to-end test. Gate commits on a green build and tests.
