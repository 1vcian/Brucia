#include "capability_evidence_core.h"
#include <string.h>

namespace brucia {
namespace companion {

EvidenceLevel resolveEvidenceLevel(const EvidenceInputs &in) {
    if (!in.compiled) return EvidenceLevel::None;
    if (in.initialized) return EvidenceLevel::Initialized;
    if (in.probe == ProbeOutcome::Present) return EvidenceLevel::Probed;
    if (in.configured) return EvidenceLevel::Configured;
    return EvidenceLevel::Compiled;
}

const char *evidenceLevelName(EvidenceLevel level) {
    switch (level) {
        case EvidenceLevel::Compiled: return "compiled";
        case EvidenceLevel::Configured: return "configured";
        case EvidenceLevel::Probed: return "probed";
        case EvidenceLevel::Initialized: return "initialized";
        case EvidenceLevel::None:
        default: return "none";
    }
}

const char *probeOutcomeName(ProbeOutcome probe) {
    switch (probe) {
        case ProbeOutcome::Present: return "present";
        case ProbeOutcome::Absent: return "absent";
        case ProbeOutcome::Busy: return "busy";
        case ProbeOutcome::NotRun:
        default: return nullptr;
    }
}

static bool sameText(const char *a, const char *b) {
    if (a == b) return true;
    if (!a || !b) return false;
    return strcmp(a, b) == 0;
}

size_t findPinConflicts(const PinUse *uses, size_t count, PinConflict *out, size_t maxOut) {
    if (!uses) return 0;
    size_t found = 0;
    for (size_t i = 0; i < count; i++) {
        if (uses[i].gpio < 0) continue;
        for (size_t j = i + 1; j < count; j++) {
            if (uses[j].gpio != uses[i].gpio) continue;
            const bool sameModule = sameText(uses[i].module, uses[j].module);
            const bool sameRole = sameText(uses[i].role, uses[j].role);
            if (sameModule && sameRole) continue; // duplicate entry, not a conflict
            if (!sameModule && sameRole && uses[i].share != PinShare::Exclusive &&
                uses[i].share == uses[j].share)
                continue; // one shared SPI/I2C bus line
            if (out && found < maxOut) {
                out[found].a = (uint16_t)i;
                out[found].b = (uint16_t)j;
            }
            found++;
        }
    }
    return found;
}

} // namespace companion
} // namespace brucia
