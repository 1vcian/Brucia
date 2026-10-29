# Research platform ideas

These are proposals and extensions, not a list of missing capabilities. They complement the current
[roadmap](../ROADMAP.md). Reliability and functional verification come first;
new demos should follow evidence from real tests.

## Current decision: stabilize before expanding

The user explicitly prioritized making everything already present work correctly.
Do not start the backlog below before completing the existing functional audit,
fixes and appropriate regression checks. Keep unavailable physical tests pending;
do not substitute compilation or simulation for hardware acceptance.

Before implementing any proposal, inventory the existing code, UI and tests:
many related tools are already present. Extend those paths instead of duplicating
them. In particular, DTMF generation/decoding, SSTV RX/TX, audio spectrum/waterfall,
oscilloscope/vectorscope, WAV/PNG exports and an RTTTL melody composer already
exist. Firmware MIDI playback references do not establish a complete BLE MIDI
controller. Existing implementation is not proof of end-to-end correctness.

The most useful direction for Brucia is to make an experiment easy to reproduce,
understand and share. The phone already provides storage, computation and a usable
screen; the next step is to connect those advantages into a research workflow.

| Priority | Idea | What the researcher gets | First proof of value |
|---|---|---|---|
| 1 | Portable experiment bundles | One export containing raw captures, device capabilities, app/firmware versions, settings, annotations, timestamps and SHA-256 hashes. A redacted copy can be shared without publishing credentials or locations. | Import a bundle on a second phone, reproduce its analysis offline and verify every artifact hash. Explicitly record that a hash proves integrity, not origin or truth. |
| 1 | Hardware doctor | A guided inventory with chip identification, initialization results and optional loopback tests. Distinguish absent, detected, initialized, tested and unknown; explain which tools each result enables. | A stock T-Embed reports its real modules, keeps absent add-ons disabled, and records an actionable reason for each failed test without hanging or rebooting. |
| 1 | Experiments with assertions | Extend macros into bounded test recipes: prerequisites, parameters, expected events, assertions, cancellation and cleanup. Save results alongside captures. | Run a passive scan recipe twice; disconnect halfway through one run; show the failed step and release every resource. Offline fixtures exercise the same recipe engine. |
| 2 | One synchronized evidence timeline | Align Wi-Fi, BLE, Sub-GHz and audio observations with device events and researcher notes. Preserve original timestamps, estimated clock offsets, gaps and dropped-event counters. | Correlate a controlled stimulus across two streams and report measured timing uncertainty. Do not infer identity or causation merely from temporal overlap. |
| 2 | Replayable sessions and a virtual device | Reopen a recorded session and replay its protocol traffic into the real app UI without a radio. Publish synthetic fixtures with no personal data. | A contributor reproduces a reported UI/protocol bug from an attached fixture; the fixture becomes a regression test. Simulated and live data are visibly distinct. |
| 2 | Before/after experiments | Compare two captures from a controlled setup: configuration changes, channel occupancy, signal distribution and observed protocol behavior. | Produce a report that separates measured changes from hypotheses, and includes duration, sample count, calibration and missing data. RSSI is not presented as a precise distance measurement. |
| 2 | Protocol decoder workbench | Inspect raw pulses/bytes, annotate fields, test a decoder against a corpus and explain why a match was made. A decoder SDK enables contributions independently of the Android UI. | Add a decoder with known-answer and malformed-input fixtures; compare competing decoders and show uncertainty instead of silently choosing a label. |
| 3 | Community verification receipts | Share opt-in test results keyed by board revision, module, firmware commit and app version. Maintainers can see reproducible failures and actual hardware coverage. | A report can be generated locally, inspected/redacted, and attached manually to an issue. No automatic telemetry or upload. A passing build is never labeled hardware-verified. |
| 3 | Multiple capture nodes | Let one session combine several supported boards for simultaneous observations, with explicit ownership of radios and measured clock drift. | Two nodes capture the same controlled event, recover from a node loss and retain provenance for every sample. Start with a bench setup before attempting field synchronization. |

## Dependencies and tradeoffs

- **Bundles, recipes and replay** need stable event types, correlated command results,
  cancellation and bounded buffers. They should reuse the existing transport and
  protocol rather than introduce a separate command path.
- **Hardware doctor** must distinguish electronic identification from an actual
  end-to-end test. A microphone or speaker often has no readable chip ID; pin
  definitions and successful driver initialization cannot prove the transducer works.
- **Decoder extensions** need a versioned input/output contract, resource limits and
  license/attribution rules. Benchmark a small isolated decoder interface before
  selecting a plugin runtime.
- **Multiple nodes and public verification** are later work. First make a single-node
  session reliable, exportable and replayable.

## Suggested first milestone

After the current audit, build a small vertical slice: **hardware inventory → passive
capture recipe → portable bundle → offline replay → report**. It should work from a
fresh installation and contain enough evidence for someone else to reproduce the
result. That is a stronger platform foundation than adding more unrelated buttons.

## Protocols and optional hardware backlog

