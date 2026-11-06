package app.brucia.session

/**
 * One screen's claim on a shared device stream (e.g. the wifi.ap scan that Wardrive, Channels,
 * RF Fingerprint, RF Timeline and the Evil Portal target list all read). [acquire] starts the
 * stream only when it is off and remembers that this screen started it; [release] stops only a
 * stream this screen started and that may still run, so leaving never turns off another owner's
 * scan, and a stream already stopped (Stop all, disconnect) gets no extra OFF. Kept across a
 * rotation with rememberRetained, so the recreated screen keeps the claim instead of re-deciding.
 */
class StreamClaim {
    /** True while this screen started the stream and has not released it. */
    @Volatile var owned = false
        private set

    /**
     * Start the stream when it is not [running]; [start] returns whether the request was accepted
     * (a rejected start leaves nothing to own). A running stream started earlier here stays owned,
     * so a reconnect that restarts it here keeps the claim.
     */
    fun acquire(running: Boolean, start: () -> Boolean) {
        if (!running && start()) owned = true
    }

    /** Stop the stream if this screen started it and it [mayRun]; the claim ends either way. */
    fun release(mayRun: Boolean, stop: () -> Unit) {
        if (owned && mayRun) stop()
        owned = false
    }
}
