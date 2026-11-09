#include "app_store_install.h"
#include <sys/stat.h>
#include <unistd.h>

namespace app_store {

namespace {
// FNV-1a 64: compares the downloaded bytes with what the storage reads back (not a security check;
// TLS authenticates the download).
constexpr uint64_t FNV_OFFSET = 1469598103934665603ULL;
uint64_t fnv1a(uint64_t h, const uint8_t *p, size_t n) {
    for (size_t i = 0; i < n; i++) h = (h ^ p[i]) * 1099511628211ULL;
    return h;
}

std::string baseName(const std::string &path) {
    const size_t slash = path.find_last_of('/');
    return slash == std::string::npos ? path : path.substr(slash + 1);
}

std::string recovery(const std::string &what, const std::string &dest) {
    return std::string(RECOVERY_REQUIRED) + what + "; the previous script is kept as '" + baseName(dest) +
           ".bak' and is restored on the next install attempt";
}
} // namespace

std::string install(Body &body, Files &fs, const std::string &dest) {
    const std::string staging = dest + ".part";
    const std::string backup = dest + ".bak";

    // A crash between the two renames below left only the backup: bring the previous install back.
    if (!fs.exists(dest) && fs.exists(backup) && !fs.rename(backup, dest)) {
        return recovery("cannot restore the previous install", dest);
    }

    const long len = body.length();
    if (len < 0) return "no Content-Length; download cannot be verified";
    if (len == 0) return "empty download";
    if (len > MAX_SCRIPT_BYTES) return "download too large";

    if (fs.exists(staging) && !fs.remove(staging)) return "cannot clear staging file";
    if (!fs.beginWrite(staging)) return "cannot create staging file";

    auto fail = [&](bool open, const std::string &why) {
        if (open) fs.endWrite();
        fs.remove(staging);
        return why;
    };

    long total = 0;
    bool sawContent = false;
    uint64_t sent = FNV_OFFSET;
    uint8_t buf[512];
    while (total < len) {
        size_t want = sizeof(buf);
        if (static_cast<long>(want) > len - total) want = static_cast<size_t>(len - total);
        const long n = body.read(buf, want);
        if (n <= 0) return fail(true, "download interrupted");
        if (n > static_cast<long>(want)) return fail(true, "download read overflow");
        for (long i = 0; i < n && !sawContent; i++) {
            const uint8_t c = buf[i];
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') continue;
            if (c == '<') return fail(true, "server sent a web page, not a script");
            sawContent = true;
        }
        if (fs.write(buf, static_cast<size_t>(n)) != static_cast<size_t>(n)) {
            return fail(true, "storage write failed (full?)");
        }
        sent = fnv1a(sent, buf, static_cast<size_t>(n));
        total += n;
    }
    if (!sawContent) return fail(true, "download is blank");
    if (!fs.endWrite()) return fail(false, "storage flush failed (full?)");
    if (fs.size(staging) != len) return fail(false, "written size does not match download");

    // A correct size does not prove the bytes reached storage: read them back after the close.
    if (!fs.beginRead(staging)) return fail(false, "cannot read back downloaded script");
    uint64_t stored = FNV_OFFSET;
    long back = 0;
    for (;;) {
        const long n = fs.read(buf, sizeof(buf));
        if (n < 0) {
            back = -1;
            break;
        }
        if (n == 0) break;
        stored = fnv1a(stored, buf, static_cast<size_t>(n));
        back += n;
    }
    fs.endRead();
    if (back != len || stored != sent) return fail(false, "stored script differs from download");

    const bool hadOld = fs.exists(dest);
    if (hadOld) {
        if (fs.exists(backup) && !fs.remove(backup)) return fail(false, "cannot clear old backup");
        if (!fs.rename(dest, backup)) return fail(false, "cannot move previous install aside");
    }
    if (!fs.rename(staging, dest)) {
        if (hadOld && !fs.rename(backup, dest)) {
            return fail(false, recovery("cannot install downloaded script nor put the previous one back", dest));
        }
        return fail(false, "cannot install downloaded script");
    }
    if (fs.exists(backup)) fs.remove(backup);
    return "";
}

PosixFiles::~PosixFiles() {
    if (out_) closeFile(out_);
    if (in_) std::fclose(in_);
}

bool PosixFiles::exists(const std::string &path) {
    struct stat st;
    return ::stat(full(path).c_str(), &st) == 0;
}

bool PosixFiles::remove(const std::string &path) { return ::unlink(full(path).c_str()) == 0; }

bool PosixFiles::rename(const std::string &from, const std::string &to) {
    return ::rename(full(from).c_str(), full(to).c_str()) == 0;
}

bool PosixFiles::beginWrite(const std::string &path) {
    if (out_) return false;
    out_ = std::fopen(full(path).c_str(), "wb");
    outOk_ = out_ != nullptr;
    return outOk_;
}

size_t PosixFiles::write(const uint8_t *data, size_t n) {
    if (!out_) return 0;
    const size_t w = std::fwrite(data, 1, n, out_);
    if (w != n) outOk_ = false;
    return w;
}

bool PosixFiles::endWrite() {
    if (!out_) return false;
    bool ok = outOk_ && !std::ferror(out_);
    if (std::fflush(out_) != 0) ok = false;
    if (syncFd(fileno(out_)) != 0) ok = false;
    if (closeFile(out_) != 0) ok = false;
    out_ = nullptr;
    return ok;
}

long PosixFiles::size(const std::string &path) {
    struct stat st;
    if (::stat(full(path).c_str(), &st) != 0) return -1;
    return static_cast<long>(st.st_size);
}

bool PosixFiles::beginRead(const std::string &path) {
    if (in_) return false;
    in_ = std::fopen(full(path).c_str(), "rb");
    return in_ != nullptr;
}

long PosixFiles::read(uint8_t *buf, size_t cap) {
    if (!in_) return -1;
    const size_t n = std::fread(buf, 1, cap, in_);
    if (n == 0) return std::ferror(in_) ? -1 : 0;
    return static_cast<long>(n);
}

void PosixFiles::endRead() {
    if (in_) std::fclose(in_);
    in_ = nullptr;
}

int PosixFiles::syncFd(int fd) { return ::fsync(fd); }

int PosixFiles::closeFile(std::FILE *f) { return std::fclose(f); }

} // namespace app_store
