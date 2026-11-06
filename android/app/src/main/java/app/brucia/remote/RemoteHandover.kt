package app.brucia.remote

/**
 * Hand-over from companion mode to Remote. Remote sends `companion_exit` and stops reading framed
 * replies, so a companion operation still waiting at that point can never get its answer: it would
 * hang until its own timeout (up to 650 s) and any later stop frame would land on the raw CLI.
 * [stopRunning] ends each busy operation through its own stop path, in order, and returns only
 * after those stops have been written (and acknowledged where the path waits for that), so the
 * caller can send `companion_exit` next.
 */
class RemoteHandover(private val steps: List<Step>) {
    /** [stop] must not return before its stop frames, if any, are on the wire. */
    class Step(val name: String, val busy: () -> Boolean, val stop: suspend () -> Unit)

    /** Stops every busy step; returns the names of those it stopped. A failing stop does not block the rest. */
    suspend fun stopRunning(): List<String> {
        val stopped = ArrayList<String>()
        for (step in steps) {
            if (!step.busy()) continue
            try { step.stop() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { }
            stopped += step.name
        }
        return stopped
    }

    companion object {
        /** Status text for operations ended by the hand-over. */
        const val REASON = "Stopped: Remote control took over the device."
    }
}
