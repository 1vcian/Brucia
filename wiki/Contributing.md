# Help build Brucia

A useful bug report, a clearer guide or a tested peripheral can make someone's first
session much better. Code contributions are welcome too.

## Choose a way in

| Contribution | What helps |
|---|---|
| Report a bug | Steps to reproduce, expected result, app/firmware versions and board. |
| Improve a guide | The step that was confusing and the wording that helped you. |
| Share a device check | Phone, board, peripheral, versions and what you observed. |
| Improve the app | Accessibility, clearer controls, recovery and focused fixes. |
| Propose a feature | The workflow you want, current limitations and hardware needed. |

Browse [open issues](https://github.com/1vcian/Brucia/issues) and the
[roadmap](https://github.com/1vcian/Brucia/blob/main/ROADMAP.md). For a larger change,
start an issue before implementation so the scope can be discussed.

## Your first pull request

1. Fork the repository and create a branch for one change.
2. Read [Building](Building.md) and [Architecture](Architecture.md) for the area you're editing.
3. Make the change and run the checks relevant to it. Documentation fixes do not need a device.
4. Explain the problem, the resulting behavior and how you checked it in the pull request.
5. Say which device checks you ran and which remain untested.

Keep public reports free of credentials and personal captures. Sensitive vulnerabilities
belong in the process described in [SECURITY.md](https://github.com/1vcian/Brucia/blob/main/SECURITY.md).

## Working across app and firmware

The [protocol](Protocol.md) is shared by both sides. Keep commands, responses and
capabilities compatible; update the protocol documentation and tests together.
Preserve the standalone device paths affected by a change. Follow the existing
service/module structure rather than putting every operation into the main screen.

Use English for code, UI and documentation, match the surrounding style, and keep
upstream credits. Contributions are licensed under AGPL-3.0.

The repository's [full contribution guide](https://github.com/1vcian/Brucia/blob/main/CONTRIBUTING.md)
has toolchain versions, commands, protocol rules and release details.

---

[Wiki home](Home.md) · [Build from source](Building.md) · [Source map](Architecture.md)
