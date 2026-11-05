package app.brucia.subghz

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * UI state of the two Sub-GHz receive streams (`subghz.raw` "Listen" and `subghz.spectrum`),
 * driven only by the SUB owner's outcome callbacks instead of an optimistic local toggle.
 * A start that the firmware rejects (for example `ERR 503` when the CC1101/RMT receiver cannot
 * be initialized), that times out, or that fails locally turns the indicator off and keeps the
 * error text for display. Errors are recorded for start requests only: a failed best-effort OFF
 * (e.g. while disconnected) is not a reason to report a receive failure.
 */
class SubGhzReceiveState {
    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening.asStateFlow()
    private val _spectrumActive = MutableStateFlow(false)
    val spectrumActive: StateFlow<Boolean> = _spectrumActive.asStateFlow()
    private val _rawError = MutableStateFlow("")
    val rawError: StateFlow<String> = _rawError.asStateFlow()
    private val _spectrumError = MutableStateFlow("")
    val spectrumError: StateFlow<String> = _spectrumError.asStateFlow()

    private val lastRequestOn = mutableMapOf<String, Boolean>()

    /** Record the user's request before it reaches the SUB owner; a new start clears the old error. */
    fun request(stream: String, on: Boolean): Unit = synchronized(lastRequestOn) {
        if (stream !in STREAMS) return
        lastRequestOn[stream] = on
        if (on) errorFlow(stream).value = ""
    }

    /** SUB owner state callback; returns false for streams this class does not own. */
    fun onState(stream: String, on: Boolean): Boolean {
        when (stream) {
            RAW -> _listening.value = on
            SPECTRUM -> _spectrumActive.value = on
            else -> return false
        }
        return true
    }

    /** SUB owner error callback (device ERR text, timeout or local rejection). */
    fun onError(stream: String, message: String): Unit = synchronized(lastRequestOn) {
        if (stream !in STREAMS || lastRequestOn[stream] != true) return
        errorFlow(stream).value = message.ifBlank { "Device error" }
    }

    /** Link closed: nothing is running and old errors no longer describe the device. */
    fun reset() = synchronized(lastRequestOn) {
        lastRequestOn.clear()
        _listening.value = false
        _spectrumActive.value = false
        _rawError.value = ""
        _spectrumError.value = ""
    }

    private fun errorFlow(stream: String) = if (stream == RAW) _rawError else _spectrumError

    companion object {
        const val RAW = "subghz.raw"
        const val SPECTRUM = "subghz.spectrum"
        private val STREAMS = setOf(RAW, SPECTRUM)
    }
}
