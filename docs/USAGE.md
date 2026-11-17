# Brucia usage

Your quick route into the [Brucia wiki](https://1vcian.me/Brucia/wiki/).

| Looking for… | Guide |
|---|---|
| First installation and USB connection | [Getting Started](../wiki/Getting-Started.md) |
| Short walkthroughs | [Tutorials](../wiki/Tutorials.md) |
| A specific tool or peripheral | [Features](../wiki/Features.md) |
| Connection and update troubleshooting | [FAQ](../wiki/FAQ.md) |
| Ways to contribute | [Contributing](../wiki/Contributing.md) |

The [tutorials](../wiki/Tutorials.md) include app screen recordings, transcripts,
expected results and recovery steps. They cover first flash, Android installation,
USB connection, updates, files, observations and audio.

## Find the right setup

Use [TESTS.md](../TESTS.md) for revision-specific results and
[hardware capabilities](HARDWARE_CAPABILITIES.md) for optional peripherals.
Each feature guide describes its hardware requirements. Preview images use sample
data; physical results are recorded separately with the versions and setup used.

## Standalone access

The device can also run from its own menus. New configurations use `BruciaNet`
as the AP name; inherited WebUI credentials/settings may still apply. Review them
before connecting an untrusted network. The selected IP is shown by the device.

## Scripts and the community store

Brucia retains the Bruce JavaScript API and `/BruceJS` paths. Scripts execute on the
device. The Bruce community store is hosted by a third party. The unreleased firmware
upgrades requests to its configured host to verified HTTPS, including from an already
installed store script. Releases through 0.1.15 still use HTTP for those downloads.
Install and run only scripts you have reviewed and trust; TLS authenticates the store,
not the individual author, and installed apps can make their own network requests.

Store responses require Content-Length: up to 256 KiB for catalogs/metadata and 2 MiB
per downloaded asset, with a 60-second body budget. Redirects and incomplete downloads
fail visibly. Files are staged and read back before replacement; a failed update
preserves the previous file or reports backup recovery required. Multi-file apps are
not installed as one transaction, and power-loss durability is not certified. Store
save destinations are confined to `/BruceJS` and `/Themes`; append/seek is refused.
See [SECURITY.md](../SECURITY.md) for the clock/certificate-date limits.
