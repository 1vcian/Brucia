package app.brucia.ble

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * One GATT connection as [NusConsoleCore] sees it. The Android adapter in [BleConsole] wraps a
 * BluetoothGatt; JVM tests use a fake. Results of started operations come back through the
 * core's on* callbacks, tagged with the [NusSession] the link was opened for.
 */
internal interface NusLink {
    fun isBonded(): Boolean
    /** Start pairing; the outcome arrives as [NusConsoleCore.onBondState]. False when it could not start. */
    fun startBond(): Boolean
    fun requestMtu(mtu: Int): Boolean
    fun discoverServices(): Boolean
    /** After discovery: resolve the NUS RX/TX characteristics; false when the service is missing. */
    fun resolveNus(): Boolean
    /** Enable TX notifications and start the CCCD write (outcome: [NusConsoleCore.onDescriptorWrite]). */
    fun enableNotifications(): Boolean
    /** Start one RX write (outcome: [NusConsoleCore.onCharacteristicWrite]). */
    fun write(chunk: ByteArray): Boolean
    /** Release the connection (bond receiver, GATT). Idempotent. */
    fun close()
}

/**
 * One connect() call. Every callback, wait and job is bound to the session it was started for:
 * the core acts on it only while it is the current, open session, and closing it cancels all of
 * its jobs and waits, so nothing of an old connection can reach the next one.
 */
internal class NusSession(val id: Long, val name: String, parent: Job?) {
    var link: NusLink? = null
        internal set
    internal val job: Job = SupervisorJob(parent)
    internal var open = true
    internal var mtu = 23
    internal val assembler = NusLineAssembler()
    /** Queued command lines, each already split into chunks for this session's MTU. */
    internal val lines = Channel<List<ByteArray>>(Channel.UNLIMITED)
    internal var pendingWrite: CompletableDeferred<Boolean>? = null
    internal var pendingDescriptor: CompletableDeferred<Boolean>? = null

    internal fun close() {
        open = false
        job.cancel()
        lines.close()
        pendingWrite = null
        pendingDescriptor = null
        link?.close()
    }
}

/**
 * Console state machine over [NusLink]. All methods run on [scope]'s dispatcher (the adapter
 * posts binder-thread GATT callbacks there). Each callback names its [NusSession]; one that is
 * not the current open session is ignored, and ownership is checked again after every wait.
 */
internal class NusConsoleCore(private val scope: CoroutineScope, private val opTimeoutMs: Long = 10_000L) {
    val state = MutableStateFlow<BleConsole.State>(BleConsole.State.Idle)
    val log = MutableStateFlow("")

    private var current: NusSession? = null
    private var nextId = 0L

    val hasSession: Boolean get() = current != null

    private fun owns(s: NusSession) = s === current && s.open

    private fun NusSession.launch(block: suspend CoroutineScope.() -> Unit) = scope.launch(job, block = block)

    fun connect(name: String, open: (NusSession) -> NusLink?) {
        disconnect()
        val s = NusSession(++nextId, name, scope.coroutineContext[Job])
        current = s
        state.value = BleConsole.State.Connecting(name)
        val link = open(s)
        if (!owns(s)) { link?.close(); return }
        if (link == null) { fail(s, "Connection failed (could not start)"); return }
        s.link = link
    }

    fun disconnect() {
        current?.let { s -> current = null; s.close() }
        if (state.value !is BleConsole.State.Failed) state.value = BleConsole.State.Idle
    }

    private fun fail(s: NusSession, reason: String) {
        if (!owns(s)) return
        state.value = BleConsole.State.Failed(reason)
        disconnect()
    }

    fun onConnectionStateChange(s: NusSession, connected: Boolean, success: Boolean, status: Int) {
        if (!owns(s)) return
        val link = s.link ?: return
        when {
            connected && success -> ensureBonded(s, link)
            !connected -> {
                val wasReady = state.value is BleConsole.State.Ready
                fail(s, if (wasReady) "Disconnected" else "Connection failed (status $status)")
            }
        }
    }

    /** Pair first (the firmware ignores writes from an unpaired phone), then set the link up. */
    private fun ensureBonded(s: NusSession, link: NusLink) {
        if (link.isBonded()) { link.requestMtu(247); return }
        state.value = BleConsole.State.Pairing(s.name)
        if (!link.startBond()) fail(s, "Could not start pairing")
    }

