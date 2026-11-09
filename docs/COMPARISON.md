# Flipper Zero, Bruce and Brucia: an evidence-based comparison

Reference setup: **stock LilyGO T-Embed CC1101 + Android phone**, compared with
stock Flipper Zero and Bruce on the same LilyGO board. Add-ons are separate.
This is an implementation comparison, not a claim that every listed Brucia path
has passed physical acceptance. See [current tests](../TESTS.md),
[hardware capabilities](HARDWARE_CAPABILITIES.md) and the
[latest audit](../TESTS.md).

Brucia retains Bruce firmware functionality and attribution, adds a device-side
companion protocol and an Android workspace. Many radio features originate in
Bruce. The phone extends analysis, storage and orchestration; it cannot supply a
missing antenna, analog front end or radio modulation capability.

## What is actually different? Review dated 2026-11-09

Baseline: official Flipper firmware/mobile documentation and official Bruce firmware
plus its companion apps, not a hypothetical device without its app. Community projects
are called out when they disprove an exclusivity claim. This is not an exhaustive audit
of every fork, script or plugin. **"Distinctive" below is an inference from the selected
primary sources and Brucia's code, not proof that nobody else has implemented it.**

| Brucia capability | Classification | Evidence and limit |
|---|---|---|
| Android audio/DSP workspace: PCM, spectrum, waterfall, scope, DTMF, SSTV | Strong differentiator in the reviewed official workflows | Brucia `audio/Sstv.kt`, `AudioScreens.kt` and PCM services implement the processing. No equivalent combined workspace is documented in the official mobile guides reviewed. Algorithms are established; physical audio acceptance remains open. |
| Saved Wi-Fi/BLE observations: phone GPS/maps, persistent timeline and place-inventory differences | Strong integration differentiator | `RfTimelineScreen.kt`, `RfFingerprintScreen.kt`, Room storage and wardrive exports. Bruce already has GPS wardriving; the distinction is the Android history/analysis workflow. A changed inventory is not proof of surveillance or precise emitter location. |
| HTTP/HTTPS inspection proxy hosted on Android | Strong processing/workflow differentiator | `mitm/MitmProxy.kt` implements the phone proxy and transaction logging. Bruce already has ARP and proxy tools; those primitives are not novel. TLS inspection needs a compatible client trusting the CA and does not defeat certificate pinning. |
| WPA/WPA2 dictionary verification on the phone | Extension of an existing capability | `wifi/DictionaryTestService.kt` and related import/rule code. Bruce already implements WPA password recovery on the ESP32. Brucia adds the Android workflow; no measured speed multiplier is claimed. |
| Reports, saved macros and analysis around captures | Integration advantage | `report/SessionReport.kt`, `automation/MacroRunner.kt`. Reporting and automation are established concepts, and Bruce already supports scripts. |
| De Bruijn planner and app-managed transmission | Added app workflow, not a new technique or ecosystem exclusive | Brucia `subghz/DeBruijnTx.kt` and service. Community Flipper material already documents De Bruijn support. No receiver success follows from combinatorial correctness alone. |
| BLE/Wi-Fi/IR spam controls | Inherited/extended tools with a unified UI | Bruce already documents BLE platform spam, beacon spam and TV-B-Gone. Brucia adds phone controls, shared operation state and stop handling. No guaranteed macOS/other-OS popup. |
| App, screen mirror, mobile update, IR library and phone-side key calculations | Not valid standalone novelty claims | Both competitors have official apps. Bruce App documents flashing, commands and mirroring; Flipper documents updates, remotes and Mfkey32 calculation. |

### Primary sources for the novelty classification

