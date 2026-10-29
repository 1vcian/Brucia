#include "brucia_flame.h"
#include "core/main_menu.h"
#include <globals.h>

#include "core/bus_HAL.h"
#include "core/powerSave.h"
#include "core/ram_profile.h"
#include "core/serial_commands/cli.h"
#include "core/utils.h"
#include "current_year.h"
#include "esp32-hal-psram.h"
#include "esp_heap_caps.h"
#include "esp_task_wdt.h"
#include "esp_wifi.h"
#include <functional>
#include <string>
#include <vector>
io_expander ioExpander;
BruceConfig bruceConfig;
BruceConfigPins bruceConfigPins;

SerialCli serialCli;
USBSerial USBserial;
SerialDevice *serialDevice = &USBserial;

StartupApp startupApp;
String startupAppJSInterpreterFile = "";

MainMenu mainMenu;
SPIClass sdcardSPI;
#ifdef USE_HSPI_PORT
#ifndef VSPI
#define VSPI FSPI
#endif
SPIClass AUX_SPI(VSPI);
#else
SPIClass AUX_SPI(HSPI);
#endif

// Navigation Variables
volatile bool NextPress = false;
volatile bool PrevPress = false;
volatile bool UpPress = false;
volatile bool DownPress = false;
volatile bool SelPress = false;
volatile bool EscPress = false;
volatile bool AnyKeyPress = false;
volatile bool NextPagePress = false;
volatile bool PrevPagePress = false;
volatile bool LongPress = false;
volatile bool SerialCmdPress = false;
volatile int forceMenuOption = -1;
volatile uint8_t menuOptionType = 0;
String menuOptionLabel = "";
#ifdef HAS_ENCODER_LED
volatile int EncoderLedChange = 0;
#endif

TouchPoint touchPoint;

keyStroke KeyStroke;

volatile int32_t RotaryNetSteps = 0;

#ifdef HAS_ENCODER
// Default no-op: boards that define HAS_ENCODER but don't implement
// pollEncoder() (shouldn't happen, but keeps the linker happy either way).
void __attribute__((weak)) pollEncoder(void) {}

// Dedicated, high-priority, tight-cadence task that does nothing but sample
// the rotary encoder A/B lines -- mirrors the Flipper port's input_srv,
// which runs encoder_poll() on its own thread every 4ms, decoupled from
// GUI/app work so the raw quadrature read is never delayed by rendering
// or by whether the previous input event has been consumed yet. Only
// exists on HAS_ENCODER boards; other boards pay zero cost for this.
static void taskEncoderPoll(void *parameter) {
    while (true) {
        pollEncoder();
        vTaskDelay(pdMS_TO_TICKS(4));
    }
}
#endif

TaskHandle_t xHandle;
void __attribute__((weak)) taskInputHandler(void *parameter) {
    auto timer = millis();
    while (true) {
        checkPowerSaveTime();
        // Sometimes this task run 2 or more times before looptask,
        // and navigation gets stuck, the idea here is run the input detection
        // if AnyKeyPress is false, or rerun if it was not renewed within 75ms (arbitrary)
        // because AnyKeyPress will be true if didn´t passed through a check(bool var)
        if (!AnyKeyPress || millis() - timer > 75) {
            NextPress = false;
            PrevPress = false;
            UpPress = false;
            DownPress = false;
            SelPress = false;
            EscPress = false;
            AnyKeyPress = false;
            SerialCmdPress = false;
            NextPagePress = false;
            PrevPagePress = false;
            touchPoint.pressed = false;
            touchPoint.Clear();
            checkAndRecoverSysI2CBus();
#ifndef USE_TFT_eSPI_TOUCH
            InputHandler();
#endif
            timer = millis();
        }
        vTaskDelay(pdMS_TO_TICKS(10));
    }
}
// Public Globals Variables
unsigned long previousMillis = millis();
int prog_handler; // 0 - Flash, 1 - LittleFS, 3 - Download
String cachedPassword = "";
int8_t interpreter_state = -1;
bool sdcardMounted = false;
bool gpsConnected = false;

