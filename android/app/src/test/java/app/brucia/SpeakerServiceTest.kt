package app.brucia

import app.brucia.audio.SpeakerService
import app.brucia.session.CommandRunner
import app.brucia.session.JsonCommandRunner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import org.json.JSONObject

class SpeakerServiceTest {
    private val runner = GateRunner()
    @Volatile private var connected = true
    private val notConnected = "Connect the device over USB first."

    private fun CoroutineScope.svc(r: CommandRunner = runner) = SpeakerService(this, r, { connected })
    private fun SpeakerService.state() = status.value to busy.value

    private suspend fun SpeakerService.result(reply: Pair<Boolean, String>, call: SpeakerService.() -> Unit): String {
        runner.auto = reply
        val n = runner.calls.size
        call()
        until { runner.calls.size == n + 1 && !busy.value }
        return status.value
    }

    /** Asserts [call] sets [text] at once and sends nothing. */
    private suspend fun SpeakerService.rejects(text: String, call: SpeakerService.() -> Unit) {
        val n = runner.calls.size
        call()
        assertEquals(text to false, state())
        delay(20)
        assertEquals(n, runner.calls.size)
    }

    @Test fun volumeClampsTextsAndTimeout() = runBlocking {
        val svc = svc()
        assertEquals("Volume 100%", svc.result(true to "") { setVolume(150) })
        assertEquals(100, svc.volume.value)
        assertEquals("volume 0%", svc.result(true to "volume 0%") { setVolume(-3) })
        assertEquals(0, svc.volume.value)
        assertEquals("Volume failed.", svc.result(false to "") { setVolume(40) })
        assertEquals("busy", svc.result(false to "busy") { setVolume(41) })
        assertEquals(listOf("speaker_volume 100" to 10_000L, "speaker_volume 0" to 10_000L,
            "speaker_volume 40" to 10_000L, "speaker_volume 41" to 10_000L), runner.calls.toList())
    }

    @Test fun volumeUpdatesWithoutConnection() = runBlocking {
        connected = false
        val svc = svc()
        assertEquals(100, svc.volume.value)
        svc.rejects(notConnected) { setVolume(42) }
        assertEquals(42, svc.volume.value)
    }

    @Test fun toneTextsRejectionsAndTimeout() = runBlocking {
        val svc = svc()
        svc.tone(440, 500)
        assertEquals("Playing 440Hz for 500ms..." to true, svc.state())
        until { runner.calls.size == 1 }
        runner.reply(0, true, "")
        until { !svc.busy.value }
        assertEquals("Tone played.", svc.status.value)
        assertEquals("played 20000Hz for 10ms", svc.result(true to "played 20000Hz for 10ms") { tone(20_000, 10) })
        assertEquals("Tone failed.", svc.result(false to "") { tone(1, 1) })
        assertEquals(listOf("speaker_tone 440 500" to 15_000L, "speaker_tone 20000 10" to 15_000L,
            "speaker_tone 1 1" to 15_000L), runner.calls.toList())
        for ((f, ms) in listOf(0 to 100, -1 to 100, 440 to 0, 440 to -5)) {
            svc.rejects("Enter a frequency and duration.") { tone(f, ms) }
        }
        svc.rejects(SpeakerService.TONE_USAGE) { tone(20_001, 100) }
        assertEquals("usage: speaker_tone <freqHz 1..20000> <ms>", SpeakerService.TONE_USAGE)
        connected = false
        svc.rejects(notConnected) { tone(0, 0) }
    }

    @Test fun playFileTextsAndTimeout() = runBlocking {
        val svc = svc()
        svc.playFile("  /music/a.wav ")
        assertEquals("Playing /music/a.wav..." to true, svc.state())
        until { runner.calls.size == 1 }
        runner.reply(0, true, "")
        until { !svc.busy.value }
        assertEquals("Playback started.", svc.status.value)
        assertEquals("file not found", svc.result(false to "file not found") { playFile("x.mp3") })
        assertEquals("Playback failed.", svc.result(false to "") { playFile("y.mp3") })
        assertEquals(listOf("speaker_play /music/a.wav" to 600_000L, "speaker_play x.mp3" to 600_000L,
            "speaker_play y.mp3" to 600_000L), runner.calls.toList())
        svc.rejects("Enter a file path first.") { playFile("   ") }
        connected = false
        svc.rejects(notConnected) { playFile("a.wav") }
    }

