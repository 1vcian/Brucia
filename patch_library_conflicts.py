"""Apply Brucia's source patches to installed third-party libraries.

Runs as a PlatformIO pre-script and again from a build middleware: PlatformIO
installs owner-less registry specs (for example ``ESP8266Audio@2.4.1``) only in
its Library Dependency Finder, after every pre-script. The second pass runs
once all dependencies are installed and before any source is compiled. A
pattern that no longer matches an installed library fails the build.
"""
import os
import re
import glob
import sys

try:
    Import("env")
except NameError:
    env = None  # standalone maintenance/tests


class PatchError(RuntimeError):
    """An installed library no longer matches an expected patch pattern."""

def cache_sbr_noise_bounds(content):
    """Keep validated loop bounds stable across calls through other input pointers."""
    start = content.find('void DecodeSBRNoise(')
    if start < 0:
        return content
    end = content.find('\n}', start) + 2
    function = content[start:end]
    if end < 2 or 'Brucia: cached validated noise bounds' in function:
        return content
    replacements = {
        'sbrGrid->numNoiseFloors <': 'noiseFloors <',
        'sbrGrid->numNoiseFloors >': 'noiseFloors >',
        'sbrGrid->numNoiseFloorsPrev >': 'previousFloors >',
        'sbrFreq->numNoiseFloorBands <': 'noiseBands <',
        'sbrFreq->numNoiseFloorBands >': 'noiseBands >',
        'noiseFloor < sbrGrid->numNoiseFloors': 'noiseFloor < noiseFloors',
        'sbrGrid->numNoiseFloorsPrev - 1': 'previousFloors - 1',
        'band < sbrFreq->numNoiseFloorBands': 'band < noiseBands',
        'DequantizeNoise(sbrFreq->numNoiseFloorBands,': 'DequantizeNoise(noiseBands,',
        'sbrGrid->numNoiseFloorsPrev = sbrGrid->numNoiseFloors;': 'sbrGrid->numNoiseFloorsPrev = noiseFloors;',
    }
    for before, after in replacements.items():
        function = function.replace(before, after)
    opening = function.index('{') + 1
    function = function[:opening] + '\n    /* Brucia: cached validated noise bounds */\n    const int noiseFloors = sbrGrid->numNoiseFloors;\n    const int previousFloors = sbrGrid->numNoiseFloorsPrev;\n    const int noiseBands = sbrFreq->numNoiseFloorBands;' + function[opening:]
    return content[:start] + function + content[end:]