- [Bruce's official companion-app guide](https://wiki.bruce.computer/companion-apps/):
  desktop/Android flashing, commands and mirroring; the separate iOS app includes
  capture/replay, files and a capture library.
- [Flipper's official mobile guide](https://docs.flipper.net/zero/mobile-app):
  remote control, update/data workflows, Mfkey32 and the IR remotes library.
- [Bruce Wi-Fi documentation](https://wiki.bruce.computer/features/wifi/): password
  recovery, wardriving-related tools, beacon spam, deauth, ARP and proxy functions.
- [Bruce password-recovery source](https://github.com/BruceDevices/firmware/blob/main/src/modules/wifi/wifi_recover.cpp)
  confirms the on-device dictionary-processing implementation. Local upstream/main
  `a59213f3` contains this module too.
- [Bruce firmware feature list](https://github.com/BruceDevices/firmware#list-of-features)
  documents BLE spam and IR TV-B-Gone among the existing functions.
- [Flipper community Sub-GHz inventory](https://github.com/UberGuidoZ/Flipper/blob/main/Sub-GHz/ReadMe.md)
  identifies existing De Bruijn material. This is community content, not a stock-firmware
  guarantee, and is cited only to avoid an exclusivity claim.

These sources describe competitor features; they do not validate Brucia hardware.
Brucia source paths above are relative to `android/app/src/main/java/app/brucia/`.

| Area | Stock Flipper Zero | Bruce on stock T-Embed CC1101 | Brucia on stock T-Embed + phone |
|---|---|---|---|
| Wi-Fi | External Wi-Fi board | Native ESP32 Wi-Fi | Native; phone-side maps, capture analysis and workflow |
| BLE | Built in; mobile app and community apps | Built in; board-dependent tools | Device radio plus Android analysis and controls |
| Sub-GHz | CC1101 with supported protocol library | CC1101 on this board | Same radio; protocol coverage and RF performance require individual verification |
| NFC 13.56 MHz | ST25R3916; card-specific reading/emulation | PN532 on this board | PN532: compatible reads/writes, Type 4 NDEF and identity-only FeliCa target paths; no universal emulation |
| RFID 125 kHz | Native read/emulate/write for supported tags | No stock LF hardware | Unavailable on stock T-Embed; compatible add-on integration required |
| iButton | Built-in contacts and supported protocols | No stock contacts | Unavailable on stock T-Embed; hardware/interface support required |
| Infrared | Built-in TX/RX | Built-in TX/RX on this board | Device TX/RX with phone catalog and custom layouts |
| Audio | No built-in microphone | Board microphone/speaker and audio tools | Existing DSP, SSTV RX/TX, DTMF, scope and RTTTL composer; physical signal chain pending |
| NRF24 / LoRa / FM / Ethernet | External hardware where supported | External modules where supported | Optional modules; presence and operation support gate each tool |
| Mobile workflow | Official app: remote, updates, data, remotes and key calculation | Official apps as well as device UI / WebUI / CLI | Android capture/analysis workspace; phone compute, storage, GPS and display |
| Firmware updates | Official update tools and mobile workflow | Board-specific distribution | Release parsing/download/flash code; final installation and recovery acceptance pending |
| Power | Standalone battery | Standalone board battery | USB power depends on phone/OTG/cable; also consumes the phone battery |

## Speed: measured processing versus unmeasured device performance

Measured 2026-11-09 with the actual Brucia Kotlin modules on an **Apple M2 Pro,
macOS 26.6, HotSpot Java 23, SunJCE**. These are host microbenchmarks, **not Android
results and not a measured Brucia-phone-versus-Bruce/Flipper speedup**. No Android
device or T-Embed was connected. Neither competitor was benchmarked in this run.

| Workload | Brucia code on this Mac (median) | Comparable competitor evidence |
|---|---|---|
| WPA2 dictionary verification, PBKDF2 + PTK + MIC | **963 candidates/s**; 200 candidates in 207.6 ms | Bruce's source comment reports about **13–14 passwords/s on T-Embed S3**. Upstream self-report, different fixture/runtime; not independently reproduced here. Phone result pending. |
| PMKID verification, PBKDF2 + HMAC | **981 candidates/s** | No matched baseline measured; do not substitute the WPA2 source comment. |
| FFT magnitude, 2,048 samples at 16 kHz | **0.0405 ms/window** (24,710 windows/s) | No equivalent competitor workload measured. A window represents 128 ms of audio; this measures computation, not display refresh or capture latency. |
| DTMF, 3.84 s of synthetic audio at 16 kHz | **1.38 ms/recording** | No equivalent competitor workload measured. Does not include microphone/USB or noisy signal acceptance. |
| SSTV Robot36, 320 × 240 synthetic image | **5.80 ms encode / 92.09 ms decode** | The generated audio still lasts **36.91 s**. Faster preparation/decoding does not shorten the signal's playback/transmission time. |

Bruce reference: [password-recovery source at the inspected upstream revision](https://github.com/BruceDevices/firmware/blob/a59213f3ec6cd302fa32c2042b2700c9c24d991a/src/modules/wifi/wifi_recover.cpp).
The two WPA2 figures above do not establish a product speed multiplier: hardware,
crypto provider, workload, warm-up and storage/UI costs differ. No Flipper multiplier
is available either; an absent or untested equivalent is not "zero speed".

Method: three warm-up batches, then seven timed batches; median shown. WPA2/PMKID
use 200 valid-length synthetic candidates with no match, so no early success or
invalid-password skips inflate the rate. Python hashlib/hmac independently verifies
PMK/MIC/PMKID before timing. FFT checks a known 1 kHz tone; DTMF checks all 16 symbols;
SSTV uses a uniform grey image with a decoded-centre sanity check. FFT and DTMF batch
1,000 and 100 calls respectively; SSTV batches one image. Samples, runtime, fixtures
and source hashes: [raw benchmark result](benchmarks/2026-11-09-host.json).
The benchmark includes allocations and result checks, excludes fixture generation,
and does not establish sustained thermal performance. It is not a full correctness suite.

Reproduce on the host with cached Kotlin dependencies:

```sh
python3 tools/benchmark_android_logic.py --output .codex_tmp/benchmarks/host.json
```

### Improvements that need different measurements

| Area | What improved | Speed evidence still needed |
|---|---|---|
| De Bruijn | Bounded app planning, frame-compatible chunks and operation lifecycle | Planning time, USB overhead and actual on-air duration are different quantities. No faster-radio or receiver-success claim from this CPU benchmark. |
| BLE / Wi-Fi / IR spam | Shared controls, conflict handling, stop and truthful status | Stop latency and observed device results on the same hardware/settings; a faster phone does not prove a higher usable radio rate or OS response. |
| USB, files and Remote | Framing/recovery and lifecycle fixes | Matched file-transfer throughput, p50/p95/p99 latency and recovery time. Historical soaks with different workloads are not an A/B benchmark. |
| Maps, history, reports and proxy | More analysis performed on Android | Fixed dataset sizes, phone model, cold/warm timings and memory use. No competitor timings collected. |

To establish a product comparison, rerun the same synthetic WPA2 fixture and candidate
list on the Android runtime and Bruce on T-Embed, recording versions, provider, worker
count, power/thermal conditions and repeated elapsed times. Report compute-only and
end-to-end times separately. For audio use identical PCM/images and distinguish CPU
processing from acquisition/playback time. These hardware measurements remain **NOT RUN**.

## What software can and cannot close

- Sub-GHz and IR are good candidates for additional compatible protocols. Matching
  CC1101 silicon is not proof of equal antenna performance, range, decoders or
  transmit behavior. RSSI waterfall is not an IQ spectrogram from a wideband SDR.
- PN532 target mode is not full MIFARE Classic/Ultralight emulation. The current
  FeliCa path emulates identity, not arbitrary card contents. Flipper's documented
  NFC-V support is another hardware/protocol gap. An NDEF record transferred via
  Type 4 does not clone its original card technology.
- LF RFID needs a coil and analog front end; iButton needs an electrical interface
  and contacts. Extra phone processing alone does not enable either.
- Recognizing a rolling-code format does not recover keys, defeat counters or
  guarantee that replay will be accepted. Protected NFC content likewise requires
  appropriate keys/access; neither device is a universal credential duplicator.
- Flipper's standalone operation, integrated hardware and existing protocol/app
  ecosystem are meaningful strengths. Brucia's phone-assisted workflow is the
  product direction, not evidence of universal superiority or exclusive novelty.
- Proposed bundles, replayable research sessions and community extensions remain
  in [the ideas backlog](../ROADMAP.md). Existing audio tools are not new ideas.

## Verification and distribution

USB connection, device information and passive AP scanning were observed on an
intermediate APK with firmware 0.8.23. They do not certify the current rebuilt
firmware and app. Logical/native tests and three representative board builds are
recorded separately from physical acceptance; the full configured-board matrix
has not been established by those three builds.

The repository is public. Publication does not close the physical acceptance checks.
No maintainer token is embedded. Missing release assets must produce an error,
not a silent download of unrelated or older firmware.

## Primary references

Checked during this audit; operation support remains card/protocol-specific:

- [Flipper NFC cards and emulation](https://docs.flipper.net/zero/nfc/read)
- [Flipper LF RFID hardware](https://blog.flipper.net/rfid/)
- [Flipper iButton hardware and protocols](https://docs.flipper.net/zero/ibutton)
- [Official LilyGO T-Embed CC1101 hardware](https://github.com/Xinyuan-LilyGO/T-Embed-CC1101)
- Brucia implementation: `src/companion`, `src/modules/rfid/PN532.cpp`, Android
  `audio`, `protocol`, `update` and `wardrive` packages. Implementation presence
  alone does not certify a successful physical operation.
