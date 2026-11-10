# Watch the app

These clips show the released Brucia 0.1.12 APK running in an Android emulator:
actual taps, screen changes, downloads and results. Each has English captions.
No board or NFC reader is connected. The WPA demo uses a synthetic handshake.

## Explore the tools

[Watch the app tour](https://1vcian.me/Brucia/media/app-tour.mp4) ·
[Captions](https://1vcian.me/Brucia/media/app-tour.vtt)

Browse Home, scroll through the tool categories and open NFC. The disconnected
state stays visible. Connect compatible hardware to enable device operations.

## Try the built-in dictionary demo

[Watch the demo](https://1vcian.me/Brucia/media/dictionary-demo.mp4) ·
[Captions](https://1vcian.me/Brucia/media/dictionary-demo.vtt)

Open Crack and tap **Demo**. The app checks 401 candidates against its built-in
synthetic WPA handshake and displays the known passphrase `brucia-demo-pass`.
No nearby network is captured or contacted. The speed shown belongs to this
emulator run; it is not a phone-versus-device benchmark.

The recording preserves the released UI, including its older target hint and
Flipper comparison text. Use the explicit Demo button for this synthetic example;
see [the comparison](../docs/COMPARISON.md) for scoped capability claims.

## Add the NFC dictionaries

[Watch the import](https://1vcian.me/Brucia/media/nfc-dictionaries.mp4) ·
[Captions](https://1vcian.me/Brucia/media/nfc-dictionaries.vtt)

1. Open NFC and scroll to the MIFARE dictionary controls.
2. Tap **Add Flipper Classic dictionary**. In this recording it loads 2,038 keys.
3. Tap **Add Proxmark3 Classic dictionary**. It adds 473 new keys, bringing the
   deduplicated total to 2,511. Upstream lists may change.

These are actual HTTPS downloads in the app. Keys stay in the app session;
importing does not start a tag operation. They apply to MIFARE Classic, not
NTAG, Ultralight or DESFire. See [NFC](Features-NFC.md) for the hardware workflow.

The [wordlists release](https://github.com/1vcian/Brucia/releases/tag/wordlists)
hosts both NFC dictionaries and the Probable WPA lists as downloadable assets,
alongside RockYou and SecLists, with source hashes and licenses. This recording
uses the original 0.1.12 APK, whose four new download URLs still point upstream;
the app catalog now points at the Brucia-hosted copies for the next build.

## Connect hardware next

Follow [Getting Started](Getting-Started.md) for installation and USB connection,
[Features](Features.md) to choose a tool, and [FAQ](FAQ.md)
for connection or update failures. Files, scripts, audio, Remote and physical
radio/tag operations need their own device checks; they are not demonstrated here.

[Recording provenance and hashes](https://1vcian.me/Brucia/media/manifest.json) ·
[Verification checklist](../TESTS.md)
