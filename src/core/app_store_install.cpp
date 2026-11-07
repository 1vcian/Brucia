#include "app_store_install.h"

namespace app_store {

std::string install(Body &body, Files &fs, const std::string &dest) {
    const std::string staging = dest + ".part";
    const std::string backup = dest + ".bak";

    // A crash between the two renames below left only the backup: bring the previous install back.
    if (!fs.exists(dest) && fs.exists(backup) && !fs.rename(backup, dest)) {
        return "cannot restore previous install";
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
        total += n;
    }
    if (!sawContent) return fail(true, "download is blank");
    if (!fs.endWrite()) return fail(false, "storage flush failed (full?)");
    if (fs.size(staging) != len) return fail(false, "written size does not match download");

    const bool hadOld = fs.exists(dest);
    if (hadOld) {
        if (fs.exists(backup) && !fs.remove(backup)) return fail(false, "cannot clear old backup");
        if (!fs.rename(dest, backup)) return fail(false, "cannot move previous install aside");
    }
    if (!fs.rename(staging, dest)) {
        if (hadOld) fs.rename(backup, dest);
        return fail(false, "cannot install downloaded script");
    }
    if (fs.exists(backup)) fs.remove(backup);
    return "";
}

} // namespace app_store
