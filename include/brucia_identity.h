#pragma once

// Product identity. Bruce-compatible commands and capture/config formats keep
// their original names; visible product labels and new-install defaults live here.
// Public links (repository, site, wiki, releases, upstream credit) are generated
// from brand.json into brucia_brand.h (docs/LAUNCH.md).
#include "brucia_brand.h"

#define BRUCIA_PRODUCT_NAME BRUCIA_NAME
#define BRUCIA_MANUFACTURER "Brucia Research"
#define BRUCIA_UPSTREAM_CREDIT "Based on " BRUCIA_UPSTREAM_NAME " (AGPL-3.0)"
#define BRUCIA_PROJECT_URL BRUCIA_REPO_URL
#define BRUCIA_GUIDE_URL BRUCIA_SITE_URL
#define BRUCIA_AP_SSID "BruciaNet"
#define BRUCIA_MDNS_HOST "brucia"
