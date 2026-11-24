#pragma once
#include <stdint.h>

namespace brucia {
namespace companion {

// The SDK distinguishes an in-flight scan from a failed one at both start and completion.
class ApScanLoop {
    enum class Phase { Ready, Running, Cooldown };
    Phase phase = Phase::Ready;
    uint32_t failedAt = 0;

public:
    void reset() { phase = Phase::Ready; }

    template<class Driver, class Results, class Failure>
    void poll(uint32_t now, Driver &driver, int running, Results results, Failure failure) {
        if (phase == Phase::Cooldown && uint32_t(now - failedAt) < 1000) return;
        int n = phase == Phase::Running ? driver.scanComplete() : driver.scanNetworks(true, true);
        if (n == running) {
            phase = Phase::Running;
        } else if (n < 0) {
            driver.scanDelete();
            failedAt = now;
            phase = Phase::Cooldown;
            failure();
        } else {
            results(n);
            driver.scanDelete();
            phase = Phase::Ready;
        }
    }
};

} // namespace companion
} // namespace brucia
