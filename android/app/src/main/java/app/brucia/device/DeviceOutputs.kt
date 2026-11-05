package app.brucia.device

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Device screen and LED power ("scr 0|1", "led 0|1", the Device screen's lite-mode switches), kept
 * outside the UI so a recreated screen shows what the device is doing instead of a fresh "on".
 *
 * The firmware does not report either output, so this is the state the app last asked for: shown at
 * once, and put back when the device rejects the request or does not answer, unless a newer request
 * for the same output replaced it. With no link both are on: companion mode starts with them on, and
 * the device turns them back on when it leaves companion mode or hears nothing for 15 s. A clean
 * disconnect also sends the restore itself ([disconnect]) so a quick reconnect finds them on.
 */
class DeviceOutputs {
    data class State(val screenOn: Boolean = true, val ledOn: Boolean = true)

    enum class Output(val command: String) { SCREEN("scr"), LED("led") }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val latest = LongArray(Output.entries.size)

    /** Record a request and return its token for [finish]; the command to send is [command]. */
    @Synchronized fun request(output: Output, on: Boolean): Long {
        val token = ++latest[output.ordinal]
        set(output, on)
        return token
    }

    /** The reply to request [token]: a failure restores the previous value if nothing newer was asked. */
    @Synchronized fun finish(output: Output, token: Long, on: Boolean, ok: Boolean) {
        if (!ok && latest[output.ordinal] == token) set(output, !on)
    }

    /**
     * The link is going down: return the restore commands for outputs the app turned off (to send
     * best-effort on the closing transport) and show both as on. Late replies are ignored.
     */
    @Synchronized fun disconnect(): List<String> {
        val s = _state.value
        for (i in latest.indices) latest[i]++
        _state.value = State()
        return buildList {
            if (!s.screenOn) add(command(Output.SCREEN, true))
            if (!s.ledOn) add(command(Output.LED, true))
        }
    }

    private fun set(output: Output, on: Boolean) {
        _state.value = when (output) {
            Output.SCREEN -> _state.value.copy(screenOn = on)
            Output.LED -> _state.value.copy(ledOn = on)
        }
    }

    companion object {
        fun command(output: Output, on: Boolean) = "${output.command} ${if (on) 1 else 0}"
    }
}
