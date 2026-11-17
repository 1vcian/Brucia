#pragma once
#include "helpers_js.h"
#include "core/app_store_delivery.h"

JSValue storeHttpFetch(JSContext *ctx, int argc, JSValue *argv, const app_store::StoreRoute &route);
