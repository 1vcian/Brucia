# Companion firmware internals

The public entry point remains `companionModeUSB(bool headless)` in `companion.h`.
Everything else lives in `brucia::companion`; internal service APIs are collected
by `internal.h`. The USB wire contract remains in
[proto/PROTOCOL.md](../../proto/PROTOCOL.md).

## Ownership

| Module | Responsibility |
|---|---|
| `companion.cpp` / `session.h` | Enter/leave companion mode, own Serial, suspend/resume the standalone CLI, poll services, restore outputs and clean up |
| `protocol.*` | Incremental bounded framing and JSON response construction; no radio drivers |
| `capabilities.*` | Probe supported chips once before capture, cache results and report CAPS |
| `commands.*` / `command_request.h` | Small dispatcher registry, CLI output capture and session/UI commands |
| `wifi.*`, `bluetooth.*`, `subghz.*` | Radio-specific command matching, capture state, callbacks, service polling and cleanup |
| `nfc.*`, `infrared.*`, `nrf24.*` | Reader/IR/optional-radio commands and their hardware-specific error paths |
| `audio.*` | Microphone, speaker, PCM streaming and FM audio ownership |
| `network.*` | LoRa/Ethernet command matching and teardown |
| `storage.*` / `codec.*` | File transfer and bounded binary/text encoding |
| `system.*` | Clock, scripts, ESP-NOW and other device tools |
| `config.h` | Compile-time driver support; it must not be mistaken for physical presence |

A service implements `dispatch…Command(const CommandRequest&)`: return `false`
when it does not recognize the command, or handle it and return `true` after
sending its response. The central registry tries these handlers, then preserves
the legacy CLI fallback. Recognition must be distinct from execution success: a
failed recognized command must not fall through and execute another CLI path.
`CommandRequest` borrows its string/envelope/session for the duration of dispatch;
services must not retain those references asynchronously.

Keep private state and helpers in their owning translation unit. Export lifecycle
functions rather than peripheral pointers or buffers. Board guards belong in
`config.h`; include actual drivers only in the source file that uses them. Retain
the ST25R3916-before-PN532 include ordering where both libraries are needed.

## Extending a service

1. Implement the bounded operation in its feature module and add command matching
   to that module's dispatcher. Do not add another subsystem to the session loop.
2. Report missing hardware/init failures using the common ACK/ERR helpers. Use
   the incoming request ID and keep every JSON frame within the advertised MTU.
3. Register a new service handler only when a new subsystem is warranted. For a
   stream, add explicit start/service/stop hooks and cleanup on session exit.
4. Gate the app from confirmed capabilities; update the protocol reference and
   add a regression fixture for parsing/routing or failure behavior.
5. Verify the primary board plus LITE/non-LED and another full-featured target.
   Building is not a substitute for hardware tests.

Host checks: `python3 tools/test_firmware_framing.py`,
`python3 tools/test_firmware_codec.py` and `python3 tools/test_protocol_e2e.py`.
These cover the real reader/codec with host shims and simulated protocol traffic;
they do not validate electrical timing, chip probes or the Android USB stack.
