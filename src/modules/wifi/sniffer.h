#pragma once
#if !defined(LITE_VERSION)
#include <Arduino.h>
#include <FS.h>
#include <SD.h>
#include <WiFi.h>
#include <esp_wifi.h> // wifi_promiscuous_pkt_t for the EAPOL helpers below
#include <set>

struct HandshakeTracker {
    bool msg1 = false;
    bool msg2 = false;
    bool msg3 = false;
    bool msg4 = false;
};

extern HandshakeTracker hsTracker;

bool handshakeUsable(const HandshakeTracker &hs);

// List of channels to hop through
// priority channels are used more often
#ifdef CONFIG_IDF_TARGET_ESP32C5
const uint8_t all_wifi_channels[] = {1,   2,   3,   4,   5,   6,   7,   8,   9,   10,  11,  12,
                                     36,  40,  44,  48,  52,  56,  60,  64,  100, 104, 108, 112,
                                     116, 120, 124, 128, 132, 136, 140, 149, 153, 157, 161};
const uint8_t pri_wifi_channels[] = {1, 6, 11, 36, 40, 44, 48, 149, 153, 157, 161};
#else
const uint8_t all_wifi_channels[] = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12};
const uint8_t pri_wifi_channels[] = {1, 6, 11};
#endif
struct BeaconList {
    char MAC[6];
    uint8_t channel;
    // Define comparison operator to use <set>
    bool operator<(const BeaconList &other) const {
        // Compare MACs (using memcmp)
        int cmp = memcmp(MAC, other.MAC, sizeof(MAC));
        if (cmp != 0) {
            return cmp < 0; // if MACs are diferent, compares lexicografically
        }
        return channel < other.channel; // If MACs are equal, compare by channel
    }
};

enum class SnifferMode : uint8_t {
    Full,
    HandshakesOnly,
    DeauthOnly,
};

extern int num_HS;
extern int num_EAPOL;
extern bool isLittleFS;
extern uint8_t ch;

void setHandshakeSniffer();
void sniffer_set_mode(SnifferMode mode);
SnifferMode sniffer_get_mode();
bool sniffer_full_mode_available();
bool sniffer_prepare_storage(FS *fs, bool sdDetected);
void sniffer_wait_for_flush(uint32_t timeoutMs = 2000);
void sniffer_reset_handshake_cache();
void markHandshakeReady(uint64_t key);
bool sniffer_is_handshake_ready(uint64_t bssidKey);

// Brucia: the deterministic pcap path the handshake sniffer writes for a given AP MAC + SSID
// (HS_<MAC>[_<sanitized SSID>].pcap under /BrucePCAP/handshakes/). Same naming the writer uses,
// exposed so the companion can report a best-effort fallback name when nothing was written this run.
// The SSID is sanitized identically to the writer; pass "" (or nullptr) when the SSID is unknown.
String snifferHandshakePath(const uint8_t mac[6], const char *ssid);

// Brucia: capture-descriptor write tracking. The companion brackets a bounded capture with
// snifferBeginCaptureTracking()/snifferEndCaptureTracking(); between them the writer records the
// handshake file it actually created or appended to. snifferLastCapture() returns false when nothing
// was written this run (so the companion reports complete:false, not a stale pre-existing file);
// when true it yields the real path, the pcap records appended this run, whether the file was newly
// created, and the file's size before this run's appends. Not used by standalone capture.
void snifferBeginCaptureTracking(const uint8_t targetMac[6]);
void snifferEndCaptureTracking();
bool snifferLastCapture(String &path, uint32_t &frames, bool &created, uint32_t &priorSize);

extern std::set<BeaconList> registeredBeacons;
extern std::set<String> SavedHS;

void newPacketSD(uint32_t ts_sec, uint32_t ts_usec, uint32_t len, uint8_t *buf, File pcap_file);

void openFile(FS &Fs);

bool writeHeader(File file);

void sniffer_setup();

void sniffer(void *buf, wifi_promiscuous_pkt_type_t type);

// EAPOL helpers (shared with the Brucia bounded PMKID capture in wifi_atks.cpp).
bool isItEAPOL(const wifi_promiscuous_pkt_t *packet);
int classifyEapolMessage(const wifi_promiscuous_pkt_t *pkt);
bool matchesTargetAP(const wifi_promiscuous_pkt_t *pkt, const uint8_t targetBssid[6]);

#endif
