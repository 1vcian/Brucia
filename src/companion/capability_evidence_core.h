#pragma once
// Brucia: Arduino-free capability-evidence logic shared by capabilities.cpp and host tests
// (tools/test_firmware_capability_evidence.py). It resolves the evidence level reported in
// CAPS d.evidence and detects static pin/bus conflicts from a board's pin assignments.
// No bus traffic happens here: probes live in capabilities.cpp.

#include <stddef.h>
#include <stdint.h>

namespace brucia {
namespace companion {

// Highest positively established evidence for one module, in increasing order.
// "Physically tested" is deliberately absent: the firmware cannot know it.
enum class EvidenceLevel : uint8_t {
    None = 0,    // no support built for this board env
    Compiled,    // support built for this board env
    Configured,  // pins/bus assigned in the board profile or saved pin config
    Probed,      // a bus transaction ran this session and saw the chip
    Initialized, // the driver was brought up successfully this session
};

// Result of the electronic presence check, separate from the level so "probed absent" is explicit.
enum class ProbeOutcome : uint8_t {
    NotRun = 0, // no electronic check exists for this module, or it was not configured/skipped
    Present,    // a bus transaction confirmed the chip
    Absent,     // the check ran and no chip answered as expected
    Busy,       // not probed: the bus may be owned by an active stream
};

struct EvidenceInputs {
    bool compiled = false;
    bool configured = false;
    ProbeOutcome probe = ProbeOutcome::NotRun;
    bool initialized = false;
};

EvidenceLevel resolveEvidenceLevel(const EvidenceInputs &in);
const char *evidenceLevelName(EvidenceLevel level);
// nullptr for NotRun (the wire field is omitted).
const char *probeOutcomeName(ProbeOutcome probe);

// How a GPIO may be legitimately shared between modules.
enum class PinShare : uint8_t {
    Exclusive = 0, // CS, CE, IRQ, reset, I2S/IR/1-Wire lines: one owner only
    SpiBus,        // SCK/MISO/MOSI: shareable with the same role on another SPI device
    I2cBus,        // SDA/SCL: shareable with the same role on another I2C device
};

struct PinUse {
    const char *module; // e.g. "nrf24", "display"
    const char *role;   // e.g. "cs", "sck", "sda"
    int gpio;           // < 0 = not connected (ignored)
    PinShare share;
};

struct PinConflict {
    uint16_t a; // index into the PinUse array
    uint16_t b; // index into the PinUse array (a < b)
};

// Two assignments on the same GPIO conflict unless both are shareable bus lines of the same kind
// and role (one SPI/I2C bus shared by several devices). Exact duplicates (same module and role)
// are ignored. Writes at most maxOut conflicts and returns the total number found.
size_t findPinConflicts(const PinUse *uses, size_t count, PinConflict *out, size_t maxOut);

} // namespace companion
} // namespace brucia
