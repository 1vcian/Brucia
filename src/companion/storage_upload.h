#pragma once
#include <cstddef>
#include <cstdint>
#include <string>

namespace brucia { namespace companion { namespace upload {
constexpr uint32_t MAX_SIZE = 16 * 1024 * 1024;
constexpr size_t MAX_CHUNK = 3072;
constexpr const char *PART = "/.brucia-upload.part";
constexpr const char *BACKUP = "/.brucia-upload.bak";
constexpr const char *JOURNAL = "/.brucia-upload.pending";

enum class Kind { Missing, File, Other, Error };
struct Store {
    virtual ~Store() = default;
    virtual Kind kind(const std::string &path) = 0;
    virtual bool load(const std::string &path, size_t limit, std::string &out) = 0;
    virtual bool write(const std::string &path, const uint8_t *bytes, size_t size, bool append) = 0;
    virtual bool digest(const std::string &path, uint32_t &size, uint32_t &crc) = 0;
    virtual bool move(const std::string &from, const std::string &to) = 0;
    virtual bool erase(const std::string &path) = 0;
    virtual bool replacesAtomically() const = 0;
};

bool validPath(const std::string &path);
bool number(const std::string &text, uint32_t &out);
bool validToken(const std::string &token);
uint32_t crcStep(uint32_t crc, const uint8_t *bytes, size_t size);

/** One staged upload. Journal precedes all mutation; backup is never discarded unless
 * the committed target is verified. FAT uses recoverable backup/promote/rollback;
 * LittleFS uses its single rename replacement. Neither certifies faulty media/power loss.
 */
class Transaction {
public:
    explicit Transaction(Store &store) : fs(store) {}
    const char *begin(const std::string &token, const std::string &path, uint32_t size, uint32_t crc, uint32_t now);
    const char *chunk(const std::string &token, uint32_t offset, const uint8_t *bytes, size_t size, uint32_t now);
    const char *commit(const std::string &token);
    const char *abort(const std::string &token);
    const char *recover();
    void expire(uint32_t now);
    // Adapter refreshes idle time after slow filesystem work, immediately before its ACK.
    void acknowledge(uint32_t now) { if (running) touched = now; }
    void close();
    bool active() const { return running; }
    uint32_t received() const { return count; }
    uint32_t expectedSize() const { return length; }
    uint32_t expectedCrc() const { return checksum; }
    bool cleanupPending() const { return dirty; }
private:
    Store &fs;
    std::string owner, target;
    uint32_t length = 0, checksum = 0, count = 0, touched = 0;
    bool running = false, dirty = false;
    bool removeIfPresent(const char *path);
    bool cleanup();
};
}}}
