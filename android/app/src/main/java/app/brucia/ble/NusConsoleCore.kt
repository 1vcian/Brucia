package app.brucia.ble

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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

/** Identity of one connect() call; every callback carries the session it belongs to. */
internal class NusSession(val id: Long, val name: String) {
    var link: NusLink? = null
}

/** Console state machine over [NusLink]. All methods run on [scope]'s dispatcher. */
internal class NusConsoleCore(private val scope: CoroutineScope, private val opTimeoutMs: Long = 10_000L) {
    val state = MutableStateFlow<BleConsole.State>(BleConsole.State.Idle)
    val log = MutableStateFlow("")

    private var current: NusSession? = null
    private var nextId = 0L
    private var mtu = 23
    private var name = ""
    private val assembler = NusLineAssembler()
    private var writes = Channel<ByteArray>(Channel.UNLIMITED)
    private var writer: Job? = null
    @Volatile private var pendingWrite: CompletableDeferred<Boolean>? = null
    @Volatile private var pendingDescriptor: CompletableDeferred<Boolean>? = null

    val hasSession: Boolean get() = current != null

    fun connect(name: String, open: (NusSession) -> NusLink?) {
        disconnect()
        this.name = name
        mtu = 23
        assembler.clear()
        writes = Channel(Channel.UNLIMITED)
        state.value = BleConsole.State.Connecting(name)
        val s = NusSession(++nextId, name)
        current = s
        s.link = open(s)
    }

    fun disconnect() {
        writer?.cancel()
        writer = null
        pendingWrite?.complete(false)
        pendingDescriptor?.complete(false)
        current?.link?.close()
        current = null
        if (state.value !is BleConsole.State.Failed) state.value = BleConsole.State.Idle
    }

    private fun fail(reason: String) {
        state.value = BleConsole.State.Failed(reason)
        disconnect()
    }

    fun onConnectionStateChange(s: NusSession, connected: Boolean, success: Boolean, status: Int) {
        if (s !== current) return
        val link = s.link ?: return
        when {
            connected && success -> ensureBonded(link)
            !connected -> {
                val wasReady = state.value is BleConsole.State.Ready
                fail(if (wasReady) "Disconnected" else "Connection failed (status $status)")
            }
        }
    }

    private fun ensureBonded(link: NusLink) {
        if (link.isBonded()) { link.requestMtu(247); return }
        state.value = BleConsole.State.Pairing(name)
        if (!link.startBond()) fail("Could not start pairing")
    }

    fun onBondState(s: NusSession, bonded: Boolean) {
        if (bonded) current?.link?.requestMtu(247)
        else fail("Pairing failed or cancelled. Enter the PIN shown on the device.")
    }

    fun onMtuChanged(s: NusSession, newMtu: Int, success: Boolean) {
        if (s !== current) return
        val link = s.link ?: return
        if (success) mtu = newMtu
        if (!link.discoverServices()) fail("Service discovery failed")
    }

    fun onServicesDiscovered(s: NusSession, success: Boolean) {
        scope.launch {
            if (s !== current) return@launch
            val link = s.link ?: return@launch
            if (!success || !link.resolveNus()) {
                fail("No BLE API on this device (turn it on: Config > Advanced > Toggle BLE API)"); return@launch
            }
            if (!subscribe(link)) { fail("Could not subscribe to the device output"); return@launch }
            state.value = BleConsole.State.Ready(name)
            append("Connected to $name. Type a command (for example: info).\n")
            startWriter(link)
        }
    }

    private suspend fun subscribe(link: NusLink): Boolean {
        val done = CompletableDeferred<Boolean>().also { pendingDescriptor = it }
        val started = link.enableNotifications()
        return started && (withTimeoutOrNull(opTimeoutMs) { done.await() } == true)
    }

    fun onNotification(s: NusSession, uuid: java.util.UUID, value: ByteArray) {
        if (uuid != NusProtocol.TX) return
        assembler.feed(value).forEach { append("$it\n") }
    }

    fun onCharacteristicWrite(s: NusSession, uuid: java.util.UUID, success: Boolean) {
        pendingWrite?.complete(success)
    }

    fun onDescriptorWrite(s: NusSession, uuid: java.util.UUID, success: Boolean) {
        pendingDescriptor?.complete(success)
    }

    /** Queue [text] as one command line; returns the local rejection, or null when queued. */
    fun send(text: String): String? {
        NusProtocol.rejection(text)?.let { return it }
        if (state.value !is BleConsole.State.Ready) return "Not connected"
        val line = NusProtocol.commandLine(text) ?: return "Nothing to send."
        append("> ${line.trimEnd()}\n")
        NusProtocol.chunks(line, mtu).forEach { writes.trySend(it) }
        return null
    }

    private fun startWriter(link: NusLink) {
        writer?.cancel()
        writer = scope.launch {
            for (chunk in writes) {
                val done = CompletableDeferred<Boolean>().also { pendingWrite = it }
                val started = link.write(chunk)
                if (!started || withTimeoutOrNull(opTimeoutMs) { done.await() } != true) {
                    append("(write failed: is the phone still paired with this device?)\n")
                }
            }
        }
    }

    fun clearLog() { log.value = "" }

    private fun append(text: String) {
        log.value = (log.value + text).takeLast(20_000)
    }
}
