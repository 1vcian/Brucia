#pragma once
// Pure validation of a subghz.spectrum sweep request (no Arduino dependencies, host-testable via
// tools/test_firmware_spectrum.py). The sweep grid is quantized to whole kHz up front, so the
// frequencies the device tunes are exactly start + i * step as reported in the EVENT (kHz).
#include <cmath>
#include <cstdint>

namespace brucia {
namespace companion {

struct SpecPlan {
    uint32_t startKHz;
    uint32_t stepKHz;
    int count;
};

// setMHZ() accepts 280..928 MHz and silently retunes anything else to 433.92 MHz.
static const uint32_t SPEC_MIN_KHZ = 280000;
static const uint32_t SPEC_MAX_KHZ = 928000;

// Returns nullptr and fills `plan` when the request is valid, otherwise a message for ERR 400.
// Rules: every value finite; start/end rounded to the nearest kHz inside 280..928 MHz; end >= start;
// step rounded to the nearest kHz and at least 1 kHz; the grid (start, start + step, ... <= end)
// has at most maxPoints points. Nothing is clamped, swapped or widened silently.
inline const char *planSpectrumSweep(double fStartMHz, double fEndMHz, double stepMHz, int maxPoints,
                                     SpecPlan &plan) {
    if (!std::isfinite(fStartMHz) || !std::isfinite(fEndMHz) || !std::isfinite(stepMHz))
        return "f_start, f_end and step must be finite numbers (MHz)";
    const double lo = SPEC_MIN_KHZ / 1000.0, hi = SPEC_MAX_KHZ / 1000.0;
    // Range check before rounding so huge values never reach the integer conversion.
    if (fStartMHz < lo - 0.0005 || fStartMHz > hi + 0.0005 || fEndMHz < lo - 0.0005 || fEndMHz > hi + 0.0005)
        return "f_start/f_end out of range (280..928 MHz)";
    long long startKHz = std::llround(fStartMHz * 1000.0);
    long long endKHz = std::llround(fEndMHz * 1000.0);
    if (startKHz < SPEC_MIN_KHZ || endKHz > SPEC_MAX_KHZ || startKHz > SPEC_MAX_KHZ || endKHz < SPEC_MIN_KHZ)
        return "f_start/f_end out of range (280..928 MHz)";
    if (endKHz < startKHz) return "f_end must be >= f_start";
    if (stepMHz <= 0 || stepMHz > hi) return "step must be > 0 MHz";
    long long stepKHz = std::llround(stepMHz * 1000.0);
    if (stepKHz < 1) return "step must be >= 0.001 MHz";
    long long count = (endKHz - startKHz) / stepKHz + 1;
    if (maxPoints < 1 || count > maxPoints) return "too many points; widen step or narrow the range";
    plan.startKHz = (uint32_t)startKHz;
    plan.stepKHz = (uint32_t)stepKHz;
    plan.count = (int)count;
    return nullptr;
}

} // namespace companion
} // namespace brucia
