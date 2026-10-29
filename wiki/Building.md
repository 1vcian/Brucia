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

PlatformIO with the pioarduino core (a Python 3.11 venv is recommended). The target
environment is `lilygo-t-embed-cc1101`.

```sh
pio run -e lilygo-t-embed-cc1101            # build
pio run -e lilygo-t-embed-cc1101 -t upload  # flash over USB
```

A merged, flashable image is produced at
`.pio/build/lilygo-t-embed-cc1101/firmware.factory.bin` - this is what the browser
flasher at https://1vcian.me/BruciaDev/ serves.

Notes:
- The board is an ESP32-S3 with native USB CDC; no external USB-serial adapter is
  needed.
- Partition layout comes from the `custom_*.csv` files.
- Firmware additions such as deauth, battery reporting and lite mode require flashing
  the latest build.

## Android app (JDK 17+)

JDK 17 or newer is required (21 and 23 are tested).

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

# Mock-device end-to-end protocol test (no hardware)
python3 tools/test_protocol_e2e.py
```

`tools/` also includes `mock_device.py` (a software device that speaks the protocol)
and `gen_mfcodes.py`.

## Continuous integration

CI runs the firmware build, the Android unit and Paparazzi screenshot tests, and the
protocol end-to-end test. Gate commits on a green build and tests.
