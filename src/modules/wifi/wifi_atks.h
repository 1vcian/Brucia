#ifndef __WIFI_ATKS_H__
#define __WIFI_ATKS_H__

#include <WiFi.h>
#include "scan_hosts.h"
#include <vector>

extern wifi_ap_record_t ap_record;

// Default target MAC (broadcast)
extern const uint8_t _default_target[6];

// Default Deauth Frame
const uint8_t deauth_frame_default[] = {0xc0, 0x00, 0x3a, 0x01, 0xff, 0xff, 0xff, 0xff, 0xff,
                                        0xff, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                                        0x00, 0x00, 0x00, 0x00, 0xf0, 0xff, 0x02, 0x00};

extern uint8_t deauth_frame[]; // 26 = [sizeof(deauth_frame_default[])]

extern uint8_t targetBssid[6];

/**
 * @brief Sends frame in frame_buffer using esp_wifi_80211_tx but bypasses blocking mechanism
 *
 * @param frame_buffer
 * @param size size of frame buffer
 */
void send_raw_frame(const uint8_t *frame_buffer, int size);

/**
 * @brief Prepare deauthentication frame with forged source AP from given ap_record
 *
 * This will send deauthentication frame acting as frame from given AP, and destination will be broadcast
 * MAC address - \c ff:ff:ff:ff:ff:ff
 *
 * @param ap_record AP record with valid AP information
 * @param chan Channel of the targetted AP
 * @param target Target MAC address (defaults to broadcast)
 */
void wsl_bypasser_send_raw_frame(
    const wifi_ap_record_t *ap_record, uint8_t chan, const uint8_t target[6] = _default_target
);

void wifi_atk_info(const String &tssid, const String &mac, uint8_t channel);

void wifi_atk_menu();

void target_atk_menu(const String &tssid, const String &mac, uint8_t channel);

void target_atk(const String &tssid, const String &mac, uint8_t channel);

void capture_handshake(const String &tssid, const String &mac, uint8_t channel);

// Brucia: per-run provenance the companion turns into the capture descriptor ACK
// (CAPS features.capture_descriptor). Describes the pcap this run actually wrote, so the phone can
// import exactly that file and verify it after transfer instead of diffing directory listings.
struct CaptureRunInfo {
    String path;                // the pcap this run actually wrote (writer-reported), or a fallback name
    uint32_t frames = 0;        // pcap records this run appended to that file (measured, not assumed)
    bool framesKnown = false;   // true only when `frames` is a real measurement of this run's writes
    bool usable = false;        // a usable capture was detected (4-way complete / >=1 PMKID-EAPOL frame)
    bool written = false;       // a file was actually created or grown this run (not just a stale file)
    bool created = false;       // the file was newly created this run (vs. appended to an existing one)
    uint32_t priorSize = 0;     // the file's size before this run's appends (0 when created)
    bool aborted = false;       // the device button ended the run before a usable capture
    bool budgetElapsed = false; // the full time budget elapsed (vs. an early success/abort exit)
};

// Bounded, headless handshake capture for the companion. `info` (optional) receives the per-run
// provenance above. Standalone capture_handshake() is unchanged.
bool capture_handshake_bounded(
    const String &tssid, const String &mac, uint8_t channel, uint32_t seconds, CaptureRunInfo *info = nullptr
);

// Brucia: headless, bounded PMKID capture for the companion app. Writes EAPOL/PMKID
// frames for the target BSSID straight to a pcap in /BrucePCAP/handshakes/ (the phone
// extracts the PMKID). `outPath` receives the pcap path. `info` (optional) receives the per-run
// provenance for the capture descriptor. Returns true if >=1 frame saved.
bool capture_pmkid_bounded(
    const String &mac, uint8_t channel, uint32_t seconds, String &outPath, CaptureRunInfo *info = nullptr
);

void beaconAttack();

// Brucia: headless, time-bounded Wi-Fi beacon spam for the companion app. The PHONE
// supplies the SSID list (newline-separated, the same format beaconSpamList() parses).
// Broadcasts every SSID as a beacon frame in a bounded loop - hopping channels - until
// durationMs elapses or the on-device ESC is pressed. Returns false if the Wi-Fi radio
// could not be brought up. AUTHORIZED / LAB USE ONLY.
bool beaconSpamListBounded(const char *list, uint32_t durationMs);

void deauthFloodAttack();

// New enhanced deauth functions
void enhancedDeauthMenu();
void showTargetSelection();
std::vector<Host> buildTargetListFromScan();

#endif