// wifi globals
// TODO put in a namespace
bool wifiConnected = false;
volatile bool g_companionActive = false; // set by companionModeUSB; mutes module Serial debug prints
bool isWebUIActive = false;
String wifiIP;

bool BLEConnected = false;
bool returnToMenu;
bool isSleeping = false;
bool isScreenOff = false;
bool dimmer = false;
char timeStr[16];
time_t localTime;
struct tm *timeInfo;
#if defined(HAS_RTC)
#if defined(HAS_RTC_PCF85063A)
pcf85063_RTC _rtc;
#else
cplus_RTC _rtc;
#endif
RTC_TimeTypeDef _time;
RTC_DateTypeDef _date;
bool clock_set = true;
#else
ESP32Time rtc;
bool clock_set = false;
#endif

std::vector<Option> options;
// Protected global variables
#if defined(HAS_SCREEN)
tft_logger tft = tft_logger(); // Invoke custom library
tft_sprite sprite = tft_sprite(&tft);
tft_sprite draw = tft_sprite(&tft);
volatile int tftWidth = TFT_HEIGHT;
#ifdef HAS_TOUCH
volatile int tftHeight =
    TFT_WIDTH - 20; // 20px to draw the TouchFooter(), were the btns are being read in touch devices.
#else
volatile int tftHeight = TFT_WIDTH;
#endif
#else
tft_logger tft;
SerialDisplayClass &sprite = tft;
SerialDisplayClass &draw = tft;
volatile int tftWidth = VECTOR_DISPLAY_DEFAULT_HEIGHT;
volatile int tftHeight = VECTOR_DISPLAY_DEFAULT_WIDTH;
#endif

#include "core/bus_HAL.h"
#include "core/display.h"
#include "core/led_control.h"
#include "core/mykeyboard.h"
#include "core/sd_functions.h"
#include "core/serialcmds.h"
#include "core/settings.h"
#include "core/wifi/webInterface.h"
#include "core/wifi/wifi_common.h"
#include "companion/companion.h"                  // companionModeUSB / g_enterCompanionRequested
#include "modules/bjs_interpreter/interpreter.h" // for JavaScript interpreter
#include "modules/others/audio.h"                // for playAudioFile
#include "modules/rf/rf_utils.h"                 // for initCC1101once
#include <Wire.h>

/*********************************************************************
 **  Function: begin_storage
 **  Config LittleFS and SD storage
 *********************************************************************/
void begin_storage() {
    if (!setupLittleFS()) {
        LittleFS.format();
        setupLittleFS();
    }
    RAM_LOG("after LittleFS");
    bool checkFS = setupSdCard();
    bruceConfig.fromFile(checkFS);
    bruceConfigPins.fromFile(checkFS);
}

/*********************************************************************
 **  Function: _setup_gpio()
 **  Sets up a weak (empty) function to be replaced by /ports/* /interface.h
 *********************************************************************/
void _setup_gpio() __attribute__((weak));
void _setup_gpio() {}

/*********************************************************************
 **  Function: _post_setup_gpio()
 **  Sets up a weak (empty) function to be replaced by /ports/* /interface.h
 *********************************************************************/
void _post_setup_gpio() __attribute__((weak));
void _post_setup_gpio() {}

/*********************************************************************
 **  Function: _pre_storage_gpio()
 **  Sets up a weak (empty) function for board fixes that must run
 **  after the first TFT access and before storage is mounted.
 *********************************************************************/
void _pre_storage_gpio() __attribute__((weak));
void _pre_storage_gpio() {}

/*********************************************************************
 **  Function: setup_gpio
 **  Setup GPIO pins
 *********************************************************************/
void setup_gpio() {

    // init setup from /ports/*/interface.h
    _setup_gpio();

    // Smoochiee v2 uses a AW9325 tro control GPS, MIC, Vibro and CC1101 RX/TX powerlines
    ioExpander.init(IO_EXPANDER_ADDRESS, &Wire);

    initCC1101once(acquireSPIBus(
        bruceConfigPins.CC1101_bus.sck, bruceConfigPins.CC1101_bus.miso, bruceConfigPins.CC1101_bus.mosi
    ));
    // acquireSPIBus() returns nullptr when these pins have no hardware controller left (e.g.
    // ARDUINO_M5STICK_C_PLUS and others that don't share SPI with the display/SD/aux bus);
    // initCC1101once(NULL) lets the driver fall back to managing the default SPI object itself.
}

