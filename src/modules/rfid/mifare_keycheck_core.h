#pragma once
// Brucia: Arduino-free MIFARE Classic key-dictionary check loop, shared by the PN532 driver
// and host tests. The caller supplies the hardware primitives (select/authenticate/reselect);
// this owns the loop control AND the completeness accounting the companion reports to the phone.
// Plain C++ so it can be tested on the host (see tools/test_firmware_mifare.py).

#include <stddef.h>
#include <stdint.h>
#include <vector>

// SAK byte values MIFARE Classic cards answer with (the driver stores these in uid.sak).
enum {
    MIFARE_SAK_MINI = 0x09,
    MIFARE_SAK_1K = 0x08,
    MIFARE_SAK_4K = 0x18,
};

// Why a batch stopped. Drives the explicit complete/partial contract sent to the phone.
enum MifareKeyCheckStop {
    MIFARE_KC_COMPLETE = 0, // every sector * (key A, key B) * candidate key was tested
    MIFARE_KC_NO_TAG,       // no MIFARE Classic tag detected at batch start
    MIFARE_KC_TAG_LOST,     // tag lost / reselect failed mid-scan -> result is partial
    MIFARE_KC_CANCELLED,    // the phone asked to stop mid-scan -> result is partial (hits so far)
};

// One successful key match.
struct MifareKeyCheckHit {
    uint8_t sector;
    bool keyB; // false = key A, true = key B
    uint8_t key[6];
};

// Hardware primitives. On device this is a PN532 adapter; in host tests, a fake.
struct MifareAuthBus {
    virtual ~MifareAuthBus() {}
    // Detect the present tag and read its UID + SAK. Return true if a tag is present.
    virtual bool selectTag(uint8_t uid[10], uint8_t &uidLen, uint8_t &sak) = 0;
    // Authenticate one sector trailer block with key type (0 = A, 1 = B) and a 6-byte key.
    virtual bool authenticate(uint8_t trailerBlock, uint8_t keyType, const uint8_t key[6]) = 0;
    // Re-select the tag after a failed auth (the PN532 desyncs). Return true if still present.
    virtual bool reselect() = 0;
    // Called with the running auth count after every attempt (watchdog yield on device).
    virtual void onAttempt(uint32_t attempts) { (void)attempts; }
    // Cooperative cancellation: polled once per candidate-key attempt. Return true to stop the
    // batch early (the companion wires this to a phone-sent stop frame it polls mid-run). The
    // scan then returns MIFARE_KC_CANCELLED with the hits found so far. Default: never stop.
    virtual bool shouldStop() { return false; }
};

// The explicit result the companion turns into a versioned ACK: tag identity + how much of
// the tag was actually tested + why the scan stopped. `hits` holds the recovered keys.
struct MifareKeyCheckResult {
    bool tagPresent = false;
    uint8_t uid[10] = {0};
    uint8_t uidLen = 0;
    uint8_t sak = 0;
    uint8_t sectors = 0;          // total sectors on this tag (0 when no tag)
    uint8_t sectorsCompleted = 0; // sectors fully tested before the scan stopped
    uint32_t keysInBatch = 0;     // candidate keys supplied in this batch
    uint32_t authAttempts = 0;    // individual authenticate() operations performed
    MifareKeyCheckStop stop = MIFARE_KC_NO_TAG;
    std::vector<MifareKeyCheckHit> hits;
};

// Sector count from the SAK byte (1K = 16, 4K = 40, Mini = 5, default 16).
uint8_t mifareSectorCountFromSak(uint8_t sak);
// Sector trailer block number for a sector (the layout the PN532 driver uses).
uint8_t mifareSectorTrailerBlock(uint8_t sector);

// Run the dictionary over the present tag. Caller owns the key list; this only tests.
// The batch is naturally bounded: it performs at most (sectors * 2 * count) authenticate()
// operations (<= 40 * 2 * 64 = 5120 for a 4K tag and a full 64-key batch), then returns. The
// shouldStop() hook only lets the phone end it earlier; there is no unbounded wall-clock loop.
MifareKeyCheckResult mifareKeyCheckCore(
    MifareAuthBus &bus, const uint8_t (*keyList)[6], size_t count
);
