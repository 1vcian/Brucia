#include "storage_upload.h"
#include <cctype>
#include <limits>

namespace brucia { namespace companion { namespace upload {
bool number(const std::string &text, uint32_t &out) {
    if (text.empty() || text.size() > 10) return false;
    uint64_t n = 0;
    for (char c : text) { if (c < '0' || c > '9') return false; n = n * 10 + (c - '0'); }
    if (n > std::numeric_limits<uint32_t>::max()) return false;
    out = static_cast<uint32_t>(n);
    return true;
}
bool validToken(const std::string &s) {
    if (s.size() != 32) return false;
    for (char c : s) if (!(c >= '0' && c <= '9') && !(c >= 'a' && c <= 'f')) return false;
    return true;
}
bool validPath(const std::string &p) {
    if (p.size() < 2 || p.size() > 192 || p[0] != '/' || p.back() == '/') return false;
    std::string lower = p;
    for (char &c : lower) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    if (lower.find("/.brucia-upload.") == 0) return false;
    size_t start = 1;
    for (size_t i = 1; i <= p.size(); ++i) {
        if (i == p.size() || p[i] == '/') {
            const std::string part = p.substr(start, i - start);
            if (part.empty() || part == "." || part == ".." || part.front() == ' ' ||
                part.back() == ' ' || part.back() == '.') return false;
            start = i + 1;
        } else if (static_cast<unsigned char>(p[i]) < 32 || p[i] == 127 ||
                   std::string("\\:*?\"<>|").find(p[i]) != std::string::npos) return false;
    }
    return true;
}
uint32_t crcStep(uint32_t crc, const uint8_t *bytes, size_t size) {
    for (size_t i = 0; i < size; ++i) {
        crc ^= bytes[i];
        for (int b = 0; b < 8; ++b) crc = (crc >> 1) ^ (0xEDB88320U & (0U - (crc & 1U)));
    }
    return crc;
}
bool Transaction::removeIfPresent(const char *path) {
    const auto k = fs.kind(path);
    return k == Kind::Missing || (k == Kind::File && fs.erase(path));
}
bool Transaction::cleanup() {
    // Keep the recovery record until both owned artifacts are gone.
    if (!removeIfPresent(PART) || !removeIfPresent(BACKUP) || !removeIfPresent(JOURNAL)) return false;
    return true;
}
const char *Transaction::recover() {
    if (running) return "upload busy";
    const auto journal = fs.kind(JOURNAL);
    if (journal == Kind::Missing) {
        if (fs.kind(PART) != Kind::Missing || fs.kind(BACKUP) != Kind::Missing)
            return "reserved upload artifacts need recovery";
        dirty = false;
        return nullptr;
    }
    std::string data;
    if (journal != Kind::File || !fs.load(JOURNAL, 512, data)) return "cannot read upload journal";
    std::string fields[5];
    size_t start = 0;
    for (auto &field : fields) {
        size_t end = data.find('\n', start);
        if (end == std::string::npos) return "invalid upload journal; artifacts retained";
        field = data.substr(start, end - start); start = end + 1;
    }
    uint32_t size, crc;
    if (start != data.size() || fields[0] != "BRUCIA-UPLOAD-1" || !validToken(fields[1]) ||
        !number(fields[2], size) || size > MAX_SIZE || !number(fields[3], crc) || !validPath(fields[4]))
        return "invalid upload journal; artifacts retained";
    const auto backup = fs.kind(BACKUP);
    if (backup == Kind::File) {
        const auto dest = fs.kind(fields[4]);
        if (dest == Kind::Missing) {
            if (!fs.move(BACKUP, fields[4])) return "rollback failed; previous file retained in upload backup";
        } else {
            uint32_t actualSize, actualCrc;
            if (dest != Kind::File || !fs.digest(fields[4], actualSize, actualCrc) || actualSize != size || actualCrc != crc)
                return "destination conflicts with recovery; previous file retained in upload backup";
        }
    } else if (backup != Kind::Missing) return "cannot inspect upload backup";
    dirty = !cleanup();
    return dirty ? "upload cleanup failed; retry recovery" : nullptr;
}
const char *Transaction::begin(const std::string &token, const std::string &path, uint32_t size, uint32_t crc, uint32_t now) {
    if (!validToken(token) || !validPath(path) || size > MAX_SIZE) return "invalid upload metadata";
    if (running) return "upload busy";
    if (const char *err = recover()) return err;
    const auto dest = fs.kind(path);
    if (dest != Kind::File && dest != Kind::Missing) return "destination is not a regular file";
    const std::string record = "BRUCIA-UPLOAD-1\n" + token + "\n" + std::to_string(size) + "\n" + std::to_string(crc) + "\n" + path + "\n";
    if (!fs.write(JOURNAL, reinterpret_cast<const uint8_t *>(record.data()), record.size(), false))
        return "journal write failed; destination unchanged";
    std::string verified;
    if (!fs.load(JOURNAL, 512, verified) || verified != record) return "journal verification failed; destination unchanged";
    owner = token; target = path; length = size; checksum = crc; count = 0; touched = now;
    if (!fs.write(PART, nullptr, 0, false)) { dirty = !cleanup(); return "staging open failed; destination unchanged"; }
    running = true;
    return nullptr;
}
const char *Transaction::chunk(const std::string &token, uint32_t offset, const uint8_t *bytes, size_t size, uint32_t now) {
    if (!running || token != owner) return "unknown upload token";
    if (offset != count || size == 0 || size > MAX_CHUNK || size > length - count) return "invalid upload offset or size";
    if (!fs.write(PART, bytes, size, true)) { close(); return "staging write failed; destination unchanged"; }
    count += size; touched = now;
    return nullptr;
}
const char *Transaction::commit(const std::string &token) {
    if (!running || token != owner) return "unknown upload token";
    uint32_t actualSize, actualCrc;
    if (count != length || !fs.digest(PART, actualSize, actualCrc) || actualSize != length || actualCrc != checksum) {
        close(); return "upload size/CRC verification failed; destination unchanged";
    }
    const auto dest = fs.kind(target);
    if (dest != Kind::File && dest != Kind::Missing) { close(); return "destination is not a regular file"; }
    if (dest == Kind::File && !fs.replacesAtomically()) {
        if (fs.kind(BACKUP) != Kind::Missing || !fs.move(target, BACKUP)) { close(); return "cannot preserve previous destination"; }
    }
    if (!fs.move(PART, target)) {
        running = false;
        if (const char *error = recover()) return error;
        return "commit failed; previous destination restored";
    }
    // Commit point: cleanup errors must not misreport a successful replacement as a failed write.
    running = false;
    dirty = !cleanup();
    return nullptr;
}
const char *Transaction::abort(const std::string &token) {
    if (!running || token != owner) return "unknown upload token";
    running = false;
    return recover();
}
void Transaction::close() { running = false; recover(); }
void Transaction::expire(uint32_t now) { if (running && uint32_t(now - touched) >= 120000) close(); }
}}}
