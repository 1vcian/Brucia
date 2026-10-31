package app.brucia.wifi

import java.io.BufferedReader
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Owns existing offline dictionary runs; a superseded run can never publish results. */
class DictionaryTestService(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val mutable = MutableStateFlow(CrackStatus())
    val status: StateFlow<CrackStatus> = mutable.asStateFlow()
    private var generation = 0L
    private var job: Job? = null

    @Synchronized fun cancel() {
        generation++
        job?.cancel()
        job = null
        mutable.value = CrackStatus(note = "Cancelled")
    }

    /** Readers are opened on the worker and always closed, including cancellation/failure. */
    @Synchronized fun start(
        handshake: Handshake?, pmkid: PmkidTarget?, total: Int, note: String,
        rules: Boolean = false, open: () -> BufferedReader,
    ) {
        generation++
        val owner = generation
        job?.cancel()
        if (handshake == null && pmkid == null) {
            mutable.value = CrackStatus(note = "Load a handshake or PMKID first.")
            job = null
            return
        }
        // Byte arrays are mutable: preserve the selected target for this run.
        val h = handshake?.let { it.copy(apMac = it.apMac.copyOf(), clMac = it.clMac.copyOf(),
            aNonce = it.aNonce.copyOf(), sNonce = it.sNonce.copyOf(), eapol = it.eapol.copyOf(), mic = it.mic.copyOf()) }
        val p = pmkid?.let { it.copy(pmkid = it.pmkid.copyOf(), apMac = it.apMac.copyOf(), staMac = it.staMac.copyOf()) }
        // With expansion the number of candidates is unknown until exhausted.
        val count = if (rules) 0 else total.coerceAtLeast(0)
        mutable.value = CrackStatus(running = true, total = count, note = note)
        job = scope.launch(dispatcher) {
            val context = currentCoroutineContext()
            var tried = 0
            val started = System.nanoTime()
            fun cancelled() = !context.isActive || synchronized(this@DictionaryTestService) { owner != generation }
            fun rate(): Int = (tried.toLong() * 1000 / ((System.nanoTime() - started) / 1_000_000).coerceAtLeast(1))
                .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            try {
                ensureActive()
                open().use { reader ->
                    val raw = reader.lineSequence()
                    val candidates = (if (rules) raw.flatMap { WordlistRules.expand(it) } else raw)
                        .onEach { ensureActive(); tried++ }
                    val progress: (Int) -> Unit = {
                        publish(owner, CrackStatus(running = true, tried = tried, total = count, keysPerSec = rate(), note = note))
                    }
                    val result = if (p != null) WpaCracker.crackPmkid(p.ssid, p.apMac, p.staMac, p.pmkid,
                        candidates, 100, progress, ::cancelled)
                    else WpaCracker.crack(h!!, candidates, 100, progress, ::cancelled)
                    ensureActive()
                    if (!cancelled()) publish(owner, CrackStatus(finished = true, tried = tried,
                        total = if (result == null) tried else count, keysPerSec = rate(), result = result, note = note))
                }
            } catch (e: CancellationException) {
                // A parent cancellation must not leave a permanent running indicator.
                publish(owner, CrackStatus(note = "Cancelled"))
                throw e
            } catch (_: Exception) {
                publish(owner, CrackStatus(note = "read error"))
            }
        }.also { running ->
            running.invokeOnCompletion { cause ->
                if (cause is CancellationException) publish(owner, CrackStatus(note = "Cancelled"))
            }
        }
    }

    @Synchronized private fun publish(owner: Long, value: CrackStatus) {
        if (owner == generation) mutable.value = value
    }
}