/*********************************************************************
 **  Function: begin_tft
 **  Config tft
 *********************************************************************/
void begin_tft() {
    tft.setRotation(bruceConfigPins.rotation); // sometimes it misses the first command
    tft.invertDisplay(bruceConfig.colorInverted);
    tft.setRotation(bruceConfigPins.rotation);
    tftWidth = tft.width();
#ifdef HAS_TOUCH
    tftHeight = tft.height() - 20;
#else
    tftHeight = tft.height();
#endif
    resetTftDisplay();
    setBrightness(bruceConfig.bright, false);
}

/*********************************************************************
 **  Function: boot_screen
 **  Draw boot screen
 *********************************************************************/
// XBM bit test (XBM is LSB-first: bit 0 is the leftmost pixel of the byte).
static inline bool bruciaXbmBit(const uint8_t *bmp, int w, int x, int y) {
    int bpr = (w + 7) / 8;
    return (bmp[y * bpr + (x >> 3)] >> (x & 7)) & 1;
}

// Linear blend of two RGB565 colours (t in 0..1).
static uint16_t bruciaBlend565(uint16_t a, uint16_t b, float t) {
    int ar = (a >> 11) & 0x1F, ag = (a >> 5) & 0x3F, ab = a & 0x1F;
    int br = (b >> 11) & 0x1F, bg = (b >> 5) & 0x3F, bb = b & 0x1F;
    int r = ar + (int)((br - ar) * t);
    int g = ag + (int)((bg - ag) * t);
    int bl = ab + (int)((bb - ab) * t);
    return (uint16_t)((r << 11) | (g << 5) | bl);
}

// Composite the two-layer flame into one RGB565 buffer and block-push it in a single
// transfer. Drawing the whole flame at once (instead of pixel-by-pixel drawXBitmap) avoids
// the visible scan/flash, so both layers always show and the animation stays fluid. `heat`
// pulses the inner colour and `bob` licks the inner flame up/down a little.
// Max animated flame size = 1.25x the base. The flame is rendered scaled inside this fixed-size
// buffer and pushed at a fixed position, so it only grows/shrinks (never below the original size)
// and never bobs - the old vertical lick revealed the background between layers ("white").
#define BRUCIA_FLAME_MAX_W ((BRUCIA_FLAME_OUTER_W * 5) / 4)
#define BRUCIA_FLAME_MAX_H ((BRUCIA_FLAME_OUTER_H * 5) / 4)

static void drawBruciaFlameAnim(int x, int y, float scale) {
    // Lazily heap-allocate the pixel buffer on first use so it does NOT sit in static .bss.
    static uint16_t *buf = nullptr;
    if (!buf) buf = (uint16_t *)malloc(BRUCIA_FLAME_MAX_W * BRUCIA_FLAME_MAX_H * sizeof(uint16_t));
    if (!buf) return;
    if (scale < 1.0f) scale = 1.0f;       // never smaller than the original
    if (scale > 1.25f) scale = 1.25f;
    uint16_t bg = bruceConfig.bgColor;
    uint16_t outerCol = bruceConfig.priColor;
    uint16_t innerCol = bruceConfig.secColor;
    int sw = (int)(BRUCIA_FLAME_OUTER_W * scale + 0.5f);
    int sh = (int)(BRUCIA_FLAME_OUTER_H * scale + 0.5f);
    if (sw > BRUCIA_FLAME_MAX_W) sw = BRUCIA_FLAME_MAX_W;
    if (sh > BRUCIA_FLAME_MAX_H) sh = BRUCIA_FLAME_MAX_H;
    int offX = (BRUCIA_FLAME_MAX_W - sw) / 2;
    int offY = (BRUCIA_FLAME_MAX_H - sh) / 2;
    for (int by = 0; by < BRUCIA_FLAME_MAX_H; by++) {
        for (int bx = 0; bx < BRUCIA_FLAME_MAX_W; bx++) {
            uint16_t c = bg;
            int rx = bx - offX, ry = by - offY;
            if (rx >= 0 && rx < sw && ry >= 0 && ry < sh) {
                int sx = rx * BRUCIA_FLAME_OUTER_W / sw; // nearest-neighbour scale
                int sy = ry * BRUCIA_FLAME_OUTER_H / sh;
                if (bruciaXbmBit(brucia_flame_outer, BRUCIA_FLAME_OUTER_W, sx, sy)) c = outerCol;
                if (bruciaXbmBit(brucia_flame_inner, BRUCIA_FLAME_INNER_W, sx, sy)) c = innerCol;
            }
            buf[by * BRUCIA_FLAME_MAX_W + bx] = c;
        }
    }
    bool oldSwap = tft.getSwapBytes();
    tft.setSwapBytes(true); // buf holds plain RGB565 values, same as drawXBitmap/fillScreen use
    tft.pushImage(x, y, BRUCIA_FLAME_MAX_W, BRUCIA_FLAME_MAX_H, buf);
    tft.setSwapBytes(oldSwap);
}

