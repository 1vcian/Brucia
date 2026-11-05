#pragma once
#include <precompiler_flags.h>

// Compile-time driver availability. Physical presence is checked separately.
#if defined(FM_SI4713) && !defined(LITE_VERSION)
#define COMPANION_HAS_FM 1
#endif
#if defined(MIC_SPM1423) || defined(MIC_INMP441)
#define COMPANION_HAS_MIC 1
#endif
#if defined(COMPANION_HAS_FM) && defined(HAS_NS4168_SPKR)
#define COMPANION_HAS_FM_AUDIO 1
#endif
// LITE builds (LAUNCHER_* and other app-slot-limited envs) compile the NRF24 companion
// endpoints out for flash headroom; Bruce's own NRF24 menus are unaffected.
#if defined(USE_NRF24_VIA_SPI) && !defined(LITE_VERSION)
#define COMPANION_HAS_NRF24 1
#endif
#if defined(USE_CC1101_VIA_SPI)
#define COMPANION_HAS_CC1101 1
#endif
#if defined(TXLED) || defined(RXLED) || defined(IR_RX_PINS) || defined(IR_TX_PINS)
#define COMPANION_HAS_IR 1
#endif
#if defined(LORA_CS) && !defined(LITE_VERSION)
#define COMPANION_HAS_LORA 1
#endif
#if defined(USE_W5500_VIA_SPI) && !defined(LITE_VERSION)
#define COMPANION_HAS_ETH 1
#endif

// Companion-only endpoint groups that LITE builds compile out for app-slot headroom. Only the
// phone-facing companion layer is dropped; Bruce's own on-device features and CLI stay as they
// are. CAPS reports each absence (capabilities.cpp) and the commands answer ERR 501.
#if !defined(LITE_VERSION)
#define COMPANION_HAS_MIFARE_KEYCHECK 1 // mifare_keycheck (phone-hosted dictionary batches)
#define COMPANION_HAS_IR_CMDS 1         // ir_tvbgone / ir_rx / ir_tx_raw / ir_jammer (Bruce's `ir` CLI stays)
#define COMPANION_HAS_GATT 1            // ble_gatt_connect / ble_gatt_read
#define COMPANION_HAS_DIAG 1            // diag + the link counters behind it
#endif