    @Test fun songLifecycleKeepsStatusOnSuccessAndClearsOnFailOrStop() = runBlocking {
        val svc = svc()
        assertEquals("Volume 50%", svc.result(true to "") { setVolume(50) })
        assertEquals("Volume 50%", svc.result(true to "playing") {
            playSong("Mario", "mario:d=4,o=5,b=100:e")
            assertEquals("Mario", songPlaying.value)
        })
        assertEquals("Mario", svc.songPlaying.value)
        assertEquals("Volume 50%", svc.result(true to "stopped") { stopSong() })
        assertEquals("", svc.songPlaying.value)
        assertEquals("song failed", svc.result(false to "song failed") { playSong("Bad", "x") })
        assertEquals("", svc.songPlaying.value)
        assertEquals("Song failed.", svc.result(false to "") { playSong("Bad", "y") })
        assertEquals(listOf("speaker_volume 50", "speaker_song mario:d=4,o=5,b=100:e", "speaker_stop",
            "speaker_song x", "speaker_song y"), runner.cmds)
        assertTrue(runner.calls.all { it.second == 10_000L })
    }

    @Test fun songWhileDisconnected() = runBlocking {
        val svc = svc()
        svc.playSong("A", "a")
        until { runner.calls.size == 1 }
        connected = false
        svc.rejects(notConnected) { playSong("B", "b") }
        assertEquals("A", svc.songPlaying.value)
        svc.stopSong()
        assertEquals("" to (notConnected to false), svc.songPlaying.value to svc.state())
        delay(20)
        assertEquals(1, runner.calls.size)
    }

    @Test fun supersededSongStartNeverClearsNewerSong() = runBlocking {
        // Replies that outlive cancellation, so the superseded start still maps its result. A
        // detached scope keeps a failed assertion from waiting on those uncancellable calls.
        val conts = CopyOnWriteArrayList<Continuation<Pair<Boolean, String>>>()
        val scope = CoroutineScope(coroutineContext + Job())
        val svc = scope.svc { _, _ -> suspendCoroutine { conts += it } }
        svc.playSong("A", "a")
        until { conts.size == 1 }
        svc.stopSong()
        until { conts.size == 2 }
        svc.playSong("C", "c")
        until { conts.size == 3 }
        conts[0].resume(false to "late")
        conts[1].resume(true to "stopped")
        delay(20)
        assertEquals("C" to ("" to true), svc.songPlaying.value to svc.state())
        conts[2].resume(false to "")
        until { !svc.busy.value }
        assertEquals("" to "Song failed.", svc.songPlaying.value to svc.status.value)
        scope.cancel()
    }

    @Test fun dtmfKeepsStatusAndIsSkippedWhileDisconnected() = runBlocking {
        val svc = svc()
        assertEquals("Tone failed.", svc.result(false to "") { tone(440, 100) })
        assertEquals("Tone failed.", svc.result(true to "dtmf") { dtmf('5') })
        assertEquals("Tone failed.", svc.result(false to "usage") { dtmf('#', 400) })
        assertEquals(listOf("speaker_dtmf 5 200", "speaker_dtmf # 400"), runner.cmds.drop(1))
        assertTrue(runner.calls.drop(1).all { it.second == 10_000L })
        connected = false
        svc.rejects("Tone failed.") { dtmf('1') }
    }

    @Test fun dtmfAndStopSongDoNotTakeOverAPendingTone() = runBlocking {
        val svc = svc()
        svc.tone(440, 5_000)
        until { runner.calls.size == 1 }
        svc.dtmf('7')
        svc.stopSong()
        until { runner.calls.size == 3 }
        runner.reply(1, true, "dtmf")
        runner.reply(2, true, "stopped")
        delay(20)
        assertEquals("Playing 440Hz for 5000ms..." to true, svc.state())
        runner.reply(0, true, "")
        until { !svc.busy.value }
        assertEquals("Tone played.", svc.status.value)
    }

    @Test fun stopSupersedesWaitingCommand() = runBlocking {
        val svc = svc()
        runner.sticky = true
        svc.tone(440, 5_000)
        until { runner.calls.size == 1 }
        svc.stop()
        runner.reply(0, true, "late")
        delay(20)
        assertEquals("Stopped." to false, svc.state())
    }

    // --- Non-blocking speaker_play (firmware features.speaker_play_async) ---

