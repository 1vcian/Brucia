#include "diag_core.h"
#include "internal.h"
#include <esp_system.h>
#include <esp_timer.h>

namespace brucia {
namespace companion {
#if defined(COMPANION_HAS_DIAG)
// diagResetReasonName() maps by number; fail the build if ESP-IDF renumbers the enum.
static_assert(ESP_RST_UNKNOWN == 0 && ESP_RST_POWERON == 1 && ESP_RST_EXT == 2 && ESP_RST_SW == 3, "reset enum");
static_assert(ESP_RST_PANIC == 4 && ESP_RST_INT_WDT == 5 && ESP_RST_TASK_WDT == 6 && ESP_RST_WDT == 7, "reset enum");
static_assert(ESP_RST_DEEPSLEEP == 8 && ESP_RST_BROWNOUT == 9 && ESP_RST_SDIO == 10, "reset enum");
static_assert(ESP_RST_USB == 11 && ESP_RST_JTAG == 12 && ESP_RST_EFUSE == 13, "reset enum");
static_assert(ESP_RST_PWR_GLITCH == 14 && ESP_RST_CPU_LOCKUP == 15, "reset enum");

namespace {
struct JsonLinkSink {
    JsonObject link;
    JsonObject err;
    void field(const char *key, uint32_t value) { link[key] = value; }
    void errField(const char *key, uint32_t value) { err[key] = value; }
};
} // namespace

void runDiagCommand(uint32_t id) {
    // Snapshot first so the reply describes the link before this reply is written (tx_frames
    // does not include it; rx_frames includes the diag request itself).
    const DiagLinkCounters link = diagLink();
    const int reset = (int)esp_reset_reason();

    JsonDocument o;
    beginEnvelope(o, MSG_ACK, id);
    JsonObject d = o["d"].to<JsonObject>();
    d["ok"] = true;
    d["v"] = DIAG_REPLY_VERSION;
    d["reset"] = diagResetReasonName(reset);
    d["reset_code"] = reset;
    d["uptime_s"] = (uint32_t)(esp_timer_get_time() / 1000000LL);

    JsonObject heap = d["heap"].to<JsonObject>();
    heap["free"] = (uint32_t)ESP.getFreeHeap();
    heap["min_free"] = (uint32_t)ESP.getMinFreeHeap();
    heap["largest"] = (uint32_t)ESP.getMaxAllocHeap();
    if (ESP.getPsramSize() > 0) {
        JsonObject psram = d["psram"].to<JsonObject>();
        psram["size"] = (uint32_t)ESP.getPsramSize();
        psram["free"] = (uint32_t)ESP.getFreePsram();
        psram["min_free"] = (uint32_t)ESP.getMinFreePsram();
        psram["largest"] = (uint32_t)ESP.getMaxAllocPsram();
    }

    JsonLinkSink sink{d["link"].to<JsonObject>(), JsonObject()};
    sink.err = sink.link["err"].to<JsonObject>();
    diagWriteLink(link, sink);

    JsonObject drops = d["drops"].to<JsonObject>();
    uint32_t lost = 0, dropped = 0;
    bool fmActive = false;
    if (fmAudioLostSamples(lost, fmActive)) {
        drops["fm_pcm_lost"] = lost;
        drops["fm_pcm_active"] = fmActive;
    }
    if (micPcmDroppedSamples(dropped)) drops["mic_pcm_dropped"] = dropped;
    sendEnvelope(o);
}
#endif // COMPANION_HAS_DIAG

} // namespace companion
} // namespace brucia
