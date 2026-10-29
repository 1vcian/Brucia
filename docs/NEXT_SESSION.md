# Next session handoff

The user requested a pause, then corrected the requested restart delay to **3 hours
and 12 minutes**. No restart automation has been created: this session exposes no
general scheduling tool. Do not claim a scheduled restart exists.

## Agreed direction

- Latest priority: make existing functionality reliable before implementing any
  new feature. All later protocol, pentest and creative proposals are saved in
  RESEARCH_IDEAS.md; inventory existing implementations before extending them.
- Finish reliability fixes and acceptance checks before producing demo media.
- Expand compatible Flipper-style protocols, especially Sub-GHz and infrared;
  inspect component licenses and preserve attribution before importing code.
- Integrate optional modules as well as stock hardware. Keep actions disabled
  unless the required hardware and operation are supported; distinguish detected,
  implemented and physically verified capabilities.
- Use the LilyGO for radio acquisition and timing, and Android for storage,
  analysis, maps, orchestration and reproducible experiment reports.
- After stabilization, consider hardware inventory, passive experiment recipes, evidence bundles,
  offline replay and reports. Further proposals are in RESEARCH_IDEAS.md.
- Preserve the Three.js visual and the infinite-battery joke. Explain USB power
  constraints without removing the joke.

## Resume checklist

Read AUDIT_2026-10-29.md and inspect the working tree before editing. Extensive
uncommitted changes already exist. The last reported software checks passed:
217 Android unit tests, Android lint with 0 errors and 18 warnings, three firmware
builds (LilyGO T-Embed CC1101, M5Stack Core4MB, Cardputer), 15 Python unit tests and
native framing/codec checks. These results do not certify subsequent edits.

Phone debugging is disconnected. Final firmware and APK still require physical
acceptance tests. Do not present intermediate-device results as final-build proof.
Git writes were denied by the current sandbox; no commit or push was completed.

The comparison cleanup remains unfinished. README.md, docs/COMPARISON.md,
docs/index.html and wiki/Features.md contain broad parity/novelty assertions.
Correct these against actual implementation and official documentation. Stock
LilyGO lacks LF RFID hardware and iButton contacts; PN532 emulation is not full
Flipper NFC parity. Matching CC1101 silicon does not imply protocol, antenna or
range parity. Rolling-code recognition does not imply valid command generation.

Official reference sources already consulted:

- https://docs.flipper.net/zero/nfc/read
- https://docs.flipper.net/zero/ibutton
- https://blog.flipper.net/rfid/
- https://github.com/Xinyuan-LilyGO/T-Embed-CC1101

