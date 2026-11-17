#pragma once
#include "app_store_install.h"

namespace app_store {
constexpr long MAX_CATALOG_BYTES = 256L * 1024L;
constexpr long MAX_ASSET_BYTES = 2L * 1024L * 1024L;

struct StoreRoute {
    bool handled = false;
    std::string url;
    std::string error;
};
// Only this configured store authority is intercepted. HTTP is upgraded before opening a socket.
StoreRoute storeRoute(const std::string &input, const std::string &httpsOrigin);
bool storeDestination(const std::string &path);
// Catalogs are complete and bounded before the JS parser sees them; no successful prefix replies.
std::string readCatalog(Body &body, Clock &clock, std::string &out);
} // namespace app_store
