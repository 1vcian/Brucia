# Sub-GHz

> Decoding and replay depend on the protocol. The CC1101 is not an IQ SDR.

Every Sub-GHz (CC1101) tool. The device is the raw radio front end; the phone does the
decoding, De Bruijn computation and storage. Gated on `caps.cc1101`.

> For authorized security research and education only. Replay, jammer, brute force and
> De Bruijn tools are dual-use - only run them against systems you own or are explicitly
> permitted to test.

## Capture & save
Captures raw OOK timings (`subghz.raw` stream) and lets you export/import **CSV** and
Flipper **.sub** files, so captures move freely between Brucia and Flipper Zero.

## On-phone OOK decode
Decodes Princeton / PT2262 / EV1527 protocols and a **rolling-code decoder**, all on the
phone. A decode sample works with no hardware attached, useful for trying the tool before
you have a device.

## Spectrum waterfall
Live CC1101 RSSI sweep (`subghz.spectrum` stream, optional sweep window) to see activity
across the band before capturing.

## Replay
Replay is available only where the stored format and protocol can be represented by
the transmit path. Unsupported saved items are refused; a successful send still needs
an independent receiver check.

## Raw transmit
Transmits a raw edge list (`subghz_tx_raw`) built or imported on the phone.

## De Bruijn brute force
The phone plans a sequence and sends it as bounded bursts through the existing raw
timing path. Bursts have gaps; combinatorial coverage does not prove compatibility
with a receiver. Stop prevents subsequent work but cannot retract an in-flight burst.
Physical behavior remains a separate acceptance check on authorized lab equipment.

## Jammer
Bounded sub-GHz noise (`subghz_jammer`). Authorized testing only.

## Brute force
A bounded brute-force transmit (`subghz_bruteforce`) for fixed-code receivers. Authorized
testing only.

## Config
Sets RX/TX frequency, modulation, bandwidth and data rate (`subghz_config`) before any
capture or transmit.

---
Back to [Features](Features.md).

---

[All tools](Features.md) · [Tutorials](Tutorials.md) · [Share a result](Contributing.md) ·
[Device test results](https://github.com/1vcian/Brucia/blob/main/TESTS.md)
