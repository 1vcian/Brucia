#if !defined(LITE_VERSION) && !defined(DISABLE_INTERPRETER)
#include "app_store_js.h"
#include "core/app_store_http.h"
#include "core/app_store_roots.h"
// mquickjs uses a moving GC; keep response values rooted while adding properties.
namespace {
struct RootedValue {
    JSContext *ctx;
    JSGCRef ref;
    RootedValue(JSContext *c, JSValue value) : ctx(c) { JS_PushGCRef(ctx, &ref); ref.val = value; }
    ~RootedValue() { JS_PopGCRef(ctx, &ref); }
};
}

// This adapter covers the store requests, not arbitrary networking by installed third-party apps.
JSValue storeHttpFetch(JSContext *ctx, int argc, JSValue *argv, const app_store::StoreRoute &route) {
    if (!route.error.empty()) return JS_ThrowTypeError(ctx, "App Store: %s", route.error.c_str());
    JSCStringBuf strings;
    JSValue save = JS_UNDEFINED;
    bool json = false, binary = false;
    if (argc > 1 && JS_IsObject(ctx, argv[1])) {
        JSValue method = JS_GetPropertyStr(ctx, argv[1], "method");
        if (!JS_IsUndefined(method)) {
            const char *text = JS_ToCString(ctx, method, &strings);
            if (!text || strcmp(text, "GET")) return JS_ThrowTypeError(ctx, "App Store only supports GET");
        }
        save = JS_GetPropertyStr(ctx, argv[1], "save");
        JSValue type = JS_GetPropertyStr(ctx, argv[1], "responseType");
        if (!JS_IsUndefined(type)) {
            const char *text = JS_ToCString(ctx, type, &strings);
            json = text && !strcmp(text, "json");
            binary = text && !strcmp(text, "binary");
        }
    }
    const bool hasSave = !JS_IsUndefined(save);
    FileParamsJS destination;
    if (hasSave) {
        if (!JS_IsObject(ctx, save)) return JS_ThrowTypeError(ctx, "App Store requires {fs,path,mode:write}");
        JSCStringBuf fsBuffer;
        const char *fs = JS_ToCString(ctx, JS_GetPropertyStr(ctx, save, "fs"), &fsBuffer);
        const char *mode = JS_ToCString(ctx, JS_GetPropertyStr(ctx, save, "mode"), &strings);
        if (!fs || (strcmp(fs, "sd") && strcmp(fs, "littlefs")) || !mode || strcmp(mode, "write") ||
            !JS_IsUndefined(JS_GetPropertyStr(ctx, save, "position"))) {
            return JS_ThrowTypeError(ctx, "App Store requires sd/littlefs and whole-file write");
        }
        JSValue params[] = {save, JS_UNDEFINED};
        destination = js_get_path_from_params(ctx, params, false);
        if (!app_store::storeDestination(destination.path.c_str()) || !destination.fs->mountpoint()) {
            return JS_ThrowTypeError(ctx, "App Store: invalid destination (BruceJS or Themes only)");
        }
    }

    NetworkClientSecure tls;
    tls.setCACert(BRUCIA_APP_STORE_ROOTS);
    tls.setHandshakeTimeout(20);
    HTTPClient http;
    http.setConnectTimeout(10000);
    http.setTimeout(10000);
    http.useHTTP10(true);
    http.setFollowRedirects(HTTPC_DISABLE_FOLLOW_REDIRECTS);
    if (!http.begin(tls, route.url.c_str())) return JS_ThrowInternalError(ctx, "App Store: HTTPS setup failed");
    const int code = http.GET();
    if (code != 200) return JS_ThrowInternalError(ctx, "App Store: HTTPS request failed (%d); no file installed", code);
    const auto dates = app_store::checkPeerCertificateDates(tls);
    if (!dates.error.empty()) return JS_ThrowInternalError(ctx, "App Store: %s", dates.error.c_str());
    app_store::HttpBody body(http);
    app_store::MillisClock clock;
    std::string content, error;
    if (hasSave) {
        // POSIX fopen does not create parents. Create only validated directories, never the target.
        for (int slash = destination.path.indexOf('/', 1); slash >= 0;
             slash = destination.path.indexOf('/', slash + 1)) {
            const String parent = destination.path.substring(0, slash);
            if (!destination.fs->exists(parent) && !destination.fs->mkdir(parent)) {
                return JS_ThrowInternalError(ctx, "App Store: cannot create destination directory");
            }
        }
        app_store::PosixFiles files(destination.fs->mountpoint());
        error = app_store::install(body, files, destination.path.c_str(), clock,
                                   app_store::DOWNLOAD_BUDGET_MS, app_store::MAX_ASSET_BYTES);
    } else {
        error = app_store::readCatalog(body, clock, content);
    }
    if (!error.empty()) return JS_ThrowInternalError(ctx, "App Store: %s", error.c_str());
    const long length = http.getSize();
    http.end();

    JSValue value = JS_UNDEFINED;
    if (!hasSave) {
        if (json) {
            // JSON-only evaluation: never execute downloaded catalog text as JavaScript.
            value = JS_Eval(ctx, content.data(), content.size(), "store-catalog", JS_EVAL_JSON);
        } else if (binary) {
            value = JS_NewUint8ArrayCopy(ctx, reinterpret_cast<const uint8_t *>(content.data()), content.size());
        } else {
            value = JS_NewStringLen(ctx, content.data(), content.size());
        }
        if (JS_IsException(value)) return value;
    }
    RootedValue bodyValue(ctx, value);
    RootedValue result(ctx, JS_NewObject(ctx));
    if (JS_IsException(result.ref.val)) return result.ref.val;
    if (hasSave) {
        JS_SetPropertyStr(ctx, result.ref.val, "saved", JS_NewBool(true));
        value = JS_NewString(ctx, destination.path.c_str());
        if (JS_IsException(value)) return value;
        JS_SetPropertyStr(ctx, result.ref.val, "savedPath", value);
    } else {
        JS_SetPropertyStr(ctx, result.ref.val, "body", bodyValue.ref.val);
    }
    JS_SetPropertyStr(ctx, result.ref.val, "status", JS_NewInt32(ctx, 200));
    JS_SetPropertyStr(ctx, result.ref.val, "response", JS_NewInt32(ctx, 200));
    JS_SetPropertyStr(ctx, result.ref.val, "ok", JS_NewBool(true));
    JS_SetPropertyStr(ctx, result.ref.val, "length", JS_NewInt32(ctx, length));
    value = JS_NewObject(ctx);
    if (JS_IsException(value)) return value;
    JS_SetPropertyStr(ctx, result.ref.val, "headers", value);
    return result.ref.val;
}
#endif
