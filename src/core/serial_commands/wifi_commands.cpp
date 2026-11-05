#include "wifi_commands.h"
#include "helpers.h" // Brucia: cliFromCompanionScript
#include "core/wifi/webInterface.h"
#include "core/wifi/wifi_common.h" //to return MAC addr
#include <globals.h>
#if !defined(LITE_VERSION)
#include "esp_netif.h"
#include "esp_netif_net_stack.h"
#include "modules/wifi/sniffer.h"
#include "modules/wifi/tcp_utils.h"
#include <modules/ethernet/ARPScanner.h>
// #include "modules/wifi/responder.h"
#endif
uint32_t wifiCallback(cmd *c) {
    Command cmd(c);
    Argument statusArg = cmd.getArgument("status");
    String status = statusArg.getValue();
    status.trim();

    Argument ssidArg = cmd.getArgument("ssid");
    String ssid = ssidArg.getValue();
    ssid.trim();

    Argument pwdArg = cmd.getArgument("pwd");
    String pwd = pwdArg.getValue();
    pwd.trim();

    if (status == "off") {
        wifiDisconnect();
        return true;
    } else if (status == "on") {
        if (wifiConnected) {
            serialDevice->println("Wifi already connected");
            return true;
        }
        if (wifiConnecttoKnownNet()) return true;
        wifiDisconnect();
        return _setupAP();

    } else if (status == "add" && ssid != "" && pwd != "") {
        bruceConfig.addWifiCredential(ssid, pwd);
        return true;
    } else {
        serialDevice->println(
            "Invalid status: " + status +
            "\n"
            "Possible commands: \n"
            "-> wifi off (Disconnects Wifi)\n"
            "-> wifi on  (Connects to a known Wifi network. if there's no known network, starts in AP Mode)\n"
            "-> wifi add SSID Password (adds a network to the list)"
        );
        return false;
    }
}

uint32_t webuiCallback(cmd *c) {
    Command cmd(c);

    Argument arg = cmd.getArgument("noAp");
    bool noAp = arg.isSet();

#if !defined(LITE_VERSION)
    // Brucia: the Web UI runs until ESC is pressed at the device; a companion script would hang there.
    if (cliFromCompanionScript()) {
        serialDevice->println("webui is not available in a companion script");
        return false;
    }
#endif
    // Brucia: `webui -noAp` without Wi-Fi opens the network menu at the device; in companion mode
    // nobody is there. Connect first (wifi_connect); the default AP mode needs no menu.
    if (g_companionActive && noAp && !WiFi.isConnected()) {
        serialDevice->println("webui -noAp needs Wi-Fi first in companion mode (connect with wifi_connect)");
        return false;
    }
    serialDevice->println(String("Starting Web UI ") + !noAp ? "AP" : "STA");
    serialDevice->println("Press ESC to quit");
    startWebUi(!noAp); // MEMO: will quit when check(EscPress)

    return true;
}
#if !defined(LITE_VERSION)
uint32_t scanHostsCallback(cmd *c) {
    // Brucia: the scan ends in a host menu at the device (and the scan itself stops only on ESC
    // there); in companion mode nobody is at the device and the companion loop would wait for one.
    if (g_companionActive) {
        serialDevice->println("arp opens a host menu at the device; not available in companion mode");
        return false;
    }
    esp_netif_t *esp_netinterface = esp_netif_get_handle_from_ifkey("WIFI_STA_DEF");
    if (esp_netinterface == nullptr) {
        usbConsole().println("Failed to get netif handle\nTry connecting to a network first");
        return false;
    }

    ARPScanner{esp_netinterface};

    return true;
}

uint32_t snifferCallback(cmd *c) {
    // Brucia: the sniffer runs until ESC is pressed at the device; a companion script would hang there.
    if (cliFromCompanionScript()) {
        serialDevice->println("sniffer is not available in a companion script");
        return false;
    }
    sniffer_setup();

    return true;
}

uint32_t listenTCPCallback(cmd *c) {
    if (!wifiConnected) {
        usbConsole().println("Connect to a WiFi first.");
        return false;
    }

    // Brucia: asks for the port on the on-device keyboard; in companion mode (routed CMD or a
    // companion script) nobody is at the device to type it.
    if (g_companionActive) {
        serialDevice->println("listen asks for the port at the device; not available in companion mode");
        return false;
    }
    listenTcpPort();

    return true;
}
#endif
/*
uint32_t responderCallback(cmd *c) {
    if (!wifiConnected) {
        Serial.println("Connect to a WiFi first.");
        return false;
    }

    responder();

    return true;
}
*/

void createWifiCommands(SimpleCLI *cli) {
    Command webuiCmd = cli->addCommand("webui", webuiCallback);
    webuiCmd.addFlagArg("noAp");

    Command wifiCmd = cli->addCommand("wifi", wifiCallback);
    wifiCmd.addPosArg("status");
    wifiCmd.addPosArg("ssid", "");
    wifiCmd.addPosArg("pwd", "");

#if !defined(LITE_VERSION)

    Command ScanHostsCmd = cli->addCommand("arp", scanHostsCallback);

    Command listenTCPCmd =
        cli->addCommand("listen", listenTCPCallback); // TODO: make possible to select port to open via Serial

    Command snifferCmd =
        cli->addCommand("sniffer", snifferCallback); // TODO: be able to exit from it from Serial

#endif
    // Command responderCmd = cli->addCommand("responder", responderCallback); TODO
}
