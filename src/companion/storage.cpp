#include "core/sd_functions.h"
#include "internal.h"
#include <globals.h>
#include <algorithm>

namespace brucia {
namespace companion {
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
// COMPANION_MAX_FRAME); to push a larger file the app streams it as a first fs_write
// (truncate) followed by repeated fs_write_append chunks. ACK { ok, size:<bytes written> };
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
