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
bool capture_handshake_bounded(const String &tssid, const String &mac, uint8_t channel, uint32_t seconds);

// Brucia: headless, bounded PMKID capture for the companion app. Writes EAPOL/PMKID
// frames for the target BSSID straight to a pcap in /BrucePCAP/handshakes/ (the phone
// extracts the PMKID). `outPath` receives the pcap path. Returns true if >=1 frame saved.
bool capture_pmkid_bounded(const String &mac, uint8_t channel, uint32_t seconds, String &outPath);

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