# List of conflicts: (file_pattern, search_pattern, replace_pattern[, applied_marker]).
# A file counts as already patched when it contains applied_marker, which
# defaults to the literal replacement. Replacements using regex group
# references are not literal, so they must name an explicit marker.
BYTECODE_CHECK = r"""
/* Brucia: structural check of an untrusted bytecode file (v2) before JS_RelocateBytecode walks it.
   Same block walk as the relocation, but every block must have a known tag and lie inside the
   buffer, and every pointer the relocation rewrites must name the start of a block of this file.
   It does not verify the opcodes themselves: a deliberately crafted file is still code the user
   chose to run. Returns 0 if OK. */
int JS_CheckBytecode(const uint8_t *buf, uint32_t buf_len)
{
    const JSBytecodeHeader *hdr = (const JSBytecodeHeader *)buf;
    const uint8_t *data, *ptr, *p_end;
    uint32_t data_len, n_words, i;
    uint8_t *starts;
    int ret = -1, pass;

    if (!buf || buf_len < sizeof(JSBytecodeHeader) || ((uintptr_t)buf % JSW) != 0)
        return -1;
    if (hdr->magic != JS_BYTECODE_MAGIC || hdr->version != JS_BYTECODE_VERSION)
        return -1;
    data = buf + sizeof(JSBytecodeHeader);
    data_len = buf_len - sizeof(JSBytecodeHeader);
    if (data_len == 0 || (data_len % JSW) != 0)
        return -1;
    p_end = data + data_len;
    n_words = data_len / JSW;
    starts = calloc((n_words + 7) / 8, 1);
    if (!starts)
        return -1;
#define BC_START(off) (starts[(off) / JSW / 8] & (1 << (((off) / JSW) % 8)))
/* a pointer JSValue is the block address plus JS_TAG_PTR */
#define BC_OFF(v) ((uintptr_t)(v) - JS_TAG_PTR - hdr->base_addr)
#define BC_TARGET_OK(v) (!JS_IsPtr(v) || (BC_OFF(v) < data_len && BC_OFF(v) % JSW == 0 && BC_START(BC_OFF(v))))
    /* pass 0: walk the blocks and record their starts; pass 1: check every pointer */
    for (pass = 0; pass < 2; pass++) {
        for (ptr = data; ptr < p_end; ) {
            int size, mtag;
            if ((size_t)(p_end - ptr) < sizeof(JSWord))
                goto done;
            mtag = ((const JSMemBlockHeader *)ptr)->mtag;
            switch (mtag) {
            case JS_MTAG_FUNCTION_BYTECODE:
                if ((size_t)(p_end - ptr) < sizeof(JSFunctionBytecode))
                    goto done;
                break;
            case JS_MTAG_VALUE_ARRAY:
                if ((size_t)(p_end - ptr) < sizeof(JSValueArray))
                    goto done;
                break;
            case JS_MTAG_STRING:
            case JS_MTAG_FLOAT64:
            case JS_MTAG_BYTE_ARRAY:
                break;
            default:
                goto done;
            }
            size = get_mblock_size(ptr);
            if (size <= 0 || (size % JSW) != 0 || (size_t)size > (size_t)(p_end - ptr))
                goto done;
            if (pass == 0) {
                uint32_t off = (uint32_t)(ptr - data);
                starts[off / JSW / 8] |= 1 << ((off / JSW) % 8);
            } else if (mtag == JS_MTAG_FUNCTION_BYTECODE) {
                const JSFunctionBytecode *b = (const JSFunctionBytecode *)ptr;
                if (!BC_TARGET_OK(b->func_name) || !BC_TARGET_OK(b->byte_code) ||
                    !BC_TARGET_OK(b->cpool) || !BC_TARGET_OK(b->vars) ||
                    !BC_TARGET_OK(b->ext_vars) || !BC_TARGET_OK(b->filename) ||
                    !BC_TARGET_OK(b->pc2line))
                    goto done;
            } else if (mtag == JS_MTAG_VALUE_ARRAY) {
                const JSValueArray *a = (const JSValueArray *)ptr;
                if (sizeof(JSValueArray) + (size_t)a->size * sizeof(JSValue) > (size_t)size)
                    goto done;
                for (i = 0; i < a->size; i++) {
                    if (!BC_TARGET_OK(a->arr[i]))
                        goto done;
                }
            }
            ptr += size;
        }
    }
    /* the two roots: a function to run and the unique-string table */
    if (!JS_IsPtr(hdr->main_func) || !BC_TARGET_OK(hdr->main_func) ||
        ((const JSMemBlockHeader *)(data + BC_OFF(hdr->main_func)))->mtag !=
            JS_MTAG_FUNCTION_BYTECODE)
        goto done;
    if (JS_IsPtr(hdr->unique_strings)) {
        if (!BC_TARGET_OK(hdr->unique_strings) ||
            ((const JSMemBlockHeader *)(data + BC_OFF(hdr->unique_strings)))->mtag !=
                JS_MTAG_VALUE_ARRAY)
            goto done;
    }
    ret = 0;
done:
#undef BC_TARGET_OK
#undef BC_OFF
#undef BC_START
    free(starts);
    return ret;
}
"""


