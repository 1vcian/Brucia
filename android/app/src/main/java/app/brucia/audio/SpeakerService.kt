package app.brucia.audio

import app.brucia.session.CommandTasks
import app.brucia.session.CommandRunner
import app.brucia.session.DeviceOperation
import app.brucia.session.JsonCommandRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Onboard speaker control (speaker_volume, speaker_tone, speaker_play, speaker_song) on one
 * single-owner [DeviceOperation]: the latest request wins. speaker_stop and speaker_dtmf are
 * sent alongside without taking it over, so a key press never strands a tone/file wait.
 * Song and DTMF replies keep [status]; [songPlaying] is cleared only by a failed start of the
 * current song or by [stopSong].
 *
 * File playback has two contracts. Firmware advertising `features.speaker_play_async` ([asyncPlay])
 * ACKs speaker_play at start with the play token `d.play` and ends it with one EVENT
 * ev="speaker.play" ([onPlayEvent]); [filePlaying] tracks it and [stopFile] stops it. Older
 * firmware replies only after the whole file, so the wait stays 600 s and cannot stop playback.
 */
class SpeakerService(
    private val scope: CoroutineScope,
    private val runner: CommandRunner,
    private val isConnected: () -> Boolean,
    private val json: JsonCommandRunner? = null,
    private val asyncPlay: () -> Boolean = { false },
) {
    private val alongside = CommandTasks(scope)
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

    private val _filePlaying = MutableStateFlow("")
    /** Path of the file the device is playing for this app ("" when none; async firmware only). */
    val filePlaying: StateFlow<String> = _filePlaying.asStateFlow()
    /** Guards the file fields below and [_filePlaying]. */
    private val fileLock = Any()
    /** Bumped whenever the tracked file is forgotten, so an older start ACK cannot claim it. */
    private var fileGen = 0L
    /** Device play token of the tracked file, null while none is tracked. */
    private var filePlay: Long? = null
    /** True from an async speaker_play send until its ACK is mapped or superseded. */
    private var fileStarting = false
    /** Terminal events not (yet) owned: an event may be read before its start ACK is mapped. */
    private val unownedEnds = ArrayDeque<JSONObject>()

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
        forgetFile() // the device stops a playing file before the tone
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
     * Play an audio file from device storage. With [asyncPlay] the device ACKs at start and the
     * terminal EVENT ([onPlayEvent]) ends it; [stopFile] stops it. Otherwise the device replies
     * only after the whole file has played, so [stop] or any newer request only stops waiting.
     */
    fun playFile(path: String) {
        val p = path.trim()
        val precheck = { if (p.isEmpty()) "Enter a file path first." else null }
        val json = json
        if (json == null || !asyncPlay()) {
            op.start("speaker_play $p", "Playing $p...", precheck, PLAY_TIMEOUT_MS) { ok, out ->
                if (ok) out.ifBlank { "Playback started." } else out.ifBlank { "Playback failed." }
            }
            return
        }
        // The operation repeats these checks; a rejected start must not look like a pending one.
        val sends = isConnected() && precheck() == null
        val gen = synchronized(fileLock) {
            forgetFileLocked()
            fileStarting = sends
            fileGen
        }
        op.startJson(json, "speaker_play $p", "Starting $p...", precheck, PLAY_START_TIMEOUT_MS) { ok, data ->
            synchronized(fileLock) {
                if (gen != fileGen) return@startJson null // superseded meanwhile; keep the status
                fileStarting = false
                if (!ok) return@startJson replyText(data).ifBlank { "Playback failed." }
                val play = (data?.opt("play") as? Number)?.toLong()
                    ?: return@startJson "Playing $p (no play token; end not reported)."
                val early = unownedEnds.firstOrNull { token(it) == play }
                if (early != null) {
                    unownedEnds.remove(early)
                    return@startJson endText(early)
                }
                filePlay = play
                _filePlaying.value = p
                "Playing $p"
            }
        }
    }

    /**
     * Terminal EVENT ev="speaker.play" (its `d.data`). Ends the tracked file and shows the outcome
     * unless another command is awaiting its reply; events of other or forgotten plays are kept
     * only until a pending start ACK can claim them.
     */
    fun onPlayEvent(data: JSONObject) {
        val play = token(data) ?: return
        val text = synchronized(fileLock) {
            if (filePlay != play) {
                if (fileStarting) {
                    unownedEnds.addLast(data)
                    while (unownedEnds.size > MAX_UNOWNED_ENDS) unownedEnds.removeFirst()
                }
                return
            }
            forgetFileLocked()
            endText(data)
        }
        op.showIfIdle(text)
    }

    /**
     * Stop the tracked file (or its pending start) on the device. The terminal EVENT normally
     * arrives before the ACK; a successful ACK ends the tracking even if that event was lost.
     */
    fun stopFile() {
        val (play, path, starting) = synchronized(fileLock) { Triple(filePlay, _filePlaying.value, fileStarting) }
        if (play == null && !starting) return
        if (starting) {
            synchronized(fileLock) { forgetFileLocked() }
            op.stop()
        }
        if (!isConnected()) {
            synchronized(fileLock) { forgetFileLocked() }
            return
        }
        alongside.run(runner, "speaker_stop", TIMEOUT_MS) { ok, out ->
            val ended = synchronized(fileLock) {
                if (play == null || filePlay != play) return@synchronized false
                if (ok) forgetFileLocked()
                ok
            }
            if (ended) op.showIfIdle("Stopped $path.")
            else if (!ok && play != null) op.showIfIdle(out.ifBlank { "Stop failed." })
        }
    }

    private fun token(data: JSONObject?): Long? = (data?.opt("play") as? Number)?.toLong()

    private fun replyText(data: JSONObject?): String =
        data?.optString("out").orEmpty().ifBlank { data?.optString("msg").orEmpty() }

    private fun endText(data: JSONObject): String {
        val path = data.optString("path")
        return when (data.optString("state")) {
            "completed" -> "Played $path."
            "stopped" -> if (data.optString("reason") == "stop") "Stopped $path." else "Playback of $path was interrupted."
            "error" -> "Playback error: $path."
            else -> "Playback ended: $path."
        }
    }

    /** Forget the tracked file (the device stopped or replaced it); its later EVENT is ignored. */
    private fun forgetFile() = synchronized(fileLock) { forgetFileLocked() }

    private fun forgetFileLocked() {
        fileGen++
        filePlay = null
        fileStarting = false
        unownedEnds.clear()
        _filePlaying.value = ""
    }

    /** Start an RTTTL melody in the device background; [stopSong] interrupts it. */
    fun playSong(name: String, rtttl: String) {
        forgetFile() // the device replaces a playing file with the song
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
        forgetFile() // the device stops a playing file before the key tone
        sendAlongside("speaker_dtmf $key $ms")
    }

    /** Fire-and-forget command that neither supersedes nor reports through [status]. */
    private fun sendAlongside(cmd: String) {
        alongside.run(runner, cmd, TIMEOUT_MS)
    }

    /** Cancel all local waits and forget the tracked file; no firmware command is sent here. */
    fun stop() {
        alongside.cancel()
        op.stop()
        synchronized(songLock) { song++; _songPlaying.value = "" }
        forgetFile()
    }

    /** Link closed: as [stop], noting that the outcome of a playing file is unknown. */
    fun disconnect() {
        val hadFile = synchronized(fileLock) { filePlay != null || fileStarting }
        stop()
        if (hadFile) op.show(LINK_CLOSED)
    }

    companion object {
        const val TIMEOUT_MS = 10_000L
        const val TONE_TIMEOUT_MS = 15_000L
        const val PLAY_TIMEOUT_MS = 600_000L
        /** Async start: stopping a previous file (<= ~2.5 s on the device) plus file/codec open. */
        const val PLAY_START_TIMEOUT_MS = 15_000L
        const val MAX_UNOWNED_ENDS = 4
        const val LINK_CLOSED = "Link closed during playback; the device stops it when it leaves companion mode."
        const val MAX_FREQ_HZ = 20_000
        const val TONE_USAGE = "usage: speaker_tone <freqHz 1..20000> <ms>"
    }
}
