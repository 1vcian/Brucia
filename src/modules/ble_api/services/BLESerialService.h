#pragma once
#if !defined(LITE_VERSION)
#include "BruceBLEService.hpp"
#include "ble_line_buffer.h"

#include <SerialDevice.h>
#include <freertos/semphr.h>

// Nordic UART Service (NUS), from upstream Bruce 20bb8b18: standard UUIDs, so generic BLE tools
// and the Brucia app's BLE console find it. Brucia: commands run only for a bonded,
// passkey-authenticated (MITM) and encrypted peer, and output goes to that peer only.
#define NUS_SERVICE_UUID "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
#define NUS_RX_CHAR_UUID "6E400002-B5A3-F393-E0A9-E50E24DCCA9E" // app -> device (write)
#define NUS_TX_CHAR_UUID "6E400003-B5A3-F393-E0A9-E50E24DCCA9E" // device -> app (notify)

class BLESerialCallbacks;

class BLESerialService : public BruceBLEService, public SerialDevice {
    NimBLECharacteristic *rx_char = nullptr;
    NimBLECharacteristic *tx_char = nullptr;
    BLESerialCallbacks *callbacks = nullptr;

    // Filled by the write callback (NimBLE host task), read by the serial-commands task.
    ble_api::LineBuffer rx;
    ble_api::PeerGate gate;
    SemaphoreHandle_t mutex = nullptr;

    void notifyChunked(const uint8_t *data, size_t len);

public:
    BLESerialService();
    ~BLESerialService() override;
    void setup(NimBLEServer *pServer) override;
    void end() override;
    size_t println() override;
    size_t println(size_t n) override;
    size_t println(const String &s) override;
    size_t println(int n, int format) override;
    size_t print(const String &s) override;
    size_t print(int n, int format = DEC) override;
    void vprintf(const char *str, va_list args) override;
    size_t println(uint32_t n) override;
    size_t write(uint8_t *str, size_t size) override;
    int read() override;
    void flush() override {}
    String readStringUntil(char terminator) override;
    int available() override;
    void setMTU(uint16_t mtu);

    /** Write callback: queue the bytes when [handle] is the authorized, still secure peer. */
    void feedRx(uint16_t handle, bool encrypted, bool authenticated, const uint8_t *data, size_t len);
    /** Pairing finished; false means the link is not secure enough (the caller disconnects it). */
    bool authorize(uint16_t handle, bool encrypted, bool authenticated);
    void peerGone(uint16_t handle);
};
#endif