conflicts = [
    # Reject malformed SBR state before indexing the 2x5 noise arrays. The
    # public routine is void, so invalid direct calls return without mutation.
    (
        ".pio/libdeps/*/ESP8266Audio/src/libhelix-aac/sbrhuff.c",
        r'(void DecodeSBRNoise\([^\n]+\) \{)(?!\n    /\* Brucia:)',
        r'\1\n    /* Brucia: bound SBR noise state before array access. */\n    if (ch < 0 || ch > 1 || sbrGrid->numNoiseFloors < 1 ||\n        sbrGrid->numNoiseFloors > MAX_NUM_NOISE_FLOORS ||\n        sbrGrid->numNoiseFloorsPrev > MAX_NUM_NOISE_FLOORS ||\n        sbrFreq->numNoiseFloorBands < 1 || sbrFreq->numNoiseFloorBands > MAX_NUM_NOISE_FLOOR_BANDS) return;',
        '/* Brucia: bound SBR noise state before array access. */'
    ),
    (
        ".pio/libdeps/*/ESP8266Audio/src/libhelix-aac/sbrfreq.c",
        r'(static int CalcFreqNoise\([^\n]+\) \{)(?!\n    /\* Brucia:)',
        r'\1\n    /* Brucia: invalid frequency state must not index log/table arrays. */\n    if (nLow < 1 || nLow > MAX_QMF_BANDS / 2 || kStart < 1 || kStart > 64 ||\n        k2 < 1 || k2 > 64 || noiseBands < 0 || noiseBands > 3) return 0;',
        '/* Brucia: invalid frequency state must not index log/table arrays. */'
    ),
    (
        ".pio/libdeps/*/ESP8266Audio/src/libhelix-aac/sbrfreq.c",
        r'ASSERT\(nQ <= MAX_NUM_NOISE_FLOOR_BANDS\);',
        'if (nQ > MAX_NUM_NOISE_FLOOR_BANDS) return 0; /* Brucia: reject before writing freqNoise */'
    ),
    (
        ".pio/libdeps/*/ESP8266Audio/src/libhelix-aac/sbrfreq.c",
        r'(sbrFreq->numNoiseFloorBands = CalcFreqNoise\([^\n]+;)(?!\n    if \(sbrFreq->numNoiseFloorBands < 1\))',
        r'\1\n    if (sbrFreq->numNoiseFloorBands < 1) return -1; /* Brucia: propagate invalid noise table */',
        '/* Brucia: propagate invalid noise table */'
    ),
    (
        ".pio/libdeps/*/ESP8266Audio/src/libhelix-aac/sbr.c",
        r'CalcFreqTables\(&\(psi->sbrHdr\[chBase \+ 0\]\), &\(psi->sbrFreq\[chBase\]\), psi->sampRateIdx\);',
        'if (CalcFreqTables(&(psi->sbrHdr[chBase + 0]), &(psi->sbrFreq[chBase]), psi->sampRateIdx) != 0) {\n                psi->sbrHdr[chBase].count = 0; /* Brucia: require a fresh valid header */\n                return ERR_AAC_SBR_BITSTREAM;\n            }'
    ),
    # NimBLE 2.5.2 defines this as uint8_t. Match the caller's declaration to
    # avoid an ABI mismatch at LTO; no return values or privacy behavior change.
    (
        ".pio/libdeps/*/NimBLE-Arduino/src/nimble/nimble/host/src/ble_gap.c",
        r'extern int is_ble_hs_resolv_enabled\(void\);',
        'extern uint8_t is_ble_hs_resolv_enabled(void);'
    ),
    # M5GFX's optional filesystem wrapper changes LovyanGFX's base class with
    # include order. Give every TU the same base, including library source TUs.
    (
        ".pio/libdeps/*/M5GFX/src/lgfx/v1/LGFXBase.hpp",
        r'#include "../boards.hpp"',
        '#include "../boards.hpp"\n#include "lgfx_filesystem_support.hpp" // Brucia: consistent base class across translation units'
    ),
    # fastsdct writes alternate slots 0..16. Its second invocation starts at
    # X[1], where exactly 17 slots remain. Correct the declared minimum size.
    (
        ".pio/libdeps/*/ESP8266Audio/src/libmad/layer3.c",
        r'void fastsdct\(mad_fixed_t const x\[9\], mad_fixed_t y\[18\]\)',
        'void fastsdct(mad_fixed_t const x[9], mad_fixed_t y[17])'
    ),
    (
        ".pio/libdeps/*/ESP8266SAM/src/render.c",
        r'static void yield\(\)',
        'static void sam_yield()'
    ),
    (
        ".pio/libdeps/*/ESP8266SAM/src/render.c",
        r'\byield\(\);',
        'sam_yield();'
    ),
    (
        ".pio/libdeps/*/JPEGDecoder/src/picojpeg.c",
        r'static uint8 init\(void\)',
        'static uint8 picojpeg_init(void)'
    ),
    (
        ".pio/libdeps/*/JPEGDecoder/src/picojpeg.c",
        r'\binit\(\);',
        'picojpeg_init();'
    ),
    (
        ".pio/libdeps/*/ESP Amiibolink/src/amiibolink.h",
        r'#include <NimBLEDevice.h>',
        '#include <Arduino.h>\n#include <NimBLEDevice.h>'
    ),
    (
        ".pio/libdeps/*/ESP Chameleon Ultra/src/chameleonUltra.h",
        r'#include <NimBLEDevice.h>',
        '#include <Arduino.h>\n#include <NimBLEDevice.h>'
    ),
    (
        ".pio/libdeps/*/ESP PN32BLE/src/pn532_ble.h",
        r'#include <NimBLEDevice.h>',
        '#include <Arduino.h>\n#include <NimBLEDevice.h>'
    ),
    # These independent libraries used the same externally linked scanCallbacks
    # class name with different bases/layouts, violating the C++ ODR under LTO.
    (
        ".pio/libdeps/*/ESP Chameleon Ultra/src/chameleonUltra.cpp",
        r'\bscanCallbacks\b',
        'BruciaChameleonScanCallbacks'
    ),
    (
        ".pio/libdeps/*/ESP Amiibolink/src/amiibolink.cpp",
        r'\bscanCallbacks\b',
        'BruciaAmiibolinkScanCallbacks'
    ),
    (
        ".pio/libdeps/*/ESP PN32BLE/src/pn532_ble.cpp",
        r'\bscanCallbacks\b',
        'BruciaPn532ScanCallbacks'
    ),
    # NimBLE 2.x takes const advertisements. Without the matching signature the
    # old method silently overloads, so the target-discovery callback never runs.
    (
        ".pio/libdeps/*/ESP Chameleon Ultra/src/chameleonUltra.cpp",
        r'void onResult\(NimBLEAdvertisedDevice\s*\*\s*advertisedDevice\)\s*\{',
        '#ifdef NIMBLE_V2_PLUS\n'
        '    void onResult(const NimBLEAdvertisedDevice *advertisedDevice) override {\n'
        '#else\n'
        '    void onResult(NimBLEAdvertisedDevice *advertisedDevice) override {\n'
        '#endif'
    ),
    (
        ".pio/libdeps/*/ESP Amiibolink/src/amiibolink.cpp",
        r'void onResult\(NimBLEAdvertisedDevice\s*\*\s*advertisedDevice\)\s*\{',
        '#ifdef NIMBLE_V2_PLUS\n'
        '    void onResult(const NimBLEAdvertisedDevice *advertisedDevice) override {\n'
        '#else\n'
        '    void onResult(NimBLEAdvertisedDevice *advertisedDevice) override {\n'
        '#endif'
    ),
    # ST25R3916-fork: support IRQ-less operation (I2C units have no interrupt
    # line). When int_pin < 0 we skip attaching the GPIO interrupt and poll the
    # chip's IRQ register over the bus instead of gating on digitalRead(int_pin).
    # The fork is pinned to a fixed commit in platformio.ini so these patterns
    # stay valid.
    (
        ".pio/libdeps/*/ST25R3916-fork/src/rfal_rfst25r3916.cpp",
        r'(?s)pinMode\(int_pin, INPUT\);.*?attachInterrupt\(int_pin, irq_handler, RISING\);',
        'if (int_pin >= 0) {\n'
        '    pinMode(int_pin, INPUT);\n'
        '    Callback<void()>::func = std::bind(&RfalRfST25R3916Class::setISRPending, this);\n'
        '    irq_handler = static_cast<ST25R3916IrqHandler>(Callback<void()>::callback);\n'
        '    attachInterrupt(int_pin, irq_handler, RISING);\n'
        '  }'
    ),
    (
        ".pio/libdeps/*/ST25R3916-fork/src/rfal_rfst25r3916.cpp",
        r'detachInterrupt\(int_pin\);',
        'if (int_pin >= 0) { detachInterrupt(int_pin); }'
    ),
    # Without an IRQ pin, isISRPending() must return true unconditionally: rfalWorker()
    # (rfal_rfst25r3916.cpp) polls it once per tick to decide whether to read the chip's IRQ
    # status registers at all, and that's the *only* thing that drives card-detection polling
    # in I2C mode. Returning the real isr_pending flag instead (which nothing ever sets without
    # a GPIO ISR) makes rfalWorker() never check the chip, so no tag is ever detected.
    (
        ".pio/libdeps/*/ST25R3916-fork/src/rfal_rfst25r3916.cpp",
        r'return \(isr_pending \|\| \(digitalRead\(int_pin\) == HIGH\)\);',
        'return (int_pin < 0) ? true : (isr_pending || (digitalRead(int_pin) == HIGH));'
    ),
    # isISRPending() being unconditionally true in I2C mode (needed above) means every one of
    # the 11 post-transaction "if (isISRPending()) { ...; st25r3916Isr(); }" checks in
    # st25r3916_com.cpp also fires on every single register access, and st25r3916Isr() ->
    # st25r3916CheckForReceivedInterrupts() -> another register read -> checks isISRPending()
    # again -> recurses forever, blowing the stack (stack-canary panic right after
    # "[ST25R] begin: mode=I2C"). Guard st25r3916Isr() against re-entrancy instead of touching
    # isISRPending(), so the legitimate per-tick poll from rfalWorker() keeps working. The guard
    # only applies when int_pin < 0: in real IRQ/SPI mode a nested call means a genuine new
    # interrupt arrived mid-transaction (isr_pending was already cleared by the com.cpp caller
    # right before it), and skipping that call would drop/delay a real event, so SPI keeps the
    # library's original (unbounded but legitimate) recursive behavior untouched.
    (
        ".pio/libdeps/*/ST25R3916-fork/src/rfal_rfst25r3916.h",
        r'volatile bool bus_busy;',
        'volatile bool bus_busy;\n    volatile bool isr_active;'
    ),
    (
        ".pio/libdeps/*/ST25R3916-fork/src/rfal_rfst25r3916.cpp",
        r'bus_busy = false;',
        'bus_busy = false;\n  isr_active = false;'
    ),
    (
        ".pio/libdeps/*/ST25R3916-fork/src/st25r3916_interrupt.cpp",
        r'void RfalRfST25R3916Class::st25r3916Isr\(void\)\n'
        r'\{\n'
        r'  if \(!isBusBusy\(\)\) \{\n'
        r'    st25r3916CheckForReceivedInterrupts\(\);\n'
        r'\n'
        r'    // Check if callback is set and run it\n'
        r'    if \(NULL != st25r3916interrupt\.callback\) \{\n'
        r'      st25r3916interrupt\.callback\(\);\n'
        r'    \}\n'
        r'  \} else \{\n'
        r'    setISRPending\(\);\n'
        r'  \}\n'
        r'\}\n',
        'void RfalRfST25R3916Class::st25r3916Isr(void)\n'
        '{\n'
        '  if (isr_active && int_pin < 0) { return; }\n'
        '  isr_active = true;\n'
        '  if (!isBusBusy()) {\n'
        '    st25r3916CheckForReceivedInterrupts();\n'
        '\n'
        '    // Check if callback is set and run it\n'
        '    if (NULL != st25r3916interrupt.callback) {\n'
        '      st25r3916interrupt.callback();\n'
        '    }\n'
        '  } else {\n'
        '    setISRPending();\n'
        '  }\n'
        '  isr_active = false;\n'
        '}\n'
    ),
    # In polling mode read the IRQ registers exactly once per call (the read
    # auto-clears them on the chip); in IRQ mode keep draining while the pin is
    # high. Converts the edge-drain while-loop into a do/while.
    (
        ".pio/libdeps/*/ST25R3916-fork/src/st25r3916_interrupt.cpp",
        r'(?s)while \(digitalRead\(int_pin\) == HIGH\) \{.*?iregs\[3\] << 24;\s*\}',
        'do {\n'
        '    st25r3916ReadMultipleRegisters(ST25R3916_REG_IRQ_MAIN, iregs, ST25R3916_INT_REGS_LEN);\n'
        '\n'
        '    irqStatus |= (uint32_t)iregs[0];\n'
        '    irqStatus |= (uint32_t)iregs[1] << 8;\n'
        '    irqStatus |= (uint32_t)iregs[2] << 16;\n'
        '    irqStatus |= (uint32_t)iregs[3] << 24;\n'
        '  } while (int_pin >= 0 && digitalRead(int_pin) == HIGH);'
    ),
    # The I2C constructor's member-init list never sets cs_pin, so it's left
    # as whatever garbage was on the heap. rfalInitialize() then unconditionally
    # does pinMode(cs_pin, OUTPUT)/digitalWrite(cs_pin, HIGH), which panics with
    # "Invalid IO <garbage>" on I2C-only boards that have no CS line wired.
    # Skip the CS pin setup entirely when running in I2C mode.
    (
        ".pio/libdeps/*/ST25R3916-fork/src/rfal_rfst25r3916.cpp",
        r'pinMode\(cs_pin, OUTPUT\);\s*\n\s*digitalWrite\(cs_pin, HIGH\);',
        'if (!i2c_enabled) {\n'
        '    pinMode(cs_pin, OUTPUT);\n'
        '    digitalWrite(cs_pin, HIGH);\n'
        '  }'
    ),
    # NFC-A anti-collision (SENS/SDD/SEL) response timeout is ~120us -
    # generous enough for a real tag's silicon, but too tight for a
    # PN532-in-target-mode emulator, which relays TgInitAsTarget/AutoColl over
    # I2C to its host. Bench-confirmed: with this at spec (1620/fc), the
    # ST25R3916 reader never once completed activation against a PN532 T4T
    # target across the whole test session; bumped to 5ms, it did (still not
    # every attempt - see the retry patch right below for the other half of
    # this fix - but previously it was zero times ever).
    (
        ".pio/libdeps/*/NFC-RFAL-fork/src/rfal_nfca.h",
        r'#define RFAL_NFCA_FDTMIN          1620U',
        '#define RFAL_NFCA_FDTMIN          rfalConvMsTo1fc(5)'
    ),
    # SDD_REQ/SEL_REQ retries during anti-collision are unconditionally
    # disabled (rt=0, meaning a single-shot attempt) whenever devLimit != 0 -
    # i.e. exactly Bruce's own single-target read/emulate-read case
    # (params.devLimit = 1 in ST25R3916.cpp). One missed/late response (see
    # the FDTMIN patch above) then fails activation outright with no recourse.
    # Retries cost only a few ms of delay and are otherwise unconditionally
    # safe, so always allow them instead of gating on devLimit.
    (
        ".pio/libdeps/*/NFC-RFAL-fork/src/rfal_nfca.cpp",
        r'\(devLimit == 0U\) \? RFAL_NFCA_N_RETRANS : 0U',
        '/* Bruce: always retry, see patch_library_conflicts.py */ RFAL_NFCA_N_RETRANS'
    ),
    # The WUPA sent at the top of rfalNfcaPollerFullCollisionResolution() (the anti-collision
    # pass POLL_COLAVOIDANCE actually runs, heavier than the lightweight TECHDETECT probe) had no
    # retry wrapper at all - unlike the SDD_REQ/SEL_REQ calls below it, a single missed/late
    # response against a PN532 I2C-relayed target fails the whole collision resolution outright
    # (ST_ERR_TIMEOUT) with zero recourse. Bench-confirmed this was the dominant remaining cause
    # of ST25R3916 failing to activate a PN532 T4T target: once this call times out, devCnt stays
    # 0 for NFC-A and the multi-tech discovery (A|B|V|F) falls through to NFC-F, which a PN532
    # armed for T4T still answers (FeliCaParams/POL_REQ listening can't be disabled independently
    # of Mode - see PN532.cpp), producing a wrong-protocol device that fails Activation instead of
    # surfacing the real NFC-A timeout. Wrapping this call the same way as SDD/SEL fixed it.
    (
        ".pio/libdeps/*/NFC-RFAL-fork/src/rfal_nfca.cpp",
        r'ret = rfalRfDev->rfalISO14443ATransceiveShortFrame\(RFAL_14443A_SHORTFRAME_CMD_WUPA, \(uint8_t \*\)&nfcaDevList->sensRes, \(uint8_t\)rfalConvBytesToBits\(sizeof\(rfalNfcaSensRes\)\), &rcvLen, RFAL_NFCA_FDTMIN\);',
        'rfalNfcaTxRetry(ret, rfalRfDev->rfalISO14443ATransceiveShortFrame(RFAL_14443A_SHORTFRAME_CMD_WUPA, (uint8_t *)&nfcaDevList->sensRes, (uint8_t)rfalConvBytesToBits(sizeof(rfalNfcaSensRes)), &rcvLen, RFAL_NFCA_FDTMIN), (/* Bruce: WUPA had no retry at all - see patch_library_conflicts.py */ RFAL_NFCA_N_RETRANS), RFAL_NFCA_T_RETRANS);'
    ),
    # 2 retries (EMVCo default) is occasionally still not enough margin for PN532's I2C-relayed
    # response time under load; bumped to 4 as a balance between activation reliability and not
    # inflating worst-case discovery latency against genuinely absent tags.
    (
        ".pio/libdeps/*/NFC-RFAL-fork/src/rfal_nfca.cpp",
        r'#define RFAL_NFCA_N_RETRANS         2U',
        '#define RFAL_NFCA_N_RETRANS         4U'
    ),
    # Compiled BruceJS (.bin): JS_RelocateBytecode walks the file trusting every block size and
    # pointer ("the bytecode is not checked so it should come from a trusted source"). A truncated
    # or corrupt file would make it read and write outside the buffer. JS_CheckBytecode bounds
    # every block and requires every relocated pointer to name a block of the same file, before
    # the interpreter relocates it (tools/test_bjs_bytecode.py fuzzes it on a host build).
    (
        ".pio/libdeps/*/mquickjs/mquickjs.h",
        r'JS_BOOL JS_IsBytecode\(const uint8_t \*buf, size_t buf_len\);',
        'JS_BOOL JS_IsBytecode(const uint8_t *buf, size_t buf_len);\n'
        '/* Brucia: 0 if buf is a structurally valid bytecode file for this build (see mquickjs.c) */\n'
        'int JS_CheckBytecode(const uint8_t *buf, uint32_t buf_len);'
    ),
    (
        ".pio/libdeps/*/mquickjs/mquickjs.c",
        # The optional group replaces an older Brucia version of the block (marker = current version).
        r'(?s)(\n/\* Brucia: structural check of an untrusted bytecode file.*?)?\nint JS_RelocateBytecode2\(JSContext \*ctx, JSBytecodeHeader \*hdr,\n',
        BYTECODE_CHECK + '\nint JS_RelocateBytecode2(JSContext *ctx, JSBytecodeHeader *hdr,\n',
        'Brucia: structural check of an untrusted bytecode file (v2)'
    ),
]