// Brucia boot embers: a little particle system of sparks that rise off the flame tip and fade from
// the bright (secondary) shade through the ember (primary) shade to the background - the "cosa figa"
// layer on top of the breathing flame. Confined to the empty band ABOVE the flame so it never
// collides with the flame buffer or the title. Each spark erases its own previous pixel, so there
// are no trails. Cheap enough for the ~45ms boot frame.
#define BRUCIA_EMBER_COUNT 20
struct BruciaEmber {
    float x, y, vy, drift;
    int life, maxlife;
    uint8_t size;
    bool alive;
};
static BruciaEmber g_embers[BRUCIA_EMBER_COUNT];
static bool g_embersInit = false;

static void bruciaEmbersStep(int cx, int baseY, int topY) {
    uint16_t bg = bruceConfig.bgColor;
    uint16_t cool = bruceConfig.priColor; // ember (orange)
    uint16_t hot = bruceConfig.secColor;  // bright (amber/yellow)
    if (!g_embersInit) {
        for (int i = 0; i < BRUCIA_EMBER_COUNT; i++) g_embers[i].alive = false;
        g_embersInit = true;
    }
    for (int i = 0; i < BRUCIA_EMBER_COUNT; i++) {
        BruciaEmber &e = g_embers[i];
        if (e.alive) tft.fillCircle((int)e.x, (int)e.y, e.size, bg); // erase old
        if (!e.alive || e.life <= 0 || e.y < topY) {
            // (Re)spawn at the flame tip. Stagger so they do not all pop at once.
            if (!e.alive && (random(0, 100) < 70)) { e.alive = false; continue; }
            e.x = cx + random(-16, 17);
            e.y = baseY + random(0, 6);
            e.vy = 0.7f + random(0, 120) / 100.0f;
            e.drift = (random(-8, 9)) / 10.0f;
            e.maxlife = 22 + random(0, 26);
            e.life = e.maxlife;
            e.size = (random(0, 100) < 65) ? 1 : 2;
            e.alive = true;
        } else {
            e.y -= e.vy;
            e.x += e.drift;
            e.life--;
        }
        float f = (float)e.life / (float)e.maxlife; // 1 fresh -> 0 spent
        // Fresh = hot; cools to the ember shade; fades to background as it dies.
        uint16_t core = bruciaBlend565(cool, hot, f);
        uint16_t c = bruciaBlend565(bg, core, f < 0.3f ? f / 0.3f : 1.0f);
        tft.fillCircle((int)e.x, (int)e.y, e.size, c);
    }
}

// Brucia flame (from the SVG, two layers): outer fills its rect in the primary shade,
// inner overlays transparently in the secondary shade.
void drawBruciaFlame(int x, int y) {
    tft.drawXBitmap(
        x, y, brucia_flame_outer, BRUCIA_FLAME_OUTER_W, BRUCIA_FLAME_OUTER_H, bruceConfig.priColor,
        bruceConfig.bgColor
    );
    tft.drawXBitmap(
        x, y, brucia_flame_inner, BRUCIA_FLAME_INNER_W, BRUCIA_FLAME_INNER_H, bruceConfig.secColor
    );
}

