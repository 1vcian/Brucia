#if !defined(LITE_VERSION)
#include "BLESerialService.h"
#include "modules/ble/ble_common.h" // bleNotifyRetry
#include <NimBLEDevice.h>
#include <vector>

BLESerialService::BLESerialService() : BruceBLEService() { mutex = xSemaphoreCreateMutex(); }

BLESerialService::~BLESerialService() {
    if (mutex) vSemaphoreDelete(mutex);
}

class BLESerialCallbacks : public NimBLECharacteristicCallbacks {
    BLESerialService *service;

public:
    explicit BLESerialCallbacks(BLESerialService *s) : service(s) {}

    void onWrite(NimBLECharacteristic *pCharacteristic, NimBLEConnInfo &connInfo) override {
        std::string value = pCharacteristic->getValue();
        if (value.empty()) return;
        service->feedRx(
            connInfo.getConnHandle(),
            connInfo.isEncrypted(),
            connInfo.isAuthenticated(),
            reinterpret_cast<const uint8_t *>(value.data()),
            value.size()
        );
    }
};

void BLESerialService::setup(NimBLEServer *pServer) {
    pService = pServer->createService(NUS_SERVICE_UUID);

    // App -> device. The stack itself refuses writes from a link that is not encrypted and
    // authenticated (it starts pairing instead); feedRx checks again per write.
    rx_char = pService->createCharacteristic(
        NUS_RX_CHAR_UUID,
        NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR | NIMBLE_PROPERTY::WRITE_ENC |
            NIMBLE_PROPERTY::WRITE_AUTHEN
    );
    callbacks = new BLESerialCallbacks(this);
    rx_char->setCallbacks(callbacks);

    // Device -> app. Notified to the authorized peer's connection only (notifyChunked).
    tx_char = pService->createCharacteristic(NUS_TX_CHAR_UUID, NIMBLE_PROPERTY::NOTIFY);

    pService->start();
    pServer->getAdvertising()->addServiceUUID(pService->getUUID());
}

void BLESerialService::end() {
    if (rx_char) rx_char->setCallbacks(nullptr);
    delete callbacks;
    callbacks = nullptr;
    if (mutex && xSemaphoreTake(mutex, portMAX_DELAY) == pdTRUE) {
        rx.clear();
        gate.reset();
        xSemaphoreGive(mutex);
    }
}

void BLESerialService::feedRx(uint16_t handle, bool encrypted, bool authenticated, const uint8_t *data, size_t len) {
    if (!mutex || xSemaphoreTake(mutex, portMAX_DELAY) != pdTRUE) return;
    if (gate.mayWrite(handle, encrypted, authenticated)) rx.feed(data, len);
    xSemaphoreGive(mutex);
}

bool BLESerialService::authorize(uint16_t handle, bool encrypted, bool authenticated) {
    if (!mutex || xSemaphoreTake(mutex, portMAX_DELAY) != pdTRUE) return false;
    const bool ok = gate.onAuthenticated(handle, encrypted, authenticated);
    xSemaphoreGive(mutex);
    return ok;
}

void BLESerialService::peerGone(uint16_t handle) {
    if (!mutex || xSemaphoreTake(mutex, portMAX_DELAY) != pdTRUE) return;
    if (handle == gate.peer()) rx.clear(); // a half-written command of the old peer never runs
    gate.onDisconnected(handle);
    xSemaphoreGive(mutex);
}

int BLESerialService::available() {
    if (!mutex || xSemaphoreTake(mutex, portMAX_DELAY) != pdTRUE) return 0;
    const int n = static_cast<int>(rx.available()); // only once a full line is buffered
    xSemaphoreGive(mutex);
    return n;
}

int BLESerialService::read() {
    if (!mutex || xSemaphoreTake(mutex, portMAX_DELAY) != pdTRUE) return -1;
    const int c = rx.read();
    xSemaphoreGive(mutex);
    return c;
}

String BLESerialService::readStringUntil(char terminator) {
    (void)terminator; // the CLI reads lines ('\n'); partial data is never handed out
    if (!mutex || xSemaphoreTake(mutex, portMAX_DELAY) != pdTRUE) return String("");
    const std::string line = rx.takeLine();
    xSemaphoreGive(mutex);
    return String(line.c_str());
}

void BLESerialService::notifyChunked(const uint8_t *data, size_t len) {
    if (tx_char == nullptr || len == 0) return;
    const uint16_t peer = gate.peer();
    if (peer == ble_api::PeerGate::NONE) return; // nobody authorized: the output goes nowhere
    // Usable ATT payload = MTU - 3 (opcode + handle); 20 B on the default MTU.
    const size_t chunk = (mtu > 3) ? static_cast<size_t>(mtu - 3) : 20;
    size_t offset = 0;
    while (offset < len) {
        const size_t n = (len - offset < chunk) ? (len - offset) : chunk;
        bool sent = tx_char->notify(data + offset, n, peer);
        for (uint8_t i = 0; !sent && i < 8; i++) {
            vTaskDelay(1);
            sent = tx_char->notify(data + offset, n, peer);
        }
        offset += n;
        vTaskDelay(pdMS_TO_TICKS(5)); // let the stack drain between chunks
    }
}

size_t BLESerialService::print(const String &s) {
    notifyChunked(reinterpret_cast<const uint8_t *>(s.c_str()), s.length());
    return s.length();
}

size_t BLESerialService::println(const String &s) {
    String toSend = s + "\r\n";
    notifyChunked(reinterpret_cast<const uint8_t *>(toSend.c_str()), toSend.length());
    return toSend.length();
}

size_t BLESerialService::println(size_t n) { return println(String(n)); }

size_t BLESerialService::println(const uint32_t n) { return println(String(n)); }

size_t BLESerialService::print(const int n, int format) { return print(String(n, format)); }

size_t BLESerialService::println(const int n, int format) { return println(String(n, format)); }

size_t BLESerialService::println() { return println(String("")); }

void BLESerialService::vprintf(const char *fmt, va_list args) {
    // The old version passed the va_list itself to sprintf (garbage output).
    va_list copy;
    va_copy(copy, args);
    const int size = vsnprintf(nullptr, 0, fmt, copy);
    va_end(copy);
    if (size <= 0) return;
    std::vector<char> buf(static_cast<size_t>(size) + 1);
    vsnprintf(buf.data(), buf.size(), fmt, args);
    notifyChunked(reinterpret_cast<const uint8_t *>(buf.data()), static_cast<size_t>(size));
}

size_t BLESerialService::write(uint8_t *str, size_t size) {
    notifyChunked(str, size);
    return size;
}

void BLESerialService::setMTU(uint16_t mtu) { this->mtu = mtu; }

#endif