LIBDEPS_PREFIX = ".pio/libdeps/*/"
SBR_CACHE_MARKER = 'Brucia: cached validated noise bounds'
GROUP_REFERENCE = re.compile(r'\\(?:\d|g<)')


def patch_entries():
    """Normalize the table to (relative path, search, replace, applied marker)."""
    entries = []
    for item in conflicts:
        file_pattern, search, replace = item[:3]
        marker = item[3] if len(item) > 3 else None
        if not file_pattern.startswith(LIBDEPS_PREFIX):
            raise PatchError(f"Unexpected patch path: {file_pattern}")
        if marker is None:
            if GROUP_REFERENCE.search(replace):
                raise PatchError(f"Group-reference replacement needs a marker: {file_pattern}")
            marker = replace
        entries.append((file_pattern[len(LIBDEPS_PREFIX):], search, replace, marker))
    return entries


def patch_tree(root, log=print):
    """Patch one environment's libdeps directory. Returns a list of errors.

    Libraries that are not installed in ``root`` are skipped. For an installed
    library every targeted file must exist and every entry must either already
    be applied (marker present) or still match its search pattern.
    """
    errors = []
    for relative, search, replace, marker in patch_entries():
        library = relative.split("/", 1)[0]
        if not os.path.isdir(os.path.join(root, library)):
            continue
        file_path = os.path.join(root, *relative.split("/"))
        if not os.path.isfile(file_path):
            errors.append(f"{file_path}: missing from installed library {library}")
            continue
        try:
            with open(file_path, 'r') as f:
                content = f.read()
            if marker in content:
                new_content = content
                log(f"Already patched: {file_path}")
            elif re.search(search, content):
                new_content = re.sub(search, replace, content)
            else:
                errors.append(f"{file_path}: pattern no longer matches: {search[:80]}")
                continue
            if file_path.endswith('libhelix-aac/sbrhuff.c'):
                new_content = cache_sbr_noise_bounds(new_content)
                if SBR_CACHE_MARKER not in new_content:
                    errors.append(f"{file_path}: DecodeSBRNoise not found for bound caching")
                    continue
            if new_content != content:
                with open(file_path, 'w') as f:
                    f.write(new_content)
                log(f"Patched: {file_path}")
        except (OSError, UnicodeDecodeError, re.error) as error:
            errors.append(f"{file_path}: {error}")
    return errors