void boot_screen() {
    tft.drawPixel(0, 0, bruceConfig.bgColor);
    int cx = tftWidth / 2;
    drawBruciaFlame(cx - BRUCIA_FLAME_OUTER_W / 2, 2);
    tft.setTextColor(bruceConfig.priColor, bruceConfig.bgColor);
    tft.setTextSize(FM);
    tft.drawCentreString("Brucia", cx, BRUCIA_FLAME_OUTER_H + 6, 1);
    tft.setTextSize(FP);
    tft.drawCentreString(BRUCE_VERSION, cx, BRUCIA_FLAME_OUTER_H + 26, 1);
    tft.setTextSize(FM);
    tft.drawCentreString(
        "THE PHONE IS THE BRAIN", tftWidth / 2, tftHeight + 2, 1
    ); // will draw outside the screen on non touch devices
}

/*********************************************************************
 **  Function: boot_screen_anim
 **  Draw boot screen
 *********************************************************************/
void boot_screen_anim() {
    boot_screen();
    int i = millis();
    // checks for boot.jpg in SD and LittleFS for customization
    int boot_img = 0;
    bool drawn = false;
    bool animCleared = false;
    if (sdcardMounted) {
        if (SD.exists("/boot.jpg")) boot_img = 1;
        else if (SD.exists("/boot.gif")) boot_img = 3;
    }
    if (boot_img == 0 && LittleFS.exists("/boot.jpg")) boot_img = 2;
    else if (boot_img == 0 && LittleFS.exists("/boot.gif")) boot_img = 4;
    if (bruceConfig.theme.boot_img) boot_img = 5; // override others

    tft.drawPixel(0, 0, 0);       // Forces back communication with TFT, to avoid ghosting
                                  // Start image loop
    while (millis() < i + 7000) { // boot image lasts for 5 secs
        if ((millis() - i > 2000) && !drawn) {
            tft.fillRect(0, 45, tftWidth, tftHeight - 45, bruceConfig.bgColor);
            if (boot_img > 0 && !drawn) {
                tft.fillScreen(bruceConfig.bgColor);
                if (boot_img == 5) {
                    drawImg(
                        *bruceConfig.themeFS(),
                        bruceConfig.getThemeItemImg(bruceConfig.theme.paths.boot_img),
                        0,
                        0,
                        true,
                        3600
                    );
                    Serial.println("Image from SD theme");
                } else if (boot_img == 1) {
                    drawImg(SD, "/boot.jpg", 0, 0, true);
                    Serial.println("Image from SD");
                } else if (boot_img == 2) {
                    drawImg(LittleFS, "/boot.jpg", 0, 0, true);
                    Serial.println("Image from LittleFS");
                } else if (boot_img == 3) {
                    drawImg(SD, "/boot.gif", 0, 0, true, 3600);
                    Serial.println("Image from SD");
                } else if (boot_img == 4) {
                    drawImg(LittleFS, "/boot.gif", 0, 0, true, 3600);
                    Serial.println("Image from LittleFS");
                }
                tft.drawPixel(0, 0, 0); // Forces back communication with TFT, to avoid ghosting
            }
            drawn = true;
        }
#if !defined(LITE_VERSION)
        // Brucia: animated SVG flame (bobs/flickers using the two theme shades),
        // replacing the old Bruce logo animation.
        if (!boot_img && (millis() - i > 2000)) {
            uint32_t t = millis() - i;
            // Center the fixed max-size buffer around where the base flame sat, so growth is centered.
            int fx = tftWidth / 2 - BRUCIA_FLAME_MAX_W / 2;
            int fy = tftHeight / 2 - 48 - (BRUCIA_FLAME_MAX_H - BRUCIA_FLAME_OUTER_H) / 2;
            if (!animCleared) {
                // Clear once and draw the static title/version; the flame itself is pushed as a
                // whole buffer every frame below, so it never flashes and both layers stay lit.
                tft.fillScreen(bruceConfig.bgColor);
                tft.setTextColor(bruceConfig.priColor, bruceConfig.bgColor);
                tft.setTextSize(FM);
                tft.drawCentreString("Brucia", tftWidth / 2, tftHeight / 2 + 30, 1);
                tft.setTextSize(FP);
                tft.drawCentreString(BRUCE_VERSION, tftWidth / 2, tftHeight / 2 + 50, 1);
                animCleared = true;
            }
            // Gentle breathing scale: 1.0 (original) up to ~1.22 and back, never below original.
            float scale = 1.0f + 0.22f * (0.5f - 0.5f * cosf(t / 380.0f));
            drawBruciaFlameAnim(fx, fy, scale);
            // Sparks rising off the flame tip (in the empty band above it) - the "figo" layer.
            bruciaEmbersStep(tftWidth / 2, fy, fy - 72 > 2 ? fy - 72 : 2);
            // Flickering glow on the title, like firelight.
            float flick = 0.25f + 0.25f * (0.5f - 0.5f * cosf(t / 90.0f)) + (random(0, 10) / 100.0f);
            if (flick > 1.0f) flick = 1.0f;
            tft.setTextColor(bruciaBlend565(bruceConfig.priColor, bruceConfig.secColor, flick), bruceConfig.bgColor);
            tft.setTextSize(FM);
            tft.drawCentreString("Brucia", tftWidth / 2, tftHeight / 2 + 30, 1);
            delay(45);
        }
#endif
        if (check(AnyKeyPress)) // If any key or M5 key is pressed, it'll jump the boot screen
        {
            tft.fillScreen(bruceConfig.bgColor);
            delay(10);
            return;
        }
    }

    // Clear splashscreen
    tft.fillScreen(bruceConfig.bgColor);
}