    /** Fake JSON transport: each speaker_play waits on its own gate. */
    private class JsonGate : JsonCommandRunner {
        val calls = CopyOnWriteArrayList<Pair<String, Long>>()
        val gates = CopyOnWriteArrayList<CompletableDeferred<Pair<Boolean, JSONObject?>>>()
        override suspend fun runJson(cmd: String, timeoutMs: Long): Pair<Boolean, JSONObject?> {
            val gate = CompletableDeferred<Pair<Boolean, JSONObject?>>().also { gates += it }
            calls += cmd to timeoutMs
            return gate.await()
        }
        fun ack(i: Int, play: Long, path: String) =
            gates[i].complete(true to JSONObject().put("ok", true).put("out", "playing $path").put("play", play))
        fun fail(i: Int, d: JSONObject) = gates[i].complete(false to d)
    }

    private val json = JsonGate()
    @Volatile private var async = true
    private fun CoroutineScope.asyncSvc() = SpeakerService(this, runner, { connected }, json, { async })
    private fun end(play: Long, state: String, path: String = "/a.wav", reason: String? = null) =
        JSONObject().put("play", play).put("state", state).put("path", path).put("ms", 1200)
            .apply { if (reason != null) put("reason", reason) }

    /** Start /a.wav and complete its ACK with [play]; returns once the file is tracked. */
    private suspend fun SpeakerService.started(play: Long, path: String = "/a.wav") {
        val n = json.calls.size
        playFile(path)
        assertEquals("Starting $path..." to true, state())
        until { json.calls.size == n + 1 }
        json.ack(n, play, path)
        until { !busy.value }
    }

    @Test(timeout = 5_000) fun asyncStartAcksAtOnceAndCompletionEventEndsIt() = runBlocking {
        val svc = asyncSvc()
        svc.started(7)
        assertEquals("Playing /a.wav" to false, svc.state())
        assertEquals("/a.wav", svc.filePlaying.value)
        assertEquals(listOf("speaker_play /a.wav" to SpeakerService.PLAY_START_TIMEOUT_MS), json.calls.toList())
        assertTrue(runner.calls.isEmpty())
        svc.onPlayEvent(end(99, "completed", "/other.wav")) // not ours: ignored
        assertEquals("/a.wav", svc.filePlaying.value)
        svc.onPlayEvent(end(7, "completed"))
        assertEquals("" to ("Played /a.wav." to false), svc.filePlaying.value to svc.state())
        svc.onPlayEvent(end(7, "stopped", reason = "stop")) // duplicate: ignored
        assertEquals("Played /a.wav.", svc.status.value)
    }

    @Test(timeout = 5_000) fun stopMidPlaySendsSpeakerStopAndReportsStopped() = runBlocking {
        val svc = asyncSvc()
        svc.started(8)
        svc.stopFile()
        until { runner.calls.size == 1 }
        assertEquals("speaker_stop" to 10_000L, runner.calls[0])
        assertEquals("/a.wav", svc.filePlaying.value) // still tracked until the device confirms
        svc.onPlayEvent(end(8, "stopped", reason = "stop")) // firmware sends the EVENT before the ACK
        assertEquals("" to ("Stopped /a.wav." to false), svc.filePlaying.value to svc.state())
        runner.reply(0, true, "stopped")
        delay(20)
        assertEquals("Stopped /a.wav.", svc.status.value)

        // A lost EVENT: the successful stop ACK still ends the tracking.
        svc.started(9, "/b.mp3")
        svc.stopFile()
        until { runner.calls.size == 2 }
        runner.reply(1, true, "stopped")
        until { svc.filePlaying.value.isEmpty() }
        assertEquals("Stopped /b.mp3." to false, svc.state())
    }

    @Test(timeout = 5_000) fun stopWhilePlaybackIsStillStartingCancelsTheStart() = runBlocking {
        val svc = asyncSvc()
        svc.playFile("/a.wav")
        until { json.calls.size == 1 }
        svc.stopFile()
        assertEquals("Stopped." to false, svc.state())
        until { runner.calls.size == 1 }
        assertEquals("speaker_stop", runner.cmds[0])
        json.ack(0, 3, "/a.wav") // late start ACK: ignored
        svc.onPlayEvent(end(3, "stopped", reason = "stop"))
        runner.reply(0, true, "stopped")
        delay(20)
        assertEquals("" to ("Stopped." to false), svc.filePlaying.value to svc.state())
    }

