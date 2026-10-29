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
#if defined(USE_NRF24_VIA_SPI)
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
