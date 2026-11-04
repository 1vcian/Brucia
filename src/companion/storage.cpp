#include "core/sd_functions.h"
#include "internal.h"
#include <globals.h>
#include <algorithm>
#include "storage_upload.h"

namespace brucia {
namespace companion {
namespace {
class UploadStore : public upload::Store {
public:
    FS *fs = nullptr;
    upload::Kind kind(const std::string &path) override {
        if (!fs) return upload::Kind::Error;
        if (!fs->exists(path.c_str())) return upload::Kind::Missing;
        File f = fs->open(path.c_str(), FILE_READ);
        if (!f) return upload::Kind::Error;
        bool dir = f.isDirectory(); f.close();
        return dir ? upload::Kind::Other : upload::Kind::File;
    }
    bool load(const std::string &path, size_t limit, std::string &out) override {
        File f = fs->open(path.c_str(), FILE_READ);
        if (!f) return false;
        size_t n = f.size();
        if (f.isDirectory() || n > limit) { f.close(); return false; }
        out.resize(n);
        bool ok = n == 0 || f.read(reinterpret_cast<uint8_t *>(&out[0]), n) == static_cast<int>(n);
        f.close(); return ok;
    }
    bool write(const std::string &path, const uint8_t *bytes, size_t n, bool append) override {
        File f = fs->open(path.c_str(), append ? FILE_APPEND : FILE_WRITE);
        if (!f) return false;
        bool ok = !f.isDirectory() && (n == 0 || f.write(bytes, n) == n);
        f.flush(); f.close(); return ok;
    }
    bool digest(const std::string &path, uint32_t &size, uint32_t &crc) override {
        File f = fs->open(path.c_str(), FILE_READ);
        if (!f) return false;
        if (f.isDirectory() || f.size() > upload::MAX_SIZE) { f.close(); return false; }
        size = f.size(); crc = 0xFFFFFFFFU;
        uint8_t buf[1024]; uint32_t count = 0, started = millis();
        while (count < size) {
            int n = f.read(buf, std::min<size_t>(sizeof(buf), size - count));
            if (n <= 0 || uint32_t(millis() - started) > 120000) { f.close(); return false; }
            crc = upload::crcStep(crc, buf, n); count += n;
            delay(1); // allow watchdog/idle tasks while verifying a large file
        }
        bool ok = f.size() == size; f.close(); crc ^= 0xFFFFFFFFU; return ok;
    }
    bool move(const std::string &from, const std::string &to) override { return fs->rename(from.c_str(), to.c_str()); }
    bool erase(const std::string &path) override { return fs->remove(path.c_str()); }
    bool replacesAtomically() const override { return fs == &LittleFS; }
} uploadStore;
upload::Transaction fileUpload(uploadStore);

bool takeWord(std::string &args, std::string &word) {
    size_t split = args.find(' ');
    if (split == std::string::npos || split == 0) return false;
    word = args.substr(0, split); args.erase(0, split + 1); return true;
}
void uploadReply(uint32_t id, const std::string &token, bool committed = false) {
    JsonDocument out; beginEnvelope(out, MSG_ACK, id);
    JsonObject d = out["d"].to<JsonObject>();
    d["ok"] = true; d["token"] = token.c_str();
    d["size"] = committed ? fileUpload.expectedSize() : fileUpload.received();
    if (committed) {
        d["committed"] = true; d["crc32"] = fileUpload.expectedCrc();
        d["cleanup_pending"] = fileUpload.cleanupPending();
    }
    sendEnvelope(out);
}
bool uploadCommand(const String &cmd, uint32_t id) {
    if (!cmd.startsWith("fs_upload_")) return false;
    std::string args(cmd.c_str()), verb, token;
    if (!takeWord(args, verb)) { sendErr(id, 400, "missing upload arguments"); return true; }
    fileUpload.expire(millis());
    const char *error = nullptr;
    if (verb == "fs_upload_begin") {
        std::string sizeText, crcText; uint32_t size, crc;
        if (!takeWord(args, token) || !takeWord(args, sizeText) || !takeWord(args, crcText) ||
            !upload::number(sizeText, size) || !upload::number(crcText, crc)) {
            sendErr(id, 400, "invalid upload metadata"); return true;
        }
        // Pin the filesystem for this transaction; never append on a fallback after SD removal.
        FS *selected = sdcardMounted ? static_cast<FS *>(&SD) : static_cast<FS *>(&LittleFS);
        if (!fileUpload.active()) {
            if (uploadStore.fs && uploadStore.fs != selected) {
                if ((error = fileUpload.recover())) { sendErr(id, 409, error); return true; }
            }
            uploadStore.fs = selected;
        }
        error = fileUpload.begin(token, args, size, crc, millis());
    } else if (verb == "fs_upload_chunk") {
        std::string offsetText; uint32_t offset;
        if (!takeWord(args, token) || !takeWord(args, offsetText) || !upload::number(offsetText, offset)) {
            sendErr(id, 400, "invalid upload chunk"); return true;
        }
        size_t n = 0;
        uint8_t *data = companionB64decode(String(args.c_str()), &n);
        if (!data || n == 0 || n > upload::MAX_CHUNK) {
            free(data); sendErr(id, 400, "invalid upload chunk encoding/size"); return true;
        }
        error = fileUpload.chunk(token, offset, data, n, millis()); free(data);
    } else if (verb == "fs_upload_commit" || verb == "fs_upload_abort") {
        token = args;
        error = verb == "fs_upload_commit" ? fileUpload.commit(token) : fileUpload.abort(token);
    } else { sendErr(id, 400, "unknown upload operation"); return true; }
    if (error) sendErr(id, 409, error);
    else {
        fileUpload.acknowledge(millis());
        uploadReply(id, token, verb == "fs_upload_commit");
    }
    return true;
}
} // namespace

void startCompanionStorage() {
    // Recover both mounted backends, even if SD currently wins storage selection.
    uploadStore.fs = &LittleFS; fileUpload.close();
    if (sdcardMounted) { uploadStore.fs = &SD; fileUpload.close(); }
}
void serviceCompanionStorage() { fileUpload.expire(millis()); }
void stopCompanionStorage() { if (uploadStore.fs) fileUpload.close(); }

static void fsReadStream(const String &pathIn, uint32_t id) {
    FS *fs;
    if (!getFsStorage(fs)) {
        sendErr(id, 404, "no storage");
        return;
    }
    String path = pathIn;
    path.trim();
    if (!path.startsWith("/")) path = "/" + path;
    File f = (*fs).open(path, FILE_READ);
    if (!f) {
        sendErr(id, 404, "open failed");
        return;
    }
    const size_t expected = f.size();
    if (f.isDirectory() || expected > 16 * 1024 * 1024) {
        f.close();
        sendErr(id, 413, "not a file or exceeds 16 MiB transfer limit");
        return;
    }
    uint8_t buf[768];
    uint32_t seq = 0;
    uint32_t total = 0;
    uint32_t checksum = 0xFFFFFFFF;
    const uint32_t started = millis();
    while (total < expected) {
        if (millis() - started > 120000) {
            f.close();
            sendErr(id, 408, "file transfer timed out");
            return;
        }
        int n = f.read(buf, std::min<size_t>(sizeof(buf), expected - total));
        if (n <= 0) {
            f.close();
            sendErr(id, 500, "read failed before end of file");
            return;
        }
        total += n;
        for (int i = 0; i < n; ++i) {
            checksum ^= buf[i];
            for (int bit = 0; bit < 8; ++bit)
                checksum = (checksum >> 1) ^ (0xEDB88320U & (0U - (checksum & 1U)));
        }
        JsonDocument out;
        beginEnvelope(out, MSG_DATA, id);
        JsonObject d = out["d"].to<JsonObject>();
        d["stream"] = "file";
        d["seq"] = seq++;
        d["chunk"] = b64encode(buf, n);
        sendEnvelope(out);
    }
    f.close();
    JsonDocument o;
    beginEnvelope(o, MSG_ACK, id);
    JsonObject d = o["d"].to<JsonObject>();
    d["ok"] = true;
    d["size"] = total;
    d["crc32"] = checksum ^ 0xFFFFFFFF;
    sendEnvelope(o);
}

// fs_write <path> <base64>        : create/overwrite (truncate) a file with the decoded bytes.
// fs_write_append <path> <base64> : open the file for append and add the decoded bytes.
// Base64 is used so arbitrary/binary content survives the JSON/CLI channel (reusing the
// file-scope companionB64decode()). Picks the FS the same way fs_read does (SD if available,
// else LittleFS; errors if neither). One write per frame (the whole CMD already fits within
// COMPANION_MAX_FRAME). Legacy clients can truncate/append; managed app uploads use
// fs_upload_* instead. ACK { ok, size:<bytes written> };
// sendErr on bad base64, too-large payload, no storage or open/write failure.
static void fsWriteCmd(const String &args, uint32_t id, bool append) {
    const char *usage = append ? "usage: fs_write_append <path> <base64>" : "usage: fs_write <path> <base64>";
    String a = args;
    // Base64 has no spaces. Split at the last separator, preserving spaces in
    // paths and the empty final field used to create/truncate an empty file.
    int sp = a.lastIndexOf(' ');
    if (sp < 0) {
        sendErr(id, 400, usage);
        return;
    }
    String path = a.substring(0, sp);
    path.trim();
    String b64 = a.substring(sp + 1);
    b64.trim();
    if (path.length() == 0) {
        sendErr(id, 400, usage);
        return;
    }
    FS *fs;
    if (!getFsStorage(fs)) {
        sendErr(id, 404, "no storage");
        return;
    }
    if (!path.startsWith("/")) path = "/" + path;

    // Legacy commands reject reserved names and ambiguous path components,
    // and cannot mutate a destination while a managed upload owns storage.
    if (!upload::validPath(std::string(path.c_str()))) {
        sendErr(id, 400, "invalid or reserved file path");
        return;
    }
    if (fileUpload.active()) {
        sendErr(id, 409, "upload busy");
        return;
    }

    size_t outLen = 0;
    uint8_t *data = companionB64decode(b64, &outLen);
    if (b64.length() != 0 && !data) {
        if (data) free(data);
        sendErr(id, 400, "bad base64");
        return;
    }
    if (outLen > COMPANION_MAX_FRAME) { // one fs_write per frame
        free(data);
        sendErr(id, 413, "payload too large");
        return;
    }
    File f = (*fs).open(path, append ? FILE_APPEND : FILE_WRITE);
    if (!f) {
        free(data);
        sendErr(id, 500, "open failed");
        return;
    }
    if (f.isDirectory()) {
        f.close();
        free(data);
        sendErr(id, 400, "cannot write a directory");
        return;
    }
    size_t wrote = outLen ? f.write(data, outLen) : 0;
    f.close();
    free(data);
    if (wrote != outLen) {
        sendErr(id, 500, "write failed");
        return;
    }
    JsonDocument o;
    beginEnvelope(o, MSG_ACK, id);
    JsonObject d = o["d"].to<JsonObject>();
    d["ok"] = true;
    d["size"] = (uint32_t)wrote;
    sendEnvelope(o);
}

bool dispatchStorageCommand(const CommandRequest &request) {
    const String &cmdStr = request.command;
    const uint32_t id = request.id;
    if (uploadCommand(cmdStr, id)) return true;
    do {

        if (cmdStr.startsWith("fs_read ")) {
            fsReadStream(cmdStr.substring(8), id);
            break;
        }

        // Brucia: push a file to the device storage. fs_write truncates+writes;
        // fs_write_append streams further chunks onto it. (append checked first:
        // "fs_write_append " does not start with "fs_write ", but keep it explicit.)
        if (cmdStr.startsWith("fs_write_append ")) {
            fsWriteCmd(cmdStr.substring(16), id, true);
            break;
        }

        if (cmdStr.startsWith("fs_write ")) {
            fsWriteCmd(cmdStr.substring(9), id, false);
            break;
        }
        return false;
    } while (false);
    return true;
}

} // namespace companion
} // namespace brucia
