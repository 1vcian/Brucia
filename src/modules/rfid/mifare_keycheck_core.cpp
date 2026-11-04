#include "mifare_keycheck_core.h"
#include <string.h>

uint8_t mifareSectorCountFromSak(uint8_t sak) {
    switch (sak) {
        case MIFARE_SAK_MINI: return 5;
        case MIFARE_SAK_4K: return 40;
        case MIFARE_SAK_1K:
        default: return 16;
    }
}

uint8_t mifareSectorTrailerBlock(uint8_t sector) {
    // Sectors 0..31 have 4 blocks (trailer at +3); sectors 32..39 have 16 blocks.
    return (sector < 32) ? (uint8_t)(sector * 4 + 3) : (uint8_t)(128 + (sector - 32) * 16 + 15);
}

MifareKeyCheckResult mifareKeyCheckCore(
    MifareAuthBus &bus, const uint8_t (*keyList)[6], size_t count
) {
    MifareKeyCheckResult r;
    r.keysInBatch = (uint32_t)count;

    if (!bus.selectTag(r.uid, r.uidLen, r.sak)) {
        r.tagPresent = false;
        r.stop = MIFARE_KC_NO_TAG;
        return r;
    }
    if (r.uidLen > 10) r.uidLen = 10;
    r.tagPresent = true;
    r.sectors = mifareSectorCountFromSak(r.sak);
    r.stop = MIFARE_KC_COMPLETE;

    for (uint8_t sector = 0; sector < r.sectors; sector++) {
        uint8_t trailer = mifareSectorTrailerBlock(sector);
        for (uint8_t kt = 0; kt < 2; kt++) { // 0 = key A, 1 = key B
            for (size_t k = 0; k < count; k++) {
                // Cooperative cancellation: a phone-sent stop frame ends the batch within one
                // authenticate() of the request. Remaining sectors/keys are NOT tested: partial.
                if (bus.shouldStop()) {
                    r.stop = MIFARE_KC_CANCELLED;
                    r.sectorsCompleted = sector; // sectors fully tested before this one
                    return r;
                }
                uint8_t keyBuf[6];
                memcpy(keyBuf, keyList[k], 6);
                r.authAttempts++;
                bool ok = bus.authenticate(trailer, kt, keyBuf);
                bus.onAttempt(r.authAttempts);
                if (ok) {
                    MifareKeyCheckHit hit;
                    hit.sector = sector;
                    hit.keyB = (kt == 1);
                    memcpy(hit.key, keyBuf, 6);
                    r.hits.push_back(hit);
                    break; // found this key type for this sector; stop testing it
                }
                // A failed auth can desync the reader; re-select the card before the next try.
                // If it is gone, the remaining sectors/keys were NOT tested: report partial.
                if (!bus.reselect()) {
                    r.stop = MIFARE_KC_TAG_LOST;
                    r.sectorsCompleted = sector; // sectors fully tested before this one
                    return r;
                }
            }
        }
        r.sectorsCompleted = (uint8_t)(sector + 1);
    }
    return r;
}
