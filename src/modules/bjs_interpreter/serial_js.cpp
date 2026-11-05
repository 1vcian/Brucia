#if !defined(LITE_VERSION) && !defined(DISABLE_INTERPRETER)
#include "serial_js.h"

#include "core/display.h"

#include "helpers_js.h"

static void internal_print_mq(JSContext *ctx, int argc, JSValue *argv, uint8_t printTft, uint8_t newLine) {
    for (int argIndex = 0; argIndex < argc && argIndex < 20; ++argIndex) {
        if (argIndex > 0) {
            if (printTft) tft.print(" ");
            usbConsole().print(" ");
        }

        JSValue v = argv[argIndex];
        if (JS_IsUndefined(v)) {
            if (printTft) tft.print("undefined");
            usbConsole().print("undefined");
        } else if (JS_IsNull(v)) {
            if (printTft) tft.print("null");
            usbConsole().print("null");
        } else if (JS_IsNumber(ctx, v)) {
            double num;
            JS_ToNumber(ctx, &num, v);
            if (printTft) tft.printf("%g", num);
            usbConsole().printf("%g", num);
        } else if (JS_IsBool(v)) {
            const char *bv = JS_IsBool(v) && JS_ToBool(ctx, v) ? "true" : "false";
            if (printTft) tft.print(bv);
            usbConsole().print(bv);
        } else {
            JSCStringBuf sb;
            const char *s = JS_ToCString(ctx, v, &sb);
            if (s) {
                if (printTft) tft.print(s);
                usbConsole().print(s);
            }
        }
    }
    if (newLine) {
        if (printTft) tft.println();
        usbConsole().println();
    }
}

JSValue native_serialPrint(JSContext *ctx, JSValue *this_val, int argc, JSValue *argv) {
    internal_print_mq(ctx, argc, argv, false, false);
    return JS_UNDEFINED;
}

JSValue native_serialPrintln(JSContext *ctx, JSValue *this_val, int argc, JSValue *argv) {
    internal_print_mq(ctx, argc, argv, false, true);
    return JS_UNDEFINED;
}

JSValue native_serialReadln(JSContext *ctx, JSValue *this_val, int argc, JSValue *argv) {
    String line;
    // Brucia: in companion mode (script_run) Serial carries the app's protocol frames; reading it
    // here would consume them and desync the link. No console input exists then: return "" at once.
    if (g_companionActive) return JS_NewString(ctx, "");
    int maxloops = 1000 * 10;
    if (argc > 0 && JS_IsNumber(ctx, argv[0])) {
        int t;
        JS_ToInt32(ctx, &t, argv[0]);
        maxloops = t;
    }
    Serial.flush();
    while (maxloops) {
        if (!Serial.available()) {
            maxloops -= 1;
            delay(1);
            continue;
        }
        line = Serial.readStringUntil('\n');
        break;
    }
    return JS_NewString(ctx, line.c_str());
}

JSValue native_serialCmd(JSContext *ctx, JSValue *this_val, int argc, JSValue *argv) {
    const char *cmd = NULL;
    JSCStringBuf sb;
    if (argc > 0 && JS_IsString(ctx, argv[0])) cmd = JS_ToCString(ctx, argv[0], &sb);
    bool r = false;
    if (cmd) r = serialCli.parse(String(cmd));
    return JS_NewBool(r);
}

#endif
