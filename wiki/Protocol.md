# Protocol

The authoritative specification is [proto/PROTOCOL.md](https://github.com/1vcian/Brucia/blob/main/proto/PROTOCOL.md).
This page is an orientation; use that file for exact field names, sizes and commands.

The USB companion uses length-prefixed JSON frames. A handshake identifies the firmware
and reports capabilities; commands have correlated replies, while subscriptions produce
events. A capability announcement is not proof that an external receiver reacted.

## Contracts that matter to clients

- Enforce the frame size and distinguish incomplete input from invalid frames.
- Match replies to pending requests; surface rejection, timeout and disconnect separately.
- Honor operation ownership. A busy radio must not be reinitialized by another tool.
- Stop may finish between units of work; it cannot retract a signal already sent.
- File transfer checks sizes/integrity and uses its transactional upload contract.
- Remote mode and the paired BLE CLI are different paths from framed companion traffic.

Use the repository's mock/e2e tests for protocol changes, then exercise the real
app/firmware pair. [TESTS.md](https://github.com/1vcian/Brucia/blob/main/TESTS.md) records remaining link-load
and recovery checks. Never infer physical acceptance from a mock peer alone.