    @Test(timeout = 5_000) fun startErrorsAndErrorEvent() = runBlocking {
        val svc = asyncSvc()
        svc.playFile("x.mp3")
        until { json.calls.size == 1 }
        json.fail(0, JSONObject().put("ok", false).put("out", "file not found"))
        until { !svc.busy.value }
        assertEquals("file not found" to "", svc.status.value to svc.filePlaying.value)
        svc.playFile("x.xyz")
        until { json.calls.size == 2 }
        json.fail(1, JSONObject().put("code", 400).put("msg", "usage: speaker_play <path>"))
        until { !svc.busy.value }
        assertEquals("usage: speaker_play <path>", svc.status.value)
        svc.playFile("y.wav")
        until { json.calls.size == 3 }
        json.fail(2, JSONObject())
        until { !svc.busy.value }
        assertEquals("Playback failed.", svc.status.value)
        svc.rejects("Enter a file path first.") { playFile("  ") }
        assertEquals(3, json.calls.size)

        svc.started(11, "/c.wav")
        svc.onPlayEvent(end(11, "error", "/c.wav"))
        assertEquals("" to ("Playback error: /c.wav." to false), svc.filePlaying.value to svc.state())
        connected = false
        svc.rejects(notConnected) { playFile("/c.wav") }
        assertEquals(4, json.calls.size)
    }

    @Test(timeout = 5_000) fun eventReadBeforeItsStartAckIsMapped() = runBlocking {
        val svc = asyncSvc()
        svc.playFile("/tiny.wav")
        until { json.calls.size == 1 }
        svc.onPlayEvent(end(5, "stopped", "/old.wav", "superseded")) // previous file, other token
        svc.onPlayEvent(end(6, "completed", "/tiny.wav"))
        json.ack(0, 6, "/tiny.wav")
        until { !svc.busy.value }
        assertEquals("" to ("Played /tiny.wav." to false), svc.filePlaying.value to svc.state())
    }

    @Test(timeout = 5_000) fun newerSpeakerCommandForgetsTheFileAndKeepsItsWait() = runBlocking {
        val svc = asyncSvc()
        svc.started(12)
        svc.tone(440, 500) // the device stops the file before the tone
        assertEquals("" to ("Playing 440Hz for 500ms..." to true), svc.filePlaying.value to svc.state())
        svc.onPlayEvent(end(12, "stopped", reason = "superseded")) // never cancels the tone wait
        assertEquals("Playing 440Hz for 500ms..." to true, svc.state())
        until { runner.calls.size == 1 }
        runner.reply(0, true, "")
        until { !svc.busy.value }
        assertEquals("Tone played.", svc.status.value)

        svc.started(13)
        svc.dtmf('5')
        assertEquals("", svc.filePlaying.value)
        until { runner.calls.size == 2 }
        runner.reply(1, true, "dtmf")
        svc.started(14)
        svc.onPlayEvent(end(14, "stopped", reason = "superseded")) // e.g. a mic stream took the I2S
        assertEquals("Playback of /a.wav was interrupted.", svc.status.value)
        svc.stopFile() // nothing tracked: nothing sent
        delay(20)
        assertEquals(listOf("speaker_tone 440 500", "speaker_dtmf 5 200"), runner.cmds)
    }

    @Test(timeout = 5_000) fun disconnectForgetsTheFileAndIgnoresItsLateEvent() = runBlocking {
        val svc = asyncSvc()
        svc.started(21)
        connected = false
        svc.disconnect()
        assertEquals("" to (SpeakerService.LINK_CLOSED to false), svc.filePlaying.value to svc.state())
        svc.onPlayEvent(end(21, "completed"))
        assertEquals(SpeakerService.LINK_CLOSED, svc.status.value)
        svc.stopFile()
        delay(20)
        assertTrue(runner.calls.isEmpty())
        // Disconnect with nothing playing leaves the status alone.
        svc.disconnect()
        assertEquals(SpeakerService.LINK_CLOSED, svc.status.value)
    }

    @Test(timeout = 5_000) fun oldFirmwareKeepsTheBlockingContract() = runBlocking {
        async = false
        val svc = asyncSvc()
        assertEquals("played /a.wav", svc.result(true to "played /a.wav") { playFile("/a.wav") })
        assertEquals(listOf("speaker_play /a.wav" to 600_000L), runner.calls.toList())
        assertTrue(json.calls.isEmpty())
        assertEquals("", svc.filePlaying.value)
        svc.onPlayEvent(end(1, "completed"))
        svc.stopFile() // no tracked file on old firmware: nothing sent
        delay(20)
        assertEquals(1, runner.calls.size)
        // Without a JSON transport the service never takes the async path.
        val noJson = SpeakerService(this, runner, { connected }, null, { true })
        assertEquals("Playback started.", noJson.result(true to "") { playFile("/b.wav") })
        assertEquals("speaker_play /b.wav" to 600_000L, runner.calls.last())
    }
}
