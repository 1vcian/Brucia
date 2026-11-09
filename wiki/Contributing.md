# Contributing

Contributions to Brucia are welcome - especially additional transports (Wi-Fi / BLE),
translations, and new modules. This page condenses `CONTRIBUTING.md`; read that and
[Architecture](Architecture.md) in the repository for full detail.

Brucia is **for authorized security research and education only**. Contributions must
keep that framing; do not add anything whose only purpose is to attack systems the
user does not own or is not permitted to test.

## The one hard rule: the protocol is the source of truth

The wire protocol (see [Protocol](Protocol.md), authoritative in `proto/PROTOCOL.md`) is mirrored
by the firmware (C++, `src/companion/`) and the app (Kotlin, `app.brucia`). **Never
add a message to one side only.** Bump `PROTOCOL_VERSION` on any schema change and
keep both sides aware of it.

## Adding a companion module (end to end)

1. **Protocol** - define the message/stream in `proto/PROTOCOL.md` first.
2. **Firmware** - in the appropriate `src/companion/` module, handle the `CMD`/`SUB` and emit
   `EVENT`/`DATA`. Keep peripheral work on the ESP; do not compute there what the
   phone can do.
3. **App** - add the Kotlin side (a ViewModel plus a Compose screen under
   `app.brucia`), parsing the same messages.
4. **Tests** - unit-test the phone-side logic; add a Paparazzi snapshot if it has UI;
   extend `tools/` if it helps.
5. **Keep standalone intact** - the device must preserve the standalone paths affected by the change.

## Build and test

See [Building](Building.md) for the full commands. In short:

```sh
python3 tools/build_firmware.py -e lilygo-t-embed-cc1101   # firmware build
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

[AGPL-3.0](https://github.com/1vcian/Brucia/blob/main/LICENSE). Brucia is a
derivative of [Bruce](https://github.com/BruceDevices/firmware) and complementary to
its official app.

## Where help is wanted

Start with repeatable functional checks, recovery, accessibility and clear documentation.
Report phone, firmware, board and peripheral versions with evidence. Protocol and
transport extensions need separate design and tests; the BLE console does not implement
the framed companion transport. Read the ordered maintenance backlog before choosing work.
