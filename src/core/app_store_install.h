#pragma once
// Checked install of the App Store script (F35). Portable (no Arduino types) so the host tests in
// tools/test_app_store_install.py run the same code with fake download/storage faults.
#include <cstddef>
#include <cstdint>
#include <string>

namespace app_store {

/** Upper bound for the store bootstrap script (about 11 KB today). */
constexpr long MAX_SCRIPT_BYTES = 64L * 1024L;

/** The HTTP response body. */
struct Body {
    virtual ~Body() = default;
    /** Content-Length, or <0 when the server did not send one. */
    virtual long length() = 0;
    /** >0 bytes read, 0 at end of stream, <0 on error or timeout. */
    virtual long read(uint8_t *buf, size_t cap) = 0;
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
    /** Flush and close; false if the data may not be on storage. */
    virtual bool endWrite() = 0;
    /** Size of [path], or <0 if missing. */
    virtual long size(const std::string &path) = 0;
};

/**
 * Download [body] to `<dest>.part`, verify it is complete (exactly Content-Length bytes, all
 * written, size re-read), then replace [dest] keeping the previous file in `<dest>.bak` until the
 * new one is in place. Returns "" on success, else the reason; on failure the staging file is
 * removed and an existing [dest] is kept, so file existence still means a complete script.
 */
std::string install(Body &body, Files &fs, const std::string &dest);

} // namespace app_store
