# Tutorials

Illustrated instructions for the public project. These videos contain diagrams and text,
**not recordings of a phone or device**. They do not establish hardware acceptance.
Each silent video has a caption track and the complete transcript below.

Start with [Getting Started](Getting-Started.md), then [Features](Features.md). Use equipment you own or are authorized
to test. The expected outcomes below still need checking on your app/firmware pair.

## Install and connect

[Watch the illustrated video](https://1vcian.me/Brucia/media/first-connection.mp4) ·
[Captions](https://1vcian.me/Brucia/media/first-connection.vtt)

1. **Choose a matching release.** Download the APK and firmware for your exact board. Read the release notes and back up files you need.

2. **Flash from a computer.** Use the public web flasher in desktop Chrome or Edge with a USB data cable. Confirm board and version before starting.

3. **Connect the Android app.** Install the APK. Connect via USB host / OTG, grant USB access and enter Brucia companion mode on the device.

4. **Check the first reply.** Confirm device and firmware information. List files or request device info. No reply? Check cable, permission, mode and serial-port ownership.

## Update and recover

[Watch the illustrated video](https://1vcian.me/Brucia/media/updates.mp4) ·
[Captions](https://1vcian.me/Brucia/media/updates.vtt)

1. **Prepare the pair.** Read the release notes. Preserve required files. Stop tools and use stable power. APK installation and firmware flashing are separate.

2. **Use Updates.** Select the correct board and release. Review verification status. Keep USB connected while the app writes and verifies firmware.

3. **Reconnect and confirm.** After completion, reconnect to companion mode. Check the reported firmware version and a benign device-info reply.

4. **If flashing fails.** Keep the error and version details. Follow the board bootloader procedure and recover with the matching image. Never treat an incomplete upload as success.

## Files and scripts

[Watch the illustrated video](https://1vcian.me/Brucia/media/files-scripts.mp4) ·
[Captions](https://1vcian.me/Brucia/media/files-scripts.vtt)

1. **List device storage.** Connect over USB and open Files. A failed listing is not an empty folder. Confirm the path and selected storage before changing files.

2. **Try a harmless file.** Use a small test file. Transfer it, open the returned copy and compare its content. Keep required device files backed up.

3. **Review before running.** Scripts run on the device. Read the code first, use a harmless local script and stop it when done. Third-party store downloads have separate trust limits.

4. **Check recovery.** Test disconnect and reconnect with disposable files. Inspect the visible result; do not infer a completed transfer from progress alone.

## Wi-Fi and BLE observations

[Watch the illustrated video](https://1vcian.me/Brucia/media/observations.mp4) ·
[Captions](https://1vcian.me/Brucia/media/observations.vtt)

1. **Use a known lab.** Open Wi-Fi or BLE observations for equipment you own or are permitted to test. Check capabilities and start one observation task.

2. **Confirm actual input.** Look for your known device and changing timestamps. Preview fixtures and a connected badge are not evidence of received radio events.

3. **Save a useful result.** Export a small observation set and inspect the file. Remove personal identifiers before sharing a report or screenshot.

4. **Stop and reconnect.** Stop the task and confirm its result. Check rotation and reconnect separately. BLE advertisements do not guarantee a popup on another operating system.

## Radio, NFC and IR checks

[Watch the illustrated video](https://1vcian.me/Brucia/media/peripherals.mp4) ·
[Captions](https://1vcian.me/Brucia/media/peripherals.vtt)

1. **Check the actual module.** Open the feature guide and inspect runtime capabilities. CC1101, PN532 and IR have different limits. Optional radios need compatible hardware.

2. **Start with inspection.** Use a saved sample, a disposable tag or a known lab signal. Read the selected protocol and operation before proceeding. Write and erase change the target.

3. **Separate reply from effect.** An accepted command does not prove a receiver reacted. Physical output requires a controlled test and an independent observation.

4. **Record the boundary.** Record app and firmware versions, peripheral, expected result and observed result. Keep blocked and not-run tests distinct from passes.

## Audio and Remote

[Watch the illustrated video](https://1vcian.me/Brucia/media/audio-remote.mp4) ·
[Captions](https://1vcian.me/Brucia/media/audio-remote.vtt)

1. **Check audio availability.** Inspect microphone and speaker capabilities. Start with a saved audio sample or a quiet, controlled input; keep output volume low.

2. **One owner at a time.** Stop audio before switching to another audio function. FM needs separate hardware; a DSP unit test is not a physical sound-path test.

3. **Enter Remote deliberately.** Stop other operations before starting Remote. Wait for confirmed mode change. The app must show a failure if entry is not acknowledged.

4. **Return to companion.** Stop Remote, wait for reconnection and request device information again. Test disconnect and rotation separately; record failures with versions.

## Evidence and further checks

Use the release notes and TESTS.md in the repository for exact revisions and results.
Optional Ethernet, FM, LoRa and NRF24 workflows need compatible peripherals.
BLE peer popups, RF receiver responses, NFC writes and physical audio are separate
acceptance checks. A simulator, UI preview or completed command does not prove them.

[Media provenance](https://1vcian.me/Brucia/media/manifest.json)
