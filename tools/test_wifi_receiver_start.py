"""Exercise the production receiver setup with each driver call failing in turn."""
import subprocess
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class ReceiverStartTest(unittest.TestCase):
    def test_failures_leave_no_callback_and_do_not_report_ready(self):
        source = (ROOT / "src/companion/wifi.cpp").read_text()
        function = source[source.index("static bool receiverOwnsRadio"):source.index("bool staStart()")]
        fixture = """
#include <cassert>
#include <cstdint>
constexpr int WIFI_OFF=0, WIFI_STA=1, WIFI_STORAGE_RAM=1, ESP_OK=0, WIFI_SECOND_CHAN_NONE=0;
using wifi_promiscuous_cb_t = void(*)();
struct wifi_promiscuous_filter_t { uint32_t filter_mask; };
int step, fail;
bool listening;
wifi_promiscuous_cb_t installed;
int result() { return ++step == fail ? -1 : 0; }
void ensureWifiPlatform() {}
struct {
    int current=0;
    int getMode() { return current; }
    bool mode(int m) { if (m && result()) return false; current=m; return true; }
} WiFi;
int esp_wifi_set_storage(int) { return result(); }
int esp_wifi_set_promiscuous_filter(wifi_promiscuous_filter_t*) { return result(); }
int esp_wifi_set_promiscuous_rx_cb(wifi_promiscuous_cb_t cb) {
    if (cb && result()) return -1;
    installed=cb; return 0;
}
int esp_wifi_set_channel(int,int) { return result(); }
int esp_wifi_set_promiscuous(bool on) {
    if (on && result()) return -1;
    listening=on; return 0;
}
"""
        main = """
void cb() {}
int main() {
    for (fail=1; fail<=7; ++fail) {
        step=0;
        bool ready=startReceiver(3, cb, 1);
        assert(ready==(fail==7));
        assert(listening==ready && installed==(ready ? cb : nullptr));
        if (ready) releaseReceiver();
        assert(WiFi.getMode()==WIFI_OFF);
    }
    {   // A driver already active before SUB ON belongs to another caller.
        WiFi.current=WIFI_STA; fail=0; step=0;
        assert(startReceiver(3, cb, 1)); releaseReceiver();
        assert(WiFi.getMode()==WIFI_STA);
    }
}
"""
        with tempfile.TemporaryDirectory() as tmp:
            binary = Path(tmp) / "check"
            subprocess.run(["c++", "-std=c++17", "-x", "c++", "-", "-o", str(binary)],
                           input=fixture + function + main, text=True, check=True)
            subprocess.run([str(binary)], check=True)


if __name__ == "__main__":
    unittest.main()