    fun onBondState(s: NusSession, bonded: Boolean) {
        if (!owns(s)) return
        if (bonded) s.link?.requestMtu(247)
        else fail(s, "Pairing failed or cancelled. Enter the PIN shown on the device.")
    }

    fun onMtuChanged(s: NusSession, newMtu: Int, success: Boolean) {
        if (!owns(s)) return
        val link = s.link ?: return
        if (success) s.mtu = newMtu
        if (!link.discoverServices()) fail(s, "Service discovery failed")
    }

    fun onServicesDiscovered(s: NusSession, success: Boolean) {
        if (!owns(s)) return
        val link = s.link ?: return
        if (!success || !link.resolveNus()) {
            fail(s, "No BLE API on this device (turn it on: Config > Advanced > Toggle BLE API)"); return
        }
        s.launch {
            val subscribed = subscribe(s, link)
            if (!owns(s)) return@launch
            if (!subscribed) { fail(s, "Could not subscribe to the device output"); return@launch }
            state.value = BleConsole.State.Ready(s.name)
            append("Connected to ${s.name}. Type a command (for example: info).\n")
            startWriter(s, link)
        }
    }

    private suspend fun subscribe(s: NusSession, link: NusLink): Boolean {
        val done = CompletableDeferred<Boolean>().also { s.pendingDescriptor = it }
        try {
            return link.enableNotifications() && withTimeoutOrNull(opTimeoutMs) { done.await() } == true
        } finally {
            if (s.pendingDescriptor === done) s.pendingDescriptor = null
        }
    }

    fun onNotification(s: NusSession, uuid: UUID, value: ByteArray) {
        if (!owns(s) || uuid != NusProtocol.TX) return
        s.assembler.feed(value).forEach { append("$it\n") }
    }

    fun onCharacteristicWrite(s: NusSession, uuid: UUID, success: Boolean) {
        if (!owns(s) || uuid != NusProtocol.RX) return
        s.pendingWrite?.complete(success)
    }

    fun onDescriptorWrite(s: NusSession, uuid: UUID, success: Boolean) {
        if (!owns(s) || uuid != NusProtocol.CCCD) return
        s.pendingDescriptor?.complete(success)
    }

    /** Queue [text] as one command line; returns the local rejection, or null when queued. */
    fun send(text: String): String? {
        NusProtocol.rejection(text)?.let { return it }
        val s = current
        if (s == null || !s.open || state.value !is BleConsole.State.Ready) return "Not connected"
        val line = NusProtocol.commandLine(text) ?: return "Nothing to send."
        append("> ${line.trimEnd()}\n")
        s.lines.trySend(NusProtocol.chunks(line, s.mtu))
        return null
    }

    /**
     * Sends queued lines one chunk at a time, each waiting for its own ACK. A failed chunk ends its
     * line: the rest is not sent. When part of the line may already be in the firmware's buffer
     * (a later chunk failed, or an earlier one's delivery is unknown), the next line would be
     * joined to it, so the session ends instead; the firmware drops the partial line on disconnect.
     */
    private fun startWriter(s: NusSession, link: NusLink) {
        s.launch {
            for (chunks in s.lines) {
                for ((i, chunk) in chunks.withIndex()) {
                    val ok = writeChunk(s, link, chunk)
                    if (!owns(s)) return@launch
                    if (ok == true) continue
                    append("(write failed: is the phone still paired with this device?)\n")
                    if (i > 0 || (ok == null && chunks.size > 1)) {
                        fail(s, "Write failed in the middle of a command. Reconnect to continue.")
                        return@launch
                    }
                    break
                }
            }
        }
    }

    /** True when acknowledged, false when it failed or could not start, null on timeout. */
    private suspend fun writeChunk(s: NusSession, link: NusLink, chunk: ByteArray): Boolean? {
        val done = CompletableDeferred<Boolean>().also { s.pendingWrite = it }
        try {
            if (!link.write(chunk)) return false
            return withTimeoutOrNull(opTimeoutMs) { done.await() }
        } finally {
            if (s.pendingWrite === done) s.pendingWrite = null
        }
    }

    fun clearLog() { log.value = "" }

    private fun append(text: String) {
        log.value = (log.value + text).takeLast(20_000)
    }
}
