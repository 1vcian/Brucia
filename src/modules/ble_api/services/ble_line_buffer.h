#pragma once
// BLE API serial bridge: received bytes become CLI lines; who may write and receive (portable, no
// Arduino/NimBLE types, host-tested by tools/test_ble_api_security.py).
#include <cstddef>
#include <cstdint>
#include <string>

namespace ble_api {

/** Bytes from the central, handed to the CLI one complete line at a time. */
class LineBuffer {
public:
    /** A line longer than this without '\n' is dropped whole (the CLI never sees a cut command). */
    static constexpr size_t MAX_LINE = 512;

    void feed(const uint8_t *data, size_t len) {
        for (size_t i = 0; i < len; i++) {
            const char c = static_cast<char>(data[i]);
            if (discarding_) {
                if (c == '\n') discarding_ = false; // resynchronise on the next line
                continue;
            }
            buf_.push_back(c);
            const size_t open = buf_.size() - (lastNewline() + 1);
            if (open > MAX_LINE) { // the unfinished line overflowed: drop it and its rest
                buf_.resize(lastNewline() + 1);
                discarding_ = true;
            }
        }
    }

    /** Bytes up to and including the first '\n', or 0 while no complete line is buffered. */
    size_t available() const {
        const size_t nl = buf_.find('\n');
        return nl == std::string::npos ? 0 : nl + 1;
    }

    /** The first complete line without its terminator (and a trailing '\r'); "" if none. */
    std::string takeLine() {
        const size_t nl = buf_.find('\n');
        if (nl == std::string::npos) return "";
        std::string line = buf_.substr(0, nl);
        buf_.erase(0, nl + 1);
        if (!line.empty() && line.back() == '\r') line.pop_back();
        return line;
    }

    /** One byte of the first complete line, or -1. */
    int read() {
        if (available() == 0) return -1;
        const int c = static_cast<unsigned char>(buf_.front());
        buf_.erase(0, 1);
        return c;
    }

    void clear() {
        buf_.clear();
        discarding_ = false;
    }

    size_t size() const { return buf_.size(); }

private:
    size_t lastNewline() const {
        const size_t nl = buf_.rfind('\n');
        return nl == std::string::npos ? static_cast<size_t>(-1) : nl;
    }
    std::string buf_;
    bool discarding_ = false;
};

/** The one connection allowed to run commands and receive their output. */
class PeerGate {
public:
    static constexpr uint16_t NONE = 0xFFFF; // BLE_HS_CONN_HANDLE_NONE

    /** Pairing finished: only an encrypted, MITM-authenticated (passkey) link is accepted. */
    bool onAuthenticated(uint16_t handle, bool encrypted, bool authenticated) {
        if (!encrypted || !authenticated) return false;
        if (peer_ != NONE && peer_ != handle) return false; // one console at a time
        peer_ = handle;
        return true;
    }
    void onDisconnected(uint16_t handle) {
        if (handle == peer_) peer_ = NONE;
    }
    /** A write is a command only from the authorized peer, still encrypted and authenticated. */
    bool mayWrite(uint16_t handle, bool encrypted, bool authenticated) const {
        return peer_ != NONE && handle == peer_ && encrypted && authenticated;
    }
    uint16_t peer() const { return peer_; }
    void reset() { peer_ = NONE; }

private:
    uint16_t peer_ = NONE;
};

} // namespace ble_api
