# Contributing

Contributions to Brucia are welcome - especially additional transports (Wi-Fi / BLE),
translations, and new modules. This page condenses `CONTRIBUTING.md`; read that and
[[Architecture]] in the repository for full detail.

Brucia is **for authorized security research and education only**. Contributions must
keep that framing; do not add anything whose only purpose is to attack systems the
user does not own or is not permitted to test.

## The one hard rule: the protocol is the source of truth

The wire protocol (see [[Protocol]], authoritative in `proto/PROTOCOL.md`) is mirrored
by the firmware (C++, `src/companion/`) and the app (Kotlin, `app.brucia`). **Never
add a message to one side only.** Bump `PROTOCOL_VERSION` on any schema change and
keep both sides aware of it.

## Adding a companion module (end to end)

1. **Protocol** - define the message/stream in `proto/PROTOCOL.md` first.
2. **Firmware** - in `src/companion/companion.cpp`, handle the `CMD`/`SUB` and emit
   `EVENT`/`DATA`. Keep peripheral work on the ESP; do not compute there what the
   phone can do.
3. **App** - add the Kotlin side (a ViewModel plus a Compose screen under
   `app.brucia`), parsing the same messages.
4. **Tests** - unit-test the phone-side logic; add a Paparazzi snapshot if it has UI;
   extend `tools/` if it helps.
5. **Keep standalone intact** - the device must remain a full Bruce with no phone
   connected.

## Build and test

See [[Building]] for the full commands. In short:

```sh
pio run -e lilygo-t-embed-cc1101            # firmware build
cd android && ./gradlew :app:testDebugUnitTest   # app unit + screenshot tests
```

## Style and conventions

- **English everywhere**: code, comments, docs, UI strings, commit messages.
- **No emoji** anywhere (code, UI, docs, commits).
- Match the surrounding code's idiom and comment density.
- Flame theme: ember `#FF6D1A`, amber `#FFB300`, deep `#E23E00`, bg `#0E0E10`.

## Commits and pull requests

- Gate commits on a green build/tests.
- Keep changes focused; describe the what and the why.
- By contributing you agree your work is licensed under **AGPL-3.0**, and that Brucia
  credits upstream Bruce - keep `LICENSE`, `NOTICE` and `THIRD_PARTY.md` intact.

## License

[AGPL-3.0](https://github.com/1vcian/BruciaDev/blob/main/LICENSE). Brucia is a
derivative of [Bruce](https://github.com/BruceDevices/firmware) and complementary to
its official app.

## Where help is wanted

The roadmap marks good first tasks. Especially welcome:

- **Transports beyond USB**: Wi-Fi (TCP/WebSocket) is the biggest functional jump
  (fully wireless offload); BLE for commands and light data only.
- **Internationalization**: source strings are English; translations are welcome
  (Italian first).
- **More modules and polish**: additional BLE spam variants, PMKID capture, more
  Sub-GHz protocols, richer analytics, NFC depth, offline map tile bundling.

Pick an item, open an issue to claim it, and keep the protocol the single source of
truth for both sides.