/*********************************************************************
 **  Function: init_clock
 **  Clock initialisation for propper display in menu
 *********************************************************************/
void init_clock() {
#if defined(HAS_RTC)
    _rtc.begin();
#if defined(HAS_RTC_BM8563)
    _rtc.GetBm8563Time();
#endif
#if defined(HAS_RTC_PCF85063A)
    _rtc.GetPcf85063Time();
#endif
    _rtc.GetTime(&_time);
    _rtc.GetDate(&_date);

    struct tm timeinfo = {};
    timeinfo.tm_sec = _time.Seconds;
    timeinfo.tm_min = _time.Minutes;
    timeinfo.tm_hour = _time.Hours;
    timeinfo.tm_mday = _date.Date;
    timeinfo.tm_mon = _date.Month > 0 ? _date.Month - 1 : 0;
    timeinfo.tm_year = _date.Year >= 1900 ? _date.Year - 1900 : 0;
    time_t epoch = mktime(&timeinfo);
    struct timeval tv = {.tv_sec = epoch};
    settimeofday(&tv, nullptr);
#else
    struct tm timeinfo = {};
    timeinfo.tm_year = CURRENT_YEAR - 1900;
    timeinfo.tm_mon = 0x05;
    timeinfo.tm_mday = 0x14;
    time_t epoch = mktime(&timeinfo);
    rtc.setTime(epoch);
    clock_set = true;
    struct timeval tv = {.tv_sec = epoch};
    settimeofday(&tv, nullptr);
    restorePersistedClock(); // override the default with the last-saved time (NVS) + start periodic save
#endif
}

/*********************************************************************
 **  Function: init_led
 **  Led initialisation
 *********************************************************************/
void init_led() {
#ifdef HAS_RGB_LED
    beginLed();
#endif
}

/*********************************************************************
 **  Function: startup_sound
 **  Play sound or tone depending on device hardware
 *********************************************************************/
void startup_sound() {
    if (bruceConfig.soundEnabled == 0) return; // if sound is disabled, do not play sound
#if !defined(LITE_VERSION)
#if defined(BUZZ_PIN)
    // Bip M5 just because it can. Does not bip if splashscreen is bypassed
    _tone(5000, 50);
    delay(200);
    _tone(5000, 50);
    /*  2fix: menu infinite loop */
#elif defined(HAS_NS4168_SPKR)
    // play a boot sound
    if (bruceConfig.theme.boot_sound) {
        playAudioFile(bruceConfig.themeFS(), bruceConfig.getThemeItemImg(bruceConfig.theme.paths.boot_sound));
    } else if (SD.exists("/boot.wav")) {
        playAudioFile(&SD, "/boot.wav");
    } else if (LittleFS.exists("/boot.wav")) {
        playAudioFile(&LittleFS, "/boot.wav");
    }
#endif
#endif
}

