# Radio: NRF24, LoRa and FM

The 2.4 GHz NRF24 add-on and the longer-range radio tools. NRF24 is gated on `caps.nrf24`
(an optional QWIIC add-on), LoRa on `caps.lora`, and FM on `caps.fm` - **FM is capability-
gated off on the T-Embed CC1101**, which has no Si4713 chip; the FM code is fully
implemented but its page is hidden on this board. See [[FAQ]].

> For authorized security research and education only. The NRF24 jammer and MouseJack
> keystroke injection are dual-use - only run them against devices you own or are
> explicitly permitted to test.

## NRF24 scan
Scans 2.4 GHz channel activity (`nrf24_scan`, `nrf24.scan` stream).

## NRF24 sniff
Sniffs nRF frames (`nrf24_sniff`, `nrf24.dev` stream).

## NRF24 jammer
Bounded 2.4 GHz noise (`nrf24_jammer`). Authorized testing only.

## MouseJack
Scans for vulnerable wireless mice/keyboards and can optionally inject keystrokes
(`nrf24_mousejack`, `nrf24.mj` stream). Authorized testing only. Gated off automatically
when no NRF24 module is attached.

## LoRa
Configure the LoRa radio (frequency, bandwidth, spreading factor, coding rate), send a
packet, and listen for incoming packets for a chosen duration. Needs a LoRa module
(`caps.lora`); not fitted on the T-Embed CC1101.

## FM transmit
Broadcasts audio over the FM transmitter (`fm_tx`), with audio sourced from a file, a
generated tone, the phone mic or the device mic (`fm_audio_begin` /
`fm_audio_data` / `fm_audio_stop`). Fully implemented in firmware but hidden on boards
without a Si4713, such as the T-Embed CC1101.

---
Back to [[Features]].
