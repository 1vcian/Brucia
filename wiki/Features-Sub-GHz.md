# Sub-GHz

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
Replays a captured signal (`subghz_replay`) back out the CC1101.

## Raw transmit
Transmits a raw edge list (`subghz_tx_raw`) built or imported on the phone.

## De Bruijn brute force
For fixed OOK codes, the phone computes the shortest De Bruijn sequence that covers every
possible code and the device transmits it - far more efficient than brute-forcing each
code individually. Authorized testing only.

## Jammer
Bounded sub-GHz noise (`subghz_jammer`). Authorized testing only.

## Brute force
A bounded brute-force transmit (`subghz_bruteforce`) for fixed-code receivers. Authorized
testing only.

## Config
Sets RX/TX frequency, modulation, bandwidth and data rate (`subghz_config`) before any
capture or transmit.

---
Back to [[Features]].