/*********************************************************************
 **  Function: setup
 **  Where the devices are started and variables set
 *********************************************************************/
void setup() {
    Serial.setRxBufferSize(
        SAFE_STACK_BUFFER_SIZE / 4
    ); // Must be invoked before Serial.begin(). Default is 256 chars
    Serial.begin(115200);

    log_d("Total heap: %d", ESP.getHeapSize());
    log_d("Free heap: %d", ESP.getFreeHeap());
    bool psramStarted = psramInit();
    // Printed unconditionally (boards force CORE_DEBUG_LEVEL=1, so log_d is invisible).
    // If PSRAM fails to init, a PSRAM board effectively becomes a no-PSRAM board and
    // Wi-Fi + BLE cannot coexist. This one boot line makes that failure mode observable.
    Serial.printf(
        "[PSRAM] init=%d found=%d total=%u free=%u | internal free=%u largest=%u\n",
        psramStarted,
        psramFound(),
        (unsigned)ESP.getPsramSize(),
        (unsigned)ESP.getFreePsram(),
        (unsigned)heap_caps_get_free_size(MALLOC_CAP_INTERNAL),
        (unsigned)heap_caps_get_largest_free_block(MALLOC_CAP_INTERNAL)
    );
    Serial.flush();

    RAM_LOG("setup-start");

    // declare variables
    prog_handler = 0;
    sdcardMounted = false;
    wifiConnected = false;
    BLEConnected = false;
    bruceConfig.bright = 100; // theres is no value yet
    bruceConfigPins.rotation = ROTATION;
    setup_gpio();
#if defined(HAS_SCREEN)
    tft.init();
    tft.setRotation(bruceConfigPins.rotation);
    tft.fillScreen(TFT_BLACK);
    // bruceConfig is not read yet.. just to show something on screen due to long boot time
    tft.setTextColor(TFT_PURPLE, TFT_BLACK);
    tft.drawCentreString("Booting", tft.width() / 2, tft.height() / 2, 1);
    RAM_LOG("first-display-elem"); // first element drawn on screen
#else
    tft.begin();
#endif
    _pre_storage_gpio();
    begin_storage();
    RAM_LOG("after-storage"); // bruceConfig/bruceConfigPins loaded from FS
    begin_tft();
    init_clock();
    init_led();
    RAM_LOG("after-tft-clock-led");

    options.reserve(20); // preallocate some options space to avoid fragmentation

    RAM_LOG("before-wifi-init"); // largest contiguous internal block here gates Wi-Fi/BLE

    // Set WiFi country to avoid warnings and ensure max power
    const wifi_country_t country = {
        .cc = "US",
        .schan = 1,
        .nchan = 14,
#ifdef CONFIG_ESP_PHY_MAX_TX_POWER
        .max_tx_power = CONFIG_ESP_PHY_MAX_TX_POWER, // 20
#endif
        .policy = WIFI_COUNTRY_POLICY_MANUAL
    };

    esp_wifi_set_max_tx_power(80); // 80 translates to 20dBm
    esp_wifi_set_country(&country);

    // Some GPIO Settings (such as CYD's brightness control must be set after tft and sdcard)
    _post_setup_gpio();
    // Some board interfaces initialize or reset the backlight in post-setup,
    // so re-apply the stored brightness after that stage completes.
    setBrightness(bruceConfig.bright, false);
    // end of post gpio begin

    // #ifndef USE_TFT_eSPI_TOUCH
    // This task keeps running all the time, will never stop
    xTaskCreate(
        taskInputHandler,              // Task function
        "InputHandler",                // Task Name
        INPUT_HANDLER_TASK_STACK_SIZE, // Stack size
        NULL,                          // Task parameters
        2,                             // Task priority (0 to 3), loopTask has priority 2.
        &xHandle                       // Task handle (not used)
    );
#ifdef HAS_ENCODER
    // Dedicated encoder sampling task, higher priority than loopTask so a
    // busy render/redraw pass can never delay reading the A/B lines.
    // Only created on boards with a rotary encoder.
    xTaskCreate(
        taskEncoderPoll, // Task function
        "EncoderPoll",   // Task Name
        2048,            // Stack size
        NULL,            // Task parameters
        3,               // Task priority (0 to 3), higher than loopTask's 2
        NULL             // Task handle (not used)
    );
#endif
    // #endif
#if defined(HAS_SCREEN)
    bruceConfig.openThemeFile(bruceConfig.themeFS(), bruceConfig.themePath, false);
    if (!bruceConfig.instantBoot) {
        boot_screen_anim();
        startup_sound();
    }
    if (bruceConfig.wifiAtStartup) {
        log_i("Loading Wifi at Startup");
        xTaskCreate(
            wifiConnectTask,   // Task function
            "wifiConnectTask", // Task Name
            4096,              // Stack size
            NULL,              // Task parameters
            2,                 // Task priority (0 to 3), loopTask has priority 2.
            NULL               // Task handle (not used)
        );
    }
#endif
    //  start a task to handle serial commands while the webui is running
    startSerialCommandsHandlerTask(true);

    wakeUpScreen();
    if (bruceConfig.startupApp != "" && !startupApp.startApp(bruceConfig.startupApp)) {
        bruceConfig.setStartupApp("");
    }

    RAM_LOG("setup-end");
}