def patch_roots(roots, log=print):
    errors = []
    for root in roots:
        errors.extend(patch_tree(root, log))
    if errors:
        raise PatchError("Library patch failed:\n  " + "\n  ".join(errors))


def _scons_patch(build_env, stage, log=print):
    # Do not rewrite another board's dependencies during an unrelated build.
    root = os.path.join(build_env.subst("$PROJECT_LIBDEPS_DIR"), build_env.get("PIOENV"))
    print(f"Patching library conflicts with ESP32 core ({stage})...")
    try:
        patch_roots([root], log)
    except PatchError as error:
        sys.stderr.write(f"Error: {error}\n")
        build_env.Exit(1)
    return root


def register_post_install_pass(build_env, root):
    """Re-run the table when the first libdeps source is collected for compiling.

    PlatformIO collects library sources only after the Library Dependency
    Finder has installed every dependency, and compiles them only after all
    SConscripts are read, so this pass sees late installs and precedes every
    compile. It patches the whole table, including installed libraries this
    environment does not compile.
    """
    prefix = os.path.realpath(root) + os.sep
    state = {"done": False}

    def after_dependency_install(node):
        if not state["done"]:
            source = os.path.realpath(node.srcnode().get_abspath())
            if source.startswith(prefix):
                state["done"] = True
                _scons_patch(build_env, "after dependency install",
                             lambda message: None if message.startswith("Already") else print(message))
        return node

    build_env.AddBuildMiddleware(after_dependency_install)
    return after_dependency_install


if env is not None:
    register_post_install_pass(env, _scons_patch(env, "pre-script"))
elif __name__ in ("__main__", "<run_path>"):
    print("Patching library conflicts with ESP32 core...")
    patch_roots(sorted(p for p in glob.glob(".pio/libdeps/*") if os.path.isdir(p)))
