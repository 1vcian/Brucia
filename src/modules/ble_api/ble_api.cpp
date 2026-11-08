#if !defined(LITE_VERSION)
#include "ble_api.hpp"
#include <NimBLEDevice.h>
#include <core/USBSerial/USBSerial.h>
#include <esp_random.h>
#include <globals.h>

BLE_API::BLE_API() = default;

// Brucia: the BLE API runs CLI commands, so a phone must pair with the passkey shown on the device
// (bonding + MITM + Secure Connections). A link that ends pairing without encryption and passkey
// authentication is dropped; only the authorized peer's writes run and only it gets the output.
class BLEAPICallback : public NimBLEServerCallbacks {
    BLE_API *api;

    void onConnect(NimBLEServer *pServer, NimBLEConnInfo &connInfo) override {
        pServer->updateConnParams(connInfo.getConnHandle(), 6, 24, 0, 400); // Improve latency
    };

    void onDisconnect(NimBLEServer *pServer, NimBLEConnInfo &connInfo, int reason) override {
        api->serial().peerGone(connInfo.getConnHandle());
    }

    uint32_t onPassKeyDisplay() override { return api->passkey(); }

    void onAuthenticationComplete(NimBLEConnInfo &connInfo) override {
        if (!api->serial().authorize(connInfo.getConnHandle(), connInfo.isEncrypted(), connInfo.isAuthenticated())) {
            NimBLEDevice::getServer()->disconnect(connInfo.getConnHandle());
        }
    }

    void onMTUChange(uint16_t MTU, NimBLEConnInfo &connInfo) override { api->update_mtu(MTU); };

public:
    explicit BLEAPICallback(BLE_API *api) : api(api) {}
};

void BLE_API::setup() {
    // Name kept as "Bruce" (upstream Bruce does the same): tools that look the device up by name.
    NimBLEDevice::init("Bruce");
    NimBLEDevice::setPower(ESP_PWR_LVL_P9); // 9 dBm, tweak if you want

    passkey_ = esp_random() % 1000000;
    NimBLEDevice::setSecurityAuth(true, true, true); // bond, MITM (passkey), LE Secure Connections
    NimBLEDevice::setSecurityIOCap(BLE_HS_IO_DISPLAY_ONLY);
    NimBLEDevice::setSecurityPasskey(passkey_);

    pServer = NimBLEDevice::createServer();
    pServer->advertiseOnDisconnect(true);
    pServer->setCallbacks(new BLEAPICallback(this));

    battery_service.setup(pServer);
    serial_service.setup(pServer);
    serialDevice = &serial_service;

    BLEAdvertising *pAdvertising = pServer->getAdvertising();
    // The 128-bit NUS UUID + battery UUID + name exceed the 31-byte advertising packet, so the rest
    // goes in the scan response (upstream Bruce 20bb8b18); the NUS UUID stays discoverable.
    pAdvertising->enableScanResponse(true);
    pAdvertising->setName("Bruce");
    pAdvertising->start();
}

void BLE_API::update_mtu(uint16_t mtu) {
    battery_service.setMTU(mtu);
    serial_service.setMTU(mtu);
}

void BLE_API::end() {
    serialDevice = &USBserial; // before the service goes away, so no output races into it
    battery_service.end();
    serial_service.end();
    BLEDevice::deinit();
    passkey_ = 0;
}

bool BLE_API::forgetPairings() { return NimBLEDevice::deleteAllBonds(); }
#endif
