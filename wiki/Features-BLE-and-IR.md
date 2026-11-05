# BLE and IR

Every BLE, IR and BadUSB tool. BLE is gated on `caps.ble`, IR on `caps.ir`; BadUSB has no
hardware gate (it drives the keyboard-injection layer over BLE or standalone).

> For authorized security research and education only. BadBLE, the spam hub, media-control
> hijack and the IR jammer/TV-B-Gone are dual-use - only run them against devices you own
> or are explicitly permitted to test.

## BLE scan
Scans nearby BLE devices (`ble.scan` stream); the phone lists, dedups and resolves
vendors.

## GATT explorer
Connects to a BLE device and enumerates every service and characteristic
(`ble_gatt_connect`, `ble.gatt` stream), and reads a characteristic (`ble_gatt_read`).

## BadBLE
Runs a Ducky/BadUSB script over BLE HID (`ble_badble`) against a paired host. Authorized
testing only.

## BLE Remote (HID)
A BLE presenter and keyboard that controls a PC over Bluetooth from the phone - a
legitimate HID remote, not an attack tool.

## iBeacon advertiser
Advertises a configurable iBeacon payload (`ble_ibeacon`). Whether any app or OS reacts to the
frame depends on the receiver's settings and has not been verified here.

## Media-control hijack
Sends BLE media-control commands to nearby devices (`ble_media`). Authorized testing only.

## Spam hub
A single hub for headless, time-bounded advertising spam by platform: **Apple**
ProximityPair (`ble_spam`), **Android** Google Fast Pair (`ble_fastpair_spam`), **Windows**
Swift Pair, **Samsung**, or **all** platforms cycled (`ble_spam_platform
<ios|android|windows|samsung|all>`). Wi-Fi **beacon spam** (`wifi_beacon_spam`) is reachable
from the same hub. Authorized testing only.

Each of these broadcasts advertisements of the named type. Whether a receiver actually shows a
pairing prompt or popup depends on its OS version and settings, and the effect on any receiver has
not been verified here. The advertisers share the single BLE radio with the `ble.scan` stream and
the BLE HID presenter: a device that is scanning or running a HID session already owns the stack,
so the advertiser replies with a busy error (409) instead of tearing that session down and
reporting a false success. Stop the scan / HID session first.

## IR catalog
A bundled universal catalog of ~1648 remote models across ~808 brands and 45 categories,
browsed category -> brand -> model, with search. Also imports any Flipper `.ir` file.

## IR learn
Learns a remote (`ir_rx`) and saves it on the phone for later transmit.

## IR transmit and raw replay
Transmits a catalog or learned button, and replays a raw IR capture (`ir_tx_raw`).

## Custom IR remote builder
A grid of taught buttons you place yourself, for building a custom remote layout out of
individually learned signals.

## TV-B-Gone
A headless power-off blast across the NA or EU region code set (`ir_tvbgone`).

## IR jammer
Floods IR to disrupt nearby receivers (`ir_jammer`). Authorized testing only.

---
Back to [[Features]].