- Extend compatible Sub-GHz and IR decoders/encoders with known-answer captures,
  malformed-input tests and separate receive/transmit verification. Evaluate
  Flipper code and formats per component, checking licenses and attribution.
- Expand NFC parsing and compatible read/write/emulation operations within PN532
  limits. Do not advertise universal cloning or Flipper NFC emulation parity.
- Consider LF RFID, iButton, NRF24/ESB, LoRa, Ethernet and GNSS modules. Specify
  electrical requirements, driver ownership, pin conflicts and operation-level
  capability gates. Missing hardware keeps its actions disabled.
- Keep rolling-code recognition separate from generation of accepted commands;
  protocol identification does not provide cryptographic keys or fresh counters.

## Red-team, blue-team and analysis backlog

These combine consolidation of existing tools with possible extensions. Inventory
each existing implementation before deciding what actually needs to be added.

| Area | Candidate scope |
|---|---|
| Wi-Fi pentest workspace | Repeatable discovery, lab deauth/disassociation, handshake/PMKID capture, phone-side dictionary verification, Evil Twin/captive-portal exercises and before/after remediation checks. Record driver, WPA3 and PMF limitations. |
| Network auditor | Host/port discovery, service identification, TLS/configuration checks and explicitly scoped default-credential tests with attempt limits. |
| Lab MITM | Consolidate ARP, DNS/HTTP observation and HTTPS proxy workflows on configured test clients. A trusted lab CA does not bypass certificate pinning. |
| BLE GATT auditor | Compare services and read/write permissions before and after pairing/authentication; bounded robustness tests. |
| HID lab | Versioned Ducky scripts, action preview, controlled execution and outcome evidence. Separate BLE pairing requirements from board-specific USB support. |
| Replay lab | Compare IR/Sub-GHz captures and test replay acceptance on owned receivers, distinguishing static and dynamic protocols. |
| NFC lab | Default-key audits on owned compatible tags, dump comparisons and supported write/emulation tests. |
| NRF24/ESB lab | Compatible-device analysis and controlled unauthenticated-packet acceptance tests with an optional module. |
| Hardware interfaces | UART consoles and I2C/SPI/debug-interface analysis with documented voltage levels and suitable adapters. |
| Protocol robustness | Bounded message mutation against explicit lab targets, recording responses, crashes and recovery. |
| Defensive baseline | New-device/service observations, explainable WIDS alerts, local IoT inventory and repeatable remediation verification. |

Every active experiment needs an explicit target, bounded duration/rate, reliable
Stop and cleanup, and evidence distinguishing a sent command from confirmed impact.

### Signal visualization and generators

- Consolidate audio spectrogram and Sub-GHz RSSI waterfall with recordings, zoom,
  markers and capture comparison. Label RSSI sweeps, PCM and pulse data accurately;
  CC1101 is not a wideband IQ SDR.
- Wi-Fi/BLE channel activity, RSSI history and advertising-rate comparisons.
- Consolidate BLE advertising spam and synthetic Wi-Fi beacon tests with duration,
  rate, counters and immediate cancellation.
- Repeatable IR/Sub-GHz sequences, audio tones/sweeps/DTMF and optional NRF24 test
  traffic. Do not assume one radio can transmit and observe simultaneously.
- Generator-plus-observer experiments: correlate a controlled stimulus with audio,
  Wi-Fi, BLE or RF observations, preserving uncertainty and avoiding causal claims
  based only on timing.

## Creative and community backlog

| Idea | Extension to evaluate |
|---|---|
| IR instrument | Map learned remote buttons to phone-side notes, drums or samples. |
| BLE MIDI controller | Encoder/push-button control of notes, chords and parameters; verify MIDI stack compatibility and radio coexistence. Distinct from existing melody playback. |
| Spectrogram drawing | Convert a drawing into audio, then recover its visual pattern through the existing spectrogram. Measure speaker/microphone bandwidth limits. |
| DTMF playground | Build retro interfaces, puzzles and shareable sequences on existing tone generation and decoding. |
| Radio orchestra | Sonify observed radio metrics with Three.js visuals; explicitly distinguish sonification from demodulated transmitter audio. |
| Signal Detective | Offline puzzles using synthetic or redacted captures to teach timing, modulation and protocol identification. |
| Personal remote dashboard | Learned controls, custom layouts and shareable scenes using existing compatible IR/radio operations. |
| SSTV postcards | A simplified photo-to-audio-to-image experience built on existing SSTV RX/TX, rather than another codec implementation. |
| Pocket groovebox | Phone-side sequencer/samples with LilyGO encoder control and synchronized LED patterns. |
| Escape-room kit | Programmable puzzles combining NFC tags, IR sequences and DTMF; declare required modules and accessories. |

Potential launch demonstrations, after verification: an IR remote as an instrument,
an image sent as sound, and a captured signal explained through a tested decoder.
These are launch hypotheses, not promises of virality.

Community foundations: documented shareable packs for puzzles, instruments,
visuals and decoders; offline demos without hardware; small contributor challenges
with expected results; redacted visualization/video exports; and a public board /
module / operation compatibility matrix. Keep the Three.js visual identity and
the infinite-battery meme, with technically accurate USB power notes.
