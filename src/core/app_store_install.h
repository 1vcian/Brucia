#pragma once
// Checked install of the App Store script (F35). Portable (no Arduino types) so the host tests in
// tools/test_app_store_install.py run the same code with fake download/storage faults.
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <string>
#include <utility>

namespace app_store {

/** Upper bound for the store bootstrap script (about 11 KB today). */
constexpr long MAX_SCRIPT_BYTES = 64L * 1024L;

/**
 * Total time the body download may take. Each read is also idle-bounded by the adapter, but a
 * server trickling a byte at a time would otherwise keep the UI blocked for a very long time.
 */
constexpr uint32_t DOWNLOAD_BUDGET_MS = 60000;

/** Monotonic milliseconds (millis() on the device, a fake in the host tests). */
struct Clock {
    virtual ~Clock() = default;
    virtual uint32_t nowMs() = 0;
};

/** The HTTP response body. */
struct Body {
    virtual ~Body() = default;
    /** Content-Length, or <0 when the server did not send one. */
    virtual long length() = 0;
    /**
     * >0 bytes read, 0 at end of stream, <0 on error or timeout. Must not wait longer than
     * [maxWaitMs] (the rest of the download budget) for data to arrive.
     */
    virtual long read(uint8_t *buf, size_t cap, uint32_t maxWaitMs) = 0;
};

/** The storage the script is installed to (one open write at a time). */
struct Files {
    virtual ~Files() = default;
    virtual bool exists(const std::string &path) = 0;
    virtual bool remove(const std::string &path) = 0;
    virtual bool rename(const std::string &from, const std::string &to) = 0;
    /** Create or truncate [path] for writing. */
    virtual bool beginWrite(const std::string &path) = 0;
    /** Bytes actually written; fewer than [n] means the storage refused (e.g. full). */
    virtual size_t write(const uint8_t *data, size_t n) = 0;
    /** Flush, sync and close; false if the data may not be on storage. */
    virtual bool endWrite() = 0;
    /** Size of [path], or <0 if missing. */
    virtual long size(const std::string &path) = 0;
    /** Open [path] for reading back what was written. */
    virtual bool beginRead(const std::string &path) = 0;
    /** >0 bytes read, 0 at end of file, <0 on error. */
    virtual long read(uint8_t *buf, size_t cap) = 0;
    virtual void endRead() = 0;
};

/**
 * [Files] on a POSIX path (the ESP-IDF VFS under the Arduino FS mountpoint on the device, a temp
 * directory in the host tests). Unlike Arduino's File, whose flush()/close() discard the fflush,
 * fsync and fclose results, endWrite() reports every one of them.
 */
class PosixFiles : public Files {
public:
    /** [root] is prefixed to every path ("/sd", "/littlefs", ...). */
    explicit PosixFiles(std::string root) : root_(std::move(root)) {}
    ~PosixFiles() override;
    bool exists(const std::string &path) override;
    bool remove(const std::string &path) override;
    bool rename(const std::string &from, const std::string &to) override;
    bool beginWrite(const std::string &path) override;
    size_t write(const uint8_t *data, size_t n) override;
    bool endWrite() override;
    long size(const std::string &path) override;
    bool beginRead(const std::string &path) override;
    long read(uint8_t *buf, size_t cap) override;
    void endRead() override;

protected:
    // Seams for the host tests: storage that accepts every write and then fails to sync or close.
    virtual int syncFd(int fd);
    virtual int closeFile(std::FILE *f);

private:
    std::string full(const std::string &path) const { return root_ + path; }
    std::string root_;
    std::FILE *out_ = nullptr;
    bool outOk_ = false;
    std::FILE *in_ = nullptr;
};

/** Prefix of the message when the previous install could not be put back at [dest]. */
constexpr const char *RECOVERY_REQUIRED = "RECOVERY REQUIRED: ";

/** Reason returned when the download did not finish within its budget. */
constexpr const char *TOO_SLOW = "download took too long";

/**
 * Download [body] to `<dest>.part` within [budgetMs] of [clock] (else [TOO_SLOW]), verify it is complete (exactly Content-Length bytes, all
 * written, flushed and synced, size re-read, and the bytes read back equal to the download), then
 * replace [dest] keeping the previous file in `<dest>.bak` until the new one is in place.
 * Returns "" on success, else the reason; on failure the staging file is removed and an existing
 * [dest] is kept, so file existence still means a complete script. The one exception is a storage
 * that refuses both the new file and putting the old one back: the old script then stays only in
 * `<dest>.bak` and the reason starts with [RECOVERY_REQUIRED] (the next install attempt restores
 * it first).
 */
std::string install(
    Body &body, Files &fs, const std::string &dest, Clock &clock, uint32_t budgetMs = DOWNLOAD_BUDGET_MS
);

} // namespace app_store
