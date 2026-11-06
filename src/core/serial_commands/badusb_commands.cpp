#include "badusb_commands.h"
#include "core/sd_functions.h"
#include "helpers.h"
#include "modules/badusb_ble/ducky_typer.h"
#include <globals.h> // g_companionActive

// Brucia: in companion mode the device's USB port IS the framed app link. USB-HID setup
// (ducky_startKb) reconfigures that port and waits without a bound for a HID host
// (`while (!tud_mounted())`), so a routed `badusb run_from_*` could hang the link with every
// frame unanswered; run_from_buffer also has no console input there. Refuse it with a reason;
// standalone use is unchanged (the app runs Ducky scripts over BLE with ble_badble).
#ifndef LITE_VERSION
static bool badusbRefusedInCompanion() {
    if (!g_companionActive) return false;
    serialDevice->println("badusb is not available in companion mode (the USB port is the app link; use BadBLE)");
    return true;
}
#endif

uint32_t badusbFileCallback(cmd *c) {
#ifndef LITE_VERSION
    // badusb run_from_file HelloWorld.txt
    if (badusbRefusedInCompanion()) return false;

    Command cmd(c);

    Argument arg = cmd.getArgument("filepath");
    String filepath = arg.getValue();
    filepath.trim();

    if (filepath.indexOf(".txt") == -1) {
        serialDevice->println("Invalid filename");
        return false;
    }
    if (!filepath.startsWith("/")) filepath = "/" + filepath;

    FS *fs;
    if (!getFsStorage(fs)) return false;

    if (!(*fs).exists(filepath)) {
        serialDevice->println("File does not exist");
        return false;
    }

#ifdef USB_as_HID
    ducky_startKb(hid_usb, false);
    key_input(*fs, filepath, hid_usb);
    delete hid_usb;
    hid_usb = nullptr;

    // TODO: need to reinit serial when finished
    // Kb.end();
    // USB.~ESPUSB(); // Explicit call to destructor
    // serialDevice->begin(115200);

    return true;
#else
    return false;
#endif
#else
    return false;
#endif
}

uint32_t badusbBufferCallback(cmd *c) {
#ifndef LITE_VERSION
    if (badusbRefusedInCompanion()) return false;
    if (!(_setupPsramFs())) return false;

    char *txt = _readFileFromSerial();
    String tmpfilepath = "/tmpramfile"; // TODO: Change to use char *txt directly
    File f = PSRamFS.open(tmpfilepath, FILE_WRITE);
    if (!f) return false;

    f.write((const uint8_t *)txt, strlen(txt));
    f.close();
    free(txt);

#ifdef USB_as_HID
    ducky_startKb(hid_usb, false);
    key_input(PSRamFS, tmpfilepath, hid_usb);
    delete hid_usb;
    hid_usb = nullptr;

    PSRamFS.remove(tmpfilepath);
    return true;
#else
    PSRamFS.remove(tmpfilepath);
    return false;
#endif
#else
    return false;
#endif
}

void createBadUsbCommands(SimpleCLI *cli) {
#ifndef LITE_VERSION
    Command badusbCmd = cli->addCompositeCmd("bu,badusb");

    Command fileCmd = badusbCmd.addCommand("run_from_file", badusbFileCallback);
    fileCmd.addPosArg("filepath");

    Command bufferCmd = badusbCmd.addCommand("run_from_buffer", badusbBufferCallback);
#endif
}
