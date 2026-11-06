#include "screen_commands.h"
#include "cli_number.h" // clinum::parseU32/parseHexU32 (safe, no-throw numeric args, backlog F03)
#include "core/settings.h"
#include "core/utils.h" // time
#include <globals.h>

uint32_t brightnessCallback(cmd *c) {
    // backlight brightness adjust (range 0-255) https://docs.flipper.net/development/cli/#XQQAI
    // e.g. "led br 127"

    Command cmd(c);

    Argument arg = cmd.getArgument("value");
    String strValue = arg.getValue();
    strValue.trim();

    int value = (atoi(strValue.c_str()) * 100) / 255; // convert to 0-100 range
    value = min(max(1, value), 100);

    serialDevice->println("Settings led brightness to " + String(value) + "%");
    setBrightness(value, false);
    return true;
}

uint32_t rgbColorCallback(cmd *c) {
    // change UI color
    // e.g. "screen color rgb 255 255 255"

    Command cmd(c);

    Argument redArg = cmd.getArgument("red");
    Argument greenArg = cmd.getArgument("green");
    Argument blueArg = cmd.getArgument("blue");
    String strRed = redArg.getValue();
    String strGreen = greenArg.getValue();
    String strBlue = blueArg.getValue();
    strRed.trim();
    strGreen.trim();
    strBlue.trim();

    // Whole-token 0..255 per channel (atoi took "12abc" as 12 and text as 0).
    uint32_t r = 0, g = 0, b = 0;
    if (!clinum::parseU32(strRed.c_str(), 0, 255, r) || !clinum::parseU32(strGreen.c_str(), 0, 255, g) ||
        !clinum::parseU32(strBlue.c_str(), 0, 255, b)) {
        serialDevice->println("Invalid color: " + strRed + " " + strGreen + " " + strBlue);
        return false;
    }

    uint16_t hexColor = tft.color565((uint8_t)r, (uint8_t)g, (uint8_t)b);
    bruceConfig.priColor = hexColor; // change global var, dont save in config
    return true;
}

uint32_t hexColorCallback(cmd *c) {
    // change UI color
    // e.g. "screen color hex ff00ff"

    Command cmd(c);

    Argument arg = cmd.getArgument("value");
    String strValue = arg.getValue();
    strValue.trim();

    if (strValue.length() % 2 == 1) {
        serialDevice->println("Invalid hex value: " + strValue);
        return false;
    }

    // std::stoul threw on non-hex text ("screen color hex zz") and an over-wide value, aborting the
    // device (backlog F03; no catch on the companion routing path). Whole token, 24-bit max.
    uint32_t value = 0;
    if (!clinum::parseHexU32(strValue.c_str(), 0xFFFFFF, value)) {
        serialDevice->println("Invalid color: " + strValue);
        return false;
    }

    uint16_t r = (value >> 8) & 0xF800;
    uint16_t g = (value >> 5) & 0x07E0;
    uint16_t b = (value >> 3) & 0x001F;

    uint16_t hexColor = (r | g | b);
    bruceConfig.priColor = hexColor; // change global var, dont save in config
    return true;
}

uint32_t clockCallback(cmd *c) {
#if defined(HAS_RTC)
    updateTimeStr(_rtc.getTimeStruct());
    serialDevice->printf("\nCurrent time: %s", timeStr);
#else
    updateTimeStr(rtc.getTimeStruct());
    serialDevice->printf("\nCurrent time: %s", timeStr);
#endif
    return true;
}

void createScreenCommands(SimpleCLI *cli) {
    Command clockCmd = cli->addCommand("clock", clockCallback);

    Command screenCmd = cli->addCompositeCmd("screen");

    Command brightCmd = screenCmd.addCommand("br/ight/ness", brightnessCallback);
    brightCmd.addPosArg("value");

    Command colorCmd = screenCmd.addCompositeCmd("color");
    Command rgbColorCmd = colorCmd.addCommand("rgb", rgbColorCallback);
    rgbColorCmd.addPosArg("red");
    rgbColorCmd.addPosArg("green");
    rgbColorCmd.addPosArg("blue");
    Command hexColorCmd = colorCmd.addCommand("hex", hexColorCallback);
    hexColorCmd.addPosArg("value");
}
