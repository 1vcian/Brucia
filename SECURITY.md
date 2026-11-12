# Security Policy

## Authorized use only

Brucia is a security research and education tool. It is built on top of
[Bruce](https://github.com/BruceDevices/firmware), an open-source penetration
testing firmware for ESP32 hardware, and extends it with a companion Android app.

**You may only use Brucia against systems, networks, and devices that you own, or
that you have explicit, written authorization to test.** This applies to every
feature in the project, firmware and app alike. Scanning, analyzing, or capturing
data from a network or device you do not own or have not been authorized to test
may be illegal in your jurisdiction, regardless of intent.

## Active-transmission features carry extra responsibility

Several Brucia features actively transmit or interfere with radio traffic rather
than passively observing it, including:

- Wi-Fi **deauthentication**
- **BLE spam**
- WPA/WPA2 **handshake capture** (which typically relies on deauthentication to
  force a reconnection)
- **Sub-GHz transmission / replay**, including the De Bruijn brute-force sequence
- **Infrared transmission**

These features can disrupt Wi-Fi networks, Bluetooth devices, garage doors, gates,
remotes, and other radio-controlled equipment that is not yours, including
equipment belonging to third parties who have nothing to do with your test. Use
them only:

- on your own equipment, in a controlled environment, or
- on systems you have clear, written authorization to test, within the scope and
  time window that authorization defines.

Do not use these features on networks or devices you do not control or have not
been explicitly authorized to target, even "just to see if it works." Wi-Fi
deauthentication and similar interference can also violate telecommunications
regulations independent of any computer-misuse law.

## Legal responsibility

Brucia is provided as a tool. The maintainers and contributors of this project
are not responsible for how it is used. **You are solely responsible for
ensuring your use of Brucia complies with all applicable local, national, and
international laws**, and for obtaining any authorization required before
testing a system that is not your own. If you are unsure whether an activity is
authorized or legal, do not perform it.

Brucia is licensed under AGPL-3.0, like the Bruce project it is derived from; see
[LICENSE](LICENSE) and [NOTICE](NOTICE). The software is provided "as is",
without warranty of any kind, as stated in the license.

## Reporting a vulnerability

When reporting a release issue, include app/firmware versions, board and redacted
reproduction steps. Public guides and illustrated videos do not certify every
physical operation. Consult [TESTS.md](TESTS.md) for the exact evidence and limits.

The Bruce community App Store is a third-party service. Its later catalog/app
downloads use plain HTTP; the bootstrap installer's checks do not authenticate
those downloads. Scripts run on the device. Review them before execution.
The App Store bootstrap installer checks the TLS chain and hostname and applies a separate
certificate-date policy: expiry is checked against the firmware source-time floor or a newer
synced clock; notBefore is checked only with a synced clock. A cold, unsynced device therefore
does not establish full current-date validity. This is specific to the installer, not a claim
about every firmware TLS client. Host tests do not replace mbedTLS/device acceptance.

The phone-hosted MITM proxy is for a single-user lab. Its bounded worker pool can be occupied
by slow clients or idle tunnels; it is not a production or high-concurrency proxy. Stop closes
tracked connections. Captures and exports can contain credentials and personal data: redact
reports before sharing them. WPA keys remain unmasked in exports by the owner's explicit choice.

If you find a security issue in Brucia (firmware, app, or build tooling):

- For a vulnerability that could compromise users, use GitHub's
  [private vulnerability reporting](https://github.com/1vcian/Brucia/security/advisories/new)
  if available. If it is unavailable, request a private reporting channel from the maintainer
  without posting exploit details, credentials or captures publicly.
- For ordinary bugs without sensitive information, open a
  [GitHub issue](https://github.com/1vcian/Brucia/issues) with versions and redacted steps.
- Include the affected commit/release, expected and observed behavior, and a minimal
  reproduction using synthetic or sanitized data. Never attach signing keys, service tokens
  or unrelated users' traffic. Allow time to investigate before public disclosure.

Please do not use Brucia's own features against the project's infrastructure or
against other users without authorization, even as part of a security report.