/**********************************************************************
 **  Function: loop
 **  Main loop
 **********************************************************************/
#if defined(HAS_SCREEN)
void loop() {
    // Brucia: the `companion` CLI command can't enter companion mode itself (it runs in
    // the serialcmds task, which companionModeUSB() suspends). It raises this flag; we
    // enter companion mode here, from the main task, where suspending the CLI task is safe.
    if (g_enterCompanionRequested) {
        g_enterCompanionRequested = false;
        companionModeUSB(true); // headless: app is already on serial, skip the QR screen
    }
#if !defined(LITE_VERSION) && !defined(DISABLE_INTERPRETER)
    if (interpreter_state > 0) {
        vTaskDelay(pdMS_TO_TICKS(10));
        interpreter_state = 2;
        Serial.println("Entering interpreter...");
        while (interpreter_state > 0) { vTaskDelay(pdMS_TO_TICKS(500)); }
        if (interpreter_state == 0) {
            Serial.println("Interpreter put to background.");
        } else {
            Serial.println("Exiting interpreter...");
        }
        if (interpreter_state == -1) { interpreterTaskHandler = NULL; }
        previousMillis = millis(); // ensure that will not dim screen when get back to menu
    }
#endif
    tft.fillScreen(bruceConfig.bgColor);

#if defined(ENABLE_RAM_LOGGING)
    static bool ramLoggedFirstMenu = false;
    if (!ramLoggedFirstMenu) {
        RAM_LOG("first-mainMenu");
        ramLoggedFirstMenu = true;
    }
#endif

    mainMenu.begin();
    delay(1);
}
#else

void loop() {
    if (g_enterCompanionRequested) {
        g_enterCompanionRequested = false;
        companionModeUSB(true); // headless: app is already on serial, skip the QR screen
    }
    tft.setLogging();
    Serial.println(
        "\n"
        "██████  ██████  ██    ██  ██████ ███████ \n"
        "██   ██ ██   ██ ██    ██ ██      ██      \n"
        "██████  ██████  ██    ██ ██      █████   \n"
        "██   ██ ██   ██ ██    ██ ██      ██      \n"
        "██████  ██   ██  ██████   ██████ ███████ \n"
        "                                         \n"
        "   Brucia - companion firmware (based on Bruce, AGPL-3.0)\n\n"
        "Tips: Connect to the WebUI for better experience\n"
        "      Add your network by sending: wifi add ssid password\n\n"
        "At your command:"
    );

    // Enable navigation through webUI
    tft.fillScreen(bruceConfig.bgColor);
    mainMenu.begin();
    vTaskDelay(10 / portTICK_PERIOD_MS);
}
#endif
