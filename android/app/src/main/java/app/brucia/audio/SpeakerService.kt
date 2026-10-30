package app.brucia.audio

import app.brucia.session.CommandRunner
import app.brucia.session.DeviceOperation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Onboard speaker control (speaker_volume, speaker_tone, speaker_play, speaker_song) on one
 * single-owner [DeviceOperation]: the latest request wins. speaker_stop and speaker_dtmf are
 * sent alongside without taking it over, so a key press never strands a tone/file wait.
 * Song and DTMF replies keep [status]; [songPlaying] is cleared only by a failed start of the
 * current song or by [stopSong].
 */
class SpeakerService(
    private val scope: CoroutineScope,
    private val runner: CommandRunner,
    private val isConnected: () -> Boolean,
) {
    private val op = DeviceOperation(scope, runner, isConnected, TIMEOUT_MS)
    val status: StateFlow<String> = op.status
    val busy: StateFlow<Boolean> = op.busy
    private val _volume = MutableStateFlow(100)
    /** Last requested volume (0..100), updated even when the device is not connected. */
    val volume: StateFlow<Int> = _volume.asStateFlow()
    private val _songPlaying = MutableStateFlow("")
    /** Name of the melody started on the device, "" when none. */
    val songPlaying: StateFlow<String> = _songPlaying.asStateFlow()
    /** Song token, guarded by [songLock]: a superseded start never clears a newer song. */
    private val songLock = Any()
    private var song = 0L

    /** Set the device speaker volume, clamped to 0..100; the device persists it. */
    fun setVolume(pct: Int) {
        val v = pct.coerceIn(0, 100)
        _volume.value = v
        op.start("speaker_volume $v", null) { ok, out ->
            if (ok) out.ifBlank { "Volume ${v}%" } else out.ifBlank { "Volume failed." }
        }
    }

    /** Play a tone; the device blocks for up to 10 s, so the wait is 15 s. */
    fun tone(freqHz: Int, ms: Int) {
        val precheck = {
            when {
                freqHz <= 0 || ms <= 0 -> "Enter a frequency and duration."
                freqHz > MAX_FREQ_HZ -> TONE_USAGE
                else -> null
            }
        }
        val running = "Playing ${freqHz}Hz for ${ms}ms..."
        op.start("speaker_tone $freqHz $ms", running, precheck, TONE_TIMEOUT_MS) { ok, out ->
            if (ok) out.ifBlank { "Tone played." } else out.ifBlank { "Tone failed." }
        }
    }

    /**
     * Play an audio file from device storage. The device replies only after the whole file has
     * played, so [stop] or any newer request only stops waiting; the playback continues.
     */
    fun playFile(path: String) {
        val p = path.trim()
        val precheck = { if (p.isEmpty()) "Enter a file path first." else null }
        op.start("speaker_play $p", "Playing $p...", precheck, PLAY_TIMEOUT_MS) { ok, out ->
            if (ok) out.ifBlank { "Playback started." } else out.ifBlank { "Playback failed." }
        }
    }

    /** Start an RTTTL melody in the device background; [stopSong] interrupts it. */
    fun playSong(name: String, rtttl: String) {
        // Not connected: the operation rejects the start, so the token is never compared.
        val token = synchronized(songLock) {
            if (isConnected()) { _songPlaying.value = name; ++song } else song
        }
        op.start("speaker_song $rtttl", null) { ok, out ->
            if (ok) return@start null
            synchronized(songLock) { if (song == token) _songPlaying.value = "" }
            out.ifBlank { "Song failed." }
        }
    }

    /** Forget the current song and, when connected, stop device playback. */
    fun stopSong() {
        synchronized(songLock) {
            song++
            _songPlaying.value = ""
        }
        if (isConnected()) sendAlongside("speaker_stop")
    }

    /** Play a DTMF key; ignored while not connected (the keypad is disabled then). */
    fun dtmf(key: Char, ms: Int = 200) {
        if (!isConnected()) return
        sendAlongside("speaker_dtmf $key $ms")
    }

    /** Fire-and-forget command that neither supersedes nor reports through [status]. */
    private fun sendAlongside(cmd: String) {
        scope.launch {
            try {
                runner.run(cmd, TIMEOUT_MS)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Nothing to report: these commands never had a status line.
            }
        }
    }

    /** Stop waiting for the current reply; device playback is untouched (see [stopSong]). */
    fun stop() = op.stop()

    companion object {
        const val TIMEOUT_MS = 10_000L
        const val TONE_TIMEOUT_MS = 15_000L
        const val PLAY_TIMEOUT_MS = 600_000L
        const val MAX_FREQ_HZ = 20_000
        const val TONE_USAGE = "usage: speaker_tone <freqHz 1..20000> <ms>"
    }
}
