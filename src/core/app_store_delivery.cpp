#include "app_store_delivery.h"
#include <algorithm>
#include <cctype>

namespace app_store {
StoreRoute storeRoute(const std::string &input, const std::string &httpsOrigin) {
    StoreRoute result;
    const size_t scheme = input.find("://");
    if (scheme == std::string::npos) return result;
    const size_t end = input.find_first_of("/?#", scheme + 3);
    std::string authority = input.substr(scheme + 3, end == std::string::npos ? end : end - scheme - 3);
    std::transform(authority.begin(), authority.end(), authority.begin(), [](unsigned char c) { return std::tolower(c); });
    // Recognize userinfo/ports too, but reject them below rather than use the generic HTTP client.
    const size_t at = authority.rfind('@');
    const std::string hostPort = authority.substr(at == std::string::npos ? 0 : at + 1);
    const size_t colon = hostPort.find(':');
    if (hostPort.substr(0, colon) != httpsOrigin.substr(8)) return result;
    result.handled = true;
    std::string protocol = input.substr(0, scheme);
    std::transform(protocol.begin(), protocol.end(), protocol.begin(), [](unsigned char c) { return std::tolower(c); });
    const bool portOk = colon == std::string::npos ||
                        (protocol == "http" && hostPort.substr(colon) == ":80") ||
                        (protocol == "https" && hostPort.substr(colon) == ":443");
    if ((protocol != "http" && protocol != "https") || at != std::string::npos || !portOk) {
        result.error = "unsupported store URL authority";
        return result;
    }
    const std::string path = end == std::string::npos ? "/" : input.substr(end);
    if (path.rfind("/service/", 0) != 0 || path.find_first_of("\\#\r\n\t ") != std::string::npos) {
        result.error = "unsupported store URL path";
        return result;
    }
    result.url = httpsOrigin + path;
    return result;
}

bool storeDestination(const std::string &path) {
    if (path.size() > 240 || (path.rfind("/BruceJS/", 0) != 0 && path.rfind("/Themes/", 0) != 0)) return false;
    size_t start = 1;
    while (start <= path.size()) {
        const size_t slash = path.find('/', start);
        const std::string part = path.substr(start, slash == std::string::npos ? slash : slash - start);
        if (part.empty() || part == "." || part == ".." || part.back() == '.' || part.back() == ' ') return false;
        for (unsigned char c : part) if (c < 32 || c == 127 || c == '\\' || c == ':') return false;
        std::string lower = part;
        std::transform(lower.begin(), lower.end(), lower.begin(), [](unsigned char c) { return std::tolower(c); });
        if ((lower.size() >= 4 && lower.compare(lower.size() - 4, 4, ".bak") == 0) ||
            (lower.size() >= 5 && lower.compare(lower.size() - 5, 5, ".part") == 0)) return false;
        if (slash == std::string::npos) return true;
        start = slash + 1;
    }
    return false;
}

std::string readCatalog(Body &body, Clock &clock, std::string &out) {
    out.clear();
    const long len = body.length();
    if (len <= 0 || len > MAX_CATALOG_BYTES) return "missing, empty or oversized store response";
    const uint32_t start = clock.nowMs();
    char buf[512];
    while (out.size() < static_cast<size_t>(len)) {
        const uint32_t elapsed = clock.nowMs() - start;
        if (elapsed >= DOWNLOAD_BUDGET_MS) { out.clear(); return TOO_SLOW; }
        const size_t want = std::min(sizeof(buf), static_cast<size_t>(len) - out.size());
        const long n = body.read(reinterpret_cast<uint8_t *>(buf), want, DOWNLOAD_BUDGET_MS - elapsed);
        if (n <= 0 || static_cast<size_t>(n) > want || clock.nowMs() - start >= DOWNLOAD_BUDGET_MS) {
            out.clear();
            return "store response interrupted or timed out";
        }
        out.append(buf, static_cast<size_t>(n));
    }
    return "";
}
} // namespace app_store
